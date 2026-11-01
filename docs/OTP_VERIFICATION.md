# OTP Verification — Design, Edge Cases & Revision

Sprint 8 (`docs/sprints/Sprint-08.md`, decisions D1–D7). Covers how BusLink
proves a passenger owns a mobile number at signup, and the attacks each rule
stops. Built question-by-question; keep this as the interview revision sheet.

---

## 1. Revision Sheet (diagrams only)

**The flow**
```
 App                          Server                              Redis
  │ POST /auth/otp/send ─────► mobile registered? → 409 (no SMS)
  │                            cooldown key? sends ≥ 5?  ──GET──►  otp-cooldown:98765…  (60 s)
  │   {mobileNo}               │ yes → 429                          otp-sends:98765…     (1 h, count)
  │                            │ no  → SecureRandom 6 digits
  │                            │       SET otp:98765… = 482913 ───►  otp:98765…          (5 min)
  │ ◄── 200 "OTP sent"         │       SmsProvider.sendOtp()         otp-attempts:98765… (5 min)
  │                                                                  
  │ POST /auth/register ─────► email taken? mobile taken? → 409
  │   {name,email,mobileNo,    otp == Redis otp:98765… ?
  │    password, otp}            ✗ → attempts+1 → 5th wrong → DELETE otp → 400
  │                              ✓ → create user + wallet → DELETE otp keys
  │ ◄── 200 tokens
```

**Why each rule exists**
```
 Rule                         Stops                              Decision
 ───────────────────────────  ─────────────────────────────────  ────────
 unique mobile_no             2nd account on same SIM            D1
 pre-check mobile             vague 409 message                  D2
 OTP in Redis + TTL           stale codes, cleanup job           D4
 OTP inside register          skipping OTP / "register first"    D5
 5 wrong tries → delete       brute force of 1,000,000 codes     D6
 60 s cooldown + 5/hour       SMS bill, harassment, guess rounds D7
 send refuses registered no.  wasted SMS, confusing owner SMS    D8
 check order email→mobile→OTP form error burning an attempt      D5
```

**Brute-force maths**
```
 no limit:     ~1,000 tries/s × 300 s = 300,000 → ~30% per OTP window
 5 tries/OTP:  5 / 1,000,000 = 0.0005% per SMS
 + 5 SMS/hour: 120 SMS/day × 5 = 600 guesses/day → 0.06% per day
```

**Stateless HTTP — why a separate verify step needs a carrier**
```
 verify  ─► "482913 ✅" ─► server forgets
 register ─► "was it verified?" ─► ??? → needs flag / token … or do both in ONE request (chosen)
```

---

## 2. Plain Explanation

An OTP is a bank counter token: short-lived, only for whoever holds the phone,
used once. Verifying it proves one fact — *the person using the app right now
has this SIM*. Combined with a UNIQUE mobile number, that means one SIM = one
account, so a passenger with unpaid DISPUTED tickets can't just make a fresh
account with a new email.

The server keeps a tiny notepad in Redis: `otp:<mobile>` → code, expiring in 5
minutes on its own (TTL). Signup sends the code in the same request as the
account details, so the check and the account creation happen together.

Every number on the notepad is also guarded: only 5 wrong guesses per code,
only one SMS per minute, only 5 SMS per hour.

---

## 3. Real-World Scenarios

| # | Scenario | What happens |
|---|---|---|
| 1 | Honest user, types code right | 200 + tokens, account created, OTP keys deleted |
| 2 | Mistypes twice, then right | attempts = 2, then success — unaffected |
| 3 | Email already taken | 409; **OTP kept**, fix email and resubmit without a new SMS |
| 4 | Mobile already registered | 409 "Mobile number already registered" |
| 5 | Script guesses codes | 5th wrong → OTP deleted → must resend (cooldown, 5/hour cap) |
| 6 | Script calls `register` without ever sending | no `otp:<mobile>` key → 400 |
| 7 | Attacker knows victim's number, tries to register it first | needs the code from the victim's SMS → fails |
| 8 | Script spams one number with sends | 2nd within 60 s → 429; 6th in an hour → 429 |
| 9 | Script sends one SMS to 10,000 different numbers | **not stopped** (per-number limits) — needs per-IP limit / CAPTCHA |
| 10 | Code typed after 5 minutes | key expired → 400, resend |
| 11a | `send` for a number that already has an account | 409, no SMS (D8) — the owner's phone doesn't buzz |
| 11 | Redis down | send/register fail — but so do fares today (no `CacheErrorHandler`); Redis was already a hard dependency |

---

## 4. Technical Terms Mapping

| Plain idea | Technical term |
|---|---|
| Code expires by itself | TTL (time-to-live), Redis key expiry |
| Server forgets between requests | Stateless HTTP |
| Carrying "verified" between calls | Session state / verification token (avoided by single-request design) |
| Unpredictable code | CSPRNG — `SecureRandom` (vs predictable `java.util.Random`) |
| Guessing all codes | Brute-force attack; key space = 10⁶ |
| Limit wrong tries | Attempt limiting / retry budget |
| Limit sends | Rate limiting (cooldown = fixed window per key) |
| Paying for attacker's SMS | SMS pumping / toll fraud |
| Locking a real user out on purpose | Account lockout DoS |
| "Register first" on someone else's number | Race / account pre-hijacking |
| Too many requests | HTTP 429 |
| Duplicate resource | HTTP 409 Conflict |
| Endpoint reveals whether an account exists | Account enumeration |
| Check before insert vs DB rule | Application pre-check vs unique constraint (check-then-act race) |
| Mock vs real SMS behind one interface | Strategy pattern / ports & adapters |
| Redis delete not undone by DB rollback | Dual-write / no distributed transaction (afterCommit hook) |

---

## 5. Derive It Yourself (Q&A)

**Q1. Why does a OTP flow with separate `/verify` and `/register` calls need extra state?**
<details><summary>Answer</summary>
HTTP is stateless. After `/verify` succeeds the server forgets; `/register`
can't know verification happened, so anyone could call `/register` directly.
You need a carrier (flag or signed token) — or send the OTP inside
`/register` so proof and creation happen in one request (BusLink's choice).
</details>

**Q2. What's the hole in a "verified" flag keyed only by phone number?**
<details><summary>Answer</summary>
The proof is tied to the number, not to the person. After the owner verifies,
anyone who knows the number can call `register` first with their own email
and take the number (account pre-hijacking). A signed token or the
single-request design binds the proof to whoever had the code.
</details>

**Q3. Is a 5-minute expiry enough to stop brute force on a 6-digit OTP?**
<details><summary>Answer</summary>
No. 10⁶ codes; a script at ~1,000/s covers ~30% in 300 s, and resends give more
rounds. You need an attempt limit (5 wrong → delete) and send limits.
</details>

**Q4. Why not lock the number after repeated failures?**
<details><summary>Answer</summary>
Lockout becomes a weapon: anyone can lock a real user out by typing wrong codes
for their number. Deleting the OTP + limiting sends gives the same protection
without letting attackers deny service.
</details>

**Q5. Why rate-limit `send` at all, if guesses are already limited?**
<details><summary>Answer</summary>
Three reasons: SMS costs money (pumping), one number can be harassed, and each
new OTP gives 5 fresh guesses. Cooldown (60 s) + cap (5/hour) bounds all three
per number.
</details>

**Q6. What do per-number limits NOT stop?**
<details><summary>Answer</summary>
Spraying one SMS each to many different numbers. Needs per-IP limits or a
CAPTCHA — harder because of proxies and shared carrier IPs (CGNAT).
</details>

**Q7. Why keep the OTP if registration fails because the email is taken?**
<details><summary>Answer</summary>
The user proved nothing wrong about the phone. Deleting the OTP would force a
new SMS (cost + cooldown) just to fix a typo. Delete only on success or on the
5th wrong code.
</details>

**Q8. If the DB has a UNIQUE constraint on mobile, why also pre-check?**
<details><summary>Answer</summary>
For a clear message. The pre-check alone has a check-then-act race (two
signups pass it at once); the constraint is the real guard, the pre-check is UX.
</details>

**Q9. Why Redis instead of a Postgres OTP table?**
<details><summary>Answer</summary>
OTPs are tiny and live 5 minutes; Redis TTL expires them automatically, so no
cleanup job. Redis was already a hard production dependency (no
`CacheErrorHandler`, fares fail without it), so it adds no new failure mode.
</details>

**Q10. Why `SecureRandom` and not `Random`?**
<details><summary>Answer</summary>
`Random` is a linear congruential generator — given a few outputs you can
predict the next. `SecureRandom` is a CSPRNG seeded from OS entropy; codes that
grant account access must be unpredictable.
</details>

**Q11. Doesn't refusing `send` for a registered number leak that the number has an account?**
<details><summary>Answer</summary>
Yes (account enumeration) — but hiding it only works if every endpoint hides
it, and signup already returns "Mobile number already registered". So hiding
it in `send` costs an SMS and confuses the real owner for zero secrecy.
Rate limits slow bulk probing.
</details>

**Q12. `register` deletes the OTP in Redis inside a DB transaction. What can go wrong?**
<details><summary>Answer</summary>
Redis isn't in the transaction (dual write). The delete happens immediately;
if the commit fails afterwards, the user isn't created but the OTP is gone.
Fix: delete in `TransactionSynchronization.afterCommit`. BusLink accepts the
simple version — the only cost is one resend after a rare DB failure.
</details>

**Q13. Why check email and mobile before the OTP?**
<details><summary>Answer</summary>
So a form mistake (email taken) returns 409 without counting a wrong OTP
attempt or consuming the code — the user fixes the email and resubmits with
the same OTP.
</details>

**Q14. What stops a used OTP being replayed to create a second account?**
<details><summary>Answer</summary>
Two things: the OTP is deleted on success (single use), and the number is now
registered, so the mobile pre-check returns 409 before the OTP is even read.
</details>
