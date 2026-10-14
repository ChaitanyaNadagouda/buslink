# BusLink Offline Sync: Demo & Revision Handbook

**Covers:** Sprint 7 (passenger QR, ticket scan, offline sync engine, schedulers)
**Built from:** the S7-22 verification run on 2026-10-14 (all 27 steps passing)
**Use it for:** (1) giving a live demo in an interview, (2) fast revision before one.

> **5 minutes before an interview?** Read **Section 0** only. It's all diagrams.
> **Giving a demo?** Follow **Section 3** (setup), then **Section 4** (the six acts) in order.
> **Deep-dive questions?** Sections 5–7, plus `docs/SYNC_RACE_CONDITIONS.md`.

---

## Table of Contents

0. [Revision Sheet (diagrams only)](#0-revision-sheet-diagrams-only)
1. [The pitch (30 seconds)](#1-the-pitch-30-seconds)
2. [Demo formats: 5, 15 or 30 minutes](#2-demo-formats-5-15-or-30-minutes)
3. [Before the demo: setup checklist](#3-before-the-demo-setup-checklist)
4. [The demo: six acts](#4-the-demo-six-acts)
5. [Design decisions: what, alternatives, why](#5-design-decisions-what-alternatives-why)
6. [Bugs I found and fixed: interview stories](#6-bugs-i-found-and-fixed-interview-stories)
7. [Known limitations & what's next](#7-known-limitations--whats-next)
8. [Cheat sheet: numbers, statuses, endpoints](#8-cheat-sheet-numbers-statuses-endpoints)
9. [psql snippets for the demo](#9-psql-snippets-for-the-demo)
10. [Troubleshooting during a demo](#10-troubleshooting-during-a-demo)

---

## 0. Revision Sheet (diagrams only)

### 0.1 The problem in one picture

```
 🚌 Bus moving through Bangalore, network drops in and out
   conductor must still sell tickets, passenger must still pay
   → sell OFFLINE now, settle with the server LATER (sync)

 📱 conductor phone ──(offline)──► stores ticket events locally
                    ──(network back)──► POST /sync/upload ──► server settles each one
```

### 0.2 The two QR codes

```
 ┌── PASSENGER QR (signed JWT) ─────────┐    ┌── TICKET QR (plain JSON) ────────────┐
 │ made by:   SERVER                    │    │ made by:   CONDUCTOR'S PHONE         │
 │ shown by:  passenger → conductor     │    │ shown by:  conductor → passenger     │
 │ proves:    "I'm a real passenger"    │    │ carries:   what to pay for           │
 │ signed?    YES (works offline)       │    │ signed?    NO (server recalculates)  │
 │ valid:     24h (iat → exp)           │    │ exp:       issuedAt + 1h (phone-only)│
 └──────────────────────────────────────┘    └──────────────────────────────────────┘
```

### 0.3 Online vs offline: same UX for the passenger

```
 ONLINE:  issue ticket → show ticket QR → passenger scans → POST /tickets/scan
          → confirm screen → [Confirm & Pay] → wallet charged now

 OFFLINE: issue ticket on phone → show ticket QR → passenger scans → read QR locally
          → confirm screen → [Confirm & Pay Later] → event stored on phone
          → later: POST /sync/upload → server charges wallet
```

### 0.4 What the server does with ONE uploaded event

```
 event ──► 🔒 lock by eventId ──► already final? ──yes──► return stored result (idempotent)
                │ no
                ▼
           claim (INSERT now)
                │
                ▼
     validate data ──bad──► REJECTED (final, never retried)
                │ ok
                ▼
     fare (server-side, never trust client) ─► wallet + ₹100 overdraft ≥ fare?
                │ yes                                   │ no
                ▼                                       ▼
            SYNCED                                  DISPUTED
     (ticket PAID, wallet charged)        (ticket DISPUTED, wallet untouched,
                                            passenger self-pays later)

     unexpected technical error ──► FAILED (retried by job, up to 10 attempts)
```

### 0.5 The status map

```
                       ┌──► SYNCED    final   (paid)
 new / FAILED ──► process ─► DISPUTED final   (owes money, self-pay)
                       ├──► REJECTED  final   (bad data)
                       └──► FAILED    retry   (technical) ── job every 5 min, max 10
                                                └── phone re-upload: any time

 PENDING / PROCESSING: internal only, never visible outside a transaction
```

### 0.6 The wallet maths used in the demo (fare = ₹36)

```
 wallet  + ₹100 overdraft = can spend   vs ₹36   outcome     wallet after
 ₹150    + 100            = ₹250        ≥        SYNCED      ₹114
 ₹20     + 100            = ₹120        ≥        SYNCED      −₹16  (overdraft)
 −₹80    + 100            = ₹20         <        DISPUTED    −₹80  (untouched)
```

### 0.7 The six demo acts

```
 Act 1  Passenger QR        signed JWT, self-heal, refresh             (steps 1–2)
 Act 2  Online ticket       issue, scan, 404 for others' tickets      (steps 3–6)
 Act 3  Offline sync        SYNCED, idempotent re-upload, SQL proof   (steps 7–14)
 Act 4  Money edge cases    overdraft, DISPUTED, self-pay, admin view (15–20, 27)
 Act 5  Bad data            REJECTED, mixed batch                     (steps 21–22)
 Act 6  Background jobs     expiry, retry + cap, key cleanup          (23–26 + checks)
```

### 0.8 The race-condition fix in one picture

```
 two copies of the same event arrive together:
   row exists  → SELECT … FOR UPDATE  → 2nd copy WAITS, then sees the final status
   row missing → INSERT (Persistable) → 2nd copy hits the PRIMARY KEY → rolled back
   any commit error → per-event try/catch → rest of the batch continues, nothing saved
```

---

## 1. The pitch (30 seconds)

> "BusLink is a digital ticketing backend for Bangalore's city buses (BMTC),
> built in Spring Boot with Postgres and Redis. The hard part is that buses
> lose network constantly, so conductors sell tickets **offline** and the phone
> **syncs** them later. I built the sync engine: it settles each offline ticket
> against the passenger's wallet with three outcomes (paid, disputed, or
> rejected), it's idempotent so retries never double-charge, it's safe when
> duplicate uploads arrive at the same moment, and background jobs handle
> retries, ticket expiry and cleanup. Let me show you."

**One-line version:** *"An offline-first ticketing backend where the hard
problem is settling money correctly after the fact: idempotent, concurrency-
safe, and honest about what it can't verify."*

---

## 2. Demo formats: 5, 15 or 30 minutes

| Time | Show | Skip |
|---|---|---|
| **5 min** | Act 3 (sync + idempotent re-upload) and Act 4 (DISPUTED → self-pay). Mention the race fix with diagram 0.8. | Acts 1, 2, 5, 6 (just describe them) |
| **15 min** | Acts 1, 3, 4, 5 + SQL-log proof of the lock | Act 2 details, Act 6 setup (describe) |
| **30 min** | All six acts + Section 6 stories | — |

**Tip:** whatever the format, **end with the SQL-log proof** (Act 3). It shows
you verified behaviour against the real database, not just mocks.

---

## 3. Before the demo: setup checklist

```
 □ 1. Containers up            docker compose up -d postgres redis   (from repo root)
 □ 2. Port 8080 free           stop any IntelliJ run first
 □ 3. Start app with demo overrides (command-line only, nothing edited):
        cd backend && set -a && . ../infrastructure/.env && set +a && \
        ./mvnw spring-boot:run -Dspring-boot.run.arguments="\
          --spring.jpa.show-sql=true \
          --sync.retry-interval-ms=10000 \
          --ticket.expiry.check-interval-ms=5000"
 □ 4. Postman: import postman/BusLink-API.postman_collection.json (fresh import
       resets collection variables, so do step 5 AFTER importing)
 □ 5. Log in:  Auth → Login · Conductor Auth → Login · Admin Auth → Login
 □ 6. Set the wallet for Act 3:  psql → balance = 150   (Section 9)
 □ 7. Have open side by side: Postman · app log (terminal) · psql
```

**What each override does (say this if asked):**

| Override | Normal | Demo | Why |
|---|---|---|---|
| `spring.jpa.show-sql` | false | true | show the real SQL (row lock, plain insert) |
| `sync.retry-interval-ms` | 5 min | 10 s | see the retry job without waiting |
| `ticket.expiry.check-interval-ms` | 1 h | 5 s | see expiry without waiting |

**Remember:** access tokens last **15 minutes**. A 401 mid-demo = re-run that login.

---

## 4. The demo: six acts

Each act: **goal → requests → expected result → what to say → if they ask.**
Numbers are the real values from the 2026-10-14 run.

---

### Act 1: Passenger QR (steps 1–2)

**Goal:** the passenger's identity works **offline**, and expired QRs fix themselves.

| # | Postman request | Expected |
|---|---|---|
| 1 | User → QR Token | 200, a JWT (`eyJ…`). Paste into jwt.io: `type=PASSENGER_QR`, `exp − iat = 86400` |
| 2 | QR Scan → Refresh QR Token | a **new** JWT, later `iat`, fresh 24 h (auto-saved to `{{qrToken}}`) |

```
 server signs QR with secret key 🔏 ──► passenger's phone ──► conductor scans OFFLINE
                                                              verifies signature locally
                                                              (no network needed)
```

**What to say:**
- "The conductor has to trust the passenger's identity with **no network**, so
  the QR is a **signed JWT**. A forged one fails the signature check."
- "It lasts 24 hours. `GET /user/qr` **self-heals**: if the stored token is
  expired, tampered or missing, it regenerates one. In the real run it upgraded
  an old pre-Sprint-7 plain-UUID QR to a JWT automatically."

**If they ask:**
- *Why does the passenger QR need signing but the ticket QR doesn't?* → The
  passenger QR is an **identity claim** checked offline. The ticket QR is just a
  data pipe: the server recalculates the fare and checks ownership itself, so
  forging it gains nothing.
- *Why self-heal on a GET (a read with a side effect)?* → Passengers only open
  the app at boarding time. A manual refresh button would never be pressed
  before expiry. The trade-off was accepted and documented.

---

### Act 2: Online ticket + scan (steps 3–6)

**Goal:** the online confirm-and-pay flow, and that nobody can see others' tickets.

| # | Postman request | Expected |
|---|---|---|
| 3 | Ticket → Issue Ticket | 200, includes **`conductorId`** + every ticket-QR field; ₹90 (2 adults + 1 child + 1 infant) |
| 4 | QR Scan → Scan Ticket (Success) | 200, confirm-screen details, status `ISSUED`, `paidAt: null` |
| 5 | QR Scan → Scan Ticket (Not Found) | 404 |
| 6 | QR Scan → Scan Ticket (Wrong Passenger) | **404**, same message as step 5 |

**What to say:**
- "Issuing returns everything the conductor app needs to draw the ticket QR,
  including `conductorId`. Scanning shows the confirm screen. It doesn't pay;
  the passenger confirms explicitly."
- "Another passenger scanning my ticket gets **404, not 403**. The lookup is by
  ticket **and** owner together, so they can't even tell the ticket exists."

**If they ask:**
- *Why 404 instead of 403?* → 403 leaks existence ("it's real, just not
  yours"). 404 makes guessing ticket IDs useless.
- *Is issuing idempotent?* → Yes, via the `X-Idempotency-Key` header (24 h TTL),
  so a conductor's retry after a network blip returns the same ticket.

---

### Act 3: Offline sync + idempotency + SQL proof (steps 7–14) ⭐

**Goal:** the core engine. One event becomes one paid ticket, however many
times it's uploaded.

**Setup:** wallet = ₹150 (`psql`, Section 9).

| # | Postman request | Expected |
|---|---|---|
| 8 | Sync → Get Sync Status (Initial) | all counts 0 (on a fresh table) |
| 10 | Sync → Upload Batch (Sufficient Balance - Synced) | `synced: 1`, a `ticketId`, "Ticket issued and paid" |
| 11 | Passenger → Get My Tickets | that ticket PAID, ₹36; **`issuedAt` ≠ `paidAt`** |
| 12 | Passenger → Get Wallet Balance | **₹114.00** (150 − 36) |
| 13 | Sync → Upload Batch (Duplicate Event) | **same `ticketId`**, nothing new created |
| 14 | Sync → Get Sync Status (After Sync) | `synced: 1`, `processing: 0` |

**Show the SQL log** (after step 10). This is the strongest moment of the demo:

```
 ① select … from sync_event where event_id=? for no key update   ← 🔒 row lock
 ② insert into sync_event (…)                                    ← plain INSERT, claimed FIRST
 ③ select conductor / bus / users / route_stop / route / wallet   ← validation
 ④ insert into transaction   (₹36 DEBIT)
 ⑤ insert into ticket        (PAID)
 ⑥ update sync_event         (→ SYNCED)
 ⑦ update wallet … where wallet_id=? and version=?               ← optimistic lock
```

**What to say:**
- "The phone gives each event its own UUID. The server uses it as the
  idempotency key: the `sync_event` row is permanent, so a re-upload just returns
  the stored result. Step 13 proves it: same ticket, one DEBIT, balance unchanged."
- "The fare is **always recalculated on the server**. The client's fare is
  logged but never trusted."
- "`issuedAt` on the ticket is the **boarding time from the phone** (12:15),
  `paidAt` is when the sync happened (12:24). History stays accurate even when
  sync is late."
- "Here's the real SQL. ① is a **row lock**, so a duplicate request waits.
  ② is a **plain insert before any validation**, so a duplicate brand-new event
  hits the primary key immediately. ⑦ is optimistic locking on the wallet."

**If they ask:**
- *Why is `processing` always 0?* → `PROCESSING` is set inside the transaction
  and replaced before commit. Under READ COMMITTED nobody else ever sees it.
  I documented that honestly rather than pretend it's a claim mechanism.
- *What if two uploads of the same event arrive at the same instant?* →
  See Section 5 and diagram 0.8. That's the race-condition fix.
- *Why per-event transactions instead of one per batch?* → One bad event must
  never roll back the good ones. The batch loop is deliberately **not**
  `@Transactional`; a separate `SyncEventProcessor` bean owns each event's
  transaction (self-invocation would bypass the proxy).

---

### Act 4: Money edge cases (steps 15–20, 27)

**Goal:** overdraft, disputes, and the passenger settling their own debt.

| # | Setup / request | Expected |
|---|---|---|
| 15 | `psql`: wallet = ₹20 | |
| 16 | Sync → Upload Batch (Overdraft Allowed) | `synced: 1`, wallet **−₹16** |
| 17 | `psql`: wallet = −₹80 | |
| 18 | Sync → Upload Batch (Exceeds Overdraft - Disputed) | `disputed: 1`, "Wallet insufficient — ticket disputed"; wallet **still −₹80** |
| 19 | Sync → Get My Tickets (Disputed Visible) | ticket DISPUTED, `paidAt: null` |
| 27 | Sync → Get Disputed Tickets (Admin) | listed with passenger, conductor and route names |
| — | `psql`: wallet = ₹150 (top-up) | |
| 20 | Sync → Pay Disputed Ticket Via Wallet | 200, `ticketStatus: PAID`, `walletBalanceAfter: 114` |
| — | Sync → Get Disputed Tickets (Admin) again | `[]` |

```
 DISPUTED ──► visible to passenger ──► visible to admin
          ──► passenger [Pay Now] ──► PAID ──► gone from admin list
```

**Run 27 before 20**: paying removes the ticket from the admin list.

**What to say:**
- "Offline, the conductor can't check the wallet. So at sync time there are
  three outcomes. Overdraft up to ₹100 is allowed. Beyond that the ticket
  becomes **DISPUTED**: the ride is recorded, **no money moves**, and the
  passenger settles it themselves with the normal wallet payment."
- "I only change the wallet between steps. Same passenger, route and ₹36 fare,
  new eventId each time. So the wallet is the only reason the outcome changes."

**If they ask:**
- *Why not just reject the ticket or block the passenger?* → The ride happened.
  Recording it as a debt is fairer and auditable. Blocking (blacklist) is Sprint
  8 scope.
- *Why isn't DISPUTED retried automatically?* → A wallet shortfall doesn't fix
  itself on a timer. Retrying is pointless; the passenger pays when ready.
- *How did DISPUTED self-pay end up working?* → It didn't at first. See the
  first story in Section 6.

---

### Act 5: Bad data: REJECTED (steps 21–22)

**Goal:** invalid events end in a **final** state and are never retried.

| # | Postman request | Expected |
|---|---|---|
| 21 | Sync → Upload Batch (Event Too Old - Rejected) | `rejected: 1`, `failed: 0`, "Event too old — possible replay or clock manipulation" |
| 22 | Sync → Upload Batch (Partial Batch - Mixed Outcomes) | `synced: 1`, `rejected: 2` ("Invalid QR token", "Bus mismatch") |
| — | Sync → Get Sync Status | `synced 3 · disputed 1 · rejected 3 · failed 0` (after a full run) |

```
 one batch, three events, three independent outcomes:
   valid        → SYNCED   (wallet charged)
   fake QR      → REJECTED
   wrong bus    → REJECTED
```

**What to say:**
- "I split failures into two kinds. **FAILED** is technical (DB hiccup) and
  retried. **REJECTED** is bad data (too old, wrong bus, fake QR). Retrying the
  same data gives the same answer, so it's final and never retried."
- "A re-upload of a REJECTED event returns the stored reason without
  re-validating."
- "One batch, independent results: the good event is charged even though two
  neighbours are rejected."

**If they ask:**
- *Why reject events older than 7 days?* → It blocks replays and badly wrong
  phone clocks. There's also a check for events dated **in the future** (beyond
  a 5-minute tolerance for clock drift).
- *How does the passenger QR stay valid for a sync days later?* → It's checked
  against the **boarding time**, not the sync time. A QR valid when the
  passenger boarded stays valid however late the bus syncs. (Section 6, story 3.)
- *"Passenger inactive" could become active later. Why final?* → If an account
  was suspended, a background job shouldn't auto-charge the old ride when it's
  reactivated. A human should review it.

---

### Act 6: Background jobs (steps 23–26 + two checks)

**Goal:** three `@Scheduled` jobs, each seen working.

**6a. Ticket expiry (steps 23–26):** unpaid ISSUED tickets older than 24 h → EXPIRED.

| # | Action | Expected |
|---|---|---|
| 23–24 | `psql`: backdate an ISSUED ticket 25 h | |
| 25 | (job runs every 5 s with the override) | log: `TicketExpiryScheduler: expired 1 stale ISSUED ticket(s)` |
| 26 | Passenger → Get Ticket By Id | `status: EXPIRED` |

**6b. Retry job:** FAILED events retried; REJECTED/DISPUTED never; cap respected.

```
 psql: insert event A  FAILED, attempts 0,  issued 10 days ago
       insert event B  FAILED, attempts 10 (at cap)
 within 10 s:
   A → retried → REJECTED "Event too old"   log: "retried 1 FAILED event(s), 0 errored"
   B → ignored → still FAILED, attempts 10
```

**6c. Idempotency-key cleanup:** expired keys deleted, live ones kept.
(Needs `--idempotency.cleanup.interval-ms=10000` and a restart: it runs once at
startup and then daily.)

```
 psql: insert key "test-expired" (expired) + "test-live" (valid)
 restart → log: "purged 1 expired idempotency key(s)" → only test-expired gone
```

**What to say:**
- "The retry job only retries **FAILED** events, and gives up after 10 attempts
  to stop 'poison' events looping forever. A capped event isn't lost: the phone
  can always re-upload it."
- "The expiry job ignores DISPUTED tickets on purpose. They're a debt to
  collect, not an abandoned ticket."

**If they ask:**
- *What if you run two app instances?* → Each would run every job. Harmless for
  expiry and cleanup, but the retry job would race. The fix is **ShedLock** (a
  DB lock row per job); noted for Sprint 9 when we scale out.
- *Why `fixedDelay` instead of `fixedRate`?* → The next run waits for the
  previous one to finish, so runs never overlap within one instance.

---

## 5. Design decisions: what, alternatives, why

| Decision | Alternatives considered | Why this one |
|---|---|---|
| Passenger QR = signed JWT | plain UUID | offline identity check needs cryptographic proof |
| Ticket QR = plain JSON | signed JWT | server recalculates fare + checks ownership; signing adds nothing |
| Per-event `@Transactional` in a **separate bean** | one transaction per batch; same-class method | one failure must not roll back others; self-invocation bypasses the proxy |
| Idempotency via the permanent `sync_event` row | separate `IdempotencyKey` (24 h TTL) | the row never expires; a TTL key would be a weaker duplicate |
| Server-side fare, `clientFare` logged only | trust the client | never trust money values from a client |
| Overdraft ₹100, then DISPUTED | reject / block passenger | ride happened; record a debt, passenger self-pays |
| **Row lock** (`SELECT … FOR UPDATE`) on lookup | `@Version` on SyncEvent; atomic claim with `REQUIRES_NEW`; Java `synchronized` | loser **waits and gets the right answer**; `synchronized` fails across instances **and** releases before commit |
| `Persistable` → real INSERT for new events | `INSERT … ON CONFLICT` + lock; Postgres advisory lock | stays pure JPA / DB-independent; loser's error is caught and a re-upload returns the winner's result |
| `saveAndFlush` at the claim | plain `save` (INSERT deferred to commit) | duplicate fails at the **start**, before touching the wallet |
| Per-event try/catch catching **everything** | catch only collision exceptions | one bug must not skip the rest of the batch; WARN vs ERROR logging keeps bugs visible |
| Loser of a race **saves nothing** | save it as FAILED | would overwrite the winner's SYNCED → retry → double charge |
| QR checked at **boarding time** + 5 min skew | check at sync time; also check `QR iat ≤ boarding` | sync can be days late; the lower bound stops no real attack and rejects honest clock drift (dropped as over-engineering) |
| `REJECTED` status + retry cap 10 | retry cap only; REJECTED only | REJECTED stops data errors immediately; the cap stops code-bug "poison" events |
| Overrides via command-line for testing | edit `application.properties` | nothing to revert; can't be committed by accident |

---

## 6. Bugs I found and fixed: interview stories

Use the **STAR** shape: Situation, Task, Action, Result. Each is ~1 minute.

### Story 1: DISPUTED tickets couldn't be paid
- **S:** The final design said disputed passengers self-pay with the existing wallet payment.
- **T:** Verify that before the Postman run.
- **A:** Re-read `payViaWallet` against the plan: it only accepted `ISSUED`, a
  leftover from an earlier draft where disputes needed an admin. Widened the
  check to `ISSUED || DISPUTED`, added a regression test.
- **R:** Caught **before** testing, not during. Step 20 now proves it end to end.
- **Lesson:** when a plan changes, re-check the code paths the old plan relied on.

### Story 2: "Fails cleanly" wasn't true (the race condition)
- **S:** A sprint note claimed concurrent duplicate processing was harmless
  because the wallet's `@Version` would make one attempt fail cleanly.
- **T:** Check that claim against the code.
- **A:** Found three problems: (1) the version error fires at **commit**,
  outside the method's try/catch, so it aborted the whole batch; (2) the
  DISPUTED path never writes the wallet, so **duplicate DISPUTED tickets** were
  possible; (3) brand-new events went through `save()` → `merge`, which could
  **overwrite** a just-committed row and charge twice. Fixed with a row lock,
  `Persistable`, `saveAndFlush`, and a per-event try/catch.
- **R:** Proven in the real SQL log (lock + plain insert). 6 new tests.
- **Lesson:** idempotency must hold **under concurrency**, not just on retries.
  And optimistic locking only protects rows you actually update.

### Story 3: Valid QRs rejected on late syncs
- **S:** Offline events may sync up to 7 days late.
- **T:** Review the sync validation.
- **A:** The passenger QR was checked against **now**. Its 24 h life meant any
  sync more than ~24 h after QR creation was rejected, even though the QR was
  valid at boarding. Changed it to check against the **boarding time** with a
  5-minute clock tolerance; added a "boarding time in the future" check.
  Considered a lower-bound check too, and **dropped it** as over-engineering.
- **R:** The 7-day window actually works now. 4 new JWT tests.
- **Lesson:** ask "valid **at what time**?". And for every validation, ask
  "what bad thing does this stop?"

### Story 4: Every sync upload would have failed (schema drift)
- **S:** The `SyncEvent` entity was reshaped this sprint.
- **T:** Review before testing.
- **A:** `ddl-auto=update` adds columns but never drops them. The table still
  had three old `NOT NULL` columns, so **every insert would fail**. Mocked unit
  tests couldn't see it. Dropped them in one `psql` transaction, proved it with
  a rolled-back test insert, and widened `error_message` to TEXT.
- **R:** Found by inspecting `\d sync_event`, not by a failing demo.
- **Lesson:** `ddl-auto=update` is not a migration tool. That's why Flyway exists.

### Story 5: Failures retried forever
- **S:** The retry job retried every FAILED event every 5 minutes.
- **A:** Most "failures" were bad **data** ("Event too old" only gets older).
  Added `REJECTED` (final) for data errors and a retry cap of 10 for technical
  ones.
- **R:** Verified: the retry job retried a FAILED event, turned it REJECTED,
  and ignored one at the cap.

### Story 6: A misleading log line
- **A:** The new catch logged every `DataIntegrityViolationException` as "lost a
  race". But Spring uses that one type for duplicate keys, NOT NULL **and**
  CHECK violations. Before the schema fix, a NOT NULL failure would have been
  blamed on a race. Reworded the log to be neutral.
- **Lesson:** log messages are debugging tools. A wrong one costs hours.

---

## 7. Known limitations & what's next

Being honest about these scores points in interviews.

| Limitation | Why it's acceptable now | Fix / when |
|---|---|---|
| `PROCESSING` never visible outside a transaction | the real protection is the row lock + primary key | documented; could be made real with an atomic claim (Option C) |
| A race loser gets an error, not the answer | caught per event; re-upload returns the winner's result | `INSERT … ON CONFLICT` or an advisory lock, if needed |
| A dishonest phone can pick a boarding time inside an old QR's 24 h window | bounded by the 7-day limit | `deviceId` verification, Sprint 8 |
| "Bus mismatch" uses the conductor's bus at **sync** time | rare (reassignment between boarding and sync) | compare against assignment history |
| Schedulers run on every instance | single instance today | ShedLock, Sprint 9 |
| DB errors mid-event don't advance the retry counter | Postgres rolls the whole transaction back | phone re-upload covers it |
| Schema via `ddl-auto=update` | dev only | Flyway (deferred) |

---

## 8. Cheat sheet: numbers, statuses, endpoints

### Numbers

| Thing | Value | Setting |
|---|---|---|
| Passenger QR lifetime | 24 h | constant in `JwtUtil` |
| Ticket QR `exp` | issuedAt + 1 h | phone-only |
| Access token | 15 min | `jwt.access-token-expiry-ms` |
| Overdraft | ₹100 | `wallet.overdraft-limit` |
| Max sync age | 7 days | `sync.max-sync-age-days` |
| Clock tolerance | 5 min | `sync.clock-skew-tolerance-minutes` |
| Retry interval / cap | 5 min / 10 attempts | `sync.retry-interval-ms`, `sync.max-retry-attempts` |
| Ticket expiry | ISSUED > 24 h, checked hourly | `ticket.expiry.*` |
| Idempotency key TTL / cleanup | 24 h / daily | `ticket.idempotency.ttl-hours`, `idempotency.cleanup.interval-ms` |
| Demo fare (Route 500K, HSR Layout → KR Puram) | 6 stages × ₹6 = **₹36** per adult | |

### Statuses

```
 SyncStatus:   PENDING · PROCESSING (internal) · SYNCED · DISPUTED · REJECTED (final)
               FAILED (retryable)
 TicketStatus: ISSUED · PAID · DISPUTED · EXPIRED · TERMINATED · CANCELLED
```

### Endpoints shown in the demo

| Endpoint | Role | Purpose |
|---|---|---|
| `GET /user/qr` | passenger | signed QR, self-healing |
| `POST /user/qr/refresh` | passenger | force a fresh QR |
| `POST /tickets/issue` | conductor | issue (idempotent, `X-Idempotency-Key`) |
| `POST /tickets/scan` | passenger | confirm-screen details (owner only) |
| `POST /sync/upload` | conductor | settle a batch of offline events |
| `GET /sync/status` | conductor | counts per status |
| `POST /payments/wallet` | passenger | pay ISSUED **or DISPUTED** ticket |
| `GET /passenger/tickets`, `/passenger/wallet/balance` | passenger | history, balance |
| `GET /admin/analytics/disputed-tickets` | admin | outstanding disputes |

### Key classes

```
 SyncController → SyncServiceImpl (batch loop, NOT transactional, per-event try/catch)
                → SyncEventProcessor (@Transactional per event: lock, claim,
                                       validate, fare, wallet, outcome)
 SyncEventRepository.findByIdForUpdate   (@Lock PESSIMISTIC_WRITE)
 SyncEvent implements Persistable         (isNew → INSERT, not merge)
 JwtUtil.extractUserIdFromPassengerQrAt   (QR valid at boarding time)
 SyncRetryScheduler · TicketExpiryScheduler · IdempotencyKeyCleanupScheduler
```

---

## 9. psql snippets for the demo

Run from the repo root (loads DB credentials from `infrastructure/.env`):

```bash
set -a && . infrastructure/.env && set +a
PSQL="docker exec -i buslink-postgres psql -U $POSTGRES_USER -d $POSTGRES_DB"
```

```sql
-- Set the passenger's wallet (use their userId)
UPDATE wallet SET balance = 150.00 WHERE user_id = '<userId>' RETURNING balance;

-- Inspect sync events
SELECT status, failed_attempts, left(error_message, 50) FROM sync_event ORDER BY created_at;

-- Prove idempotency: one ticket, one DEBIT for an event
SELECT count(*) FROM transaction WHERE reference_id = '<eventId>';

-- Act 6a: backdate an ISSUED ticket past 24 h
UPDATE ticket SET issued_at = NOW() - INTERVAL '25 hours'
 WHERE ticket_id = '<ticketId>' AND status = 'ISSUED';

-- Act 6b: a retryable FAILED event (gets REJECTED as too old) + one at the cap
INSERT INTO sync_event (event_id, created_at, updated_at, status, failed_attempts, error_message,
  bus_id, conductor_id, destination_stop, issued_at, origin_stop, passenger_qr_token, route_id, user_id)
VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a', now(), now(), 'FAILED', 0,  'demo', gen_random_uuid(),
  '<conductorId>', 'KR Puram Railway Station', now() - interval '10 days', 'HSR Layout', 'qr',
  gen_random_uuid(), gen_random_uuid()),
 ('bbbbbbbb-0000-0000-0000-00000000000b', now(), now(), 'FAILED', 10, 'demo', gen_random_uuid(),
  '<conductorId>', 'KR Puram Railway Station', now() - interval '10 days', 'HSR Layout', 'qr',
  gen_random_uuid(), gen_random_uuid());

-- Clean up demo rows afterwards
DELETE FROM sync_event WHERE event_id IN ('aaaaaaaa-0000-0000-0000-00000000000a',
                                          'bbbbbbbb-0000-0000-0000-00000000000b');
```

**Find the SQL-log proof** (if the app's output goes to `app.log`):

```bash
grep -E "for no key update|insert into sync_event" app.log
```

---

## 10. Troubleshooting during a demo

| Symptom | Cause | Fix |
|---|---|---|
| 401 "Full authentication is required" | token expired (15 min) or variables reset by a re-import | re-run the matching Login |
| Register → 409 | the user already exists | use Login instead |
| Self-pay (step 20) → 400 insufficient | wallet still −₹80 (effective ₹20 < ₹36) | top up with `psql` first |
| Admin disputed list empty | already paid | run it before self-pay |
| Duplicate upload creates nothing new | that's correct (idempotency) | point it out! |
| `processing` always 0 | by design (internal state) | explain diagram 0.5 |
| App won't start: port 8080 in use | IntelliJ instance running | stop it first |
| `contextLoads` / app can't reach DB | containers down or `.env` not loaded | `docker compose up -d`; `set -a && . infrastructure/.env` |
| Retry job "does nothing" | no FAILED events exist (data errors are REJECTED) | insert one with `psql` (Section 9) |
