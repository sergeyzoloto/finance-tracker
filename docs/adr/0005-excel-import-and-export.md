# ADR 0005: Import and export of the Excel ledger

**Status:** Accepted retroactively in DOC-1 (2026-11-14, D-722 of
[the requirements](../family-budget/requirements.md)). An as-built record of the decisions taken in D3b to D4c
(2026-10-12 to 2026-11-07), deployed in production since D4c (`df402d0`, 2026-11-07); the decisions themselves are in
the requirements, by number. Nothing here is new.

## Context

The owner kept his finances in an Excel workbook from 2017, a double-entry ledger
([ADR 0001](0001-double-entry-ledger.md)); his partner keeps one of the same layout. Both go on keeping them: every
month each exports his workbook and imports it into the app (the runbook's
[step 12](../../deploy/RUNBOOK.md#12-monthly-import-two-participants)). So the import is not a one-off migration but a
repeated sync of a growing file into a ledger the app also changes, with a family budget whose records both members'
files touch ([ADR 0003](0003-family-budget-membership-and-cross-ledger-posting.md),
[ADR 0004](0004-multi-currency-family-ledger.md)).

What has to hold:

- A row imported once is recognised again, whatever else changed, and is never imported twice.
- An edit in Excel reaches the app; an edit in the app is never silently overwritten.
- Nothing is deleted because a row is gone from a file.
- Each import proves that the ledger moves as the file does, or saves nothing.
- A user can take his data out in the same format and bring it back without changes.
- No private data enters the repository: the real exports stay in the git-ignored `data/private/`, and the tests use
  synthetic files (`backend/src/test/resources/import/`).

## Decision

### The file

- **One transactions file per user** (D4b; D-227 to D-234): CSV with semicolons and the workbook's 32 columns in their
  order (D-235): 15 typed ones (ID, Date, Exp. Item, Sum, Currency, Counteragent, Comment, Debit, Credit, Family, the
  two sides' currency and sum, LOAN_CA) and 17 that Excel computes from them. The accounts, categories and daily
  balance files that D3b read are no input any more (D-228, D-232); the five-file import is gone (D-234). Any other
  header refuses the file, naming the columns (`Workbook`).
- **Dates** are `dd.MM.yyyy`, decimals with a point, values as stored (D-240, `ExcelValues`).
- **Accounts and categories come from the rows** (D-228): the code from the code columns, the name from the name
  columns, the type from the type columns. A code the app lacks is created and listed; a code with two names or two
  types in the file, a name of two codes, or a code the app has with another type refuses the whole file before any
  write (D-229, D-262, D-270). A personal account's or category's name follows the file at every import; a family
  category keeps its name (D-254).
- **IDs are permanent** (D-86): never renumbered, and a deleted row's number never reused.

### Identity

- A personal entry is `external_ref = 'xls:<ID>'` in the importer's personal ledger (D-153).
- A family record is `xls:<seat member id>:<ID>`, keyed by the family budget, the seat whose file the row comes from
  and the Excel ID, not by who uploads it (D-142), so that after a claim the member's own import takes over his rows
  without duplicates. A conversion's two settlements add `:<currency>` (D-153).
- The reference is unique per ledger, deleted records included (V13, V14).

### Sync

- **Stored with each written row:** a content hash of the row and the version the import left (`import_hash` and
  `import_version` on `journal_entry`, V14; `content_hash` and `imported_version` on `family_record`, V13, D-113).
- **On each import, for each row an earlier import wrote** (D-219): first what the current code would write is compared
  with what the ledger holds. Equal is unchanged (re-stamped if only the hash differs). Not equal while the file row is
  as last imported: if the app changed it since (its version isn't the imported one), it is "changed in the app, the
  file unchanged", listed and left alone; if not, it is re-planned and written over. The file row changed: written
  over if the app didn't change it, a conflict if it did (D-139). Writes go through `EntryService` and
  `FamilyRecordService`, so they are validated, journaled and re-posted as the app's own changes are.
- **A member's own side** of a family record (payment, receipt or settlement side) has its own hash in his ledger
  (`imported_family_side`, V15, D-181) and is compared the same way, for the partner since D3c and for the owner since
  D4c (D-337, D-339, D-340).
- **Gone from the file:** reported with its IDs, never deleted (D-138); only within the file's period, its first to its
  last row date (D-256).
- **Deleted in the app:** stays deleted. V14's `imported_entry_deleted` keeps the reference of a personal entry the
  import wrote and the app deleted, and the next import lists the row as a conflict instead of writing it again
  (D-150); a deleted family record keeps its reference (D-113). "Delete all my data" removes these with the ledger.
- **Rows the app made** are exported as `app-<entry id>` and `app-f<record id>` (D-244). The import never writes them:
  one unchanged is nothing to do, one changed or naming nothing of the user's is listed (D-259, D-266).

### Dry run, then commit

- An import is a dry run by default: it writes everything in one transaction, reports, and rolls back. The page offers
  Commit only for exactly the files, budget and date its dry run read, and once committed not again until one of them
  changes (D-221; `frontend/src/Import.tsx`).
- A commit is one transaction with a savepoint per row, so a row the database refuses doesn't stop the others; it
  saves nothing if any row has an error.
- **"Import up to"** (D-165): a date on the page, `--up-to` on the command line. Rows of either file dated after it are
  neither imported nor reported missing, and the check stops there. A later import with a later date adds the newer
  rows through the usual sync.

### The check against the file

- **What is checked** (D-231, D-255, D-256, D-263, D-264, `Reconciler`): per row, the computed columns against what
  Excel's rules compute from the typed ones and what the import builds against them; per month-end from the file's
  first row date to its last (at most "Import up to"), each account's movement in the ledger against the file's,
  with `FAMILY_DEBT`, `UNALLOCATED` and `RESERVE` as one sum per currency. A difference is explained only by a named
  cause (minor-unit rounding, four decimals, a change made in the app, a settlement's date, a value the CSV shows
  rounded, or what no row stands for, D-223, D-265); anything else fails the check.
- **No commit while it fails** (D4c, D-336, D-343): a commit whose check fails saves nothing, outcome `ABORTED`, with
  `refusedFor` `CHECK_FAILED` (and `ROW_ERRORS` for row errors), on every path, the command line included. There is no
  override: the user fixes the file or the app, exports again and checks again. Since every difference fails the check,
  a row gone from the file, a row deleted in the app, a conflict and an app row changed in the file now refuse a commit
  too (D-345).
- Another member's records cancel out in the pooled sum, so neither member's check waits for the other's import
  (D-258). V15's per-seat cutoffs (`family_import_cutoff`, D-175) are neither read nor written since D4b (D-267).

### The family budget's rows

- **Before a claim** the owner's import also takes the partner's family rows for the partner's seat, a member without
  an account. The page filters the partner's file in the browser and uploads only the family rows' seven columns
  (D-140, D-151); the server refuses any other column or row (422).
- **After the claim** each member imports only his own file (D-175): his personal rows into his ledger, and his side
  attached to the records his rows stand for. His settlement rows never create settlements; each is matched to an
  existing one (D-178). The claim gives him authorship of the records from his seat's file (D-176).

### Export

- `GET /api/export/transactions` and `/api/export/summary` (D4a, D-250): the user's own rows, personal entries and the
  family records he paid or is a side of, never another member's (D-239), in the import's format: UTF-8 with a byte
  order mark, CRLF, the 32 columns, the computed ones computed by Excel's rules (D-237, D-240, `ExcelRow`,
  `TransactionsCsv`). The whole period or from one date to another, named `Transactions_<from>_<to>.csv` (D-241).
- Each row is written in the form the ledger holds it; where two forms are possible, the one whose hash the import
  stored (D-245, D-247). Entries of another shape are skipped and counted by kind (D-248).
- **Self-check** (D-242, D-249): per account and currency, the balance at the end less the balance before the start
  equals what the exported rows move plus what other members' records and the skipped entries move.
- **Round trip** (D-238, D-256, D-259): an export imported back into the same account creates, updates and conflicts
  nothing, and its check passes. `ExportApiTests` and `DemoRoundTripApiTests` test it, and the end-to-end suite does it
  on the demo and in production.

### Where it runs

The production imports run in the browser, on Settings → Import (`/settings/import`), as the signed-in user, who makes
sure the browser is signed in as himself (D-173). `ImportRunner` (Spring profile `import`) is the command line, for a
throwaway database only. Before a monthly import in production, the step is rehearsed on a production dump restored
locally (`scripts/rehearsal-db.sh`, D-214, D-220, D-225). The procedure is the runbook's [step
12](../../deploy/RUNBOOK.md#12-monthly-import-two-participants), not repeated here.

## Consequences

- The workbooks stay the owners' source of truth for their own rows, and the app can still be used alongside: an edit
  in the app survives the next import, listed, until the file agrees or the user resolves it.
- An import commits only a ledger that matches the file, which makes each month's import an audit of both. The price
  is that any difference, also one the app made on purpose, blocks the commit until the file or the app is fixed.
- Nothing is deleted by an import. A row removed in Excel has to be deleted in the app by hand, as its report says.
- The importer needs four database objects of its own: V13's sync columns on `family_record`, V14's on `journal_entry`
  and `imported_entry_deleted`, and V15's `imported_family_side` (V15's `family_import_cutoff` stays, unused).
- A whole history imports in one request and one transaction; the import refreshes the planner's statistics every
  `ANALYZE_EVERY` writes to keep that fast (`ImportService`). Caddy and the api set no timeout that ends it (D-160).
- The export lets a user leave with his data in the format he came with.

## Rejected alternatives

Only those the requirements record:

- **Recognising a row by its hash alone** (D3b's first rule): replaced by comparing what the code would write with what
  the ledger holds (D-219), so that a change in the importer's mapping re-plans rows instead of reporting conflicts.
- **The five-file import** with the accounts, categories and daily balance files (D3b): replaced by one transactions
  file (D-232, D-234), with no mode beside it, once a rehearsal showed the same differences.
- **Names and order kept in the app** (D-230): superseded by D-254, names from the file, no order column.
- **The per-account `FAMILY_DEBT` check and the owner's half of the partner's incomes** (D-169): dropped for the pooled
  sum (D-255), which also removed the members' waiting for each other (D-258).
- **A commit despite a failing check** (D4b's rule, D-271): refused since D4c with no override (D-336).
- **The claim as a background job** (D-182 allowed one if needed): it stays one request in one transaction.
- **Replaying every production import before a rehearsal** (D-191): replaced by restoring production's latest dump
  (D-220).
