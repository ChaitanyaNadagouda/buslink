# Sync Race Conditions — Explained Simply

**Status:** ✅ Implemented 2026-10-13 (Option B, Parts A–C, plus `saveAndFlush`
at the claim). Unit-tested; the real locking still needs checking in the SQL log
during S7-22. See [Section 16](#16-what-was-implemented-2026-10-13).
**Sprint:** 7 (`feature/offline-sync`)
**Related:** `docs/sprints/Sprint-07.md` → "Open Items" and "Carry-over"

> **Want to test yourself?** Use **Section 15**. Answer each question before
> opening the hidden answer, and you'll rebuild the whole fix from scratch.
>
> **Short on time?** Read only **Section 0 (Revision Sheet)**. It's all diagrams.
> The rest explains the same thing step by step, in plain words, then maps it to
> the proper technical terms you'll need in interviews.

---

## Table of Contents

0. [Revision Sheet (diagrams only)](#0-revision-sheet-diagrams-only)
1. [The problem in one picture](#1-the-problem-in-one-picture)
2. [How can two requests arrive together? (real scenarios)](#2-how-can-two-requests-arrive-together-real-scenarios)
3. [What happens today: the three cases](#3-what-happens-today-the-three-cases)
4. [Why the `PROCESSING` status doesn't help](#4-why-the-processing-status-doesnt-help)
5. [Why one error breaks the whole upload](#5-why-one-error-breaks-the-whole-upload)
6. [The fix (Option B), simply](#6-the-fix-option-b-simply)
7. [Other fix options we considered](#7-other-fix-options-we-considered)
8. [The proper technical terms (for interviews)](#8-the-proper-technical-terms-for-interviews)
9. [Where this lives in the code](#9-where-this-lives-in-the-code)
10. [Proposed plan: files to change](#10-proposed-plan-files-to-change)
11. [How to verify the fix](#11-how-to-verify-the-fix)
12. [Interview questions](#12-interview-questions)
13. [Questions for the follow-up session](#13-questions-for-the-follow-up-session)
14. [How this was found](#14-how-this-was-found)
15. [Derive it yourself: the 14-question walkthrough](#15-derive-it-yourself-the-14-question-walkthrough)
16. [What was implemented (2026-10-13)](#16-what-was-implemented-2026-10-13)

---

## 0. Revision Sheet (diagrams only)

### 0.1 The whole problem in one line

```
 Our "have I already done this?" check works when requests come ONE AT A TIME,
 but NOT when two copies of the same request arrive AT THE SAME TIME.
```

### 0.2 What the server does for each uploaded ticket (event)

```
   ┌─────────┐      ┌──────────────────────────────┐      ┌─────────┐
   │ 1 CHECK │ ───► │ 2 WORK                       │ ───► │ 3 SAVE  │
   │ "done   │      │ check passenger, calc fare,  │      │ "done!" │
   │ before?"│      │ charge wallet, create ticket │      │         │
   └─────────┘      └──────────────────────────────┘      └─────────┘
        │
        └── yes, done before → return old result (no double charge) ✅
```

### 0.3 The race

```
 Request A:  CHECK "done?" → NO ──► WORK ──────────► SAVE
 Request B:      CHECK "done?" → NO ──► WORK ──────────► SAVE
                         ▲
        A hasn't saved yet, so B ALSO sees "not done" → both do the work ❌
```

### 0.4 Shop analogy

```
   📄 one order slip, two shop assistants

   Assistant A: checks "completed" file → not there → packs parcel 📦
   Assistant B: checks "completed" file → not there → packs parcel 📦
                                                       (A hasn't filed it yet)

   Customer gets 📦📦 for one order.
```

### 0.5 The three cases at a glance

```
 ┌──────┬────────────────────────────┬──────────────────────────┬───────────┐
 │ Case │ Situation                  │ What happens today       │ Money?    │
 ├──────┼────────────────────────────┼──────────────────────────┼───────────┤
 │  1   │ Enough money (SYNCED)      │ charged once ✅           │ safe      │
 │      │                            │ but whole upload errors ❌│           │
 ├──────┼────────────────────────────┼──────────────────────────┼───────────┤
 │  2   │ Not enough money (DISPUTED)│ 2 disputed tickets ❌     │ may pay 2x│
 ├──────┼────────────────────────────┼──────────────────────────┼───────────┤
 │  3   │ Brand-new event, unlucky   │ 2 paid tickets ❌         │ CHARGED 2x│
 │      │ timing                     │                          │           │
 └──────┴────────────────────────────┴──────────────────────────┴───────────┘
```

### 0.6 Why Case 1 is safe but Cases 2 and 3 aren't

```
  Wallet has a "version counter" 🔢 that catches two people updating it at once.

  Case 1:  both requests UPDATE the wallet  → counter clash → 2nd rejected ✅
  Case 2:  wallet is NEVER updated          → counter never checked        ❌
  Case 3:  2nd request reads wallet AFTER   → counter already moved on,
           1st finished                       looks like a normal update   ❌
```

### 0.7 The "Busy" sign that never shows

```
  Plan:    hang "PROCESSING" sign → others see it → they back off
  Reality: sign only becomes visible when you FINISH,
           and by then you've already swapped it for "SYNCED"

           ┌──── inside the request (invisible to others) ────┐
           │  PROCESSING ─────► work ─────► SYNCED              │
           └────────────────────────────────────────────────────┘
                                         ▼ others only ever see this
                              FAILED ─────────────────────► SYNCED
```

### 0.8 How two requests arrive together (real scenarios)

```
 A  Slow network → app times out → resends whole batch   (Case 3, or 2)
 B  Auto-sync + conductor taps "Sync now" together       (Case 3)
 C  App killed mid-upload → restarts → resends            (Case 3)
 D  Proxy/load balancer resends a slow request            (Case 3)  Sprint 9+
 E  Retry job + conductor re-upload, same moment          (Case 2)  TODAY
 F  Two servers both run the retry job                    (Case 2)  Sprint 9+
```

### 0.9 Scenario A: the "catch-up" effect

```
 Request #1:  e1 ✓  e2 ✓  e3 ✓ … e9 ✓  e10 (working… slow)
 Request #2:  e1 ⚡ e2 ⚡ … e9 ⚡ ──────────► e10   ← both on e10 together!
              (already done = instant)
```

### 0.10 The fix: make the second request WAIT

```
 BEFORE                                  AFTER (Option B: lock the event)
 ──────                                  ───────────────────────────────
 A: check → NO → work → save             A: 🔒 lock → check → work → save → 🔓
 B: check → NO → work → save  ❌          B: 🔒 lock … ⏳ waiting …
                                            → check → "already done" → old result ✅

 + brand-new event: force "create new" → 2nd copy fails cleanly (no overwrite)
 + one bad event no longer breaks the whole upload batch
```

### 0.11 One-line lesson

```
  "Idempotency must hold under CONCURRENCY, not just under retries."
   The server can't trust the app to never send twice.
```

---

## 1. The problem in one picture

When the conductor's device uploads an offline ticket (an **event**), the server
does three things:

```
   1. CHECK  →  "Have I already handled this event?"
   2. WORK   →  validate passenger, work out the fare, charge wallet, create ticket
   3. SAVE   →  write down "done"
```

If the check says "already done", the server returns the old result. That's how
we stop a ticket being charged twice. This idea is called **idempotency**: doing
the same thing twice gives the same result as doing it once.

**The catch:** this only works if requests come **one at a time**. If two copies
of the same event arrive **at the same moment**, both reach step 1 before either
has reached step 3:

```
   Request A:  CHECK "done?" → no ──► WORK ──► SAVE
   Request B:     CHECK "done?" → no ──► WORK ──► SAVE
                          ▲
             A hasn't saved yet, so B also sees "not done"
```

**Both do the work.** Two assistants, one order slip, two parcels.

This pattern is called a **check-then-act race**: the gap between *checking*
and *acting* lets someone else slip in.

---

## 2. How can two requests arrive together? (real scenarios)

These aren't made up. They come from how real offline-first apps on moving
buses behave.

### Case 3 scenarios: a brand-new event sent twice

#### Scenario A: slow network, the app gives up and resends (most likely)

The bus has been offline for an hour. The phone has 40 unsynced tickets. The bus
reaches a stop and gets weak signal.

```
 10:00:00  App uploads 40 events ─────────────► Server starts (request #1)
           (~10 DB queries per event → 40 events ≈ 8–10 seconds)

 10:00:05  App's 5-second timeout fires. No reply yet.
           App thinks "failed" → sends SAME 40 events ─► request #2
```

The server now runs **two requests for the same 40 events at once**. Request #1
didn't fail. It was just slow.

**Why it's more likely than it sounds: the catch-up effect.**

```
 Request #1:  e1 ✓  e2 ✓  e3 ✓ … e9 ✓   e10 (working on it…)
 Request #2:  e1 ⚡ e2 ⚡ e3 ⚡ … e9 ⚡ ──► e10
                                          ▲
                   both now on e10 at the same moment
```

Events #1 already finished return their saved result instantly, so #2 races
through them and **catches up** with #1. Now both are processing the same new
event together.

#### Scenario B: auto-sync and the manual button fire together

Offline-first apps usually sync in two ways:
- **automatically**, when the phone detects the network is back
- **manually**, when the conductor taps "Sync now"

```
  📶 network returns
     ├──► auto-sync starts upload ─────────────┐
     └──► conductor sees bars, taps "Sync now" ┴──► 2 uploads, same events
```

#### Scenario C: the app is killed mid-upload

```
  upload in progress ──► 📱 app killed (low memory / swiped away / battery)
                          server keeps processing request #1 (doesn't know)
  app restarts ──► events still "unsynced" on device ──► uploads again
                   (request #1 may still be running)
```

#### Scenario D: proxy or load balancer retries (Sprint 9+)

Once nginx or an AWS load balancer sits in front of the app, some setups
**resend a slow request to another server automatically**. The same upload
runs twice, and the app didn't resend anything.

### Case 2 scenarios: an earlier-FAILED event processed twice

Case 2 needs an event that **failed earlier** (for example, a brief DB glitch)
and is then picked up by two things at once. On the second attempt the wallet is
too low, so it becomes DISPUTED.

#### Scenario E: retry job + conductor re-upload (possible today)

```
 10:00  Upload: event e7 FAILED (temporary DB glitch)
 10:05  ⏰ Retry job wakes up → starts retrying e7  ─────┐
 10:05  📱 Conductor's app re-uploads batch (incl. e7) ──┤ same moment
                                                         ▼
               both see e7 = FAILED → both create a DISPUTED ticket ❌
```

**Why would the app re-upload e7 at all?** Because of
[Section 5](#5-why-one-error-breaks-the-whole-upload): when something goes
wrong, the device gets **one error for the whole batch**, can't tell what got
through, and re-sends everything. One problem sets up the next.

#### Scenario F: two servers both run the retry job (Sprint 9+)

```
  Server 1 ── every 5 min ──► retry FAILED [e1, e2, e3] ─┐
                                                         ├─► same events, same moment
  Server 2 ── every 5 min ──► retry FAILED [e1, e2, e3] ─┘
```

Impossible today (one server). After scaling out it would happen on almost
**every** cycle. Fix at that point: **ShedLock** (only one server gets to run
the job at a time).

**Note:** Case 3's scenarios also produce Case 2 when the wallet is low: two
DISPUTED tickets instead of two PAID ones. Same race, different branch.

### Scenario summary

| Scenario | Case | Exists today? |
|---|---|---|
| A. Slow network → app timeout resend | 3 (or 2) | Yes, once the app exists |
| B. Auto-sync + manual tap | 3 | Yes, once the app exists |
| C. App killed and restarted mid-upload | 3 | Yes, once the app exists |
| D. Proxy/load balancer retry | 3 | Sprint 9+ |
| E. Retry job + conductor re-upload | 2 | **Yes, the server side already does this** |
| F. Two servers' retry jobs | 2 | Sprint 9+ |

---

## 3. What happens today: the three cases

First, one helper idea. The **wallet has a version counter** 🔢. Every time the
wallet is saved, the counter goes up by one. When saving, the database checks:
*"is the counter still what it was when I read it?"* If not, someone else
changed the wallet in the meantime, so the save is rejected.

(Technical name: **optimistic locking**, the `@Version` field on `Wallet`.)

### Case 1: passenger has enough money (SYNCED) → money safe ✅, upload messy ❌

```
 Request A:  read wallet (counter 5) → charge ₹90 → save (counter 5→6) ✅
 Request B:  read wallet (counter 5) → charge ₹90 → save … counter is 6, not 5!
                                                      → REJECTED ✅
```

- Money is charged **only once**. ✅
- **But** that rejection happens at the very last moment and crashes **the whole
  upload batch**. See [Section 5](#5-why-one-error-breaks-the-whole-upload). ❌

### Case 2: passenger doesn't have enough money (DISPUTED) → real bug ❌

When the wallet is too low, we **don't touch the wallet at all**. We just create
a DISPUTED ticket. No wallet save means **no counter check**, so nothing notices
the duplicate.

```
 Wallet: -₹80, overdraft ₹100 → can spend ₹20 → fare ₹90 → too low → DISPUTED

 Request A                               Request B
 ─────────                               ─────────
 check e7 → FAILED (go ahead)            check e7 → FAILED (go ahead)
 wallet too low                          wallet too low
 create ticket #1 (DISPUTED)             create ticket #2 (DISPUTED)
 mark e7 DISPUTED → save ✅               mark e7 DISPUTED → waits for A…
                                         …then saves over it ✅ (no check!)

 Result:  🚌 1 ride ──► 🎫🎫 2 DISPUTED tickets ──► "Pay Now" shows twice
```

- The passenger could **pay twice** for one ride.
- The admin's disputed-tickets report shows **two** fare-evasion entries.

### Case 3: brand-new event, sent twice at the wrong moment → worst ❌

This one has an extra twist. When our code says "save this event", Spring Data
has to decide **"is this new (create it) or existing (update it)?"** Our events
carry an ID made by the phone, so Spring Data **can't tell**, and always does
*"look it up first, then create or update"* (technical name: **merge**).

That "look it up first" happens at a **different moment** from our own check,
which opens a second gap:

```
 Request A (1st upload)                   Request B (timeout resend)
 ──────────────────────                   ──────────────────────────
 check e10 → not found                    check e10 → not found
 save → looks up → none → CREATE
 charge wallet ₹150 → ₹60
 ticket #1 PAID
 FINISHED ✅ ─────────────────────────────►
                                          save → looks up → FOUND A's record!
                                            → OVERWRITES it (instead of failing)
                                          reads wallet: ₹60 (fresh, counter fine)
                                          charge ₹90 → -₹30
                                          ticket #2 PAID
                                          FINISHED ✅

 Result:  🚌 1 ride ──► 🎫🎫 2 PAID tickets ──► 💸💸 charged twice
```

**Why didn't the version counter catch it?** B read the wallet **after** A had
completely finished. To the counter it looks like a normal, separate payment.
Nothing is out of date.

The window is tiny (B's check must happen before A finishes, and B's save after),
but Scenario A's catch-up effect is exactly what lands in tiny windows.

---

## 4. Why the `PROCESSING` status doesn't help

The plan (Sprint-07.md, S7-14 Step 3) was: mark the event `PROCESSING` at the
start, so a second request sees it and backs off.

**The rule that breaks it:** while a request is running, its changes are
**invisible to everyone else** until it **finishes**. (Technical name: Postgres's
default isolation level, **READ COMMITTED**.)

```
 ┌──────────── inside request A (nobody else can see this) ────────────┐
 │  set PROCESSING ───► do the work ───► set SYNCED                    │
 └──────────────────────────────────────────────────────────────────────┘
                                                │ FINISH
                                                ▼
   what everyone else sees:   FAILED ─────────────────────► SYNCED
                                       (PROCESSING never appears)
```

It's like hanging a **"Busy" sign** on a door, but only after you've left the
room. Nobody ever sees it.

**Side effect you'll notice in Postman:** `GET /sync/status` always reports
`processing: 0`. That's expected, not a bug in the test.

**Rule for "claim" flags:** a status flag only works as a "this is mine" claim if
it's (1) **saved and visible before** the work starts, and (2) **only one
request can win it**. Ours is neither.

---

## 5. Why one error breaks the whole upload

Each event is processed in its own little "all-or-nothing" unit (a
**transaction**). Spring wraps the method like this:

```
   loop ──► [ START ] ──► processEvent() code ──► returns ──► [ FINISH / SAVE ]
                              ▲                                      │
                   our try/catch is in here            errors at this point
                                                       happen OUTSIDE the
                                                       try/catch ──► go to the loop
```

The final "save everything" step (where the version-counter check happens)
runs **after** our method has already returned, so our `try/catch` **can't
see** that error. It goes up to the batch loop, which has **no** `try/catch`:

```
 Upload batch: [ e1 ][ e2 ][ e3 ][ e4 ]
                 ✅    ❌ error at the final save
                        │
                        └──► loop stops ──► e3, e4 never processed
                                       ──► device gets ONE error (HTTP 409)
                                           for the whole batch
```

- e2 is fully undone, so **money is safe**. ✅
- e3 and e4 are skipped. The device can't tell e1 succeeded. ❌
- The device re-uploads everything. e1 returns "already done", the rest get
  processed. **It recovers**, but this re-upload is exactly what sets up
  Scenario E.

The retry job behaves the same way: one error ends that run, and the remaining
FAILED events wait 5 more minutes.

---

## 6. The fix (Option B), simply

### Part A: make the second request wait in line

When we look up an event, **lock it**. Anyone else who wants the same event
has to wait until we finish.

```
 Request A:  🔒 lock e7 → check → work → save → 🔓 unlock
 Request B:  🔒 lock e7 … ⏳ waiting … → check → "already DISPUTED"
                                         → returns A's result ✅ (no 2nd ticket)
```

Shop analogy: whoever picks up the order slip **holds onto it** until it's
filed. The other assistant waits, then sees it's done.

(Technical name: **pessimistic locking**, `SELECT ... FOR UPDATE`.)
→ Fixes **Case 2**, and Case 1 now ends calmly instead of with an error.

### Part B: brand-new events must be "created", never "overwritten"

You can't lock something that doesn't exist yet, so Part A doesn't help Case 3.
Instead, tell Spring Data plainly: **"this is new, just create it."** If a second
copy tries to create the same ID, the database refuses it (duplicate ID), and
that request is undone. No overwrite, no second charge.

```
 Request A:  CREATE e10 ✅ … finishes
 Request B:  CREATE e10 ❌ "already exists" → whole request undone → no charge ✅
```

(Technical name: implement **`Persistable`** on `SyncEvent` so Spring Data does a
plain **INSERT** instead of a **merge**.)
→ Fixes **Case 3**.

### Part C: one bad event must not break the whole batch

Wrap each event in its own `try/catch` **in the loop** (outside the
all-or-nothing unit, where it *can* see final-save errors). If one fails, mark
it "FAILED, please re-upload" and **carry on** with the rest. Same for the retry
job's loop.

```
 Upload batch: [ e1 ✅ ][ e2 ❌ → "please re-upload" ][ e3 ✅ ][ e4 ✅ ]
               device gets a result for EVERY event
```

→ Fixes [Section 5](#5-why-one-error-breaks-the-whole-upload).

### Not changed by this fix
- `PROCESSING` stays as an internal label. We document honestly that it's never
  visible, rather than build extra machinery to make it real (see Option C).
- Two-server retry jobs (Scenario F) stay a future item: **ShedLock** in
  Sprint 9 if we scale out. With Parts A to C in place, a duplicate run would
  only waste a bit of work, not create wrong data.

---

## 7. Other fix options we considered

```
 Option A  version counter on SyncEvent     2nd request ──► ❌ error at save
 Option B  lock the event row  (CHOSEN)     2nd request ──► ⏳ waits ──► ✅ right answer
 Option C  "claim" it first, separately     2nd request ──► sees "claimed" ──► skips
```

| Option | In simple words | Good | Not so good |
|---|---|---|---|
| **A** | Give `SyncEvent` its own version counter like the wallet | Smallest change; also fixes Case 3 | The 2nd request still crashes with an error (needs Part C anyway); adds a DB column |
| **B** ✅ | Lock the event while working on it | The 2nd request gets the **correct answer**, not an error | Holds a lock for one event's duration; needs Part B for brand-new events |
| **C** | Save "PROCESSING" in its own tiny step first, and only proceed if you were the one who set it | Makes `PROCESSING` actually work | If the server crashes mid-way, the event is stuck in PROCESSING forever → needs a cleanup job to un-stick it. More moving parts. |

---

## 8. The proper technical terms (for interviews)

| Plain words used above | Technical term | One-line meaning |
|---|---|---|
| "doing it twice = doing it once" | **Idempotency** | Same request repeated → same result, no extra side effects |
| check, then act, gap in between | **Check-then-act race condition** | Two parties both pass a check before either acts |
| changes invisible until you finish | **READ COMMITTED isolation** (Postgres default) | A transaction only sees other transactions' *committed* data |
| "all-or-nothing unit" | **Transaction** (`@Transactional`) | Everything inside commits together or rolls back together |
| final save happens after the method returns | **Proxy-based transactions + commit-time flush** | Spring commits after the method; Hibernate sends SQL late, so errors appear outside the method |
| wallet's version counter | **Optimistic locking** (`@Version`) | `UPDATE ... WHERE version = ?`; 0 rows → `OptimisticLockException`. Only guards rows you **update** |
| lock the event, others wait | **Pessimistic locking** (`SELECT ... FOR UPDATE`, `@Lock(PESSIMISTIC_WRITE)`) | Row locked until commit; others wait, then read the committed result. Can't lock a missing row |
| "look it up first, then create or update" | **`merge`** (vs **`persist`**) | Spring Data `save()` merges when it can't tell the entity is new (client-assigned ID, no `@Version`) |
| tell Spring Data "this is new" | **`Persistable<ID>`** with an `isNew` flag | Forces `persist` → plain INSERT → duplicate ID fails on the primary key |
| only one server runs the job | **Distributed scheduler lock** (**ShedLock**) | A lock row in a shared DB table decides which instance runs a `@Scheduled` job |

---

## 9. Where this lives in the code

| Location | What to look at |
|---|---|
| `service/SyncEventProcessor.java:66-82` | `processEvent()`, `@Transactional`: check → set `PROCESSING` → save → `resolve()` inside `try/catch` |
| `service/SyncEventProcessor.java:90-113` | `findOrInitSyncEvent()`: plain `findById` (**no lock**) |
| `service/SyncEventProcessor.java:202-219` | SYNCED branch: **updates `Wallet`** (version counter checked) |
| `service/SyncEventProcessor.java:221-223` | DISPUTED branch: **wallet not touched** (no counter check) |
| `service/impl/SyncServiceImpl.java:37-47` | Batch loop: **no `try/catch`** |
| `scheduler/SyncRetryScheduler.java:26-38` | Retry job loop: **no `try/catch`** |
| `exception/GlobalExceptionHandler.java:60-73` | Turns final-save errors into one HTTP 409 for the whole request |
| `entity/SyncEvent.java` | ID made by the phone, **no `@Version`**, **no `Persistable`** → `save()` always merges |
| `entity/Wallet.java:48` | `@Version`, the wallet's version counter |

---

## 10. Proposed plan: files to change

| File | Change | Fixes |
|---|---|---|
| `repository/SyncEventRepository.java` | + `findByIdForUpdate` with `@Lock(PESSIMISTIC_WRITE)` | Case 2 (Part A) |
| `service/SyncEventProcessor.java` | use the locked lookup; mark brand-new events as new | Parts A and B |
| `entity/SyncEvent.java` | implement `Persistable<UUID>` (transient `isNew` flag, reset on load/save) | Case 3 (Part B) |
| `service/impl/SyncServiceImpl.java` | `try/catch` around each event in the loop | Section 5 (Part C) |
| `scheduler/SyncRetryScheduler.java` | `try/catch` around each event in the loop | Section 5 (Part C) |
| `SyncEventProcessorTest` | 9 stubs `findById` → `findByIdForUpdate` | — |
| new `SyncServiceImplTest` | one event throws → rest of the batch still processed | Part C proof |
| `docs/sprints/Sprint-07.md` | correct open item 2; record as an approved deviation | — |

```java
// Part A, the core of it
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM SyncEvent s WHERE s.eventId = :eventId")
Optional<SyncEvent> findByIdForUpdate(@Param("eventId") UUID eventId);
```

---

## 11. How to verify the fix

```
  Unit tests (mocks) ──────► prove Part C (loop keeps going) ✅
                             CAN'T prove locking or create-vs-overwrite ❌

  SQL log (show-sql) ──────► lookup shows "for update"            ✅
                             new event shows plain "insert", no
                             "select" before it                    ✅

  Two psql terminals ──────► see the "waiting" with your own eyes ✅

  Integration test ────────► two threads, real Postgres, assert
  (Testcontainers)           exactly ONE ticket exists             ✅ (out of scope for now)
```

**Two-terminal demo** (shows Part A's waiting behaviour):
```sql
-- Terminal 1
BEGIN; SELECT * FROM sync_event WHERE event_id = '<id>' FOR UPDATE;
-- (leave it open)

-- Terminal 2: hangs here until Terminal 1 runs COMMIT or ROLLBACK
BEGIN; SELECT * FROM sync_event WHERE event_id = '<id>' FOR UPDATE;
```

---

## 12. Interview questions

- **"Your API is idempotent. What if two identical requests arrive at the same
  time?"**
  → Check-then-act race. Idempotency must hold **under concurrency**, not just
  sequentially. Use a row lock, a unique constraint, or an atomic conditional
  update.
- **"Optimistic vs pessimistic locking: when do you use which?"**
  → Optimistic when clashes are rare and the loser can just retry. Pessimistic
  when the loser should **wait and see the result** (our case) or clashes are
  costly. And optimistic only protects rows you actually **update**.
- **"Why can't you catch a commit error inside a `@Transactional` method?"**
  → Spring commits **after** the method returns, and Hibernate sends SQL late.
- **"What does Spring Data `save()` actually do?"**
  → `persist` if it thinks the entity is new, otherwise `merge`. Client-assigned
  IDs with no `@Version` always merge. Fix with `Persistable`.
- **"Why didn't your PROCESSING flag prevent double processing?"**
  → Uncommitted changes are invisible under READ COMMITTED. A claim must be
  committed first and winnable by only one party.
- **"How do you run scheduled jobs safely on several servers?"**
  → ShedLock or leader election, and make the job itself idempotent anyway.
- **"Why not just tell the mobile team never to send twice?"**
  → Timeouts, crashes, proxies and auto-retries create duplicates anyway. The
  server must be correct regardless.

---

## 13. Questions for the follow-up session

1. Walk the Case 2 timeline step by step. At which step could each guard
   (status flag, version counter, row lock, duplicate-ID check) have stopped it?
2. Why does Case 1 get protected by **failing**, while Option B lets the second
   request **succeed with the right answer**?
3. In Case 3, why doesn't the wallet's version counter catch the double charge?
4. In Scenario A, why does the catch-up effect make the race much more likely?
5. What would change under SERIALIZABLE isolation instead of READ COMMITTED?
   (Trade-off: lots of "please retry" errors everywhere.)
6. Option C: what is a "stuck claim", and why does it need a cleanup job?
7. When would holding a row lock be a bad idea? (Hint: a very busy row.)
8. Should `PROCESSING` be removed, kept as a label, or made real?
9. Is `FOR UPDATE` on a missing row a no-op in Postgres? What about MySQL's gap
   locks? (Different databases behave differently.)

---

## 14. How this was found

1. At the start of the 2026-10-11 session, `Sprint-07.md` described the
   `PROCESSING` issue as a **known limitation, not a correctness bug**. The
   reasoning was that the wallet's version counter would turn any clash into
   *"one cleanly-FAILED duplicate attempt."*
2. Re-reading the actual code against that claim showed two errors in it:
   - the clash doesn't fail "cleanly", it breaks the whole batch (Section 5);
   - in the DISPUTED case the wallet isn't touched, so the counter doesn't
     protect anything (Case 2).
3. While planning Option B, a third hole appeared: brand-new events can't be
   locked, and `save()` overwrites instead of failing (Case 3).
4. The real-world scenarios (Section 2) were worked through afterwards, to
   answer "how could two requests even arrive together?"
5. The fix is **parked** until this has been walked through properly. Nothing
   in the code has changed yet.
6. On 2026-10-11 the fix was **derived question by question** (Section 15)
   rather than handed over. Implementation is planned for the next session,
   done the same way: one step at a time, driven by questions.

---

## 15. Derive it yourself: the 14-question walkthrough

This is the question-by-question path we used on 2026-10-11 to arrive at the
fix. **Answer each one in your head first**, then open the answer. Questions
build on each other, so go in order.

```
  Q1        Q2–Q3         Q4        Q5–Q8              Q9–Q12            Q13–Q14
  goal ──► where does ──► how ──► when to lock ──► brand-new events ──► don't break
           the lock       much     & release         (no row to lock)    the batch
           live?          to lock?
  └────────────── Case 2 (row exists) ──────────────┘└─── Case 3 ───┘└─ Part C ─┘
```

---

### Part 1: Case 2 (the row already exists)

**Scene:** event e7 is FAILED in the DB, the wallet is too low, and two
requests process e7 at the same time. Both check, both see `FAILED`, both
create a DISPUTED ticket. The check itself is correct:
`if (status == SYNCED || status == DISPUTED) return existingResult;`

#### Q1. The check is correct, so why didn't it stop B? What must be different about *when* B checks?

<details><summary>Answer</summary>

B must check **after A has finished saving**. Then B sees `DISPUTED` and stops.
The duplicate isn't two different events: it's the **same event (e7)
processed twice**, producing two tickets.

```
 A:  check ──► work ──► save ✅
 B:                            check → sees DISPUTED → stop ✅
```
</details>

#### Q2. A and B don't know about each other. What could act as a "traffic signal" that both go through, letting A in and making B wait?

<details><summary>Answer</summary>

First idea: **Java `synchronized`**. Only one thread can be inside at a time.
On paper it makes B wait. Q3 and Q8 test whether it really works.
</details>

#### Q3. With two running copies of BusLink (Sprint 9: two machines behind a load balancer, one shared Postgres), A lands on copy #1 and B on copy #2. Does `synchronized` make B wait?

<details><summary>Answer</summary>

**No. B goes straight in.** A `synchronized` lock lives in **one copy's
memory**. Copy #2 has its own memory and its own lock, and knows nothing about
copy #1's. The only thing all copies share is **the database**, so the lock
must live there.

```
   🏦 Branch 1: token machine #1      🏦 Branch 2: token machine #2
          └────────► 🗄 same central database ◄────────┘
   (each branch's machine only queues its own customers)
```

**Clarification: what "server" means here.** One *server/instance* = one
running copy of the BusLink app (a JVM, e.g. `./mvnw spring-boot:run`). Same
codebase, started several times, each copy with its own memory, all talking to
**one** Postgres. Phones are clients, not servers.
</details>

#### Q4. 50 conductors are uploading different events. Only A and B fight over e7. Lock the whole `sync_event` table, or just the e7 row?

<details><summary>Answer</summary>

**Just the e7 row.** The other 49 conductors carry on untouched; only the two
requests fighting over e7 wait. Rule: **lock as little as possible, for as
short a time as possible.**

```
 │ e1     │ SYNCED   │  free
 │ e7  🔒 │ FAILED   │  A holds it, B waits
 │ e9     │ PENDING  │  free
```
</details>

#### Q5. If A takes the lock only at step 3 (SAVE), does that stop the duplicate? At which step must A lock?

<details><summary>Answer</summary>

**No. By then B has already checked** and decided to go ahead. The lock has to
cover the **check**. The check becomes "read this row **and hold it** until
I'm done."

```
 A:  🔒 check ──► work ──► save ──► 🔓
 B:  🔒 ⏳ waits ...................► check → DISPUTED → stop ✅
```
</details>

#### Q6. A plain `SELECT` never locks. Which clause makes it "read this row because I'm about to update it"?

<details><summary>Answer</summary>

```sql
SELECT * FROM sync_event WHERE event_id = 'e7' FOR UPDATE;
```
In Spring Data: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository query.
Technical name: **pessimistic locking**.
</details>

#### Q7. If A releases the lock right after calling `save()` but before COMMIT, what does B see? So when must A release?

<details><summary>Answer</summary>

B sees **`FAILED`**. A's change is still a draft only A can see. So the lock
must be held **until COMMIT**. Postgres does this automatically: `FOR UPDATE`
locks are released at COMMIT (or ROLLBACK). No manual unlock is needed.

**Clarification: the gap between `save()` and COMMIT.** A change goes through
three stages:

```
 save() called          →  FLUSH: SQL sent         →  COMMIT
 (in Hibernate's memory)   (draft inside A's          (permanent + visible
  invisible to B            transaction, still          to everyone) ✅
                            invisible to B)
```
`save()` = typing into a form · flush = autosaved draft · commit = pressing
Submit. In our code, COMMIT is done by Spring **after `processEvent()`
returns**, so almost all the work sits in that gap.
</details>

#### Q8. Bonus: with just ONE running copy, why does `synchronized` still fail?

<details><summary>Answer</summary>

`synchronized` releases **when the method returns**, which is **before** Spring
commits. B gets in during that gap, still sees `FAILED`, and creates a
duplicate.

```
                         body runs          method returns    COMMIT
                         ───────────────────────┼───────────────┼──►
 synchronized (Java)     🔒 ────────────────────► 🔓 (too early ❌)
 FOR UPDATE (Postgres)   🔒 ─────────────────────────────────────► 🔓 ✅
```
So `synchronized` fails twice: across copies (Q3) **and** on one copy (Q8).
</details>

---

### Part 2: Case 3 (a brand-new event, no row yet)

**Scene:** the phone times out and resends. Request A and Request B carry
**the same event e10** (same `eventId`, created by the phone). There's no e10
row in the table yet.

```
 📱 phone creates e10 ──► Request A { eventId: e10 }
                     └──► Request B { eventId: e10 }   (resend after timeout)
     1 event, 2 requests → should give 1 ticket
```

#### Q9. Both run `SELECT ... FOR UPDATE` for e10. What do they find, what gets locked, and does B wait?

<details><summary>Answer</summary>

**Nothing found, so nothing locked, so B doesn't wait.** `FOR UPDATE` can only
lock rows that exist. Brand-new events need a different guard, one that acts
**at the moment of creating**.
</details>

#### Q10. `event_id` is the PRIMARY KEY. What does Postgres do if two requests both INSERT e10?

<details><summary>Answer</summary>

It **rejects the second INSERT** with a duplicate-key error, and that request
rolls back: no second ticket, no second charge.

If A has inserted but not committed yet, Postgres makes B's INSERT **wait**:
if A commits, B gets duplicate key; if A rolls back, B's insert succeeds.
Either way **exactly one request owns e10**.
</details>

#### Q11. Our code calls `save()`, which for `SyncEvent` does "SELECT first, then INSERT or UPDATE". If A already committed e10, what does B's `save()` do? Does the PK ever get a chance?

<details><summary>Answer</summary>

B's SELECT **finds A's row**, so B does an **UPDATE** (overwrite). The PK rule
only applies to INSERTs, so **it never triggers**. B then charges again.

**Clarification: why `save()` does a SELECT for a new event.** We know it's
new, but `save()` guesses from the ID: *ID null → new → INSERT (`persist`);
ID filled in → maybe existing → SELECT first (`merge`).* `Ticket`'s ID is
generated by the DB (null before save → INSERT). `SyncEvent`'s ID comes from
the phone (never null) → always `merge`.
</details>

#### Q12. If we could tell `save()` "trust me, this is new", what would it do, and what happens to B?

<details><summary>Answer</summary>

It does a plain **INSERT**. B gets **duplicate key → rollback**.
How: the entity implements **`Persistable`**, so it answers `isNew()` itself.

**Clarification: how does A's ID reach B?** It doesn't. Both requests already
carry e10 from the phone. A and B never talk. **Postgres is the meeting
point**: it checks B's INSERT against the row A put there. `Persistable` adds
no new protection; it just stops `save()` from **going around** the primary key
that was there all along.

```
 WITHOUT Persistable:  B: SELECT → found → UPDATE   (PK never checked) ❌
 WITH Persistable:     B: INSERT → duplicate key → rollback            ✅
```
</details>

---

### Part 3: don't let one rejection break the batch

#### Q13. B's duplicate-key error usually fires at COMMIT, outside `processEvent()`. Where does it go, what happens to B's other 39 events, and where do you put the `try/catch`?

<details><summary>Answer</summary>

It goes **to the loop**, which has no `try/catch`, so **the batch stops** and
e11..e40 are never processed. Fix: **`try/catch` around each `processEvent()`
call in the loop.** The loop is outside the transaction, so it can catch
COMMIT errors. The same goes for the retry job's loop.

```java
for (SyncEventRequestDTO event : batch.events()) {
    try {
        results.add(syncEventProcessor.processEvent(event, conductorId));
    } catch (Exception ex) {
        results.add(/* FAILED, "please re-upload" */);
    }   // loop continues ✅
}
```

**Who wins?** Whoever INSERTs first; send order doesn't matter. The loser's
error is caught and its other events continue. If the phone sees "re-upload",
the next upload's locked check finds the winner's result and returns it.
</details>

#### Q14. In that `catch`, should we also save e10 as `FAILED` in the DB?

<details><summary>Answer</summary>

**No.** The DB already holds the winner's `SYNCED`. Overwriting it with
`FAILED` would make the retry job reprocess e10 → ticket #2 and a **second
charge**, recreating the very bug we fixed.

```
 DB: e10 = SYNCED ──(loser saves FAILED ❌)──► e10 = FAILED
     ──► retry job ──► 🔒 → FAILED → go ahead → charged AGAIN 💸💸
```
**Rule: a loser's rollback writes nothing. The winner's committed result is
the truth.**
</details>

---

### What the 14 answers add up to

```
 for each event in batch:                         ← try/catch here (Q13, Q14)
    ┌─ transaction ─────────────────────────────┐
    │ 🔒 SELECT ... FOR UPDATE (row exists)      │ ← Q1–Q8:  loser waits, sees truth
    │    or INSERT as new (row missing)         │ ← Q9–Q12: loser hits duplicate key
    │ work → save                                │
    └─ COMMIT → 🔓 ──────────────────────────────┘
```

| Q | Insight | Becomes |
|---|---|---|
| 1 | B must check after A finishes | the goal |
| 2–3 | Java locks live in one copy's memory | lock in the DB |
| 4 | Lock one row, not the table | row lock |
| 5–6 | Lock at the check | `SELECT ... FOR UPDATE` |
| 7 | Hold until COMMIT | Postgres releases at commit |
| 8 | `synchronized` releases before commit | wrong tool, even on one copy |
| 9 | Missing row = nothing to lock | need another guard |
| 10 | PK rejects duplicate INSERT | DB's built-in guard |
| 11 | `save()` merges → UPDATE → PK bypassed | the `merge` trap |
| 12 | Force INSERT | `Persistable` (Part B) |
| 13 | COMMIT errors escape the method | `try/catch` in the loop (Part C) |
| 14 | Loser writes nothing | winner's result stays the truth |

---

## 16. What was implemented (2026-10-13)

Built step by step, led by questions. The user made each design decision; the
assistant wrote the syntax.

```
 for each event in batch:  processIsolated() ── try/catch ─────────────── Part C
   ┌─ @Transactional processEvent ──────────────────────────────────────┐
   │ findByIdForUpdate(eventId)                                         │ Part A
   │   ├─ found   → 🔒 row locked; a 2nd request waits, then sees truth │
   │   └─ missing → build new SyncEvent (isNew = true)                  │ Part B
   │ saveAndFlush(PROCESSING)                                           │ ★ extra
   │   └─ new row → INSERT sent NOW; a duplicate loser waits, then      │
   │                gets duplicate key HERE, before any work            │
   │ resolve(): validate → fare → wallet → ticket → save                │
   └─ COMMIT → 🔓 ──────────────────────────────────────────────────────┘
```

| Part | File | Change | Decided by |
|---|---|---|---|
| A | `repository/SyncEventRepository.java` | new `findByIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)` + JPQL `@Query`); `findById` untouched so plain reads stay lock-free | new method, not an override (so reads don't pay for the lock) |
| B | `entity/SyncEvent.java` | `implements Persistable<UUID>`; `@Transient @Builder.Default boolean isNew = true`; `getId()`, `isNew()`; `markNotNew()` on `@PostLoad` + `@PrePersist` | default `true` (fails loudly, not silently); true only when built fresh |
| ★ | `service/SyncEventProcessor.java` | lookup → `findByIdForUpdate`; claim save → `saveAndFlush` | B should find out at the **start**, not after doing all the work |
| C | `service/impl/SyncServiceImpl.java` | per-event `processIsolated()`: catch **everything**; collisions → WARN, anything else → ERROR (bugs stay visible); returns FAILED *"Could not be processed — please re-upload"*; **saves nothing**; raw exception text never sent to the client | catch everything so one bad event can't skip the rest |
| C | `scheduler/SyncRetryScheduler.java` | same per-event try/catch; logs an errored count | — |

**Choices considered and rejected along the way:**
- Java `synchronized`: lives in one instance's memory, and releases at method
  return (before commit). Fails even on a single instance.
- `INSERT ... ON CONFLICT DO NOTHING` + lock, or a Postgres advisory lock: both
  would let the loser **wait and get the correct answer** instead of an error,
  but they're Postgres-specific native SQL. Kept `Persistable` (pure JPA); the
  loser's error is caught, and a re-upload returns the winner's result.
- `@PostPersist` instead of `@PrePersist`: with a client-assigned ID Hibernate
  may delay the INSERT until commit, so `@PostPersist` could fire too late.
- The Lombok trap: `@Builder` ignores field initialisers. `@Builder.Default`
  is what makes `= true` stick (pinned by a test).

**Tests (98/98 pass):**
- `SyncEventProcessorTest` (9): stubs switched to `findByIdForUpdate` / `saveAndFlush`.
- new `SyncServiceImplTest` (2): a losing event mid-batch → the rest still
  processed, the loser is FAILED with a generic message, **no repository
  writes**; an unexpected exception → same isolation.
- new `SyncEventTest` (3): built fresh → `isNew()` true; after `markNotNew()`
  → false; `getId()` returns `eventId`.
- new `SyncRetrySchedulerTest` (1): one event throws → the next is still retried.
- `BusLinkApplicationTests.contextLoads`: app boots against real Postgres, so
  the locked JPQL query is accepted at startup.

**Not provable with mocks. Check during S7-22:** with SQL logging on, the sync
lookup shows `for no key update`, and a new event shows a plain
`insert into sync_event` with no `select` before it.

**Still open (unchanged):** `PROCESSING` remains internal and never visible
(`GET /sync/status` → `processing: 0`); ShedLock for multiple instances is
Sprint 9+ scope.
