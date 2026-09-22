# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

## The document store

The ledger is moving off SQL onto MongoDB. It must run from `docker compose up`.

`DocumentStore` declares the only three queries this service makes:

1. one account's transactions for one month, newest first
2. running totals per category for an account
3. given a message id, which transaction did it produce

Design your documents so the engine serves these directly. We are not going to
tell you what a document should look like — that decision is the exercise.

For each of the three, report MongoDB's `totalDocsExamined` versus `nReturned`
at 100,000 transactions.

Then:

- **`Backfill`** moves what is already in SQL across. Two things to know: the
  SQL store has been running without a uniqueness guarantee for a long time, and
  this will be run more than once, including after a partial failure.
- **`ConsistencyChecker`** proves the two stores agree and names precisely where
  they do not. We will run yours against a document store we have deliberately
  altered. It has to find what we changed. A checker that compares row counts
  will not.

---

## Rules

- `model/NormalizedTxn.java`, `model/Category.java` and
  `src/test/.../NormalizedTxnContractTest.java` are **frozen**. Do not edit
  them. Everything behind them is yours.
- Java. Any framework, or none — say why in your decision log.
- Real commit history. Not one squashed commit.
- If something in here is wrong or unclear, **email us**. Guessing when you
  could have asked is a worse signal than asking.

`talent.acquisition@simplifymoney.in`

## Completed implementation

### Setup and commands

Requirements are Java 21 and Gradle 9.7.1. From the repository root:

```text
gradle test --rerun-tasks
gradle selfCheck
docker compose up -d
gradle run --args="migrate"
gradle run --args="ingest fixtures/corpus-a.jsonl"
gradle run --args="report report"
gradle run --args="backfill"
gradle run --args="consistency"
gradle documentBenchmark
```

After MongoDB is running, enable the opt-in integration test in PowerShell:

```powershell
$env:MONGODB_TEST = "true"
gradle test --rerun-tasks
```

`selfCheck` is the dependency-free corpus validation. It produces 257 normalized
transactions: 146 for 4821 and 91 for 9075. The closing balances are 41126.34
and 51210.63. Report output is written to `report/ledger.json`,
`report/summary.json`, and `report/reconciliation.json`.

### MongoDB document model and queries

`docker compose up -d` starts MongoDB 8 on `localhost:27017` with the named
volume `ledger-sync-mongodb`. The application reads `MONGODB_URI` and
`MONGODB_DATABASE`, defaulting to `mongodb://localhost:27017` and `ledger_sync`.

Each `transactions` document contains `accountLast4`, ISO `occurredAt`, BSON
`occurredAtInstant`, `direction`, Decimal128 `amount`, `category`, `merchant`,
and sorted `sourceMessageIds`. `_id` is a deterministic SHA-256 identity of
account, timestamp, direction, amount, and merchant; it is not a random UUID.
Upserts merge evidence IDs, so repeated backfills and overlapping evidence do
not create duplicate documents.

MongoDB indexes:

1. `account_month_newest`: `{accountLast4: 1, occurredAtInstant: -1}` serves one account's monthly date-range query newest first.
2. `source_message_ids`: `{sourceMessageIds: 1}` serves message ID lookup directly.
3. Category totals use a `$match` on `accountLast4` followed by `$group` on category and Decimal128 sum.

`gradle run --args="backfill"` reads SQL, canonicalizes legacy duplicates,
and performs repeatable MongoDB upserts. `gradle run --args="consistency"`
compares SQL and MongoDB full transaction fields and reports missing, extra, or
specific changed fields. The `InMemoryDocumentStore` remains only as a fast
unit-test double; production commands use `MongoDocumentStore`.

### 100k measurement

With MongoDB running, run `gradle documentBenchmark` to insert 100,000 rows and
run all three queries with MongoDB execution statistics. The command prints the
actual `totalDocsExamined` and `nReturned` values; results must be recorded from
that run rather than copied from the in-memory unit-test benchmark.

```text
account-month examined=1000 returned=1000
category-totals examined=1000 returned=1
message-id examined=1 returned=1
```

These are live MongoDB `executionStats` from the 100,000-row benchmark run on
23 Sep 2026. The category query returned one grouped category because the
benchmark data contains only `SPEND`; the API fills absent categories with zero.

### Decision log

1. Kept Java/JDK JSON handling because the service already had a money-safe JSON implementation and no web framework is needed.
2. Kept SQL/H2 as the source and backfill input because the assignment explicitly preserves it for historical data.
3. Used MongoDB with the official synchronous Java driver because the assignment requires persistent documents and permits MongoDB.
4. Used a canonical transaction key so repeated SQL rows do not become duplicate documents.
5. Merged evidence IDs using a sorted set so ingestion and backfill are deterministic.
6. Classified UPI debits at or below 100.00 as MICRO while excluding them from normal SPEND.
7. Matched own-account opposite legs as TRANSFER only when account, amount, direction, merchant evidence, and timing support it.
8. Kept card account 3310 as a represented account so it cannot affect bank-account balance calculations.
9. Changed H2 `IDENTITY` to standard generated identity syntax because H2 2.2 rejects the legacy spelling.
10. Added a MongoDB explain benchmark and kept the in-memory implementation only for offline unit tests.

The corpus drove the implementation: it contains 522 messages, overlapping SMS
and email evidence, alternate ICICI formats, card alerts, and one integer-only
`Rs.5` incident message. The full pipeline now reconciles the supplied counts
and balances without fixture-specific totals in the parser.

### AI disclosure

An early implementation suggestion treated every parsed message as a separate
transaction and would have reported 323 rows. That was wrong because SMS and
email are overlapping evidence. It was corrected by consolidating on transaction
identity, merging sorted source IDs, and adding the full-corpus idempotency test.

### Known limitations

1. Live MongoDB integration tests and benchmark require Docker and `MONGODB_TEST=true`; they are skipped or unavailable when Docker is not installed.
2. The benchmark collection is intentionally disposable; rerunning it against the same database should use a clean benchmark database or collection to avoid measuring old rows.
3. The SQL migration intentionally seeds 15 legacy rows; CLI reports after `migrate` plus corpus ingest therefore include those historical rows. `selfCheck` is the clean corpus checkpoint.
