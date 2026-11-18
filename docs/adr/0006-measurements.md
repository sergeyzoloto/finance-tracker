# HIST-0 measurements

All queries were run read-only (`SET default_transaction_read_only = on`) with
`docker exec -i finance-tracker-rehearsal-db psql -X -U postgres -d finance` on 2026-11-18. The database
was already running; nothing was started, recreated or written. Nothing was run against production.

Caveats, read first:

- The rehearsal database is a production restore with the two participants' subs mapped onto dev users. Its data
  ends around 2026-11-16. It holds one real family budget (ledger 229, 12,634 records) and two real personal
  ledgers (264 and 228), plus small test ledgers (15, 1777, 1778).
- `pg_stat_user_tables` counters are not usable for "how many rows were updated in production": the rehearsal's
  sub mapping updated every row (`journal_entry.n_tup_upd` = 43,024 on a fresh restore). I used `updated_at`
  instead, and say where that is only an upper bound.
- Row counts below are exact (`count(*)`); the size query uses `reltuples` for the estimate column only.

## 1. Rows and size per table (today)

```sql
SELECT c.relname AS tbl, c.reltuples::bigint AS est_rows,
 pg_size_pretty(pg_table_size(c.oid)) AS heap, pg_size_pretty(pg_indexes_size(c.oid)) AS idx,
 pg_size_pretty(pg_total_relation_size(c.oid)) AS total
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='app' AND c.relkind='r' ORDER BY pg_total_relation_size(c.oid) DESC;
```

| table | rows | heap | indexes | total |
|---|---:|---:|---:|---:|
| exchange_rate (shared ECB/CBR + manual) | 223,190 | 11 MB | 8.9 MB | 20 MB |
| journal_entry | 43,029 | 13 MB | 6.4 MB | 19 MB |
| posting | 86,429 | 6.3 MB | 8.5 MB | 14 MB |
| family_record_change (today's journal) | 12,648 | 7.3 MB | 1.0 MB | 8.3 MB |
| family_entry_link | 36,430 | 3.4 MB | 3.4 MB | 6.8 MB |
| family_record | 12,635 | 3.4 MB | 1.8 MB | 5.2 MB |
| family_share | 24,000 | 2.0 MB | 0.9 MB | 3.0 MB |
| imported_family_side | 8,784 | 1.8 MB | 0.6 MB | 2.4 MB |
| counterparty | 1,416 | 0.4 MB | 0.9 MB | 1.3 MB |
| account | 75 | | | 168 kB |
| ledger_member | 8 | | | 128 kB |
| category | 93 | | | 112 kB |
| import_batch | 12 | | | 96 kB |
| ledger_invite 1, user_settings 4, users 4, ledger 6, imported_entry_deleted 0 | | | | tiny |

Whole database: 89 MB (`pg_database_size`).

Average row width (`pg_column_size(t.*)`): journal_entry 150 B, posting 65 B, family_record 261 B,
family_share 80 B, family_entry_link 91 B, account 153 B, counterparty 113 B.
`family_record_change`: 674 B per row including its indexes (8,336 kB / 12,648).

## 2. What the entries are (this decides what needs versions)

```sql
SELECT kind, count(*) FROM app.journal_entry GROUP BY 1 ORDER BY 2 DESC;
```

| kind | entries |
|---|---:|
| FAMILY_SHARE | 23,282 |
| FAMILY_PAYMENT | 11,972 |
| TRANSFER | 3,114 |
| EXPENSE | 2,212 |
| FAMILY_SETTLEMENT | 1,175 |
| INCOME | 1,035 |
| CURRENCY_EXCHANGE | 94 |
| LOAN_GIVEN | 85 |
| LOAN_REPAID | 55 |
| MANUAL | 3 |
| OPENING_BALANCE | 1 |
| FAMILY_OPENING | 1 |

So 36,433 of 43,029 entries (85 %) are written by the family posting, not by a person. Only 6,596 are the user's
own entries (6,599 with the three MANUAL). The family side of the history belongs to 12,635 `family_record`
rows, not to the entries they post. Average 2.01 postings per entry.

Churn of derived rows: `posting` holds 86,429 rows but its identity has reached 301,258. About 215,000 posting
rows were inserted and deleted since the database began, because saving an entry replaces all its postings and
a family re-post replaces the posted entry. A trigger-based history of `posting` would have recorded all of it.

Entries written by an import: batch 12: 2,587, batch 30: 3,881, and 9 smaller batches; 36,433 have no batch (posted).

## 3. Imports: what a typical import changes

```sql
SELECT id, report->>'outcome', report->'totals' FROM app.import_batch ORDER BY id;
```

All 12 batches are COMMITTED (dry runs roll back and leave no batch). `import_batch.report` is 316 to 320 bytes
per batch (outcome and totals only, no per-row data).

| batch | date | ledger | created | updated | unchanged | conflicts | note |
|---:|---|---:|---:|---:|---:|---:|---|
| 12 | 2026-10-13 | 228 | 14,754 | 0 | 0 | 0 | first import of the owner's history |
| 28 | 2026-10-24 | 228 | 6 | 14 | 14,734 | 0 | |
| 30 | 2026-10-24 | 264 | 3,881 | **8,332** | 7,819 | 0 | the partner's claim: large re-map |
| 32 | 2026-10-24 | 228 | 57 | 0 | 6,935 | 0 | |
| 34 | 2026-10-24 | 228 | 0 | 0 | 6,992 | 0 | |
| 36 | 2026-10-24 | 264 | 177 | 6 | 20,032 | 0 | |
| 38 | 2026-10-24 | 264 | 0 | 0 | 20,356 | 0 | |
| 40 | 2026-10-24 | 228 | 0 | 0 | 6,992 | 0 | |
| 110 | 2026-11-05 | 228 | 57 | 0 | 6,966 | 0 | October |
| 113 | 2026-11-05 | 264 | 186 | 6 | 20,355 | 0 | October |
| 474 | 2026-11-14 | 228 | 16 | 0 | 57 | 0 | up to 2026-11-13 |
| 477 | 2026-11-14 | 264 | 96 | 1 | 335 | 0 | up to 2026-11-13 |

Reading: a monthly import re-reads 7,000 to 20,000 rows and changes 0 to 190 of them. An import of this kind
creates 57 to 190 rows and updates 0 to 6. The one large rewrite (batch 30: 8,332 updated) was a one-off
re-map. With D-995 ("nothing changed, no version") the 20,000 unchanged rows of each import produce no
versions; without it they would produce 20,000 versions per import (about 15 MB at the sizes below).

## 4. Family record changes today (what the existing journal shows)

```sql
SELECT action, count(*), count(DISTINCT record_id) FROM app.family_record_change GROUP BY 1;
```

| action | rows | records |
|---|---:|---:|
| CREATE | 12,635 | 12,635 |
| UPDATE | 7 | 7 |
| DELETE | 6 | 6 |

12,622 records have one journal row, 13 have two. By day: 2026-10-13: 12,167 creates; 2026-10-24: 191 creates,
6 updates, 6 deletes; 2026-11-05: 191; 2026-11-14: 85; 2026-11-16: 1 create, 1 update.
Bytes of `changes` jsonb: CREATE avg 462 (max 580), UPDATE avg 80, DELETE 5.
`family_record.version`: 12,622 at 0, 13 at 1. So in the family budget, edits are rare: 13 of 12,635 records
were ever changed after creation; the volume is creation.

## 5. Personal entries: how many are rewritten

```sql
SELECT kind, count(*) FROM app.journal_entry WHERE updated_at > created_at + interval '1 minute' GROUP BY 1;
SELECT (kind LIKE 'FAMILY%') family, version, count(*) FROM app.journal_entry GROUP BY 1,2 ORDER BY 1,2;
```

Entries with `updated_at` more than a minute after `created_at`: 10,757. Of these FAMILY_PAYMENT 7,819
(re-posted payments, derived), FAMILY_SETTLEMENT 554 (derived), TRANSFER 1,629, INCOME 369, EXPENSE 323,
CURRENCY_EXCHANGE 68, MANUAL 3, FAMILY_SHARE 1. So 2,392 of 6,599 personal entries (36 %) were saved again at
least once, almost all by imports (the Oct 24 batches). `updated_at` can't tell a changed row from a rewritten
equal one, so this is an upper bound of real changes.
`version`: personal entries 6,591 at 0 and 8 at 1; family-posted entries 28,056 at 0 and 8,374 at 1.
(`version` is bumped by a re-save; the 8 personal ones at 1 are the only ones saved again by the app with a
counted version, so most of the 2,392 "rewrites" were the importer's, which writes through a path that resets it;
I did not chase this further.)

## 6. Entries and records by month (the steady-state rate)

```sql
SELECT to_char(entry_date,'YYYY-MM'), count(*), count(*) FILTER (WHERE kind LIKE 'FAMILY%') ...
SELECT to_char(record_date,'YYYY-MM'), count(*) FROM app.family_record ... GROUP BY 1;
```

| month | entries | family-posted | personal | family records |
|---|---:|---:|---:|---:|
| 2026-04 | 547 | 493 | 54 | 166 |
| 2026-05 | 647 | 592 | 55 | 201 |
| 2026-06 | 671 | 624 | 47 | 210 |
| 2026-07 | 626 | 570 | 56 | 192 |
| 2026-08 | 625 | 575 | 50 | 195 |
| 2026-09 | 604 | 558 | 46 | 188 |
| 2026-10 | 628 | 573 | 55 | 193 |
| 2026-11 (to the 16th) | 277 | 250 | 27 | 84 |

A normal month: about 190 family records, about 50 personal entries (two members' personal ledgers together
hold about 25 each; these figures are over both), a handful of new payees, few edits.

## 7. Size of one version (JSONB, measured, nothing written)

```sql
SELECT avg(pg_column_size(doc)) FROM (SELECT jsonb_build_object('date',e.entry_date,'kind',e.kind,'payee',e.payee_id,'memo',e.memo,'ref',e.external_ref,
  'postings',(SELECT jsonb_agg(jsonb_build_object('line',p.line_no,'account',p.account_id,'currency',p.currency,'amount',p.amount,'category',p.category_id,'cp',p.counterparty_id) ORDER BY p.line_no)
   FROM app.posting p WHERE p.entry_id=e.id)) doc FROM app.journal_entry e WHERE e.kind NOT LIKE 'FAMILY%') s;
-- family: jsonb_build_object of the record's fields + jsonb_agg(to_jsonb(share)); counterparty: to_jsonb(c)
```

| unit | rows | avg payload | max |
|---|---:|---:|---:|
| personal entry with postings | 6,599 | 380 B | 1,855 B |
| family record with shares | 12,635 | 685 B | 726 B |
| counterparty | 1,416 | 180 B | 284 B |

A version row adds about 100 to 150 B of row header and columns (ledger, type, record id, sequence, revision,
operation, actor, time, 32-byte hash, cause) and about 100 B of indexes (primary key, per-record unique, per-ledger
revision). Measured reference: `family_record_change` costs 674 B per row all included with a 462 B payload.

## 8. Projection (recommended option: application-written versions, derived rows not versioned)

Baseline, one version per live record: 6,599 + 12,635 + 1,416 + 75 + 93 = about 20,800 versions.
Per-version cost (payload + 250 B): personal entry 630 B, family record 935 B, counterparty 430 B.
Baseline = 6,599 × 630 + 12,635 × 935 + 1,416 × 430 + 168 × 400 = 4.2 + 11.8 + 0.6 + 0.07 = **about 16.7 MB**
(today the whole database is 89 MB; the exchange rates are 20 MB of it and are not versioned).

Steady state (from section 6: about 190 family records and about 50 personal entries a month, plus say 10 other
new or changed rows): 250 versions a month, mostly family records at 935 B: **about 0.23 MB a month, 2.8 MB a year**.

| horizon | typical (250 versions/month) | pessimistic (typical plus two re-map imports a year of 8,300 changed rows, 5.8 MB each) |
|---|---:|---:|
| baseline | 16.7 MB | 16.7 MB |
| after 1 year | 19.5 MB | 31 MB |
| after 5 years | 31 MB | 89 MB |

Event tables are small: sign-in, sign-out and expiry (about 3 events a day for two users, 150 B each: about
0.2 MB a year), snapshots and restores (about 150 B each, a few a month), notifications (one per recipient per
change set, coalesced; under 0.1 MB a year). A trigger-based history of `posting` and `journal_entry` rows for
the same horizon would record the 215,000-row churn of section 2 plus every re-posted derived entry, roughly
5 to 10 times the figures above (an estimate from the churn ratio, not measured), so I did not project it in detail.

Cost on the write path: one more insert (about 300 to 700 B) and one hash comparison per record save; an
unchanged save writes nothing. Current-state reads touch none of the new tables.
