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

### 6.7 HIST-4 part 1 as built (D-1193 to D-1201)

Migration V22 adds the `snapshot` table (ledger_id with ON DELETE CASCADE, revision, name, kind without a CHECK,
created_by, created_at) and an index on (ledger_id, created_at DESC, id). Its trigger is the V19 guard
(`history_immutable()`): UPDATE is refused, DELETE only under `app.writer = 'delete-all'` or a ledger's deletion, and
it is not gated by `app.history` (the previous image never writes the table, so rollback is unaffected; D-1040).
Create (`POST /api/snapshots`) and list/get (`GET`) are personal only; the state of a ledger at a revision
(`LedgerState`) is read-only, in one transaction, and uses `DISTINCT ON` over the (ledger_id, revision) index: 18 ms
for 20,000 versions on a test database (EXPLAIN with no sequential scan). Erasure: "Delete all my data" deletes the
user's snapshots, and deleting the personal ledger takes them by the cascade. The activity event SNAPSHOT_CREATED has
no ledger id (as manual rates do), so the Activity page shows it while the family switch is off, and the delete-all
path removes it by actor. Drift is not repaired by the snapshot code: `HistoryJobs` (the baseline job, at start) and
the lazy rule in `HistoryService.write` write the missing BASELINE and RECONCILE versions; until one of them runs, a
create is refused with `HISTORY_INCONSISTENT`.

### 6.8 HIST-4 part 2b as built (D-1226 to D-1233)

`POST /api/snapshots/{id}/restore` with `{"atRevision": n}` applies the plan of `RestorePlanner` all or nothing
(`ledger.history.RestoreService`). Personal ledgers only; the family route answers 409 `FAMILY_SNAPSHOTS_UNSUPPORTED`,
another user's snapshot 404. One READ COMMITTED transaction:

1. the ledger's row `FOR UPDATE` (the lock every record writer's revision takes; readers are not blocked);
2. `ledger.revision != atRevision`: 409 `RESTORE_STALE`, nothing written (D-1222);
3. the plan again, inside the lock; blockers (also `FAMILY_GUARDED`: an entry a live system-owned family link names,
   which `journal_entry_family_guard` would refuse to change or remove): 422 with `violations`, `blockers`, `restorable: false` and `counts`;
4. the BEFORE_RESTORE snapshot at the current revision (no FOR SHARE, one `SNAPSHOT_CREATED` event; D-1010, D-1229);
5. revision R, once (D-1228: also when the plan writes no record);
6. the writes: entries ARCHIVED (under `app.writer = 'restore-archive'`, so no `imported_entry_deleted` row, D-1038,
   D-1224) and DELETED (V14's trigger as for the app's delete); the restored entries' postings, payee and external ref
   are cleared; removed counterparties, categories and accounts are deleted (the planner removes only what no posting
   uses; one a posting uses is KEPT, rule 12); the restored reference records' keys are freed with a temporary
   `~restore~<id>` and then set; RESTORED and RETURNED reference records; RESTORED (UPDATE, version + 1) and RETURNED
   (INSERT with the same id) entries, postings in line order; a RETURNED entry's `imported_entry_deleted` row goes;
7. the versions through `HistoryService.restored`: op RESTORE with `restored_from`, DELETE, or ARCHIVE holding the last
   live content, cause RESTORE, revision R, the member as actor; a drifted record gets its BASELINE or RECONCILE first;
8. the sync stamps (D-1219, D-1231, D-1233), on every RESTORED and RETURNED entry whose `external_ref IS NOT NULL`:
   `import_version = version`; `import_hash` = `RESTORED_IMPORT_HASH` (the SHA-256 hex of "restored") for a RETURNED
   entry, and `coalesce(import_hash, RESTORED_IMPORT_HASH)` for a RESTORED one;
9. `restore_run`, one `restore_item` per written record, one `RESTORE` event (revision R, details `snapshotId`, `name`,
   `beforeSnapshotId`, `runId`, `counts`); the answer `{runId, revision, beforeSnapshotId, counts}` (D-1227).

Any failure after step 3 rolls the whole transaction back. Derived FAMILY_* entries, debt accounts and
UNSPECIFIED_PAYMENTS are no outcomes of the plan and are never written (D-1031). An ARCHIVE version of a row that is
gone counts as a removal for the integrity check and the baseline job, as a DELETE does. The Activity page reads
"Restored “name”: n records changed" (D-1230).

Decisions:

- D-1226 (manager) a test seam: `RestoreService.stepHook` is called after each row write with a step name such as
  `entry:<id>`; the default does nothing, tests set it and reset it after each test.
- D-1227 (manager) the answer and the errors as listed above, in the existing problem envelope.
- D-1228 (manager) a restore always runs in full, even when the plan changes no record.
- D-1229 (manager) the BEFORE_RESTORE snapshot is named `Before restoring '<name>'`, cut to 80 characters; its creator
  is the member.
- D-1230 (manager) "n records changed" counts RESTORED + RETURNED + DELETED + ARCHIVED; KEPT is not written.
- D-1231 (manager) the sync stamp is written after the writes, in the same transaction, with no version of its own.
  It is the entry's own `version` column, read after all the writes, which the importer compares with
  `import_version`.
- D-1232 (manager) no new migration.
- D-1233 (manager; confirmed by the PM, following D-1219, including the marker for a RESTORED entry that has a ref and no hash) D-1219's marker is `import_hash` = the SHA-256 hex of the ASCII string "restored"
  (`RestoreService.RESTORED_IMPORT_HASH`): 64 hex digits, so V14's CHECK holds, and no file row's hash matches it.

Every transaction that writes rows of a personal ledger (`journal_entry`, `posting`, `account`, `ledger_category`,
`counterparty`, `imported_entry_deleted`, snapshot and restore tables) first locks that ledger's row with
`HistoryService.lockLedger` (`SELECT id FROM ledger WHERE id = :id FOR NO KEY UPDATE`), once per transaction and
without taking a revision, so a save that changes nothing still takes none (D-995). It does so before any read that
locks a row of that ledger, such as the entry validation's FOR SHARE or the archive's FOR UPDATE of an account. A family
writer locks the family ledger's row, then the family record's, then the personal ledgers' rows by ascending id, and only
then any personal row. A save during a restore therefore waits at its start and cannot deadlock with it (D-1234). In
the tests, `history.lock-order-check=true` makes a revision of a ledger whose row the transaction has not locked first
fail with "ledger <id> written before its row was locked"; the restore's own FOR UPDATE counts as that lock (D-1235).
The guard covers only writes that take a revision; writes with no version (an `imported_entry_deleted` row alone,
`UserDataService`'s raw SQL, `release_family_memberships`) are outside D-1235.

## 7. Family acceptance (D-992)

### 7.1 What has to change

Today `FamilyPostingService.post` posts a changed record into every member's ledger in the actor's transaction,
and ADR 0003's D-10 says each member's debt account equals their family balance, always. Under D-992:

- `ledger.revision` numbers the family ledger's change sets (section 5.4). Only changes of **records** are
  revisions: create, edit, delete, settle, import, restore. Membership changes, the split rule, member names and
  category renames are not (D-1014): they change no member's postings, or only labels.
  Label-only changes (a family category rename, and the other changes of D-1014 that change no record) take a ledger revision and make nothing pending: a member's accepted revision advances over them only when he was caught up (D-1159).
- `ledger_member.accepted_revision` per member with an account: the last revision whose effects his books show.
  Set to the head at join, claim and return; null for LEFT and FORMER and for members without an account.
- Pending(m) = the change sets with revision > `accepted_revision(m)`.
- The record's change posts **only for members who are caught up and only the actor's own books**, at once, as
  today (the actor is caught up by rule 7.2). For everyone else the posting waits.
- **Accept** (D-1012, D-1026: *catch-up*, no per-change accept): for member m, in one transaction, re-post from the
  live record every record changed since `accepted_revision(m)`, for m only (the existing `postRecord` already
  keys its work by member; it gets a member filter), re-run his `openings`, and set `accepted_revision` to the head.
  Because every re-post reads the live record, no "family state as of revision R" is ever needed.
  **As built (HIST-3 part 3, D-1168, D-1167):** `POST /api/family-ledgers/{ledgerId}/accept` locks the family ledger row
  FOR NO KEY UPDATE first, then, for the caller only (an ACTIVE member with an account; others are refused as the
  family endpoints refuse them): re-posts each record pending for him (D-1159; created, edited, settled, or deleted by
  removing his posting as a normal delete does), re-runs his openings, sets `posted_revision` on his links to their
  record's `last_revision`, then sets `accepted_revision` to the ledger's current revision. It takes no ledger revision
  and writes no record version of the family ledger. Idempotent: a caught-up member gets 200 with 0. In the same
  transaction his unread `FAMILY_CHANGES`, `FAMILY_IMPORTED` and `FAMILY_RESTORED` notifications up to that revision are
  marked read. With the switch off the endpoint answers 404.
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
HIST-2 delivers notifications as information, before acceptance exists, so the model is proven first. Retention: no time limit; a row goes with its recipient's data, or with the budget (D-1146).

### 7.4 Cost, plainly

This is the expensive part. It changes the meaning of ADR 0003 D-7 and D-10, touches `FamilyPostingService`,
`CrossLedgerWriter` callers, every family test (they post for two members in one request and assert D-10 at
once; each now needs an accept step), the end-to-end family specs (03, 04, 05, 07, 09 act as two accounts),
the integrity report, the importer's rules and the deploy scripts (a switch for it). I estimate 2,500 to 4,000 lines
including tests, the highest risk of the series. **The cheapest variant that meets D-992** is the one above:
catch-up accept (no per-change accept), the author must be caught up, live labels with pending marks. Anything
that needs "state of the family ledger as of revision R" (per-change accept, the exact Entries numbers without
link facts) costs about half again as much and is deferred unless Sergey needs it. The whole feature sits behind
its own switch (`app.family.acceptance.enabled`, off by default, D-1023), so HIST-3 can be deployed dark. Here, "posted" means "checked as matching": a link whose posted_revision equals its record's last_revision needs no rewrite.

**As built (HIST-3 part 4).** The switch reaches production through `deploy/deploy.sh acceptance on|off`, modelled on
`switch`: it changes only the `FAMILY_ACCEPTANCE_ENABLED` line of `.env`, restarts the api alone and checks its
`Family acceptance: on|off` line. Its pre-check, `deploy/checks/acceptance.sql` read as `finance_checks`, lets `on` go
on only with the family switch on, every member's counter at its ledger's head, every link at its record's last
revision and nothing pending; `off` refuses while anything is pending, or when that can't be read (D-1175, D-1177,
D-1179). A lagging uploader's dry run reports the family rows it couldn't check as a count, not as row errors
(D-1173, D-1180). The acceptance screens are part 5 (D-1174); production stays off.

### 7.5 The interface as built (HIST-3 part 5, D-1184 to D-1192)

Behind the acceptance switch, with nothing changed while it is off. The UI learns that acceptance is on by asking a
budget's pending summary: an answer means on, a 404 (an unknown path) means off, and the answer is kept for the
session, so with acceptance off nothing is asked again and nothing is drawn (D-1185, D-1186).

- **Banner.** Above every page of a family budget, "3 changes by others are waiting for you" with "Accept", only above 0.
  The count is asked when a page of the budget opens, on each move between its pages and after an Accept, never by a
  timer (D-1120, D-1187). After the banner's Accept the banner goes, the activity and an open record load again and a
  line says how many changes were accepted.
- **Mark.** A record with `pendingForMe` carries "Waiting for you" in the list and on its page, titled with what it
  means: it shows the latest version and the member's own books follow once he accepts. Balances, reports and the personal
  pages are not changed (7.2, D-1029, D-1188).
- **A refused write** (409, `FAMILY_PENDING_CHANGES`, with `pendingChanges`) shows the server's message and, in the same
  box, "Accept 3 changes" (D-1027). One click accepts and says how many; the form keeps what was typed, nothing is
  retried, and the user presses the same button again (D-1184). It covers the new expense, income and settlement, a record
  page's edit and delete, the payer's own payment entry, the personal editor's family expense and the import's commit
  (D-1189).
- **Notifications.** Rows of the kinds FAMILY_CHANGES, FAMILY_IMPORTED and FAMILY_RESTORED get "Accept" while the member
  has pending changes in that budget; afterwards the list reloads, the header badge asks again and the rows the accept
  covered show as read (D-1167, D-1190).
- **Import.** The dry run's named note is shown above its totals, with "N family rows not checked until you accept"
  when above 0; a commit refused for pending changes follows the refused write above (D-1172, D-1173, D-1191).

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
- **Notifications about a leaver** are kept in the database but are not shown to him once he has left; they go when he deletes his data or the budget is closed. Others' notifications show his name while his membership is LEFT and "Former member" once it is FORMER (D-1146).
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
| **HIST-3b** (optional) | accepted facts on `family_entry_link`, personal reads switch to them | the next free number, if ever built (D-1194) | medium (planner-sensitive statements) | about 600 lines | exact numbers on Entries while changes are pending |
| **HIST-4: personal snapshots and restore** | snapshots (manual, before import, before restore), preview, restore of a snapshot and of one version, archived status, undo | V22 (`snapshot`, part 1) and V23 (`restore_run`, `restore_item`, part 2) (D-1194) | medium: restore invariants; mitigated by the all-or-nothing preview and the oracle test | about 1,800 lines | "Snapshots" in Settings; "Restore this version" in history; an import page's undo |
| **HIST-5: family snapshots and restore** | the same for family budgets, owners only, as a change set that others accept | none or small | medium-high (meets HIST-3) | about 800 lines | family snapshots, restore preview by author, notifications |

Amended 2026-11-22 (HIST-2 charter, D-1075): HIST-2's V20 holds only the `notification` table and the replaced
erasure functions; `family_record_change` is not frozen in HIST-2 (HIST-1's dual write stays, so that rollback to
the previous image keeps working) and is frozen and dropped in a later migration. See section 13.

Amended 2026-11-25 (HIST-4 part 1, D-1194 to D-1201): HIST-4 part 1 is V22 (the `snapshot` table, personal ledgers
only, backend only) and part 2 is V23 (restore tables); HIST-3b, if ever built, takes the next free number. A
snapshot takes the ledger's row with `FOR SHARE`, reads the revision after the lock, checks the counts of live records
against the state at that revision, and checks the cap of 1,000 per ledger before its insert, all in one transaction.
The cap is a safety limit, not an invariant: two snapshots made at once can both read 999 and both be inserted
(D-1197); that race is accepted.

Amended 2026-11-26 (D-1296, section 15): a snapshot is a book snapshot, covering the personal ledger and the member's
part of each family budget he is ACTIVE in; `FAMILY_SNAPSHOTS_UNSUPPORTED` is retired for it. The HIST-5 row above now
means a snapshot of a whole family budget (all members' records), by consent; its owners-only rule (D-1015, D-1030)
applies to HIST-5 only.

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
D-1146 (manager, HIST-2 tail) the privacy text about notifications after a member leaves follows what the code does. Code path (file and method names only): `FamilyMembershipService.end` writes MEMBER_LEFT through `Notifications.membership`; recipients are fixed in `Notifications` (RECIPIENTS: the ACTIVE members with an account, never the actor or the leaver); `Notifications.page` shows only rows of ACTIVE memberships and reads the author's name at read time ("Former member" only for a FORMER or missing member); `NotificationText.of` builds the text; the only deletions are in V20's `release_family_memberships` and `delete_family_ledger` and the cascades; the only time rule is `HistoryJobs.purgeSignInEvents`. As the code does it: a leave gives one MEMBER_LEFT notification to each other member with an account, and the leaver is named there while their membership is LEFT; the leaver no longer sees their notifications but the rows are kept until "Delete all my data" or the budget's closure; a removed member with no account and no share, not named by any record, is deleted at once and its MEMBER_LEFT notification goes with it (no notification is shown for that case). No notification and no family history record expires by time. The 13 months apply to sign-in, sign-out and ended-session events only (`purgeSignInEvents`). This states what D-1121 meant by "deleted when you leave" differently: a leave does not delete notifications (D-1142 already says so).

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
- D-1105 (manager, HIST-2 step 4) A notification is written in the same transaction as the change it describes (as D-1079 for events). A change that fails writes no notification; a notification insert that fails rolls the change back.
- D-1106 (manager, HIST-2 step 4) The merge: an unread FAMILY_CHANGES for the same recipient, ledger and author is updated in place (`from_revision` the smaller, `to_revision` the larger, `changes_count` added, `created_at` refreshed) instead of a new row. A read one is never changed; a new row is then created. Two transactions merging the same row must not lose a count.
- D-1107 (manager, HIST-2 step 4) Reads and marks: `GET /api/notifications?page=&size=` (default 50, max 200, newest first) returns `{content, page, size, total, totalPages}`; each row has id, kind, at, read, author `{memberId, name}` (name at read time, "Former member" for a FORMER or missing member), a revision range and a text built when read from the kind, the author's name and the count. `POST /api/notifications/{id}/read` marks one of the caller's (another user's or a missing one: 404); `POST /api/notifications/read-all` marks the caller's unread rows. Only rows of ledgers where the caller is ACTIVE are returned or marked. With the family switch off all three answer 404 like an unknown path.
- D-1108 (manager, HIST-2 step 4) An actor is never notified of their own change; the recipients are fixed when the notification is written (members ACTIVE at that instant), and no notification is written for a ledger that is not family-enabled.
- D-1109 (developer, HIST-2 step 4) No V21: V20's `notification` table has every column and CHECK needed, and its erasure functions are unchanged (`release_family_memberships` deletes the rows a user received; `delete_family_ledger` and the cascades take the rest).
- D-1110 (developer, HIST-2 step 4) The merge is one atomic statement in `Notifications.familyChanged`: a data-modifying CTE `UPDATE notification ... SET changes_count = changes_count + 1 ... WHERE read_at IS NULL RETURNING recipient_member_id`, then an INSERT for the recipients it did not update. A concurrent merge waits for the row lock and PostgreSQL re-evaluates the row after it (READ COMMITTED), so the count is never lost; a row marked read meanwhile no longer matches and a new row is created. Writers of one ledger are also serialized by the ledger's row lock. Tested with two transactions on one row.
- D-1111 (developer, HIST-2 step 4) Recipients are ACTIVE members with an account (`user_sub` set) of a SHARED ledger, other than the actor and the author; a place without an account gets none. The write is skipped when `app.family.enabled` is off. FAMILY_CHANGES counts one change per create, update, delete or settlement call and takes the ledger revision that call's transaction took (HIST-1); a call that took none (nothing changed) writes nothing. The import's own record writes (`*ForImport`) write no FAMILY_CHANGES.
- D-1112 (developer, HIST-2 step 4) Membership kinds: the author is the member the event is about (who joined, left, was removed or claimed a seat), the actor is who did it (an owner, for a removal); neither is notified. MEMBER_LEFT covers both leaving and removal (no content says which). They carry no revision range and no count. A member without an account whose row is deleted on removal takes the notification about them with it (the author's foreign key cascades).
- D-1113 (developer, HIST-2 step 4) FAMILY_IMPORTED is written once per committed import that names a family budget, at the end of the import's transaction, with the revision the import took on that budget, or no range when it changed only the importer's own side of the records. A dry run or a refused import writes none.
- D-1114 (developer, HIST-2 step 4) Texts (English, built in the pure class `NotificationText`): "{name} made a change in the family budget", "{name} made {n} changes in the family budget", "{name} imported records into the family budget", "{name} joined the family budget", "{name} left the family budget", "{name} took their place in the family budget"; "Someone" when there is no author. Each row also returns its `ledgerId` and its `changesCount`. Marking a read row again keeps its first `read_at`.
- D-1115 (PM, HIST-2 1b step 2) amends D-1113: FAMILY_IMPORTED is written only when the import took a revision on the family budget, at that revision. An import that changed only the importer's own side of the records, or nothing, notifies nobody; a second import of the same file writes none.
- D-1116 (PM, HIST-2 1b step 2): a version skipped under D-1085 (its previous version is missing, it is past seq 1, and it is not a CREATE, DELETE or ARCHIVE; a seq 1 BASELINE is no gap and is not logged) is logged at WARN with the ledger id, the record id and the version's seq, no content; `HistoryIntegrity`'s report has no place for such a count, so only the log.
- D-1117 (developer, HIST-2 1b step 2): the journal's count query finds the skipped versions in the same scan (its `changes` set keeps them, flagged, and the page query leaves them out), so a healthy read runs no extra statement; the WARN is written on every journal read that meets the gap, once per skipped version. The record history (`RecordHistory`) is not logged.

Acceptance criteria of part 1:

- (a) a record's family journal is identical before and after the cut-over: no change shown twice, none lost; narrowed
  by D-1082 (changes of a rollback), D-1083 (an erased comment read from the old table), D-1084 (the join order of an
  AMOUNT split's shares) and D-1085 (a version whose previous version is missing);
- (b) the family journal's answer has the same shape;
- (c) no version holds the payer's private side;
- (d) a personal record's history is for its owner only, a family record's for the ACTIVE members of its ledger only;
  a non-member gets the answer of a missing family budget (404);
- (e) rollback to the previous image still works: the old table is still written.

**As built for HIST-3 (part 4).** The reconcile of D-1160 for the operator is `deploy/manual/reconcile-acceptance.sql`,
V21's backfill as a rerunnable transaction with counts before and after, which refuses while anything is pending by
the stored counters (D-1165); the runbook's "Family acceptance (HIST-3)" holds it, byte for byte, after a guard that
stops unless acceptance is off in `.env`, to run after a rollback to an image older than V21 and before
`acceptance on` (D-1176). The stage check `deploy/checks/HIST-3.sql` and `.expected` keeps HIST-2's lines and adds
V21's columns, its trigger and four invariants of the counters with acceptance off (D-1179).

## 14. HIST-2 part 2 (frontend and deploy)

Status: as built (HIST-2 part 2, step 4). Part 2 is the frontend of HIST-2 over part 1's API (section 13); it changes no backend. Decisions D-1118 to D-1145 are listed below, after "As built".

### As built

- Step 1, the History panel on records (`443d41d` for D-1118 to D-1121, `25639a3`): `frontend/src/history.ts`, `HistoryPanel.tsx` (the panel is component state, not URL state, D-1122; labels as fixed maps, D-1126 and D-1127; 20 versions a page, D-1125; a sheet under 40rem, D-1128), the History action in `Entries.tsx`, `EntryEditor.tsx` (saved entries only), `Accounts.tsx`, `Categories.tsx` and `FamilyExpenses.tsx` (a family record, only with the family switch on), `icons.tsx`, `index.css`, with `history.test.ts` and `HistoryPanel.test.tsx`.
- Step 2, the Activity page (`37b5322`): `activity.ts`, `Activity.tsx` at `/activity` (D-1129, the page in the URL; D-1131, a sentence per kind; D-1132, the session note), the "Activity" entry in the name menu just above Log out in `Header.tsx` (D-1130), with `activity.test.ts` and `Activity.test.tsx`.
- Step 3, the badge and the list (`b6aba68`): `notifications.ts`, `unread.tsx`, `Notifications.tsx` at `/notifications`, the badge in `Header.tsx`, the route in `App.tsx`. The count is fetched when the shell mounts and on each route change and after a mark, never by a timer (D-1134); the badge hides at 0 and shows "99+" over 99 (D-1135); opening a row marks it read, then goes to the journal or the members of its family budget (D-1136), nothing changes before the server has answered (D-1138) and a failed mark keeps the row unread with "Try again" (D-1137); a failed count hides the badge (D-1139); the badge and the route exist only with the family switch on (D-1140). Tests: `notifications.test.ts`, `Notifications.test.tsx`.
- Step 4, this commit: the privacy page's two new sections "Record history" and "Activity and notifications" and its "How long it is kept" bullet (`frontend/public/privacy.html`, D-1141, D-1142); the deploy checks `deploy/checks/HIST-2.sql` and `.expected`, the `numbers.sql` key and the restore test's `finance.conf` line (D-1143); the RUNBOOK's note (D-1145); `e2e/specs/16-history-notifications.spec.ts` (D-1144), run only with `E2E_FAMILY=on`: A changes a family record; B's badge, the list, opening the row and the count after; A's History on the record; A's Activity from the name menu. The test catalog's and the frontend map's entries for part 2.

Not done in part 2: the payees' History action (D-1124: no screen lists payees as rows), HIST-3's acceptance of notified changes (no "Accept"), HIST-4 and HIST-5.

- D-1118 (PM, HIST-2 part 2 step 1) a "History" action on a record opens a side panel (a full-screen sheet on a phone below the existing breakpoint), no new top-level page: on Entries rows (and in the entry editor for a saved entry), accounts, categories, payees (counterparties), and family records. It lists versions newest first, with load-more paging: date and time in the user's date format and time zone (the existing helpers in `dates.ts` and `timeZone.ts`, never the browser's clock: no `new Date()` for display), who (the actor's name; "Import" and "System" for those kinds), what happened (Created, Changed, Deleted, Archived, Restored, and "State when history began" for the baseline), the cause when it is an import, demo, merge and so on, and the field changes as "field: old → new" with human labels, money formatted as elsewhere (`formatMoney`, Big.js, never JS numbers). No decision numbers and no technical words in screen text. A record that has no history (404) says so plainly in the panel. Colours only from theme tokens. Payees are not in this step: see D-1124.
- D-1119 (PM, HIST-2 part 2 step 1) the Activity page is reached from the user's name menu (next to Settings and Log out), titled "Activity": a paged list of the user's own sign-ins, sign-outs, ended sessions, settings and rates changes and, when the user is in a family budget, its membership and settings events, each with a plain sentence built from the row, the time, and a note that restarts end sessions without a sign-out event (ADR 2.3). Names as the API returns them.
- D-1120 (PM, HIST-2 part 2 step 1) the notification badge is in the header next to the user's menu, shows the unread count (hidden at 0, "99+" over 99), is fetched on page load and after each navigation or after the user marks something read, never by a timer; with the family switch off the badge and the notifications page do not exist. The list page ("Notifications") shows the text the API returns, the time, unread marked, a "Mark all as read" button; opening an item marks it read and goes to the family journal (kinds FAMILY_CHANGES, FAMILY_IMPORTED) or the members tab (membership kinds) of that ledger. No "Accept" (HIST-3).
- D-1121 (PM, HIST-2 part 2 step 1) the privacy page gets: the history of records (what a version holds, that it is kept, that "Delete all my data" erases it), the activity events and their 13 months, the notifications (in the app only, no content, hidden when you leave, and deleted with your data or when the budget is closed); every promise of the existing sections stays true. Note (D-1150): a leave hides the notifications and deletes none; they are deleted only through the deletion of the family budget (`delete_family_ledger`, V20) or the release of the memberships when the user deletes their data (`release_family_memberships`, V20).
- D-1122 (manager, HIST-2 part 2 step 1) the panel's open state and its loaded pages are component state, not URL state; opening it does not change the page's URL. It is a dialog over the page, not a page the user shares.
- D-1123 (manager, HIST-2 part 2 step 1) on Entries the History action is in each row's existing action menu (or beside the row's actions, whichever matches the screen's pattern); in the entry editor it is offered only for a saved entry, never for a new one. Entries' data loading, summary and statements do not change; only the action and the panel are added.
- D-1124 (manager, HIST-2 part 2 step 1) the payee History action waits for a screen that lists payees as rows; none exists today (the Your payees tab holds a checkbox and members). The payee action comes with a payees screen, which this step does not build (manager decision, accepted by the PM).
- D-1125 (manager, HIST-2 part 2 step 1) load-more: 20 versions per page, a "Load more" button, hidden when all versions are loaded; while a page loads the button is disabled and shows a loading text; an error on load-more keeps the loaded versions and shows the error with a retry.
- D-1126 (manager, HIST-2 part 2 step 1) field labels are a fixed map per record kind, written in the app's plain language (the same words the form uses for the field). An unknown field name shows as "Other detail" with its value, never the raw key. Values: money as `formatMoney` in its currency; dates as `formatDate` in the user's format; booleans as "Yes"/"No"; null as an em dash "—"; a reference that is an id the frontend cannot name shows as "Removed item" (not the id).
- D-1127 (manager, HIST-2 part 2 step 1) cause labels: a fixed map from the API's cause values to plain text (import, demo data, merge, and the rest as the API defines them); an unknown cause shows as "Other change". Op labels: CREATE "Created", UPDATE "Changed", DELETE "Deleted", ARCHIVE "Archived", RESTORE "Restored", BASELINE "State when history began".
- D-1128 (manager, HIST-2 part 2 step 1) on a phone (below 40rem, the app's existing breakpoint) the panel is a full-screen sheet with its own close button; above it, a right-side panel. Nothing scrolls sideways at 375 px.
- D-1129 (manager, HIST-2 part 2 step 2) the Activity page's page number is in the URL (`/activity?page=N`); the page size is the API's default (50); the list pages with "Newer" and "Older".
- D-1130 (manager, HIST-2 part 2 step 2) the "Activity" menu entry sits directly above "Log out" and below "Settings"; the page is available whatever the family switch is.
- D-1131 (manager, HIST-2 part 2 step 2) each activity row's sentence comes from a fixed map of kind to plain text; an unknown kind shows "Other activity"; a null subject name of a membership row shows "Former member" and a null actor name "Someone".
- D-1132 (manager, HIST-2 part 2 step 2) the Activity page has one session note under its title: a session can end when the browser closes or after a restart, without a sign-out event.
- D-1133 (manager, HIST-2 part 2 steps 2 and 3) times through `formatInstant` in the user's format and zone; the empty, error and loading texts; no technical word or decision number in visible text; theme tokens only.
- D-1134 (manager, HIST-2 part 2 step 3) the unread count is fetched when the signed-in shell mounts, on each pathname change, and after a mark; never by a timer, focus or visibility.
- D-1135 (manager, HIST-2 part 2 step 3) the badge links to `/notifications` with the accessible name "N unread notifications" (or "1 unread notification"); "99+" over 99.
- D-1136 (manager, HIST-2 part 2 step 3) a membership kind opens its ledger's members tab; FAMILY_CHANGES and FAMILY_IMPORTED open the family journal; the mark is sent first and the navigation follows its answer; a failed mark leaves the row unread and does not navigate.
- D-1137 (manager, HIST-2 part 2 step 3) the list pages like the Activity page: 50 a page, the page in the URL, "No notifications on this page." past the end and "No notifications yet." on an empty first page.
- D-1138 (manager, HIST-2 part 2 step 3) no optimistic update; "Mark all as read" sends read-all and then reloads the list and the count; a failed mark shows its error with "Try again" and keeps the rows.
- D-1139 (manager, HIST-2 part 2 step 3) a failed count request hides the badge, with no error text in the header.
- D-1140 (manager, HIST-2 part 2 step 3) with the family switch off there is no badge, no request to either notification endpoint, and `/notifications` redirects to `/` like the family routes.
- D-1141 (manager, HIST-2 part 2 step 4) the privacy page's new text is in two new sections, outside the Family budgets section, which stays equal to the draft.
- D-1142 (manager, HIST-2 part 2 step 4) the privacy text is plain: no decision number and no technical word; it states only what the code does (for example, the 13 months apply to sign-in, sign-out and ended-session events only, and notifications are not deleted when a member leaves). Owner decisions on these are open.
- D-1143 (manager, HIST-2 part 2 step 4) the deploy checks `HIST-2.sql` and `HIST-2.expected` follow HIST-1's pattern (read-only, invariants only, no count that moves by itself); `numbers.sql` gets the `notifications` key and the restore test's `finance.conf` gets its count. The developer and the manager both used "D-1142" for the deploy checks in messages; this number is the one that stands.
- D-1144 (manager, HIST-2 part 2 step 4) one end-to-end spec, `16-history-notifications.spec.ts`, run only with `E2E_FAMILY=on`, for two accounts: a family change, the badge and the list, the History panel and the Activity page.
- D-1145 (manager, HIST-2 part 2 step 4) the RUNBOOK gets only a note under "Deployed revisions" that HIST-2 part 2 is not deployed; its checklist follows review.

### 14.x HIST-3 part 1 (D-1154 to D-1158)

- D-1154 (PM, HIST-3) HIST-3 is developed now and deployed only if accepted and verified in production by 2026-11-28; otherwise it is deployed after the December import.
- D-1155 (manager, HIST-3 part 1) the counters are three additive columns and no index: `ledger_member.accepted_revision` (BIGINT NULL, set for ACTIVE members with an account only), `family_record.last_revision` (BIGINT NOT NULL DEFAULT 0), `family_entry_link.posted_revision` (BIGINT NOT NULL DEFAULT 0). No new index: the counters are read only by the integrity check and by HIST-3's later parts; no read statement changes.
- D-1156 (manager, HIST-3 part 1) the acceptance switch is the property `app.family.acceptance.enabled` (environment `FAMILY_ACCEPTANCE_ENABLED`, default false), read by a bean `FamilyAcceptanceSwitch` next to `FamilySwitch`. In this part nothing branches on it; it only logs its state at startup.
- D-1157 (manager, HIST-3 part 1) the backfill in V21: `accepted_revision` := the ledger's revision for each ACTIVE member with an account; `last_revision` := the revision of the record's latest version, or 0 when it has no version; `posted_revision` := its record's `last_revision`. After the migration nothing is pending.
- D-1158 (manager, HIST-3 part 1) join, claim and return set `accepted_revision` to the ledger's head at that transaction (after its lock); leave and becoming FORMER set it to null. A deleted record has no row, so it has no counter; its links are removed or re-posted by the change set that deletes it.

### 14.y HIST-3 part 2 (D-1159 to D-1164)

- D-1159 (PM, HIST-3 part 2) pending is computed from records, never from gaps in revision numbers: record R is pending for member m when R.last_revision > accepted_revision(m) (equivalently: no non-detached link and last_revision > accepted_revision, or a non-detached link with posted_revision < last_revision). A revision that changes no record (a category rename and the other label-only changes of D-1014) advances accepted_revision ONLY for members whose accepted_revision equals the previous head (revision - 1); lagging members keep theirs. With the switch off, everybody advances at each revision, as before.
- D-1160 (PM, HIST-3 part 2) an idempotent service method re-runs the V21 backfill (counters from record_version, links, and accepted_revision := head for ACTIVE members with an account). It refuses, with a clear error, while the switch is on, or when any family ledger has a member whose accepted_revision is below head. The deploy script uses it before the switch is turned on (part 4: the rollback to the V20 image writes no counters).
- D-1161 (manager, HIST-3 part 2) a family record change set (create, edit, delete, settle) with the switch on posts only into the actor's books (and, for a settlement, the actor's side) and only if the actor is caught up; it sets last_revision = R on the record, posted_revision = R on the actor's links, and accepted_revision = R for the actor. Other members are not touched; their records become pending by D-1159.
- D-1162 (manager, HIST-3 part 2) the author rule: an actor who is not caught up and tries to create, edit, delete or settle a family record gets 409 with the stable code `FAMILY_PENDING_CHANGES` and the count of pending records, in the message "accept the n pending changes first". The count is one indexed statement, in the reusable method `pendingRecords(ledger, member)`, which part 3 uses for Accept. Detached links (detached_at set) are ignored; members without an account accept nothing and are never pending. The 409 offers "Accept" in the same place only in part 4 (D-1027).
- D-1163 (manager, HIST-3 part 2) a deleted family record is recognised by its soft delete (deleted_at set, V7) and its last_revision, which the delete moves like any change; no new table. Other members' links to it are pending by D-1159 until they accept (part 3).
- D-1164 (manager, HIST-3 part 2) an Excel import commit by a lagging actor goes through the same posting path and gets the same 409 (`FAMILY_PENDING_CHANGES`); the importer's own refusal text is part 3 and is not written here.
- D-1165 (manager, HIST-3 part 2) the reconcile of D-1160 (`AcceptanceCounters`) refuses on the counters as stored, before it recomputes them: a member with a record whose last_revision is above their accepted_revision, or an attached link posted below its record's last_revision. The recomputed counters would refuse the rollback case itself (the V20 image writes no counter, so links look stale), and the stored check still refuses real lag from a switch-on period. The importer's commit (D-1164) takes the author rule's check under the ledger lock at its start, before any row or category is written, so a lagging uploader gets 409 FAMILY_PENDING_CHANGES.

### 14.z HIST-3 part 3 (D-1166 to D-1172)

- D-1166 (PM, HIST-3 part 3) HIST-3 deploys dark: the existing e2e specs 03, 04, 05, 07 and 09 stay on switch OFF and unchanged; adapting them is the later "turn acceptance on in production" step, not part of HIST-3. Part 4 adds one new local spec and the deploy script's switch.
- D-1167 (PM proposal, HIST-3 part 3) a successful accept marks the accepting member's unread FAMILY_CHANGES, FAMILY_IMPORTED and FAMILY_RESTORED notifications of that ledger with to_revision at most the head he accepted as read. Nothing else about notifications changes.
- D-1168 (manager, HIST-3 part 3) the Accept action (POST /api/family-ledgers/{ledgerId}/accept): one transaction, the family ledger's row lock first; for the calling ACTIVE member with an account only; re-posts into his books only every record with a pending change (D-1159), created, edited, deleted (soft: his posting is removed as a normal delete does) or settled; re-runs his openings; sets posted_revision on his links and then accepted_revision to the ledger's current revision. It takes no ledger revision. Idempotent: a caught-up member gets 200 with 0. Returns the number of records accepted. With the switch off the endpoint answers 404 as an unknown path does.
- D-1169 (manager, HIST-3 part 3) the pending marks on the family records list and detail: pendingForMe is computed by one batched statement per page, over the page's record ids and the caller's member row; with the switch off it is false and no statement runs.
- D-1170 (manager, HIST-3 part 3) the pending summary (GET /api/family-ledgers/{ledgerId}/pending) answers the count and the ids of the caller's pending records; with the switch off it answers 404 as an unknown path does.
- D-1171 (manager, HIST-3 part 3) the integrity report with the switch on: a member with pending records shows pendingChanges with its count and his debt-versus-family-balance comparison is skipped; a caught-up member is compared as before. With the switch off the output is byte-for-byte as now.
- D-1172 (manager, HIST-3 part 3) the importer's dry run by a lagging uploader lists the pending changes as a named note, which does not fail the run: "N family changes are waiting for you; accept them before committing". The commit refusal of D-1164 is unchanged.

### 14.w HIST-3 part 4 (D-1173 to D-1180)

- D-1173 (PM, HIST-3 part 4) the lagging uploader's dry run lists ONE named non-failing note ("N family changes are waiting for you; accept them before committing", D-1172) and NO per-row errors for family rows caused by the author rule. The family rows that would have been checked are reported in the run's summary as a count, worded "not checked until you accept". The run stays DRY_RUN and writes nothing. The commit refusal (409, FAMILY_PENDING_CHANGES, D-1164) is unchanged.
- D-1174 (PM, HIST-3 part 4) the acceptance UI is part 5, not part 4. HIST-3 deploys dark without it.
- D-1175 (manager, HIST-3 part 4) `deploy/deploy.sh acceptance on|off` copies `switch`'s preflight: `on` refuses on health, a HEAD that isn't the last good deploy, images that aren't its content and unfinished work; `off` prints each of them as a WARNING and goes on, as `switch off` does. Unlike `switch off` (D-50), both directions need the read-only role, since both read the pending count. It reads neither the pages nor the numbers: the restart changes no data and no page.
- D-1176 (manager, HIST-3 part 4) the reconcile is the manual SQL file `deploy/manual/reconcile-acceptance.sql` (V21's backfill as `AcceptanceCounters` reruns it, with counts before and after, refusing while anything is pending by the stored counters). The operator never runs the file: the runbook's block ("Family acceptance (HIST-3)") holds its text in a heredoc after a guard that stops unless `FAMILY_ACCEPTANCE_ENABLED` in `.env` is absent or `false`, and a fresh backup, run as the app's login `finance` like "Delete a user". `deploy/tests/run.sh` checks that the block is the file byte for byte; the file was tried on a throwaway database migrated by Flyway to V21.
- D-1177 (manager, HIST-3 part 4) `acceptance off` refuses while any record is pending ("accept first", with the count) and also when the count can't be read (no role, the check failing, or an unexpected line).
- D-1178 (manager, HIST-3 part 4) `verify` and `finish` print one line about family acceptance (the `.env` line and the api's logged line), for information only: never a problem, never a stop, so their verdicts don't change.
- D-1179 (manager, HIST-3 part 4) the pre-check is `deploy/checks/acceptance.sql`, a check file without an `.expected` (like `numbers.sql`): one line `acceptance members=<m> links=<l> pending=<p>` that `deploy.sh` parses; `on` needs all three 0 and the family switch on in `.env` and in the running api. `numbers.sql` gets no key (V21 adds no table). The stage check `HIST-3.sql` is HIST-2's lines with the Flyway line at V21, then V21's columns, its trigger by name and four invariants of the counters that hold with acceptance off.
- D-1180 (manager, HIST-3 part 4) D-1173's count is `totals.notCheckedUntilAccepted` of the import report (also in the stored run summary, and one line of the Markdown report when above 0); it counts each family row whose write the author rule refused in a dry run, including a payment side and each row of a grouped settlement.
- D-1181 (manager, HIST-3 part 4) detached family links (a member who left, `detached_at` set) are never stamped again (`CrossLedgerWriter` stamps attached links only), so the pre-check `deploy/checks/acceptance.sql`, the invariant of `HIST-3.sql` and the reconcile's counts before and after count attached links only (`detached_at IS NULL`). The reconcile's UPDATE of the links stays V21's (and `AcceptanceCounters`'): over every link, detached ones included, setting each to its record's `last_revision`; nothing reads a detached link's counter, and a record the leaver is in is frozen, so it doesn't move afterwards. `FamilyCountersApiTests.aDetachedLinkIsNotCountedByTheStageCheck` runs HIST-3's query after a leave and the remaining member's change sets.

## 15. Book snapshots (HIST-4 family part)

Status: design (2026-11-26), not built. Section 10 holds the decisions of HIST-0, so this part is section 15. The PM's
decisions D-1290 to D-1296 are taken as given; D-1300 to D-1334 are the manager's and settle what the PM left open.
The owner can overrule any "(manager)" decision. Line numbers refer to HEAD `398779c`.

### 15.0 The PM's decisions (given)

- D-1290 (PM) A snapshot covers the member's whole book: the personal ledger and his part of every family budget where
  he is an ACTIVE member; it stores the revision of each of these ledgers.
- D-1291 (PM) A restore returns only the family records whose every change after the snapshot was made by the restoring
  member (versions after the family ledger's snapshot revision with actor = him, imports by him included). A record
  anyone else changed after the snapshot stays as it is and is listed in the preview (KEPT_OTHER_MEMBER).
- D-1292 (PM) A restored family record is re-posted under the budget's current membership and split rules, exactly as an
  edit of the record made now would be. A record that cannot be posted under the current rules is a blocker naming it.
- D-1293 (PM) D-1219 extends to family records imported from the restoring member's own Excel.
- D-1294 (PM) "Restore this version" works for a family record too, under D-1291 to D-1293.
- D-1295 (PM) For other members a restore is the restoring member's change of those records: acceptance off, posted at
  once with the usual notification; acceptance on, a change set they accept.
- D-1296 (PM) FAMILY_SNAPSHOTS_UNSUPPORTED (D-1193) is retired for book snapshots; HIST-5 becomes "snapshot of a whole
  family budget (all members' records), by consent".
- D-1236, D-1237 and D-1238 (restoreArchived marks, the version-restore answer, `GET /api/history/archived`) stay and
  extend to FAMILY_RECORD. They are not yet committed in this repository; the first code part that implements them records them.

### 15.1 What the code does today (the base this builds on)

- A snapshot is one row of one ledger (V22: `snapshot.ledger_id`, `revision`), created only for a personal ledger
  (`SnapshotService.java:73`, `:174-178`), under `SELECT revision FROM ledger … FOR SHARE` (`:77`), with the live-count
  check (`:79-85`) and the cap of 1,000 (`:87-91`).
- The restore locks the personal ledger `FOR UPDATE` and marks it locked (`RestoreService.java:102-105`), checks one
  revision (`:109-111`), plans again (`:112`), writes the BEFORE_RESTORE snapshot (`:116`), takes one revision (`:120`),
  writes, stamps the sync columns (`:221-227`), then one `restore_run` with `before_snapshot_id NOT NULL` (V23 line 17)
  and its items (`:123-138`).
- The planner is set-based over one ledger and the four personal types (`RestorePlanner.java:129-137`, `:183-200`,
  `:528-531`, `:541-546`; `LedgerState.java:22-30`). It refuses entries a family budget posted (`FAMILY_GUARDED`,
  `RestorePlanner.java:313-323`) and never touches derived rows.
- `lockLedger` takes `FOR NO KEY UPDATE` once per transaction (`HistoryService.java:101-112`); the revision fails a
  ledger not locked first when the test guard is on (`:350-353`). Family writers lock the family ledger
  (`FamilyRecordService.java:1970-1978`), the record (`:1981-1992`), then every member's personal ledger by ascending
  id (`CrossLedgerWriter.java:80-92`, called at `FamilyRecordService.java:311`, `:488`, `:771`).
- A family record's content holds `deleted` and `externalRef` and the shares (`RecordContents.java:218-255`); a soft
  delete writes op DELETE with the content kept (`HistoryService.java:229`, `:250`), and the row stays
  (`HistoryJobs.java:148`). Versions carry `actor_member` (the family `ledger_member` id) and cause IMPORT for imports
  (`HistoryService.java:271-279`).
- Posting a record re-reads the live record and members and rewrites every link (`FamilyPostingService.java:267-366`);
  with acceptance on only the actor is posted and his `accepted_revision` moves (`:177-192`). A link that is not
  system-owned and belongs to another member makes the posting throw (`:341-345`, `:637-641`).
- The import recognises a family row by `external_ref` and compares `content_hash` and `imported_version`
  (`ImportSync.java:80-97`); a record deleted in the app stays deleted (`:84-86`); the stamp is
  `FamilyImportSync.java:73-80`; V13 pairs the five import columns (V13 lines 191-209).
- `notification` already accepts the kind `FAMILY_RESTORED` (`Notifications.java:132`); nothing writes it yet.
- No code writes a `BEFORE_IMPORT` snapshot yet (D-1200).

### 15.2 Data model (point 1)

- **D-1300 (manager) A child table, not a JSON column and not one snapshot row per ledger.** V24 adds
  `snapshot_ledger (snapshot_id BIGINT NOT NULL REFERENCES snapshot (id) ON DELETE CASCADE, ledger_id BIGINT NOT NULL
  REFERENCES ledger (id) ON DELETE CASCADE, revision BIGINT NOT NULL CHECK (revision >= 0), member_id BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, ledger_id))`, an index on `(ledger_id)`, and V19's `history_immutable()` as its guard (it
  reads `OLD.ledger_id`, so a delete passes under `delete-all` or when the ledger is gone, V19 lines 61-77). `member_id`
  is the snapshot maker's `ledger_member` id in that budget (no foreign key, as `restore_item`'s versions). Options
  considered: a JSONB map on `snapshot` (no foreign key, so a deleted budget would leave a stale id, and an UPDATE would
  be needed, which the guard refuses); one `snapshot` row per ledger grouped by a book id (every list, count and cap
  query of `SnapshotService` would change, and a family ledger would "own" rows of one person's book). The child table
  is additive and leaves every existing statement as it is.
- **D-1301 (manager) The personal part stays on the `snapshot` row; old rows are not rewritten.** `snapshot.ledger_id`
  and `revision` remain the personal ledger's. `snapshot_ledger` holds only family ledgers. A snapshot with no
  `snapshot_ledger` row (every existing MANUAL and BEFORE_RESTORE row, and any snapshot taken with the family switch off
  or by a member of no budget) is a personal-only book; no backfill, no migration of old rows. A BEFORE_RESTORE
  snapshot of a book restore gets the same family rows as a book snapshot taken at that instant.
- **D-1302 (manager; confirmed by the PM) Cap: 20 family ledgers per snapshot; above it the snapshot is refused** with 409
  `SNAPSHOT_TOO_MANY_BUDGETS` ("You are in more than 20 family budgets, so a snapshot can't cover them all"), never
  truncated: a snapshot that silently left a budget out would restore a book that isn't whole. The 1,000 snapshots per
  personal ledger (D-1195) stays and also counts BEFORE_RESTORE rows, as today (`RestorePlanner.java:195-200`).
- **D-1303 (manager; confirmed by the PM) Erasure and departures.**
  - "Delete all my data": the personal ledger's snapshots go as today; their `snapshot_ledger` rows go by the
    `snapshot_id` cascade under `app.writer = 'delete-all'`. The family `restore_run` rows of the member stay with the
    budget, like the versions they wrote (section 8), and read "Former member".
  - Leaving a budget: the rows stay. A restore of that snapshot skips the budget (no blocker), and the preview lists it
    under `skippedBudgets` with reason `NOT_A_MEMBER` ("You are no longer a member of this family budget; its records
    are left as they are"). If he has returned and is ACTIVE again, the budget is included.
  - A budget deleted: its `snapshot_ledger` rows and its `restore_run` rows go by the `ledger_id` cascades (V23 line 9,
    V24); the snapshot stays as a smaller book, and the preview lists nothing for it.
- **D-1304 (manager) One `restore_run` per ledger, linked to the personal run.** V24 adds `restore_run.parent_run_id
  BIGINT` (no foreign key: the personal run goes with the user's erasure while the family runs stay), drops NOT NULL from
  `before_snapshot_id`, and adds `CHECK (before_snapshot_id IS NOT NULL OR parent_run_id IS NOT NULL)`. The personal run
  is written as in 2b (kind SNAPSHOT or VERSION). A family run has `kind` BOOK (or VERSION), `snapshot_id` and
  `before_snapshot_id` NULL (both point at personal-ledger rows that the user's erasure deletes, so a foreign key there
  would block delete-all), `parent_run_id` = the personal run, `actor_member` = his family member id. Rollback: the image
  before V24 always sets `before_snapshot_id`, never reads `snapshot_ledger` or `parent_run_id`, and treats every
  snapshot as personal (D-1031 holds for it), so it runs unchanged on V24 (D-1040).

### 15.3 Taking a book snapshot consistently (point 2)

- **D-1305 (manager) Lock order and strength.** In one READ COMMITTED transaction: (1) read the list of family ledgers
  where he is ACTIVE with his account, without a lock; (2) `SELECT revision FROM ledger WHERE id = :f FOR SHARE` for
  each, by ascending id, reading each revision after its own lock; (3) the personal ledger `FOR SHARE` as today
  (`SnapshotService.java:77`), its revision after the lock; (4) read the list again, and if it differs (he joined, left
  or was removed meanwhile) answer 409 `SNAPSHOT_RETRY` ("Your family budgets changed while the snapshot was taken; try
  again"); (5) the consistency check per ledger (D-1307), the caps, the inserts, one SNAPSHOT_CREATED event with
  `ledgers` in its details.
- **Why it is consistent.** Every writer that changes a member's personal ledger because of a family budget holds that
  budget's row `FOR NO KEY UPDATE` from its start to its commit (`FamilyRecordService.java:308-311`,
  `FamilyAcceptance.java:63`, `ImportService.java:437-446`, `CrossLedgerWriter.java:80-92`). `FOR SHARE` conflicts with
  `FOR NO KEY UPDATE` and not with itself. Once the snapshot holds every one of his budgets' rows, no family change can
  reach his personal ledger before the snapshot commits; a personal-only save that commits between steps 2 and 3 is
  seen by step 3's read. So the revisions stored are a cut no change set crosses.
- **Why no deadlock (D-1234).** The snapshot takes family rows by ascending id, then the personal row, and nothing else
  with a lock: exactly the D-1234 order with no record row. A family writer of budget F holds F, maybe the record, then
  personal rows ascending; it never waits for a family row the snapshot holds while holding a personal row the snapshot
  wants, because it takes its family row first and only one. Acceptance takes F, then personal rows
  (`FamilyPostingService.java:202-206`). A concurrent book restore by the other member takes family rows ascending,
  then records, then personal rows (D-1316): both sides go up the same order. The one writer outside the order is
  `UserDataService.deleteAll` (personal row before the family rows, the open follow-up of 2c-1): a snapshot by the same
  user at the same instant as his own delete-all can end in 40P01 for one of them, which is retried by nobody and
  answers 500. Accepted until that follow-up is fixed; part 15.10/a fixes it first (D-1334).
- **D-1306 (manager) FOR SHARE is enough (D-1196 kept).** The snapshot writes no row of the ledgers it locks, takes no
  revision and must not block other snapshots or readers; `FOR SHARE` waits for every change set in progress and keeps
  new ones out until it commits, which is all the cut needs. `FOR NO KEY UPDATE` would serialize snapshots of two
  members of one budget for no gain.
- **D-1307 (manager) The consistency check per family ledger** compares the number of `family_record` rows (deleted ones
  too, since a soft delete keeps the row, `HistoryJobs.java:148`) with the number of records that have a version at or
  before the revision, and the live family categories with the state's FAMILY_CATEGORY count. A mismatch refuses with
  `HISTORY_INCONSISTENT` as for the personal part (`SnapshotService.java:80-85`).

### 15.4 The plan (point 3)

Per family ledger F in the snapshot (ACTIVE now), with M = the snapshot's revision of F, H = F's head and me = his
member id in F:

- **D-1308 (manager; confirmed by the PM) Who changed a record.** One statement per F: `SELECT record_type, record_id, bool_and(actor_member
  = :me) AS mine FROM record_version WHERE ledger_id = :f AND revision > :m AND record_type IN ('FAMILY_RECORD',
  'FAMILY_CATEGORY') GROUP BY 1, 2`. A record is his when every such version has `actor_member = me`, whatever the
  cause (EDIT, IMPORT, RESTORE). A version with `actor_member` NULL (a BASELINE or RECONCILE, SYSTEM) counts as someone
  else's: nobody can tell whose change it was. A record not his is KEPT_OTHER_MEMBER and is not written.
- **D-1309 (manager) Outcomes of a family record** (live = the row with `deleted_at IS NULL`; target = its latest version
  at or before M):
  - RESTORED: target is a live version, the record is live now, the hashes differ;
  - RETURNED: target is a live version, the record is soft-deleted now (undeleted, `deleted_at` cleared);
  - DELETED: target is a DELETE (it was deleted at M), the record is live now (soft-deleted again);
  - ARCHIVED: no target (created after M, by him, an import of his included) or the target is an ARCHIVE: soft-deleted,
    and its import keys freed (D-1324);
  - UNCHANGED: as now, or deleted both then and now;
  - KEPT_OTHER_MEMBER: anyone else changed it after M (D-1291);
  - each item of RESTORED and RETURNED carries `recalculated: true` when the shares under today's rules differ from the
    target's shares (D-1292), so the preview can say "shares recalculated for today's members".
- **D-1310 (manager) Family categories follow the same rule.** A FAMILY_CATEGORY changed after M only by him is
  RESTORED, RETURNED or removed (ARCHIVED/DELETED) as personal reference records are; a removal is KEPT when a family
  record after the plan (KEPT_OTHER_MEMBER ones included) or any member's posting uses it (rule 12; one statement over
  `posting.category_id` and `family_record.category_id`). A category anyone else touched is KEPT_OTHER_MEMBER. Reason:
  a restored record may need its category back, and treating categories differently from records would make "every
  change after the snapshot was mine" mean two things.
- **D-1311 (manager) Settlements** are records like the others under D-1308 and D-1309. A settlement whose other side
  has been put on an account of the other member's (a link that is not system-owned) is a blocker when the plan would
  change or remove it: the posting would throw (`FamilyPostingService.java:341-345`) and `requireNotPlaced` refuses it
  today (`FamilyRecordService.java:1029-1040`).
- **D-1312 (manager; confirmed by the PM) The payer's own side is not history.** The payment account, note and payee of the payer (D-16) are
  in his derived payment entry, not in the version, so no version records a later move of them and D-1291 does not see
  it. RESTORED keeps the payment as it is now (`Unchanged`); RETURNED and a payer whose link is gone post the payment
  as "paid later" (`Later`, the UNSPECIFIED_PAYMENTS account, as `join` does at `FamilyPostingService.java:247-248`), and
  the preview counts them in `paymentLater` with the text "the payment goes to 'paid later'; choose the account again".
  The payment account is not added to family record versions (D-16, rule 11). A link of another member that is not system-owned is the blocker of D-1311.
- **D-1313 (manager) The stale check covers every ledger.** The preview answers `atRevisions` (ledger id to revision,
  the personal one included); the restore request sends it back (`{"atRevisions": {...}}`; a personal-only snapshot may
  still send `atRevision`). After the locks, any ledger whose revision differs gives 409 `RESTORE_STALE` with the same
  text as D-1227; a ledger present in one and not the other (joined, left) is stale too.
- **D-1314 (manager) Blockers** (422, nothing written, the 2b envelope; record type and id set where one is named):

  | code | when | message |
  |---|---|---|
  | `FAMILY_PENDING_CHANGES` | acceptance on and he has pending changes in F (`FamilyAcceptance.java:137-145`) | "Accept the n pending changes in '<budget>' first" |
  | `FAMILY_JOINED_AFTER` | a restored record dated before a member's join date gives that member a share or a side | "family record N is dated before <member> joined, so it can't be restored as it was" |
  | `FAMILY_MEMBER_NOT_ACTIVE` | the payer, the payee or a member with a share in the target is no longer ACTIVE (left, removed, FORMER) | "family record N names <member>, who is no longer in the family budget" |
  | `FAMILY_RECORD_FROZEN` | the record is frozen now (`FamilyRecordService.java:1922-1934`) and the plan changes it | "family record N is frozen: a member it involves has left the family budget" |
  | `FAMILY_CATEGORY_MISSING` | the target's category is deleted or archived after the plan | "family record N uses category C, which is missing or archived; restore that category first" |
  | `FAMILY_SIDE_PLACED` | D-1311, or another member's side of an expense on his own account | "<member> has put their side of family record N on an account of theirs; only they can change it" |
  | `FAMILY_INVALID_TARGET` | any other refusal of the record's validators (split, amounts, currency), with the validator's own text | the validator's message, prefixed "family record N: " |
  | `KEY_COLLISION` | a RESTORED or RETURNED record's `external_ref` is held by another record now (an import created it again) | as 2b's text, "family record N has the same unique key as family record K" |
  | `HISTORY_INCONSISTENT` | D-1307 fails for F | "The history of '<budget>' doesn't match it yet; try again later" |
  | `SNAPSHOT_LIMIT_REACHED`, `REFERENCE_MISSING`, `INVALID_TARGET`, `DERIVED_RECORD`, `FAMILY_GUARDED` | as today, personal part | unchanged |

  No start-date blocker: a budget's start date never changes (V7's `ledger_fill_start_date`, V7 lines 19-37), so a
  target dated on or after it then still is.
  A personal entry that uses a family category is checked against the family part's state after the plan, so a family
  category the plan removes is a `REFERENCE_MISSING` there.
- **D-1315 (manager; confirmed by the PM) Who may restore.** Any ACTIVE member with an account restores his own book; no owner rule, because
  D-1291 already limits the restore to his own changes. D-1015/D-1030 (owners only) stay for HIST-5's whole-budget
  restore.

### 15.5 The write (point 4)

- **D-1316 (manager) One transaction, one order.** READ COMMITTED: (1) `lockLedger` of every family ledger in the plan,
  ascending; (2) for each F, the candidate records `FOR UPDATE` by ascending id (one statement per F); (3) the personal
  ledgers of all members of those budgets by ascending id (`CrossLedgerWriter.lockLedgers`, which also covers his own);
  (4) the stale check (D-1313); (5) the plan again under the locks; blockers give 422; (6) the BEFORE_RESTORE book
  snapshot at the current revisions (D-1318); (7) the family writes per F, then the personal writes as 2b; (8) stamps,
  runs, items, events, notifications. The personal ledger's row is taken with `lockLedger` (`FOR NO KEY UPDATE`), not
  the 2b `FOR UPDATE`: every writer takes `FOR NO KEY UPDATE` first and it conflicts with itself and with `FOR SHARE`, so
  it excludes as much; `markLocked` is then no longer needed by the restore (kept for any other caller).
- **D-1317 (manager) Records go through the family record code.** New package-private methods of
  `FamilyRecordService`: `restoreRecord(family, recordId, target)` and `removeForRestore(family, recordId, archive)`.
  `restoreRecord` runs the checks of `updateRecord` (date, category, payer, payee, amounts, currency, split,
  JOINED_AFTER, frozen) on the target's family fields, computes the shares from the target's split method
  and basis with the current members (`ShareSplit`, as an edit), writes the row and shares, writes the version through
  `HistoryService.restored` (op RESTORE, `restored_from` = target version, cause RESTORE), stamps `last_revision`
  (`FamilyRecordService.java:2132-2139`), writes the import columns as D-1323 says (the five together, never some of
  them: V13 lines 203-204) and calls `FamilyPostingService.post(family, recordId, payment)` with D-1312's
  payment. The permission checks of `updateRecord` (author, payer, owner) are replaced by D-1308. The planner calls the
  same check code (no write) so that preview and apply refuse the same records; a refusal at apply that the plan missed
  rolls everything back (500 is a bug, the tests assert none).
  `removeForRestore` sets `deleted_at` as `delete` does (`:506-512`) and posts with `Unchanged`, so every member's
  derived entries go; with `archive` it also clears the five import columns (D-1324) and writes op ARCHIVE whose content
  is the live record after the write (deleted, no ref), so the integrity check, the baseline job and the lazy drift rule
  need no family exception (`HistoryJobs.java:157-170`, `HistoryService.java:224-241`). A DELETED one writes op DELETE.
  Family categories are written as personal reference records in 2b (`RestoreService.java:186-203`), against `category`
  rows with `user_id IS NULL`.
- **D-1318 (manager) BEFORE_RESTORE covers the whole book.** `SnapshotService.createBeforeRestore` gets the map of
  revisions and writes the `snapshot` row and one `snapshot_ledger` row per family ledger of the restore (the ledgers he
  is ACTIVE in now; none for a budget he left), at the revisions read under
  the locks, before any revision is taken. No `FOR SHARE`: the restore holds stronger locks.
- **D-1319 (manager; confirmed by the PM) Revisions.** One revision per ledger written, taken once per transaction (`HistoryService.revision`
  remembers it per ledger, `:361-367`); a family ledger in the plan with nothing to write takes none (unlike D-1228 for
  the personal ledger): such a budget gets no revision and nothing becomes pending for its other members. The personal ledger always takes
  its revision (D-1228 kept).
- **D-1320 (manager) Runs, events and notifications.** The personal `restore_run` as 2b, its `counts` holding the
  personal outcomes and a `family` map per ledger; one family `restore_run` per F written (D-1304) with its
  `restore_item` rows (FAMILY_RECORD and FAMILY_CATEGORY, written outcomes only; KEPT_OTHER_MEMBER is counted in
  `counts`, not itemised, as KEPT in 2b). One `RESTORE` activity event without ledger id (2b) plus one per F with
  `ledger_id` = F (so the budget's Activity shows "X restored their records: n records changed"). One FAMILY_RESTORED
  notification per other ACTIVE member with an account of F, range R..R, `changes_count` = the written records,
  never to the restorer (D-1108); text "{name} restored their records in the family budget" (one row of
  `NotificationText`).
- **D-1321 (manager) Acceptance (D-1295).** Off: `post` posts to every member at once (`only` null). On: the restorer must
  be caught up (blocker D-1314); `post` posts only to him and moves his `accepted_revision`
  (`FamilyPostingService.java:177-192`); the others' records are pending by `last_revision` (D-1159) and their Accept
  re-posts them from the live record, so nothing new is needed on their side. FAMILY_RESTORED is marked read by their
  Accept (D-1167, `Notifications.java:128-133`).

### 15.6 Undo (point 5)

- **D-1322 (manager) Undo is a restore of the BEFORE_RESTORE book snapshot, under the same rules.** The restore's own
  versions carry `actor_member` = the restorer, so a record nobody else touched since comes back to its state before the
  restore. A record another member changed between the restore and the undo is KEPT_OTHER_MEMBER: it keeps the other
  member's state, which was made on top of the restored one, and the undo preview lists it with that member's name and
  the text "changed by <member> after your restore; left as it is". Nothing forces the undo to be complete: "nothing is
  lost" still holds (all versions stay, D-993), and the restorer can restore that record's earlier version himself if
  D-1291 allows it at that time.

### 15.7 Import sync for family records (point 6)

- **D-1323 (manager) The marker is the 2b marker, in `family_record.content_hash`.** After the writes of F, in the same
  transaction and with no version (D-1231's rule), for every RESTORED or RETURNED record. V13's CHECK
  (V13 lines 203-204) wants `external_ref`, `content_hash`, `imported_version`, `imported_by_member_id` and
  `imported_at` all NULL or all set, so `restoreRecord` always writes the five together:
  - the target has no `external_ref`: all five NULL;
  - the target has an `external_ref` and the live row holds its import columns (a RESTORED or RETURNED record that was
    never archived): `imported_version = version` (read after all the writes), the other four kept, so its stored
    hash stays the one of the last import;
  - the target has an `external_ref` and the live row's import columns are NULL (the record was ARCHIVED, D-1324, and
    is now RETURNED, restored after a `KEY_COLLISION` was cleared, or brought back by an undo, D-1322): all five set,
    `external_ref` = the target's, `content_hash = RESTORED_IMPORT_HASH`, `imported_version = version`,
    `imported_by_member_id` = the restorer's member id in F, `imported_at = now()`.
  The importer then sees the record as untouched since its import (`version = imported_version`): a differing file
  row whose hash is the stored one is REPLANNED (`ImportSync.java:91-92`), a differing plan with another hash is EDITED (`ImportSync.java:94`),
  and both are written over it; with an equal plan it is UNCHANGED for the stored hash or RESTAMP for another (`:89`).
- **D-1324 (manager) An ARCHIVED family record frees its import keys.** A family record is soft-deleted, and a deleted
  one stays deleted for every import (`ImportSync.java:84-86`), so a plain soft delete would make "undo an import, fix the
  file, import again" fail. `removeForRestore(…, archive = true)` therefore sets `external_ref`, `content_hash`,
  `imported_version`, `imported_by_member_id` and `imported_at` to NULL in the same UPDATE as `deleted_at`. The next
  import finds no record with the ref and creates a new one (NEW), as D-1038 does for a personal entry; the earlier
  versions keep the ref, so restoring the archived record later meets `KEY_COLLISION` if the import made it again.
  A DELETED outcome (deleted at M) keeps its keys: it was a deletion in the app then, and stays one.
- **Round trip.** import (creates r, his IMPORT version) → book snapshot S → import of an edited file (EDITED, his IMPORT
  version) → restore S (r is his only: RESTORED; it keeps the edited file's hash, `imported_version = version`) →
  re-import of the same edited file: REPLANNED (`ImportSync.java:91-92`), the file's version is written; re-import of
  the first file: RESTAMP (`:88-89`). An import of a row created after S: ARCHIVED by
  the restore, NEW at the re-import. If the other member edited r between S and the restore: KEPT_OTHER_MEMBER, and the
  re-import's result is as without the restore. The same holds for a partner's place whose rows he imports (his
  actor, D-1308).

### 15.8 Performance (point 7)

- **D-1325 (manager) The planner's statements are a fixed number per ledger, never per record.** Personal part: 2b's
  fixed set (about 15). Per family ledger, about 10: the head; the D-1307 counts (two); D-1308's grouping; the targets
  (`DISTINCT ON` at or before M over the candidates, one statement); the live records with shares (two, set-based, as
  `RecordContents.loadAll` does for entries); members; categories and their uses (two); links not system-owned of the
  candidates (one); pending (one, when acceptance is on). Total about 15 + 10L for L budgets. The rule checks of D-1317
  run in memory over what was loaded.
- **The write** is per record, because posting is: for a budget with m members with an account, each written record
  costs about 8 statements (row, shares, version reads and insert, last_revision) plus about 4 + 3m for `post` (record,
  members, shares, links, then per member a compare and at most a replace), and each F about 3 + 3·m·c for `openings`
  (c currencies) plus run, items (one multi-row insert), event and notification. With w_F written records:
  about 15 + 10L + Σ_F (10 + 3·m_F·c_F + w_F·(12 + 3·m_F)) statements, plus 2b's personal part. A month of the rehearsal
  book (about 190 family records, m = 2) is about 3,500 statements.
- **Locks held.** The family rows are held for the whole restore, so the other member's family saves wait for it; that
  is the time to measure.
- **What to time in the rehearsal** (on the restored production dump, median of 3, as D-1041): the book snapshot's
  duration; the preview of a book snapshot one month back and twelve months back (statement count and wall time, with
  the plans of the new statements, no sequential scan on `record_version`); the restore of the one-month snapshot (wall
  time, statement count, time the family ledger row was held); its undo; the import round trip of 15.7 with the
  December file; the Entries timings of PERF-1 after the restore (unchanged plans); the integrity report after each step.

### 15.9 The test oracle (point 8)

- **D-1326 (manager) After a book restore of snapshot S:**
  1. the personal ledger equals `LedgerState.at(personal, N)` for every record except KEPT (2b's oracle,
     `RestoreStateRandomTests`);
  2. for each family ledger F: every record not KEPT_OTHER_MEMBER equals its state at M in its family fields (type,
     date, category, payer, payee, amount, currency, split method and basis, comment, deleted, external ref), with the
     shares recomputed for today's members; a record created after M by him is soft-deleted with no import keys; every
     KEPT_OTHER_MEMBER record is byte for byte as before the restore;
  3. every member's derived entries (FAMILY_* entries, links, openings) equal what posting every live record of F now
     gives: the test re-posts F into a fresh copy and compares, or, equivalently, the existing `FamilyInvariants` and
     D-10 checks pass for every member who is caught up (acceptance on: after each one's Accept);
  4. `/api/reports/integrity` passes for the personal ledger and every F;
  5. undo (D-1322) with no change in between gives back the state before the restore in every ledger (records and
     derived entries), except that the versions and runs of both restores remain.
  `LedgerState` gets a family variant (`at(ledgerId, revision, types)` with FAMILY_RECORD and FAMILY_CATEGORY, keeping
  the soft-deleted records with their `deleted` flag) for 2.

### 15.10 Implementation parts (point 9)

Each part is one /team task. Tiers as README "Backend tests": targeted runs by the developer; the manager runs quick,
and full where the part adds a migration or new random code (D-1204, D-1206).

- **D-1327 (manager) a. Migration and the book snapshot.** V24 (D-1300, D-1304); `SnapshotService.create` takes the
  book (D-1305 to D-1307), `get` answers the counts per ledger; `createBeforeRestore` takes the map (D-1318);
  `LedgerState` family variant; `UserDataService.deleteAll` lock order fixed (D-1334). The family route of
  `SnapshotController` stays 409 (D-1296 is for book snapshots, which are taken on the personal route). Tests: extend
  `SnapshotApiTests`; new `BookSnapshotApiTests` (rows written, cap, retry on a membership change, a snapshot held at its
  locks while a family record is saved, accepted and restored by the other member, each with no 40P01, as
  `RestoreApplyApiTests` does for 2c-1); `SnapshotStateRandomTests` extended with a family member; deploy check
  `HIST-4.sql` for V24. Tier: targeted, then full (migration). Reuses `SnapshotService`, `lockLedger`; `markLocked`
  unchanged.
- **D-1328 (manager) b. The planner.** `RestorePlanner.ofSnapshot` reads `snapshot_ledger` and calls a new
  `FamilyRestorePlanner` per F (D-1308 to D-1314, D-1325); the check code shared with `FamilyRecordService`
  (D-1317, a package-level validator moved out of it, no behaviour change). Tests: extend `RestorePreviewApiTests`; new
  `FamilyRestorePreviewApiTests` (each outcome, each blocker code and text, statement count independent of record count
  by a statement-counting datasource). Tier: targeted, then quick.
- **D-1329 (manager) c. The write.** `RestoreService` book path (D-1316 to D-1321): `lockLedger` replaces its `FOR
  UPDATE`; `FamilyRecordService.restoreRecord` and `removeForRestore`; `FamilyPostingService.post` reused unchanged;
  `Notifications.familyRestored`; `NotificationText` row. Tests: new `FamilyRestoreApplyApiTests` with acceptance off and
  on (`AcceptingMode`), every member's books, notifications and runs; extend `RestoreApplyApiTests` (concurrency with the
  other member's save, accept and restore; step hook in the family writes); `FamilyAcceptApiTests` (accepting a
  restore). Tier: targeted, then quick.
- **D-1330 (manager; confirmed by the PM) d. Version restore and archived marks for FAMILY_RECORD (D-1294, D-1236 to D-1238).**
  `RestorePlanner.ofVersion` accepts FAMILY_RECORD: D-1291 applies to the versions after the chosen one; a record
  someone else changed is refused with `FAMILY_CHANGED_BY_OTHER` ("family record N was changed by <member> after this
  version; it can't be restored") rather than a plan with nothing to do; `GET /api/history/archived` lists ARCHIVED
  family records of the budgets he is ACTIVE in. Tests: new `FamilyVersionRestoreApiTests`; extend `HistoryReadApiTests`.
  Tier: targeted, then quick.
- **D-1331 (manager) e. Import round trip.** D-1323, D-1324 and 15.7's sequence. Tests: new `FamilyRestoreImportApiTests`
  (the round trip with his file, expecting REPLANNED on the re-import of the edited file and RESTAMP on the first
  file; with a partner's place; with the other member's edit in between; an archived imported record that a restore
  brings back and its undo, both checking the five import columns set or NULL together, no 23514); extend
  `FamilyImportSyncApiTests` and `AcceptImportApiTests`. Tier: targeted, then quick.
- **D-1332 (manager) f. Random tests.** New `BookRestoreStateRandomTests` (two members, random personal and family
  changes by both, imports, snapshots, restores and undos, the oracle of D-1326 after each, acceptance off and on), and a
  restore step added to `FamilyRecordRandomTests`' operation mix. Tier: targeted at `-Dtests.random.scale=0.1`, then
  full (new random code).
- **D-1333 (manager) Frontend and e2e are a separate part** after a to f (the preview's KEPT_OTHER_MEMBER list,
  skipped budgets, "paid later" count; FAMILY_RESTORED rows; one e2e spec for two accounts under `E2E_FAMILY=on`).
- **D-1334 (manager) `UserDataService.deleteAll` takes the family rows by ascending id before the personal row**, the
  2c-1 follow-up, in part a, because book snapshots make the reverse order meet more often.
