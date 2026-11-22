# ADR 0006: History, record versions, snapshots and family acceptance

Status: accepted (HIST-0 drafted it on 2026-11-18; the owner's answers, D-1026 to D-1038, and the PM's decisions
D-1039 to D-1042 were added in HIST-1's first commit, which moved it here). D-989 to D-997 were taken before
HIST-0, D-998 to D-1025 are HIST-0's own, D-1026 to D-1042 the owner's and the PM's. Measurements are in
[0006-measurements.md](0006-measurements.md). The decisions are listed in
[requirements.md](../family-budget/requirements.md) (D-989 to D-1042).

## 1. Context

Today the app keeps history in three unrelated places: `family_record_change` (the family budget's journal,
written by the application, field by field), `import_batch` (one row per committed import) and
`imported_entry_deleted` (a sync memory). A personal entry has no history at all: saving it replaces its
postings and the old values are gone. Sign-in, sign-out and session expiry leave no trace in the app (the HTTP
session is in memory; Keycloak keeps its own login events for 30 days).

Decisions already taken (not reopened here):

- D-989 (PM): HIST-0 is design only.
- D-990 (PM): one model for the action log and record history: every change to a record makes a new immutable
  version, so the log and the history cannot disagree.
- D-991 (PM): a snapshot is a marker of a moment in the history, not a copy; the state then is derived from versions.
- D-992 (Sergey): in a family budget a change by one member reaches the others only when they accept it. Only
  "accept" for now. Until then a member's books keep showing the family ledger as before the change.
- D-993 (Sergey): restoring a snapshot gives "archived" status to every record changed since and makes the
  versions as of the snapshot current again; nothing is lost; the same for one record's earlier version; no
  retention or cleanup yet.
- D-994 (Sergey): versions, snapshots and restore cover personal ledgers and family budgets from the start,
  with notifications for family members.
- D-995 (Sergey): no new version when every field is unchanged; any change makes one; the Excel import follows
  the same rule.
- D-996 (Sergey): "Delete all my data" (D-20) removes the user's history too; the privacy policy states what is
  kept and for how long.
- D-997 (PM): notifications are in-app only.

What the data looks like (rehearsal database, a production restore; details in `measurements.md`):

- 43,029 journal entries, of which **36,433 (85 %) are written by the family posting** into members' ledgers
  (`FAMILY_SHARE`, `FAMILY_PAYMENT`, …). Only 6,596 are a person's own entries. 12,635 family records.
- `posting` holds 86,429 rows but its identity is at 301,258: every save replaces all postings, so about
  215,000 rows were written and deleted. Row-level history would record that churn.
- Family records are almost never edited (13 of 12,635 have a second journal row); the volume is creation:
  about 190 records and 50 personal entries a month. An import reads 7,000 to 20,000 rows and changes 0 to 190.
- The database is 89 MB; exchange rates are 20 MB of it and are shared data.

## 2. Inventory

### 2.1 What one "record" is (D-998)

A record is what a user sees as one thing, saved as one unit:

| record type | unit | stable id | notes |
|---|---|---|---|
| `ENTRY` | a personal `journal_entry` with all its `posting` rows | entry id | the user's own entries, imports included |
| `ACCOUNT` | an `account` row | account id | rename, archive, restore, default currency, `requires_counterparty` |
| `CATEGORY` | a personal `category` row | category id | rename, archive |
| `COUNTERPARTY` | a `counterparty` row | counterparty id | rename, kind, archive (payee created while saving an entry is its own record) |
| `FAMILY_RECORD` | a `family_record` row with its `family_share` rows | record id | expense, income, settlement, refund; the family fields only (D-16: never the payer's account, note or payee) |
| `FAMILY_CATEGORY` | a family `category` row (no `user_id`) | category id | rename, archive, delete |

Not records, so no versions:

- **Derived rows** the family posting writes: `FAMILY_*` entries and their postings, `family_entry_link`,
  `Debt(L)` and placeholder accounts, opening balances and corrections. They are a function of the family
  records and the memberships. They are the 85 % above. Their history is the history of the records.
- **Shared reference data**: ECB and CBR rows of `exchange_rate`, `flyway_schema_history`.
- **Sync state**: `import_hash`, `import_version`, `content_hash`, `imported_*`, `imported_family_side`,
  `imported_entry_deleted`, `family_import_cutoff`. They describe the sync, not the content (D-1006).
- V1's `transactions` and `categories` (dead tables).

Things that change but are not records, so they produce **log events only** (old and new value, no version, no
restore, outside snapshots; D-999): `user_settings` (base currency, share ratio, time zone, date format,
`shows_payees`), the user's manual exchange rates (also removed by the CBR loader's first load, D-858: log it
per affected sub), family ledger settings (name, base currency, split rule), memberships (join, claim, leave,
removal, return, owner), invites (created, revoked, accepted, declined), demo load, delete-all.

### 2.2 Every write path

| path | tables written | history effect |
|---|---|---|
| `EntryService` create / update / delete / `createImported` (REST, import page, `ImportRunner`) | journal_entry, posting, counterparty (payee created), import_batch | one `ENTRY` version per entry per call; delete = version + physical delete (D-1002) |
| `AccountService`, `CategoryService`, `CounterpartyService` | account, category, counterparty | version per row; category delete (family, unused only) = version + delete |
| `StarterLedger` (first sight of a user, `restore` for the demo) | account, category, user_settings, ledger | baseline versions with actor SYSTEM, cause `PROVISION` |
| `DemoLedgerService.load` | everything above, plus a family budget | versions, cause `DEMO`, one change set |
| `ImportService` (importer's own accounts, categories, counterparties, entries; `FamilyRecordService.createForImport` / `settleForImport` / `updateForImport`) | as above plus family_record, family_share, imported_family_side | versions, cause `IMPORT` with the `import_batch` id; unchanged rows write nothing (D-995) |
| `FamilyRecordService` create / update / delete / settle | family_record, family_share, family_record_change | `FAMILY_RECORD` version; a revision (D-1003) |
| `FamilyPostingService` / `CrossLedgerWriter` (post, join, rejoin, `openings`, `replace`, `delete`) | journal_entry, posting, family_entry_link, account | no versions (derived) |
| `CrossLedgerWriter.detach`, `relinkDebt`, `reattach` (leaving, removal, return) | account, category, **posting (category ids moved)** | system writes to a person's entries and categories: `ENTRY` / `CATEGORY` versions, actor SYSTEM, cause `LEAVE` / `RETURN` (the one derived-looking path that changes a real record) |
| `FamilyLedgerService.create`, `merge` (D-11), `importCategory`, `FamilyInviteService.accept` | category, posting, ledger, ledger_member | merge re-points postings and deletes a personal category: `ENTRY` versions and `CATEGORY` delete version, cause `MERGE` |
| `FamilyLedgerService` members, split rule, rename; `FamilyMembershipService`; `LedgerInvites` | ledger, ledger_member, ledger_invite | log events |
| `SettingsService`, `UserSettingsRepository` | user_settings | log event |
| `RateService` manual rates | exchange_rate (user's rows) | log event |
| `EcbRateLoader`, `CbrRateLoader` | exchange_rate (shared) | none (D-858's deletion of manual RUB rows: log per sub) |
| `UserDataService.deleteAll`, SQL functions `release_family_memberships`, `delete_family_ledger`, the runbook's "Delete a user" | everything | erasure path (section 8): deletes history in the same statement set; must be allowed through the `app.writer` guard |
| Flyway migrations | any | baseline job (D-1020), never silent rewrites of versions |

Two consequences. First, the database trigger guards of V7 (`app.writer`) are the model for the backstop
(D-1004). Second, the runbook's SQL and `release_family_memberships` write to tables directly, so they must
learn about versions: `UserDataApiTests` already runs the runbook's statements, so this is testable.

### 2.3 Sign-in, sign-out and session expiry

These are **log events** (`activity_event`), keyed by the Keycloak `sub`, with no email, no IP address and no
user agent (D-1007; Caddy and the app already keep IPs in logs for 15 days, and the policy lists them).

What it needs:

- sign-in: a listener on Spring Security's interactive-authentication success event (oauth2 login). It runs before
  `CurrentUserResolver` provisions the user, so the event has `actor_sub` and no ledger id (a sub, not a foreign
  key). First-ever sign-in is therefore logged before the `users` row exists.
- sign-out: the app's logout success handler (it already ends the Keycloak session) writes `SIGN_OUT`.
- session end by expiry: an `HttpSessionListener.sessionDestroyed` that finds no logout flag in the session writes
  `SESSION_EXPIRED`. `SessionAccessTokenFilter` invalidating a session after a refused refresh writes
  `SESSION_ENDED` with reason `REFRESH_REFUSED`.
- known gap: sessions live in the JVM, so a restart or deploy ends every session without any listener call. The log
  cannot show those ends; the first event after a deploy is a sign-in. It says so in the history view's note.
- bearer-token API calls have no session and write no sign-in event.
- one small insert per event, in its own transaction; a failure to log never fails the sign-in.

## 3. The existing pieces (D-1005, D-1006)

| piece | decision | why |
|---|---|---|
| `family_record_change` | **Migrate, then retire.** HIST-1 keeps writing it and also writes versions (dual write, one transaction). HIST-2 reads the family journal from versions (a change is the diff of two consecutive versions, so log and history cannot disagree, D-990). After that the table is frozen and dropped in a later migration (D-22). Its `SPLIT_RULE_RESET` rows become log events. | It stores `old`/`new` per field, which versions make redundant; 12,648 rows, 8 MB; only 13 records have a second row, so no information is lost by baselining existing records at their current state (their old journal stays readable until the table is dropped). |
| `import_batch` | **Keep.** Versions point at it (`cause_batch_id`). | It is the import event: file name, SHA-256, outcome, totals. 12 rows. |
| `imported_entry_deleted` | **Keep.** | It is sync memory read by every import; deriving it from DELETE versions would add a join to the importer for no gain. |
| import sync (`import_hash`, `import_version`, `imported_family_side`, family `content_hash`, `imported_version`) | **Keep, and exclude from version content.** | A re-stamp is not a change of the record (D-995). `import_version` compares with `journal_entry.version`, the optimistic-lock counter; with D-995 an unchanged save no longer bumps it, which the sync wants anyway. |
| `journal_entry.version`, `family_record.version` | Keep (optimistic locking, `?version=`). | Unrelated to history numbering. |

## 4. Storage options

Criteria are D-990 to D-995, effect on current-state reads (PERF-1), migration effort, restore, erasure
(D-996) and testability.

### (a) History tables kept by triggers (`valid_from` / `valid_to`)

One history table per table (`journal_entry_h`, `posting_h`, `family_record_h`, `family_share_h`, …), filled by
row triggers.

- D-990/D-991: the log and history agree, since the log is the history. But a "record" is several rows (an entry
  and its postings; a record and its shares), and a trigger sees rows. Reading "the entry as of time T" is a
  temporal join over two to four tables.
- D-995 (no version when unchanged): a row trigger can test `OLD IS DISTINCT FROM NEW`, but every save deletes and
  re-inserts all postings, so each save of an unchanged entry would still write `posting_h` rows; the aggregate
  comparison cannot be done in a row trigger. The measured churn (215,000 deleted postings) is the cost. A
  statement-level or deferred trigger could compare aggregates, at the price of a complex trigger.
- Who and why: a trigger does not know the member, the import batch or the cause, unless the app sets them with
  `SET LOCAL` first (V7 already does this for `app.writer`).
- Derived rows (85 % of entries) would be recorded too, unless excluded by a trigger condition on `kind`.
- Current-state reads: unaffected if the history is in separate tables; temporal columns on the live tables would
  touch every query, so no.
- Backstop: complete by construction: nothing can bypass it, the runbook's SQL included.
- Restore: re-assemble the aggregate from several history tables at time T and write it back.
- Erasure: delete history rows by ledger; the comment erasure walks rows of two tables.
- Testing: DB-level tests are simple; asserting aggregate semantics (D-995) is not.

### (b) Application-written version rows (typed columns or JSONB)

One table, one row per record change, holding the whole record as canonical JSON.

- D-990/D-991: a version is the unit; the log of record changes is a query over versions. A write path that
  forgets to write a version is the risk, closed by a database backstop (D-1004) and by an integrity check.
- D-995: the service computes the canonical content, hashes it, and compares with the latest version's hash
  before writing anything. One comparison per record, not per row; the unchanged save writes nothing at all.
- Who and why: known in the application (member, import batch, cause, change-set revision).
- Derived rows: simply not versioned.
- Current-state reads: unchanged (section 5).
- Restore: the version holds the aggregate; restoring is writing it back through the validators.
- Erasure: delete by ledger; comment erasure is a window query over one table's JSON.
- Testing: the project already checks invariants after every operation of random tests; add "latest version
  equals live content" and an oracle for "state at revision N". Canonicalization has unit tests.
- JSONB vs typed columns: JSONB. Four record types with different shapes and a changing schema (a column added in
  V13 would otherwise force a version-table migration each time); `content_version` says which shape.

### (c) Event sourcing

Events are the source of truth; the tables are projections.

- D-990: by construction. D-995: trivial.
- Migration effort: every write path, the 18 migrations' triggers and the posting code become projections of
  events; derived family postings become a projection of a projection; the importer's sync and the D-28 locks
  need an event model. Months of work with the highest risk in the project.
- Current-state reads: projections can stay as today, but writes pay for the event and projection both, and
  projection lag or rebuilds become an operational concern.
- Erasure: events are meant to be immutable; D-996 and D-20's comment erasure fight the model.
- Testing: replay equals state is a strong property, but rewriting the existing tests' setup is not.

### Comparison

| | (a) triggers | (b) app-written versions | (c) event sourcing |
|---|---|---|---|
| D-990 log and history agree | yes (log = rows) | yes if the log is read from versions (D-1003) and a backstop exists | yes |
| D-991 state at a moment | temporal join, several tables | one row per record | replay |
| D-995 no version when unchanged | hard (rows are rewritten; aggregate compare) | one hash comparison | trivial |
| Actor, cause, batch | needs `SET LOCAL` plumbing | native | native |
| Current-state reads (PERF-1) | same if separate tables | same | same, write path heavier |
| Migration effort | medium, wide (all tables) | medium (every service, once) | very high |
| Restore | assemble aggregates from rows | write back one document | replay + new events |
| Erasure D-996 | simple | simple | conflicts with immutability |
| Test effort | easy to prove completeness, hard to prove semantics | semantics easy, completeness needs the backstop | hardest |
| Volume | 5 to 10 times (row churn, derived rows) | 17 MB baseline, 0.23 MB/month | similar to (b) |

**Recommendation: (b) (D-1000).** It is the only option that makes D-995 simple and gives restore a unit to
write back. Its weak point, a forgotten write path, is covered by a database backstop in the style V7 already
uses (D-1004). ADR 0003 topic H chose application-written for the family journal for the same reasons.

## 5. Design of option (b)

### 5.1 Tables (draft, HIST-1's migration V19, additive)

```sql
CREATE TABLE record_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id     BIGINT NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,   -- erasure by ledger
    record_type   VARCHAR(16) NOT NULL,        -- ENTRY, ACCOUNT, CATEGORY, COUNTERPARTY, FAMILY_RECORD, FAMILY_CATEGORY
    record_id     BIGINT NOT NULL,             -- the live row's id; no foreign key (the live row may be gone)
    seq           INTEGER NOT NULL,            -- 1, 2, … per record
    revision      BIGINT NOT NULL,             -- the ledger's change set (D-1003)
    op            VARCHAR(10) NOT NULL,        -- BASELINE, CREATE, UPDATE, DELETE, ARCHIVE, RESTORE
    content       JSONB NOT NULL,              -- canonical aggregate; for DELETE/ARCHIVE the last content (so it can come back)
    content_hash  BYTEA NOT NULL,              -- SHA-256 of the canonical JSON
    content_ver   SMALLINT NOT NULL,           -- shape of content
    actor_kind    VARCHAR(8) NOT NULL,         -- USER, SYSTEM, IMPORT
    actor_member  BIGINT,                      -- ledger_member id (personal ledgers have one); null for SYSTEM
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    cause         VARCHAR(12) NOT NULL,        -- EDIT, IMPORT, DEMO, PROVISION, LEAVE, RETURN, MERGE, RESTORE, BASELINE
    cause_batch   BIGINT,                      -- import_batch id
    restored_from BIGINT,                      -- version id this one copies (RESTORE)
    tx_id         BIGINT NOT NULL DEFAULT txid_current(),   -- for the backstop
    UNIQUE (ledger_id, record_type, record_id, seq)
);
CREATE INDEX ON record_version (ledger_id, revision);
```

plus `activity_event` (id, `actor_sub`, nullable `ledger_id`, `at`, `kind`, `subject_type`, `subject_id`,
`revision`, `details` JSONB with old/new for settings and members; no email), `ledger.revision` (BIGINT, default
0), later `snapshot`, `restore_run`, `restore_item`, `notification` and `ledger_member.accepted_revision`.
Versions are immutable: a trigger refuses UPDATE and refuses DELETE unless the transaction is an erasure
(`app.writer = 'delete-all'` or the ledger's deletion), as V7's guards do.

### 5.2 Content (D-1001)

- Canonical JSON: keys sorted, amounts as normalized decimal strings (`10.50` and `10.5` are equal), dates
  ISO, absent and null the same, postings in line order, shares by member id.
- **ids, never names**: accounts, categories, counterparties and members are ids; names are read when shown, so
  FORMER anonymization and renames need no rewrite.
- **included**: everything the user can see or change, `external_ref` among it (an ID the import sets is a
  change, D-995). **excluded**: `version`, `updated_at/by`, `import_hash`, `import_version`, `content_hash`,
  `imported_version`, `imported_at/by`, posting ids.
- family records: only the family fields (type, date, category, payer, payee member, amounts and currencies,
  rate fields, split method, comment, shares, deleted state). Never the payer's account, note or payee (D-16).
- the hash is of the canonical JSON; the service writes nothing, not even the row, when the new hash equals the
  latest version's.

### 5.3 Current-state reads stay as fast (the PERF-1 answer, D-1002)

- The live tables stay the single source of the current state and gain **no column and no predicate** in HIST-1
  and HIST-2. No current-state statement joins `record_version`.
- Delete and archive remove the row from the live tables (the version holds the content, `external_ref` included),
  so no `archived_at` filter appears on hot paths. Re-insertion on restore keeps the id
  (`OVERRIDING SYSTEM VALUE`).
- PERF-1's planner workaround (`enable_nestloop = off` for the summary's count statement) concerns statements
  that HIST-1 and HIST-2 do not touch. Acceptance criterion for both: the Entries page's statements on the
  restored production dump keep their plans and timings (all under 1 s, summary under 500 ms), measured as in
  PERF-1.
- HIST-3 (acceptance) is the only stage that touches read paths, and only by adding two integers (section 7.4).
- Cost on the write path is one insert and one hash comparison per record save.

### 5.4 Revisions and the log (D-1003)

`ledger.revision` counts change sets: one transaction that changes records of that ledger gets one revision,
taken while the ledger row is locked (`FOR UPDATE`; the family code already locks the ledger first, and a personal
write would take the same lock first, so lock order does not change). An import commit, a restore, a demo load
and a delete-and-re-post are each one change set. All versions of the change set carry the revision.

The "log" of record changes is `SELECT … FROM record_version` (who, when, what, cause) with
`activity_event` merged in by time; the field-by-field diff is computed from two consecutive versions when
read. Nothing is written twice, so they cannot disagree (D-990).

### 5.5 Backstop and integrity (D-1004)

- A deferred constraint trigger on the live record tables (`journal_entry`, `posting`, `account`, `category`,
  `counterparty`, `family_record`, `family_share`; not rows written by the family posting, recognised by
  `app.writer = 'family-posting'`) requires a `record_version` row with the same `txid_current()` for the changed
  record. A path that forgets a version fails at commit, like V7's guard.
- **Rollback must work (D-1040).** The previous image knows nothing of versions, and V19 stays in place when it runs
  again. The trigger therefore enforces the rule **only for a connection that says it is version-aware**: the new
  application sets the session setting `app.history = 'on'` on every connection it takes from the pool (Hikari's
  `connection-init-sql`, plus `SET LOCAL` in the tests' plain JDBC), and the trigger returns at once when
  `current_setting('app.history', true)` is not `on`. The previous image, `psql` and the runbook's SQL never set it,
  so their writes pass unchecked, as before V19. Trade-off: the backstop does not cover a writer that forgets
  the setting (only the new application connects with the setting, so only a new bug in the pool's configuration
  could lose it; a test proves that every pooled connection has it) and a manual SQL write is not checked, as no manual
  write was before. The replaced SQL functions (`release_family_memberships`, `delete_family_ledger`) keep their
  signatures and the behaviour the previous image relies on and only add the erasure of history, and
  `family_record_change` keeps being written.
- **Drift after a rollback.** A write by the previous image changes a live record without a version. The next
  version-aware write to that record first compares the live content's hash with the latest version's hash and, when
  they differ, writes a `BASELINE` version of the live state (cause `RECONCILE`) before its own version; the baseline
  job does the same for every record (and writes a `DELETE` version, cause `RECONCILE`, for a record whose live row is
  gone). The integrity check reports drift as a count of records "not yet reconciled", never as a failure of
  a record's content.
- `/api/reports/integrity` gains a check: for every live record the latest version's hash equals the hash of the
  live content; for every record whose latest version is DELETE or ARCHIVE no live row exists.
- Random tests (`FamilyRecordRandomTests`, `FamilyLifecycleRandomTests`) run both after every operation, plus an
  oracle: the test keeps a full copy of the state at a few revisions and compares it with "latest version with
  revision ≤ N per record".

### 5.6 Baseline (D-1020)

No migration backfill (SQL-built JSON would have to match the Java canonical form byte for byte for the hash
comparison). An application job writes `BASELINE` versions for every live record, per ledger in its own
transaction, idempotent, and a lazy rule covers the gap: a first change to a record without versions writes its
`BASELINE` (the state before the change) and then the new version (and, since D-1040, the same for a record whose
latest version no longer equals its live content). About 20,800 rows; seconds.

## 6. Restore and snapshots

### 6.1 Snapshot (D-1008)

A snapshot is a row `(ledger_id, revision N, name, kind MANUAL | BEFORE_IMPORT | BEFORE_RESTORE, created_by,
created_at)`. About 150 bytes. The state at N is, per record, the latest version with `revision ≤ N`; a record
without such a version did not exist (or the version is DELETE/ARCHIVE: it was gone). Later restores do not change
that, because a restore only adds versions with higher revisions.

### 6.2 Archived versus current (D-1009)

- A version is **current** if it is the record's latest and its op is not DELETE or ARCHIVE; that is exactly what
  the live tables hold.
- A version is **archived** if a restore set it aside: it has a higher revision than the snapshot and a restore
  run covers its record. This is derived from `restore_item` rows (restore run, record, the version it replaced,
  the version it wrote), so version rows stay immutable.
- A record is **archived** (as a record) if it did not exist at the snapshot and a restore removed it from the
  live tables: its last version has op `ARCHIVE`, its content is kept, and restoring any of its versions brings it back.

### 6.3 Restoring a snapshot at revision N (D-1009)

1. Preview first (like an import's dry run): the plan, counts per outcome and every blocker. Nothing is written.
2. In one transaction: lock the ledger, make a `BEFORE_RESTORE` snapshot at the current revision (this is the undo,
   D-1011), take revision R.
3. For every record with a version after N: target = latest version with revision ≤ N.
   - target exists and differs from the live state: write a `RESTORE` version (copy of the target, `restored_from`
     set) and apply it to the live tables: update if live, re-insert with the same id if it had been deleted or
     archived. **Deleted after N: it comes back.**
   - target exists and equals the live state (changed and changed back): nothing.
   - target is DELETE or ARCHIVE, record live now: delete it (op DELETE, cause RESTORE).
   - **no target (created after N): the record becomes archived**: an `ARCHIVE` version and removal from the live
     tables. It disappears from lists, reports and balances, and its content stays and can be restored.
4. Order: reference data first when writing (accounts, categories, counterparties), entries and records after;
   the reverse when removing.
5. Every write goes through the same validators and database triggers as a normal save. All or nothing: any
   violation (a category deleted since, a frozen family record, a member who left) fails the whole restore and
   lists the blockers. Nothing is half-restored.
6. A `restore_run` and its `restore_item` rows record what happened; an `activity_event` `RESTORE` is the log line.

Reading of D-993 (the owner's D-1035): records created after the snapshot become archived and restorable, records
deleted after it return, later versions are archived, nothing is lost.

**Imports after a restore (D-1038).** The Excel file stays the source. A record that a restore **archives** is not an
in-app deletion: it writes no row to `imported_entry_deleted`, so a later import of a file that still holds the row
brings it back (created as new, its `external_ref` free again). A record that a restore **brings back** removes its
row from `imported_entry_deleted`. "Undo a bad import, fix the file, import again" must work.

Not restored (D-1009): memberships and the split rule (not records), derived family entries (they follow the
family records and acceptance), settings and manual rates (log only, D-999).

A restore can be undone: it is a normal change set, and its `BEFORE_RESTORE` snapshot is the target of a second
restore. Newer versions stay in the history, so nothing is lost (D-993).

**One record's earlier version** is the same operation for one record: pick a version, preview, write a
`RESTORE` version that copies it (also for a deleted or archived record).

### 6.4 Automatic snapshots (D-1010)

Decided yes (D-1036): a `BEFORE_IMPORT` snapshot before every import commit and a `BEFORE_RESTORE` snapshot before
every restore. A snapshot costs one 150-byte row, so there is no reason not to. An import is the one action that
changes thousands of records at once; "undo this import" is then one restore.

### 6.5 Who may do what

- personal ledger: its user creates and restores.
- family budget: any ACTIVE member creates a snapshot (a marker, no effect). Restoring a family snapshot: **owners
  only** (D-1015, D-1030). It archives records other members created since the snapshot, and with
  "accept only" they cannot refuse; the preview lists the affected records by author.

### 6.6 How a family restore meets D-992

A family restore is a change set (revision R) like any edit. It applies to the shared family ledger at once; for
each other member it is pending until they accept (section 7), and the restorer's own books follow immediately if
he is caught up. Members see "X restored 'Before the October import': 143 records changed" in their pending list.
Until they accept, their books stay as they were. Because there is no decline, the restore reaches everyone
eventually; that is the cost of D-992 and D-993 together, and D-1030 accepts it.

## 7. Family acceptance (D-992)

### 7.1 What has to change

Today `FamilyPostingService.post` posts a changed record into every member's ledger in the actor's transaction,
and ADR 0003's D-10 says each member's debt account equals their family balance, always. Under D-992:

- `ledger.revision` numbers the family ledger's change sets (section 5.4). Only changes of **records** are
  revisions: create, edit, delete, settle, import, restore. Membership changes, the split rule, member names and
  category renames are not (D-1014): they change no member's postings, or only labels.
- `ledger_member.accepted_revision` per member with an account: the last revision whose effects his books show.
  Set to the head at join, claim and return; null for LEFT and FORMER and for members without an account.
- Pending(m) = the change sets with revision > `accepted_revision(m)`.
- The record's change posts **only for members who are caught up and only the actor's own books**, at once, as
  today (the actor is caught up by rule 7.2). For everyone else the posting waits.
- **Accept** (D-1012, D-1026: *catch-up*, no per-change accept): for member m, in one transaction, re-post from the
  live record every record changed since `accepted_revision(m)`, for m only (the existing `postRecord` already
  keys its work by member; it gets a member filter), re-run his `openings`, and set `accepted_revision` to the head.
  Because every re-post reads the live record, no "family state as of revision R" is ever needed.
- Two additive integers make pending visible without joining versions: `family_record.last_revision` (the
  revision of its latest change) and `family_entry_link.posted_revision` (the revision an entry was posted
  from). A record is pending for m when `last_revision > posted_revision` of m's link, or when m has no link and
  `last_revision > accepted_revision`.

### 7.2 Rules (D-1012, D-1016)

- **Author**: his own change is accepted for him automatically (`accepted_revision := R`), but only when he is
  caught up. A member with pending changes who tries to change, delete, settle or import a family record gets 409
  "accept the n pending changes first". Without this rule an author's change would jump over others' pending
  ones, and his books would need "state as of revision". This is the price of the cheapest variant. Decided (D-1027): the refusal offers "Accept" in the same place, one click.
- **Frozen, settlement lock (D-28), joins, leaves**: unchanged. A member who leaves with pending changes loses them
  (his books were detached at his accepted state).
- **Members without an account** have no books and accept nothing.
- Dashboards, reports, Entries, balances of the personal ledger need **no change in HIST-3's cheapest variant**:
  they read the member's own entries, which hold the accepted state. FAMILY_DEBT / `Debt(L)` balances show the
  accepted state, and D-10 becomes "equals the family balance as of the accepted revision". Every test that
  asserts D-10 (`FamilyInvariants`, six random test classes, E3's check) accepts first or skips a member with
  pending changes; `/api/reports/integrity` shows `pendingChanges: n` instead of a difference.
- **Family tab** (balances, records, report): shows the latest family state to everyone, with "pending for you"
  marks on records changed since the viewer's accepted revision (D-1014, D-1028). It is the family's
  ledger; the viewer's books lag until he accepts.
- **Where a personal page joins to the live record** (Entries' family line, UX-18's summary of a family record by
  its full amount and others' shares, the Category and payee filters by record, the cash flow's scope by the
  record's payer, export, import reconciliation) it would show the new value next to the old posting while a
  change is pending. Cheapest variant: show the live labels with a "pending change" mark; the summary's "others'
  shares" can be off for a pending record, and the mark says so. The exact variant (HIST-3b): `family_entry_link`
  carries the accepted facts (record type, payer, amount, currency, category, payee), written by the writer with
  the entry; those joins read the link, not the live record. That also removes a join from PERF-1's statements,
  but it changes the planner-sensitive Entries statements and needs its own timing check. Decided (D-1029): the cheapest variant; HIST-3b only if the marks get in the way.
- **Excel import while changes are pending** (D-1016): the importer's check compares his ledger with his file;
  while he has pending changes his ledger lags. A dry run works and lists the pending changes as a named, non-failing
  note; a **commit is refused** ("accept the n pending changes first"), in the style of D-336. If the importer is
  caught up, his import proceeds and becomes a change set that is pending for the other member, who may be
  mid-way through his own import: his commit is then refused until he accepts, and his dry run says why.
- **Restore** (6.6): same rules.

### 7.3 Notifications (D-1017, D-997)

`notification(id, recipient_sub, ledger_id, kind, from_revision, to_revision, actor_member, created_at, read_at)`:
kinds `FAMILY_CHANGES` (pending change set(s) by another member), `FAMILY_RESTORED`, `FAMILY_IMPORTED` and, since D-1034,
`MEMBER_JOINED`, `MEMBER_LEFT` and `SEAT_CLAIMED`. No text
and no record content; the text is made when read ("Mum changed 3 records"), names resolved then (FORMER reads
"Former member"). In-app only: a badge in the header and a list page with Accept. Unread `FAMILY_CHANGES` from
the same author in the same ledger are merged into one row (range widened), so a day of edits is one entry.
HIST-2 delivers notifications as information, before acceptance exists, so the model is proven first.

### 7.4 Cost, plainly

This is the expensive part. It changes the meaning of ADR 0003 D-7 and D-10, touches `FamilyPostingService`,
`CrossLedgerWriter` callers, every family test (they post for two members in one request and assert D-10 at
once; each now needs an accept step), the end-to-end family specs (03, 04, 05, 07, 09 act as two accounts),
the integrity report, the importer's rules and the deploy scripts (a switch for it). I estimate 2,500 to 4,000 lines
including tests, the highest risk of the series. **The cheapest variant that meets D-992** is the one above:
catch-up accept (no per-change accept), the author must be caught up, live labels with pending marks. Anything
that needs "state of the family ledger as of revision R" (per-change accept, the exact Entries numbers without
link facts) costs about half again as much and is deferred unless Sergey needs it. The whole feature sits behind
its own switch (`app.family.acceptance.enabled`, off by default, D-1023), so HIST-3 can be deployed dark.

## 8. Erasure and privacy (D-1018, D-1019, D-1025)

- **Delete all my data (D-996, D-20):** in the same transaction as today, delete the personal ledger's versions,
  snapshots, restore runs, the user's notifications and the user's `activity_event` rows (by sub). The version
  table's immutability guard allows this only under `app.writer = 'delete-all'`. The runbook's "Delete a user"
  statements get the same.
- **Family records and their versions stay** with the family budget as long as it exists, as the family records do
  (policy: "How long family records are kept"). Versions hold member ids, never names, so the leaver is "Former
  member" everywhere at once. The leaver's **comments** are erased (D-20) from every version: a comment value is
  attributed to the member of the version in which it last changed (a window query over the record's versions:
  `lag`, a running group id, `first_value` of the actor); every version carrying a value he wrote loses it.
  This runs inside `release_family_memberships` (a new migration replaces the function, as V9 and V10 did), so it
  works for delete-all and for the runbook's SQL, and the existing D-20 tests extend to versions.
- **A deleted family budget** deletes its versions, restore runs, snapshots and notifications with it
  (`delete_family_ledger`).
- **Leaving (LEFT):** the name stays in the history, the personal ledger and its history stay. **Removed or FORMER:**
  the actor of old versions reads "Former member"; the leaving itself is a log event naming him by id.
- **Notifications about a leaver** are deleted for him; others' notifications show "Former member".
- **Backups** keep copies as the policy already states (14 days on the server, 60 days on the laptop, Hetzner's 7).
- **Retention (D-993, D-1019, D-1033, D-1042):** versions are never deleted automatically. Sign-in, sign-out and
  expiry events are kept 13 months, with no IP address and no browser, and a daily job (HIST-1) deletes older ones, so
  the policy is true from the first day.

**Privacy policy text** (new section "History, snapshots and sign-in events", published with HIST-1, since HIST-1
starts collecting; `privacy.test.ts` and `docs/family-budget/privacy-draft.md` follow the usual way):

> Version history. When you add, change or delete an entry, an account, a category or a payee, the app keeps the
> earlier version: the values, when, and what caused the change (you, an import, the demo, the system). It lets you see what
> changed and, later, go back. Your history is kept until you use "Delete all my data", which deletes it at once.
> Family budgets. The history of a family record belongs to the family budget, like the record: it is kept as long
> as the budget exists, shown to all its members, and never holds your payment account, your private notes or your
> payees. If you leave or delete your data, your name in it becomes "Former member" and the comments you wrote
> are erased from the history too. Snapshots and notifications. A snapshot is a named point in the history, with no
> copy of your data; notifications tell you that another member changed a family record. Both are deleted
> with your data. Sign-in events. The app records when you sign in, sign out or your session expires, with the time
> and your account's id, not your email, your address or your browser. They are kept for 13 months or until you delete your data, whichever
> comes first. Backups keep copies for the periods stated under "How long it is kept".

## 9. Stages

Order as requested: versions and log; notifications and acceptance; snapshots. Each is separately deployable.
All migrations are additive (D-22). **Rollback must work after every migration of the series (D-1040, correcting
D-1024's first form, "rollback is the previous image ignoring the new tables"):** the previous image must run and
write normally against the new schema. A backstop that refuses writes of an image that knows nothing of versions
would make rollback impossible, so the backstop checks only version-aware connections (section 5.5); replaced
SQL functions keep their signatures and old callers' behaviour; `family_record_change` keeps being written until a
later stage drops it. HIST-1 is the only feature stage of the batch it ships in (D-1039); its deploy checklist
carries a rollback drill. A later stage whose rollback cannot work this way says so and has its own condition in
`rollback.sh`, as V11, V13 and V14 do.

| stage | scope | migrations | risk | size | what the user sees |
|---|---|---|---|---|---|
| **HIST-1: versions and the log** (invisible, D-1037) | `record_version`, `activity_event`, `ledger.revision`; a history module with canonical content and hashing; every personal write path (entries, accounts, categories, counterparties, demo, provisioning, merge, detach) writes versions; family records write versions beside `family_record_change` (dual write); the backstop trigger; the integrity check; the baseline job; sign-in, sign-out, expiry events; erasure paths (delete-all, `release_family_memberships`, `delete_family_ledger`, runbook SQL); the privacy policy section | V19 (tables, revision column, guards, erasure functions; the backstop only for version-aware connections, D-1040) | **medium-high**: touches every write path; mitigated by the backstop, random tests and the PERF-1 timing check | about 2,000 lines with tests | **nothing**, apart from the policy text (D-1037); history views come with HIST-2 |
| **HIST-2: history views and notifications** | family journal reads from versions (diff of two versions), `family_record_change` frozen; history views for entries, accounts, categories, payees; `notification` table, the header badge and list, informational `FAMILY_CHANGES` | V20 (`notification`, journal freeze) | low-medium | about 1,200 lines | a History view per record; a badge and a list of "X changed n records" (no effect on books yet) |
| **HIST-3: family acceptance** (dark behind its switch) | revisions on records, `accepted_revision`, `last_revision`, `posted_revision`, deferred posting, accept action, the author rule, pending marks, integrity and tests adapted, the importer's rule, deploy script's switch | V21 | **high**: changes ADR 0003 D-7 and D-10; every family test and e2e spec | 2,500 to 4,000 lines | with the switch on: family changes by others wait until "Accept"; pending marks; the Accept button |
| **HIST-3b** (optional) | accepted facts on `family_entry_link`, personal reads switch to them | V22 | medium (planner-sensitive statements) | about 600 lines | exact numbers on Entries while changes are pending |
| **HIST-4: personal snapshots and restore** | snapshots (manual, before import, before restore), preview, restore of a snapshot and of one version, archived status, undo | V23 (`snapshot`, `restore_run`, `restore_item`) | medium: restore invariants; mitigated by the all-or-nothing preview and the oracle test | about 1,800 lines | "Snapshots" in Settings; "Restore this version" in history; an import page's undo |
| **HIST-5: family snapshots and restore** | the same for family budgets, owners only, as a change set that others accept | none or small | medium-high (meets HIST-3) | about 800 lines | family snapshots, restore preview by author, notifications |

Amended 2026-11-22 (HIST-2 charter, D-1075): HIST-2's V20 holds only the `notification` table and the replaced
erasure functions; `family_record_change` is not frozen in HIST-2 (HIST-1's dual write stays, so that rollback to
the previous image keeps working) and is frozen and dropped in a later migration. See section 13.

## 10. Decisions of HIST-0 (D-998 onwards)

D-998 a record is the user-visible unit (section 2.1); derived family rows are not records.
D-999 settings, manual rates, memberships, invites and ledger settings are log events with old and new values, not versions.
D-1000 option (b): application-written versions in one JSONB table; current state stays in the live tables.
D-1001 canonical content, ids not names, bookkeeping excluded; hash equal means no write at all.
D-1002 delete and archive remove the live row; the live tables hold only live records; hot paths gain no predicate.
D-1003 revisions are per ledger and per change set; the log of record changes is read from versions; other events go to `activity_event`.
D-1004 database backstop (a deferred trigger that wants a version in the same transaction) plus an integrity check and test oracle.
D-1005 `family_record_change`: dual write in HIST-1, versions are the source from HIST-2, table frozen and dropped later.
D-1006 `import_batch`, `imported_entry_deleted` and the sync stamps stay; stamps are not content.
D-1007 sign-in, sign-out and expiry events by sub only, no email, IP or browser; restarts end sessions silently and the log says so.
D-1008 a snapshot is `(ledger, revision, name, kind)`; state is derived from versions.
D-1009 restore semantics (section 6.3): all or nothing, preview first, records created after become archived, deleted ones return, memberships and derived rows untouched.
D-1010 automatic snapshots before every import commit and every restore.
D-1011 undoing a restore is restoring its `BEFORE_RESTORE` snapshot.
D-1012 family acceptance as catch-up: per-member `accepted_revision`, accept advances to the head; the author is accepted automatically when caught up; a member with pending changes may not change family records or commit an import.
D-1013 D-10 becomes "debt account equals the family balance as of the accepted revision"; integrity shows pending, not a failure.
D-1014 the Family tab shows the latest family state with pending marks; labels (category and member names) are live; only record changes are revisions.
D-1015 only owners restore a family snapshot; any ACTIVE member creates one.
D-1016 an import's dry run works with pending changes and says so; its commit is refused until the importer accepts.
D-1017 notifications are references (range of revisions, author), no content, coalesced while unread, deleted with the user.
D-1018 erasure: delete-all deletes the personal history, snapshots, notifications and events by sub; family versions stay without names and without the leaver's comments, erased by a window query inside `release_family_memberships`.
D-1019 no automatic deletion of history yet (D-993); sign-in events need a limit before release (proposal: 13 months).
D-1020 baseline by an application job plus a lazy baseline on first change, no SQL backfill.
D-1021 actor is a `ledger_member` id (personal ledgers have one) with kind USER, SYSTEM or IMPORT.
D-1022 family versions hold the family fields only, never the payer's private side (D-16).
D-1023 acceptance has its own switch, off by default, so HIST-3 deploys dark.
D-1024 all migrations additive; rollback is the previous image (corrected by D-1040: the previous image must write normally against the new schema, so the backstop only checks version-aware connections).
D-1025 the privacy policy section ships with HIST-1, before it collects anything.
D-1026 (Sergey) accept all pending changes at once (catch-up); no per-change accept.
D-1027 (Sergey) a member with pending changes accepts first before changing family records or committing an import; the refusal offers "Accept" in the same place.
D-1028 (Sergey) the Family tab shows the latest family state with "pending for you" marks.
D-1029 (Sergey) while a change is pending, Entries shows live labels with a mark; "others' shares" may be off and the mark says so; HIST-3b only if the marks get in the way.
D-1030 (Sergey) only owners restore a family snapshot; archiving other members' records without a decline is accepted for now.
D-1031 (Sergey) a personal snapshot does not undo what the family posted into the member's books.
D-1032 (Sergey) settings and manual rates are log-only, outside snapshots.
D-1033 (Sergey) sign-in events are kept 13 months; no IP address, no browser.
D-1034 (Sergey) notifications also for a member joining, leaving or claiming a seat; unread ones from the same author in the same ledger are merged.
D-1035 (Sergey) D-993 as read in section 6.3.
D-1036 (Sergey) automatic snapshots before every import commit and every restore.
D-1037 (Sergey) HIST-1 is invisible apart from the privacy policy text; history views come with HIST-2.
D-1038 (Sergey) imports after a restore: Excel stays the source; a record a restore archives is not an in-app deletion, a record it brings back leaves `imported_entry_deleted` (section 6.3).
D-1039 (PM) HIST-1 is the only feature stage of its batch.
D-1040 (PM) rollback must work after V19 (section 5.5, section 9).
D-1041 (PM) HIST-1's acceptance numbers, on the rehearsal database, median of 3: the Entries timings within PERF-1's targets and plans, an import commit at most 20 % slower, a second import of the same file writing 0 versions and 0 revisions, the baseline job's rows, duration and idempotence, the integrity check passing after the baseline and the random tests.
D-1042 (PM) sign-in events older than 13 months are deleted by a daily job in HIST-1; versions are never deleted automatically.

## 11. The owner's answers (Sergey, 2026-11-18)

The twelve questions of the draft are answered; the answers are D-1026 to D-1038 (section 10) and are folded into
sections 5.5, 6.3, 6.4, 6.5, 7, 8 and 9.

| # | question | answer |
|---|---|---|
| 1 | accept granularity | all pending at once, catch-up (D-1026) |
| 2 | a member with pending changes making changes or committing an import | accept first, with "Accept" offered in the refusal (D-1027) |
| 3 | the Family tab | the latest family state with "pending for you" marks (D-1028) |
| 4 | Entries' labels while a change is pending | live labels with a mark, "others' shares" may be off; HIST-3b only if the marks get in the way (D-1029) |
| 5 | who restores a family snapshot | owners only; archiving others' records without a decline accepted for now (D-1030) |
| 6 | a personal snapshot and what the family posted | does not undo it (D-1031) |
| 7 | settings and manual rates | log-only, outside snapshots (D-1032) |
| 8 | sign-in events | 13 months, no IP address, no browser (D-1033, D-1042) |
| 9 | which events notify | also a member joining, leaving or claiming a seat; unread ones from one author in one ledger merged (D-1034) |
| 10 | D-993's reading | as in section 6.3 (D-1035) |
| 11 | automatic snapshots | before every import commit and every restore (D-1036) |
| 12 | History view in HIST-1 | invisible; views come with HIST-2 (D-1037) |

New item (D-1038): imports after a restore; see section 6.3.

## 12. HIST-1 as built (D-1043 to D-1061)

What the first stage did, where it differs from the draft above. Nothing here changes a decision of sections 1 to 11;
the backstop and rollback sections (5.5, 9) already carry D-1040.

- **Tables (V19):** `record_version` as in 5.1, with `revision >= 1` for a change and a baseline's one revision lower
  (down to 0, the ledger's state before its first change); `activity_event` with an optional ledger and no email, address
  or browser; `ledger.revision`; `history_pending`, an unlogged table that holds one mark per changed record inside a
  transaction and is empty at rest. No `snapshot`, `restore_run`, `restore_item`, `notification` or
  `accepted_revision`: they come with their stages.
- **Backstop (D-1045, D-1046):** row triggers mark a changed record at the write, where the writer is known; a deferred
  constraint trigger on the marks requires the version at commit. The root tables only (`journal_entry`, `account`,
  `category`, `counterparty`, `family_record`), because a save of an entry or a record always changes its row's version;
  derived rows are exempt by what they are. A connection that did not set `app.history` to `on` is not checked.
- **Writing a version (D-1043, D-1044, D-1047, D-1048):** after the write, from the live record; the state before is the
  committed database, read through a pool of its own (D-1061: a nested transaction on the application's pool starves it); the revision is taken at the first version of a transaction by an
  update of the ledger's row. An entry's save that changes nothing writes nothing at all.
- **Content (D-1049):** as 5.2, with the family record's signed amount and deleted flag; the shapes are version 1.
- **Events (D-1054):** only sign-in, sign-out, expiry and an ended session; the others of D-999 come with HIST-2.
- **Erasure (D-1052):** inside `release_family_memberships`, as 8 says; the version's stored hash is stale after a comment
  is erased, so comparisons recompute it from the stored content.
- **Jobs (D-1055):** the baseline in chunks of 250 records at startup, in the background; the 13 months of sign-in events.
- **Not done in HIST-1:** the History views and the family journal read from versions (HIST-2), the log events of
  settings, manual rates, memberships and invites, snapshots and restore (HIST-4, HIST-5), acceptance (HIST-3).

## 13. HIST-2 (charter)

HIST-2 has two parts. **Part 1, backend** (this charter's four steps); **part 2, frontend** (the History views, the
header badge and the notification list), chartered separately.

Part 1:

1. **Step 1: charter and the family journal from versions.** This section; the family journal
   (`FamilyRecordService.journal`) reads a record's changes from its `FAMILY_RECORD` versions, each the difference of two
   consecutive versions, in the shape `family_record_change` held. Before a record's first version (its first
   BASELINE) the old rows are read; system changes (`SPLIT_RULE_RESET`) stay in the old table. No migration.
2. **Step 2: history endpoints** for entries, accounts, categories, counterparties and family records; the
   `notification` table (V20) and the unread-count endpoint.
3. **Step 3: the log events of D-999**: settings, manual rates, ledger settings, memberships (joined, left, seat
   claimed), invites and `SPLIT_RULE_RESET`, written as D-1079 says; the activity list endpoint.
4. **Step 4: notifications** `FAMILY_CHANGES`, `FAMILY_IMPORTED`, `MEMBER_JOINED`, `MEMBER_LEFT` and `SEAT_CLAIMED`,
   behind `FAMILY_LEDGERS_ENABLED`, with the merge rules of 7.3 and D-1034 (unread ones from the same author in the
   same ledger merged into one row, its range widened).

Decisions (author per entry; the owner can overrule):

- D-1075 `family_record_change` is not frozen and not dropped in HIST-2. HIST-1's dual write stays (a rollback to the
  previous image must keep working, and that image reads the old table). Only the reading of the family journal moves
  to versions. No migration in step 1.
- D-1076 the log events of D-999 (settings, manual rates, ledger settings, memberships: joined, left, seat claimed,
  invites; SPLIT_RULE_RESET) are written in HIST-2 (step 3) and shown in a simple "Activity" list in part 2, next to the
  user's own sign-in, sign-out and expiry events.
- D-1077 notifications are informational only, no "Accept" (HIST-3): kinds FAMILY_CHANGES, FAMILY_IMPORTED,
  MEMBER_JOINED, MEMBER_LEFT and SEAT_CLAIMED; recipients are the other ACTIVE members, never the author; no text or
  content is stored; unread FAMILY_CHANGES from one author in one ledger merge, range widened (D-1034); the unread count
  is one cheap indexed statement, fetched on load and after navigation, never by an interval poll; everything
  family-related stays behind FAMILY_LEDGERS_ENABLED.
- D-1078 history is shown as a side panel opened from a record (part 2); there is no "deleted records" list (HIST-4
  brings restore). A personal record's history is for its owner only; a family record's for the ACTIVE members of that
  ledger only.
- D-1079 the D-999 events are written in the same transaction as the change they describe.
- D-1080 (developer, HIST-2 step 1) the cut-over per record: if the record's first version is a BASELINE, journal
  rows written strictly before its time are shown, and a row written at the same instant (the lazy baseline's own
  transaction) belongs to the versions; a record whose first version is a CREATE shows versions only, a record without
  versions its journal rows only. A change read from a version carries the version's id negated.
- D-1081 (manager, HIST-2 step 1) the family journal's URL, request parameters and answer do not change when it is read
  from versions; a create row names the currency only when it differs from the ledger's current main currency.
- D-1082 (manager, HIST-2 step 1) changes the previous image writes to `family_record_change` after a rollback, between
  two versions, are not shown as rows of their own: the next version-aware write's RECONCILE baseline makes them one
  UPDATE diff at the reconcile's time, with no author. This is an accepted limitation, and criterion (a) is narrowed
  for them.
- D-1083 (manager, HIST-2 step 1) a comment that erasure removed is read from the old table's own row of the same
  change (same record, instant and action, naming the comment field), and the row then shows "comment null→null"; the
  journal reads `family_record_change` until the table is dropped, and the drop's migration must first leave the
  erasure its own mark in the version.
- D-1084 (manager, HIST-2 step 1) a create row lists its shares in join order, not in the request order of an AMOUNT
  split, which no version stores; the shares and amounts are the same. Owner to confirm.
- D-1085 (manager, HIST-2 step 1) a version whose previous version is missing is skipped as a row, unless it is a
  CREATE, DELETE or ARCHIVE, instead of failing the request. No version can be missing today (only whole-ledger erasure
  deletes versions), so the skip hides nothing yet; a known gap to close before any partial deletion of versions.
- D-1086 (manager, HIST-2 step 2) one history endpoint per record kind, `GET /api/history/{recordType}/{recordId}`,
  paged; a personal record's history is for its owner only (404 for anyone else), a family record's for the ACTIVE
  members of its ledger only, a non-member answering exactly as a missing family record (D-1093) (404), and off behind the
  family switch.
- D-1087 (manager, HIST-2 step 2) the notification table holds no text and no content; a notification row is a
  reference (kind, author, the revision range, the count); its text is built when read (step 4 builds the text).
- D-1088 (manager, HIST-2 step 2) the unread count is one indexed statement over the partial index on unread rows; the
  frontend fetches it on load and after navigation, never by a timer (part 2 decides the client side).
- D-1089 (developer, HIST-2 step 2) The personal delete-all deletes notifications inside `release_family_memberships`, where V19 deletes `activity_event`: by sub through `ledger_member` before the rows lose the sub, and per membership after the lock. `delete_family_ledger` deletes the ledger's notifications explicitly, in V19's order. Same `app.writer` handling; the rest of each function is V19's text.
- D-1090 (developer, HIST-2 step 2) `notification` has no immutability trigger, since `read_at` must be updatable; its erasure needs no guard, and the functions still set the writer as V19 does.
- D-1091 (developer, HIST-2 step 2) The author's foreign key is `ON DELETE CASCADE`, as the spec says. Member rows are never deleted today, only marked FORMER.
- D-1092 (developer, HIST-2 step 2) `notification` has the CHECKs `from_revision >= 0`, `changes_count >= 0` and `to_revision >= from_revision`, and indexes on (recipient, created_at), ledger_id and author_member_id beside the required partial index on unread rows.
- D-1093 (developer, HIST-2 step 2) A family record's history has no ledger id in its path: the API looks through the caller's ACTIVE family budgets for the one holding the record's versions, each lookup scoped to that budget's ledger. A non-member and a missing record both answer 404 "Family record N not found", not "Family budget N not found", since no budget id is known.
- D-1094 (developer, HIST-2 step 2) An unknown `recordType`, FAMILY_CATEGORY, and a FAMILY_RECORD history with the family switch off all answer like an unknown path (404 by NoResourceFoundException). The personal history endpoint works whatever the switch.
- D-1095 (developer, HIST-2 step 2) The answer is `{content, page, size, total, totalPages}`; each version has `seq`, `revision`, `op`, `at`, `cause`, `actor{kind, memberId, name}` and `changes`. `name` is null for SYSTEM and IMPORT and "Former member" for a FORMER or missing member. Personal changes are `[{field, old, new}]` over the top-level keys of the canonical content, values and ids as stored; a first version lists every key with old null.
- D-1096 (developer, HIST-2 step 2) A family version reuses `FamilyJournalDiff` and the journal's own change-building code, so names resolve exactly as in the journal. Unlike the journal, every version is listed: a BASELINE with no earlier version reads as a creation, and a version with no visible change has empty `changes`. The erased-comment rule matches the journal's.
- D-1097 (developer, HIST-2 step 2) The unread count runs one SQL statement (notification joined to `ledger_member` by sub and ACTIVE status, `read_at IS NULL`) in `ledger.history.Notifications`, which is added to the ArchitectureTests list of classes allowed SQL keyed by the user's sub.
- D-1098 (manager, HIST-2 step 3) The activity list (`GET /api/activity?page=&size=`, default 50, max 200) shows the caller's own events (`activity_event` rows whose actor is the caller's sub) and the events of the family ledgers where the caller is an ACTIVE member. Nothing else. Behind the family switch only for the ledger events; the caller's own sign-in, sign-out and expiry events show whatever the switch.
- D-1099 (manager, HIST-2 step 3) SPLIT_RULE_RESET is written to `activity_event` in addition to the `family_record_change` row it already writes (dual write, as D-1075), so the journal's reading of system changes is unchanged; the activity list shows it from `activity_event` only.
- D-1100 (manager, HIST-2 step 3) When a member's data is erased (`release_family_memberships`, V19's and V20's pattern), the leaver's own ledger events (actor sub) are deleted in the same function; other members' events about the leaver (for example "X joined") are kept, with the actor's name shown as "Former member" at read time. Keep the functions' signatures and old callers' behaviour (D-1040); a replaced function's text changes only by the delete.
- D-1101 (developer, HIST-2 step 3) No V21: `activity_event.kind` has no CHECK, and V20's `release_family_memberships` already deletes every row whose actor is the user's sub (ledger rows included) and "Delete all my data" calls it, so D-1100 holds without a change to the erasure functions.
- D-1102 (developer, HIST-2 step 3) Events name members by id only (`subject_type` MEMBER and `subject_id`, the actor by sub), never by name: the list resolves names at read time (the member's name now, "Former member" for a FORMER or missing member), so an erased member's name stays nowhere. Each row of the list carries, in addition to the fields D-1098's step names, `subject {memberId, name}` for a membership's event (null otherwise). Join events (MEMBER_JOINED, SEAT_CLAIMED) are written with the joiner as their actor, so they go with the joiner's own erasure; the events kept about a leaver are other users' (MEMBER_LEFT, SPLIT_RULE_RESET, INVITE_CREATED for their seat). Owner to confirm.
- D-1103 (developer, HIST-2 step 3) Kinds: SETTINGS_CHANGED (`details.changes`: `{field, old, new}` of each changed field of the settings, time zone or date format), MANUAL_RATE_SET, MANUAL_RATE_CHANGED and MANUAL_RATE_REMOVED (`date`, `currency`, `old`, `new`; a CSV upload writes one per changed rate), LEDGER_SETTINGS_CHANGED (name, base currency, split rule and shares by member id), MEMBER_JOINED and SEAT_CLAIMED (the invite's use: `inviteId`, `returned`), MEMBER_LEFT (`removed`: by an owner or by themselves), INVITE_CREATED (`inviteId`, `seatMemberId`, `hours`; never the token), INVITE_REVOKED and SPLIT_RULE_RESET. A save that changes nothing writes none. Not logged: the creation of a family budget, a declined invite, the invites revoked by a member's leaving, and FORMER marking and resets done inside `release_family_memberships`.
- D-1104 (developer, HIST-2 step 3) SPLIT_RULE_RESET is shown with actor kind SYSTEM (no member, no name), like a version's LEAVE cause; its row keeps the acting user's sub, so it goes with that user's erasure. Every other event is the acting user's (USER); a person-level event (no ledger) has no member and no name. A member who left still sees their own ledger events (they are the actor), but no other event of that family budget.

Acceptance criteria of part 1:

- (a) a record's family journal is identical before and after the cut-over: no change shown twice, none lost; narrowed
  by D-1082 (changes of a rollback), D-1083 (an erased comment read from the old table), D-1084 (the join order of an
  AMOUNT split's shares) and D-1085 (a version whose previous version is missing);
- (b) the family journal's answer has the same shape;
- (c) no version holds the payer's private side;
- (d) a personal record's history is for its owner only, a family record's for the ACTIVE members of its ledger only;
  a non-member gets the answer of a missing family budget (404);
- (e) rollback to the previous image still works: the old table is still written.
