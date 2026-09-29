# ADR 0003: Family budget: membership-based access and cross-ledger posting

**Status:** Accepted, 2026-09-29, after the F1 review (proposed 2026-09-28 in stage F1). The owner's
answers to the open questions are under [Resolved questions](#resolved-questions), and the topics
below include them. F2a's migration V5 (topic J) is implemented on the branch
`feature/family-budget`, not yet deployed. The requirements and decisions D-1 to
D-26 are in [docs/family-budget/requirements.md](../family-budget/requirements.md);
what the code does today is in [docs/family-budget/current-state.md](../family-budget/current-state.md).

## Context

The app keeps one double-entry ledger per user (ADR 0001). Every owned row carries `user_id`, the
Keycloak `sub`, every query filters by it, and the database's triggers reject a posting that refers to
another user's account, category or counterparty. The ledger itself has no row and no id: there is
no `ledgers` table, although the task assumed one.

The family budget (D-1) is a settlement mechanism between the members' personal ledgers. Members
record family expenses and incomes, the shares are posted into each member's personal ledger, and
each member's "Debt to family budget" liability carries the difference. That needs three things the
code doesn't have:

- a ledger as a thing of its own, with members, so that access follows membership rather than
  `user_id` (D-2, D-3, D-4);
- one code path that may write into another user's personal ledger, and only the rows D-8 lists;
- family data that several users read and some of them change, with a journal of who changed what
  (D-14, D-16).

Production runs one user (the owner), with 10 accounts, 15 categories and no entries on 2026-09-28.
The Excel import is postponed until family posting ships (D-21). The next migration, V5, is the first
that transforms rows that exist in production.

### Sign convention in this ADR

Postings follow rule 2: debit positive, credit negative. The "Debt to family budget" account is a
LIABILITY, so its displayed balance is minus the sum of its postings (rule 4). D-7's figures for
"debt to family" are displayed balances. A member's **family balance** B(m) is what member m owes the
family: positive means m owes, negative means the family owes m. It equals the displayed balance of
m's personal "Debt to family budget" account (D-10).

D-7's expense example as postings (payer A pays 100 by card, 50/50 with B):

| Ledger | Entry | Postings | Displayed debt afterwards |
| --- | --- | --- | --- |
| A's | payment, A's own entry, linked to the record | card −100; Debt(family) +100 | −100 |
| A's | A's share, posted | UNALLOCATED +50 with the family category; Debt(family) −50 | −50 |
| B's | B's share, posted | UNALLOCATED +50 with the family category; Debt(family) −50 | +50 |

The income example: the recipient's receipt is card +1000, Debt −1000; each share is UNALLOCATED
−500 with the income category, Debt +500. The recipient ends at +500 and the partner at −500, as D-7
says.

## Decision

Twelve topics, A to L. Each gives the options considered, the recommendation, its consequences, and
the decisions it satisfies.

### A. Ledger scoping of existing rows

**Options.**

1. **`ledger_id` on every ledger-scoped table**, backfilled from the owner's personal ledger, with
   `user_id` kept during a transition and dropped later.
2. **Derive the ledger from `user_id`**: a personal ledger is its user, and a family ledger is a
   pseudo-owner stored in `user_id` (say `ledger:<uuid>`). No new column; every query stays as it
   is.
3. **`ledger_id` only on the new family tables**, with personal rows scoped by `user_id` as today.

**Recommendation: option 1.** The tables `account`, `category`, `counterparty`, `journal_entry` and
`import_batch` get `ledger_id BIGINT NOT NULL REFERENCES ledger`. `posting` stays without an owner
column: it belongs to its entry. Two tables stay per user, because they are about the person, not a
ledger: `user_settings` (the personal ledger's base currency and the legacy shared-expense settings,
1:1 with the personal ledger) and `exchange_rate` (manual rates are the user's own, ADR 0002). A
family ledger keeps its own settings on its `ledger` row.

**The transition.** Both columns exist from F2 until a cleanup migration after F7.

- V5 creates a personal ledger for every sub found in any owned table, and fills `ledger_id` from it.
- A `BEFORE INSERT` trigger fills a missing `ledger_id` from `user_id`'s personal ledger, creating
  the ledger and its member if the sub has none. So the code before F2, which doesn't know the
  column, keeps working against the V5 schema: that is what makes a rollback to the previous image
  safe (D-22).
- A consistency trigger checks, on insert and on update, that a row of a PERSONAL ledger has the
  `user_id` of that ledger's member, and that a row of a SHARED ledger has `user_id` NULL (from F3;
  `category.user_id` then becomes nullable, which relaxes a constraint and drops nothing).
- The cleanup migration, once no image older than F2b can run, drops `user_id` from those five
  tables, the composite foreign keys and unique constraints built on it, and the two transition
  triggers. `users`, `user_settings` and `exchange_rate` keep theirs.

**Consequences.**

- One scoping rule for every query, personal or family: `ledger_id = :ledgerId`, with the ledger id
  coming only from a membership check (topic C).
- Ledger ids appear in URLs of family pages. They are sequence numbers, not subs, so no sub leaks
  into a URL or a family row (D-3).
- About 120 lines of SQL of transition machinery, which the cleanup removes.
- Option 2 would put the personal ledger's id, the sub itself, into URLs and family rows, which D-3
  forbids, and it mixes identities with containers in one column that rule 11 defines as the sub.
- Option 3 is two access models, which D-2 rules out. A family category on a personal posting (D-11)
  would still need a rule across the two.

**Satisfies** D-2 (access by membership, one model), D-3 (no sub in family rows), D-22 (additive,
rollback by image), and leaves room for D-1's family-owned account: an `account` row with the family
ledger's id.

### B. The membership table and D-4

**Options.** D-4's invariants in the service only; with unique indexes over a join (impossible in
PostgreSQL); or with the ledger's type copied into the membership row so that partial unique indexes
can see it. Membership as columns of `users` doesn't fit a nullable sub (D-3).

**Recommendation: two tables, with the type copied and pinned by a foreign key.**

```sql
CREATE TABLE ledger (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          VARCHAR(8) NOT NULL CHECK (type IN ('PERSONAL', 'SHARED')),
    name          VARCHAR(100),          -- SHARED only
    base_currency CHAR(3),               -- SHARED only; a personal ledger's is user_settings.base_currency
    split_rule    VARCHAR(7),            -- SHARED only: EQUAL or PERCENT (F3)
    archived_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, type),
    CHECK (type = 'PERSONAL' OR (name IS NOT NULL AND base_currency IS NOT NULL))
);

CREATE TABLE ledger_member (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id     BIGINT NOT NULL,
    ledger_type   VARCHAR(8) NOT NULL,
    user_sub      TEXT,                  -- NULL: a member without an account, or FORMER
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(6) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    status        VARCHAR(6) NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'FORMER')),
    join_date     DATE NOT NULL,
    left_date     DATE,
    share_percent NUMERIC(7, 4),         -- the default split's custom percentage (F3)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (ledger_id, ledger_type) REFERENCES ledger (id, type) ON DELETE CASCADE,
    UNIQUE (ledger_id, user_sub),        -- a sub at most once per ledger; NULLs are seats
    UNIQUE (ledger_id, id),              -- for composite foreign keys from family rows
    CHECK ((status = 'ACTIVE') = (left_date IS NULL)),
    CHECK (status <> 'FORMER' OR user_sub IS NULL),
    CHECK (role = 'MEMBER' OR (user_sub IS NOT NULL AND status = 'ACTIVE')),
    CHECK (ledger_type = 'SHARED' OR (user_sub IS NOT NULL AND role = 'OWNER' AND status = 'ACTIVE'))
);
-- Each user has one personal ledger, and a personal ledger has one member.
CREATE UNIQUE INDEX ON ledger_member (user_sub) WHERE ledger_type = 'PERSONAL';
CREATE UNIQUE INDEX ON ledger_member (ledger_id) WHERE ledger_type = 'PERSONAL';
```

V5 (F2a) creates both tables without `split_rule` and `share_percent`, which F3's V6 adds with the
split rule.

Triggers complete it:

- `ledger_type` can't change (the composite foreign key already refuses it while members exist).
- A deferred constraint trigger: at commit, a PERSONAL ledger has its member ("exactly one").
- `user_sub` goes from NULL to a sub (claiming a seat) or from a sub to NULL (FORMER), never from one
  sub to another; FORMER is final. `join_date` changes only while the membership has no sub, up to
  and including the claim of the seat (D-18), and when a LEFT member becomes ACTIVE again (D-26).
- The function `personal_ledger_id(sub)` returns the sub's personal ledger, creating it with its
  OWNER member if needed: it inserts the ledger, then the member with `ON CONFLICT DO NOTHING` on the
  partial unique index, and if another transaction's member won, it deletes its own new ledger and
  selects the other. So parallel first requests create one, without a lock. Provisioning, the
  backfill and the fill trigger of topic A all use it.
- `ledger_invite` (topic G) refers to `(ledger_id, ledger_type)` with `CHECK (ledger_type =
  'SHARED')`, so a personal ledger can't get an invite.

**Consequences.** Every D-4 invariant holds in the database, and the service checks them first to
answer with a clear 409 or 422. "At least one personal ledger per user" is the provisioning's job,
since a user without rows has no database presence to check; `CurrentUserResolver` already provisions
on first sight. A sub that left a ledger comes back through the same membership row, reactivated with
the acceptance date as its join date (D-26).

**Satisfies** D-2, D-3, D-4, D-15 (roles), D-18 (join date fixed, no second seat), D-19 and D-20
(statuses, nulled sub).

### C. Access enforcement

**Options.** Filter by membership in every query (a join to `ledger_member` in each statement); row
level security in PostgreSQL with a per-transaction setting of the sub; or resolve the ledger once per
request and scope every query by the resolved `ledger_id`.

**Recommendation: resolve once, then scope by ledger id, with the scope as a type.**

- `ledger/access/LedgerScope`, a final class with a package-private constructor: ledger id, type,
  member id, role. Only `ledger/access/LedgerAccess` makes one:
  - `personal(sub)`: the sub's personal ledger (provisioned on first sight, as today);
  - `member(sub, ledgerId)`: the ledger if the sub has an ACTIVE membership in it, else
    `NotFoundException("Ledger n not found")`, the same answer as for a ledger that doesn't exist;
  - `owner(sub, ledgerId)`: as `member`, for an owner's action (D-15). A MEMBER gets 409 naming the
    rule rather than 404, since the ledger itself is visible to them.
- Services take a `LedgerScope` instead of `String userId`. A raw ledger id or a sub never reaches a
  service method that reads or writes ledger rows; `CurrentUser` stays for what is about the person
  (`/api/me`, settings, manual rates, delete-all).
- `OwnedRepository` becomes `LedgerScopedRepository`, with the same rule: no lookup, list or delete
  by id alone. Lookups become `findByIdAndLedgerId`, `findAllByLedgerIdOrderByCode`, and so on. Every
  native statement filters by `ledger_id = :ledgerId` in place of `user_id = :userId`.
- The 404 rule is unchanged: anything outside the resolved ledger reads as missing, and a ledger the
  user isn't an ACTIVE member of reads as a ledger that doesn't exist. A LEFT member loses access
  (D-19). A posted entry in the user's own ledger is visible but read-only: PUT and DELETE answer
  409 "posted from the family budget …; change it there" (D-8).
- Row level security is rejected for now: one database login serves every request, every policy
  would depend on a setting the app must never forget, and the importer and the posting service need
  exceptions. The triggers stay the backstop instead.

**Triggers.**

- `posting_check_references` compares `ledger_id` where it compared `user_id`: the account and the
  counterparty must be in the entry's ledger. Balance-sheet accounts are never shared (D-11), so this
  stays strict.
- The category rule gets the family case (D-11): the category is in the entry's ledger, or it is in
  a SHARED ledger in which the entry's personal ledger's member (the same sub) has an ACTIVE
  membership. LEFT members need no exception: leaving detaches them (D-19, topic E), so none of their
  postings references a family category any more.
- `forbid_ledger_id_change` joins `forbid_user_id_change`.
- In F2 the trigger checks both columns; the user checks come first, so today's error messages stay.

**An architecture test (F2b).** It allows `JdbcClient` and `@Query` only in methods that take a
`LedgerScope`, with a named list of exceptions for what is about the person or shared by all users
(provisioning, settings, manual and ECB rates, delete-all), each with its reason. A new native query
that forgets the scope then fails the build instead of relying on review.

**Consequences.** The membership check is one indexed lookup per request. Mistakes in a single query
are caught by the triggers for writes and by the isolation tests for reads. Row level security can
still be added later as defense in depth.

**Satisfies** D-2 (active membership decides), D-8 (posted rows read-only), D-11 (family categories
on personal postings), rule 11's 404 semantics.

### D. The family ledger's data model

**Options.**

1. **Journal entries inside the family ledger**: one EQUITY account per member, the payment posted
   to the payer's account and a categorized posting per share, so that each family entry balances
   and the members' balances sum to zero by rule 2. A side table still holds what postings can't:
   original amount and currency, split method, author, editor, comment.
2. **Dedicated tables**: a family record and its shares, from which balances and reports are
   computed; the personal ledgers get ordinary journal entries.
3. **A hybrid**: dedicated record and share tables, plus a mirror journal entry per record in the
   family ledger.

**Recommendation: option 2.**

```sql
CREATE TABLE family_record (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id            BIGINT NOT NULL,                  -- a SHARED ledger
    type                 VARCHAR(10) NOT NULL CHECK (type IN ('EXPENSE', 'INCOME', 'SETTLEMENT')),
    record_date          DATE NOT NULL,
    category_id          BIGINT REFERENCES category,       -- a category of this ledger; NULL for a settlement
    payer_member_id      BIGINT NOT NULL,                  -- payer, recipient, or who pays in a settlement
    payee_member_id      BIGINT,                           -- who is paid in a settlement
    original_amount      NUMERIC(19, 4) NOT NULL,
    original_currency    CHAR(3) NOT NULL,
    base_amount          NUMERIC(19, 4) NOT NULL,          -- in the ledger's base currency (D-13)
    split_method         VARCHAR(10),                      -- EQUAL, PERCENT, AMOUNT, ONE_MEMBER
    comment              VARCHAR(500),
    author_member_id     BIGINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_member_id BIGINT NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    deleted_at           TIMESTAMPTZ,
    deleted_by_member_id BIGINT,
    version              INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (ledger_id, payer_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, payee_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, author_member_id) REFERENCES ledger_member (ledger_id, id)
    -- … and the editor and deleter the same way
);

CREATE TABLE family_share (
    record_id            BIGINT NOT NULL REFERENCES family_record ON DELETE CASCADE,
    member_id            BIGINT NOT NULL REFERENCES ledger_member,
    amount               NUMERIC(19, 4) NOT NULL,          -- in the base currency, stored (D-6)
    percent              NUMERIC(7, 4),                    -- as entered, for PERCENT and EQUAL
    updated_by_member_id BIGINT NOT NULL REFERENCES ledger_member,
    updated_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (record_id, member_id)
);
```

- A deferred trigger checks, at commit, that the shares of every EXPENSE and INCOME record that isn't
  deleted add up to `base_amount` exactly, and that a SETTLEMENT has none. Another checks that the
  category belongs to the record's ledger and has the right type.
- A member's family balance, computed on each call (rule 13), over records that aren't deleted:
  B(m) = Σ expense shares of m − Σ expenses m paid + Σ incomes m received − Σ income shares of m −
  Σ settlements m paid + Σ settlements m was paid. Because every record's shares add up to its
  amount, the balances of all members, with or without an account, add up to zero by construction
  (D-1).
- Shares are split by `split_method` when written and stored (D-6, D-12). EQUAL and PERCENT: every
  share is T × p rounded HALF_UP to the currency's minor unit, and the remainder goes to the member
  with the largest share; on a tie to the payer (the recipient for income), then by join order. AMOUNT: as entered, and they must add up. ONE_MEMBER: one share
  of T. The default rule (`ledger.split_rule`, `ledger_member.share_percent`) applies to new records
  only; the participants are the members that are ACTIVE with `join_date` on or before the record's
  date.
- **Frozen** is computed, not stored: a record is frozen when its payer or a member with a share is
  LEFT or FORMER (D-19, D-20). The service refuses changes to it with 409.
- A deleted record keeps its row (`deleted_at`), so the change journal and E1's history stay
  consistent; its posted rows are removed (topic E).
- **The base currency** (D-13) can't change once the ledger has a record (service check and trigger).
  `base_amount` is the card's charge when the card is in the base currency, else the ECB rate of the
  record's date (`RateBook`, without the user's manual rates, which are private), editable.
- E1 (report by category and month with each member's contribution) is one statement over
  `family_record` and `family_share`, like `ReportService.cashFlow`.
- A family-owned account later (D-1): an `account` row in the family ledger, and a record whose
  payer is that account (`payer_account_id`, exactly one of the two payers set) with a journal entry
  in the family ledger. Nothing here prevents it.

**Consequences.** Family data is read in its own terms (payer, shares, comment), and the
members-sum-to-zero invariant holds by a simple per-record check. Option 1 would reuse rule 2 for
the invariant, but it needs artificial EQUITY accounts per member, puts categories on member
accounts, and still needs the side table for D-6's fields; every edit would change two
representations. Option 3 doubles the data for nothing a report needs.

**Satisfies** D-1, D-6 (every field), D-12, D-13, D-16 (editor and time per record and per share),
D-19 (frozen), and D-1's future joint account.

### E. The posting service

**What it posts.** For a family ledger L and each member m with an account, ACTIVE, whose
`join_date` isn't after the record's date (D-7):

| Link type | Written by | Personal postings (in L's base currency) | Editable in the personal UI |
| --- | --- | --- | --- |
| `SHARE` of an expense | posting service | UNALLOCATED +s with the family category; Debt(L) −s | no |
| `SHARE` of an income | posting service | UNALLOCATED −s with the family category; Debt(L) +s | no |
| `PAYMENT` (expense) | the payer, as their own entry | account −T; Debt(L) +T | yes, the private side (which account) |
| `PAYMENT` (income) | the recipient, as their own entry | account +T; Debt(L) −T | yes, as above |
| `PAYMENT` on the placeholder | posting service, for a claimed seat's earlier payments (D-18), and for a payer who chose "Specify later" (D-14) | "Payments without a specified account" ∓T; Debt(L) ±T | the member reassigns it to an account, which makes it their own |
| `SETTLEMENT` | the side whose paying account is known, as their own entry (D-24) | account ∓x; Debt(L) ±x | yes |
| `SETTLEMENT` on the placeholder | posting service, for the other side of that settlement (D-24) | "Payments without a specified account" ∓x; Debt(L) ±x | the member reassigns it, as above |
| `OPENING_BALANCE` | posting service, at the join date (D-18) | Debt(L) −B; OPENING_BALANCE +B, where B is the balance before the join date | no |
| `CORRECTION` | posting service, when a LEFT member returns (D-26) | Debt(L) −d; OPENING_BALANCE +d, dated on the new join date, where d is the family balance less the displayed personal debt balance | no |

A share of zero posts nothing. A payment in another currency than the base currency goes through
`FX_EXCHANGE` (rule 9): account −T in X, FX_EXCHANGE +T in X, FX_EXCHANGE −base in the base
currency, Debt(L) +base (D-13).

**The accounts.**

- `Debt(L)`: one per family ledger in each member's personal ledger, created when the member joins:
  a system LIABILITY with the family's base currency as default currency, a new column
  `account.family_ledger_id` naming L (unique per personal ledger), code `FAMILY_DEBT_<L's id>`, name
  "Debt to family budget: <family's name>". The legacy `FAMILY_DEBT` stays as it is (D-21).
- "Payments without a specified account": one system ASSET per personal ledger, code
  `UNSPECIFIED_PAYMENTS`, created when first needed.
- `UNALLOCATED` and `OPENING_BALANCE` are found by code as today (`AccountRole`). Rule 5 puts every
  categorized posting on `UNALLOCATED`, so share entries post there, and D-8 (amended) names it. From
  F4a it is a system account: it can be renamed but not archived or deleted, so the posting service
  always finds it.

**How a posted row is represented.** An ordinary `journal_entry` with its postings, so that every
personal report counts it without change, plus a link row (D-9):

```sql
CREATE TABLE family_entry_link (
    entry_id         BIGINT PRIMARY KEY REFERENCES journal_entry ON DELETE CASCADE,
    family_ledger_id BIGINT NOT NULL REFERENCES ledger,
    member_id        BIGINT NOT NULL REFERENCES ledger_member,
    record_id        BIGINT REFERENCES family_record,     -- NULL for OPENING_BALANCE
    link_type        VARCHAR(15) NOT NULL
        CHECK (link_type IN ('SHARE', 'PAYMENT', 'SETTLEMENT', 'OPENING_BALANCE', 'CORRECTION')),
    system_owned     BOOLEAN NOT NULL,                     -- written by the posting service
    detached_at      TIMESTAMPTZ,                          -- the member left (D-19)
    UNIQUE (record_id, member_id, link_type)
);
CREATE UNIQUE INDEX ON family_entry_link (family_ledger_id, member_id) WHERE link_type = 'OPENING_BALANCE';
```

The entry's `kind` gets `FAMILY_SHARE`, `FAMILY_PAYMENT`, `FAMILY_SETTLEMENT`,
`FAMILY_OPENING` and `FAMILY_CORRECTION` as hints for the UI (rule 6). A contribution to a joint
account becomes another link type later.

**Idempotent re-posting.** `FamilyPostingService.repost(record)` runs in the transaction that
created, changed or deleted the record, after locking the record row. It computes the wanted posted
entries for every eligible member from the record's stored shares, and compares them with the
existing links by `(record_id, member_id, link_type)`: an equal entry stays, a different one is
replaced (same entry id, new version, postings replaced), a missing one is created, and one no longer
wanted is deleted. Running it twice changes nothing. When a payer changes the payment's amount,
currency or date in the personal entry (D-14), the same transaction updates the record, re-splits the
shares by the stored method (AMOUNT needs new shares from the user), writes the change journal, and
re-posts. Deleting the payment entry deletes the record after the UI's warning.

**Settlements (D-24).** The member who records a settlement and knows their paying account records
it with that account, as their own entry. The posting service posts the other side's part to that
member's "Payments without a specified account" at once, so D-10 holds for both sides immediately;
they reassign it to an account later. After one side has left, each side records their part in their
own ledger (D-19).

**Detach (D-19).** Leaving or being removed runs one transaction through `CrossLedgerWriter`, also
when an owner removes a member (the one case in which it writes into another user's ledger for
something other than a record):

1. Every category of L that the member's postings use is copied into their personal ledger (the
   personal category that merged into it at joining, if any, is unarchived and takes the family
   name), and those postings are re-pointed to the copies.
2. Their links to records of L get `detached_at`, and `system_owned` becomes false: the posted
   entries are ordinary personal entries, which the member may edit or delete.
3. `Debt(L)` stays with its balance and loses its `family_ledger_id`: an ordinary LIABILITY named
   after the family.

Afterwards no row of the member's personal ledger references L. The link rows, marked detached, stay
as the family side's record of what was posted; they are deleted with the entries they point to.

**Returning members (D-26).** Reactivation (topic G) links the same `Debt(L)` again (found by its
code), matches categories by code as at the first join, and posts every record from the new join
date. If the displayed balance of `Debt(L)` then differs from the member's family balance B(m), one
`CORRECTION` entry dated on the join date posts the difference d, which the acceptance screen shows
beforehand.

**Isolating the cross-ledger write path (D-8).**

- `ledger/family/posting/FamilyPostingService` is the only user of a package-private
  `CrossLedgerWriter`, the only code that writes a journal entry into a ledger it has no
  `LedgerScope` for. It takes only `PostedEntry` values built by the service's own factories (share,
  opening balance, correction, placeholder payment or settlement), and before writing it checks every
  posting: the account is the member's `Debt(L)`, their "Payments without a specified account", their
  `OPENING_BALANCE` (for an opening balance or a correction only) or their `UNALLOCATED` (for a share
  only, with a category of L); no counterparty and no payee. It writes the link row in the same
  statement batch. The detach is its one other operation, with the three steps above and nothing
  else.
- A database backstop: a trigger on `journal_entry`, `posting` and `family_entry_link` refuses to
  change or delete an entry whose link is `system_owned`, unless the transaction has set
  `app.writer` to `family-posting` or `delete-all` (`SET LOCAL` in `CrossLedgerWriter` and in
  `UserDataService`). All code shares one database login, so this catches mistakes, not attacks.
- An architecture test fails if any class but `FamilyPostingService` depends on `CrossLedgerWriter`
  (ArchUnit as a test dependency, or a reflection check without one), and its own isolation tests
  (topic K).

**Consequences.** Personal reports, the integrity check and delete-all work on posted rows unchanged.
The price is that a family edit touches up to one entry per member, all in one transaction. D-10
holds as long as every change to records, shares, members or join dates goes through `repost`;
topic K's invariant check verifies it after every test.

**Satisfies** D-7, D-8, D-9, D-10, D-13's FX routing, D-14's linked payment and "Specify later",
D-18, D-19's detach, D-24, D-26.

### F. Family categories

**Options.**

1. **One category row owned by the family ledger**, referenced directly by postings in the members'
   personal ledgers and listed with their own categories.
2. **Synchronised copies** in every personal ledger, kept equal by the service.
3. **A family category table** and a link from each member's personal category to it.

**Recommendation: option 1**, the only one D-11 allows ("a single object … no synchronised copies").

- `category.ledger_id` is the family ledger, `user_id` NULL. `UNIQUE (ledger_id, code)` as for any
  ledger.
- `GET /api/categories` returns the personal ledger's categories and those of every family ledger the
  user is an ACTIVE member of, each with its ledger's id and name and whether the user may rename or
  archive it (owners only, D-15).
- A personal category may not take the code of a family category the user sees (409), so codes stay
  unambiguous in the user's list.
- Joining (D-11): the joining member's categories with a family code are merged in the acceptance
  transaction: their postings are repointed to the family category, and the personal category is
  archived. On a name conflict the acceptance screen's choice renames the family category (the
  joining member isn't an owner; this is the one exception, as D-11 says).
- A family category is deleted only if no posting in any ledger and no family record uses it,
  otherwise archived (the foreign keys are `ON DELETE RESTRICT` already).
- Reports: `cashFlow` and `cashFlowInBase` accept the categories that the ledger's own postings
  reference, instead of `c.user_id = :userId`; the triggers guarantee that those are the ledger's or a
  family's it is an ACTIVE member of.

**Consequences.** A rename by an owner shows in every member's personal reports at once. A member
who leaves gets personal copies of the family categories their postings use (the detach, topic E), and
a member who returns has them matched by code again (D-26). Option 2 would drift and double every
rename; option 3 needs two ids per posting or a second lookup in every report.

**Satisfies** D-11 in full, and C4 (members see family categories, never personal ones).

### G. Invites and the acceptance flow

**Storage.**

```sql
CREATE TABLE ledger_invite (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id           BIGINT NOT NULL,
    ledger_type         VARCHAR(8) NOT NULL CHECK (ledger_type = 'SHARED'),
    token_hash          BYTEA NOT NULL UNIQUE,          -- SHA-256 of the token
    join_date           DATE,                           -- a seat's; a new member joins on acceptance (D-18)
    seat_member_id      BIGINT,                         -- the seat to claim, or NULL for a new member
    created_by_member_id BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,           -- 72 hours by default, 7 days at most
    revoked_at          TIMESTAMPTZ,
    used_at             TIMESTAMPTZ,
    used_by_member_id   BIGINT,
    declined_at         TIMESTAMPTZ,                    -- declining consumes the token (D-17)
    FOREIGN KEY (ledger_id, ledger_type) REFERENCES ledger (id, type),
    FOREIGN KEY (ledger_id, seat_member_id) REFERENCES ledger_member (ledger_id, id),
    CHECK (expires_at <= created_at + interval '7 days'),
    CHECK ((seat_member_id IS NULL) = (join_date IS NULL))
);
```

- The token is 32 bytes from `SecureRandom` (256 bits), base64url; only its SHA-256 is stored. A
  salt adds nothing for a random token of that size.
- **The link is `https://app.finance-nl.com/invite#<token>`.** A fragment is never sent to a server,
  so the token stays out of Caddy's and nginx's logs and out of `Referer`.

**Keeping the token across sign-in and registration.** The frontend's `/invite` page reads the
fragment, removes it from the address bar, and keeps the token in `localStorage` with its expiry. It
then calls `/api/me`; without a session it starts the login as any page does. After sign-in, in any
tab of that browser, the app sees the pending token and opens `/invite`.

- `localStorage` is shared by the tabs of the origin, so the tab the verification email opens finds
  the token too. The current `returnTo` is in `sessionStorage`, which that tab doesn't share
  (current-state, section 12).
- It survives the api's restarts, which end every session.
- Keycloak isn't touched: the redirect URI and the realm stay as they are.
- A verification link opened in another browser lands there without the token; the original tab
  still carries on and has it, and opening the invite link again works as long as it's valid.
- The SPA erases the token after any outcome: accepted, declined, invalid or expired (one answer for
  both, as D-17 requires). It also erases it at logout and at its expiry.
- The token goes to the API only in request bodies (`lookup`, `accept`, `decline`), never in a URL,
  so it stays out of the api's logs too.

The alternative, a public backend endpoint that stores the token in the HTTP session before the
login, survives the verification link in the same browser too, but not an api restart. It needs a
new unauthenticated path, a route in `deploy/finance.caddy`, and puts the token in a URL path, which
lands in logs. Rejected.

**Acceptance.**

1. `POST /api/invites/lookup` with the token, behind sign-in (D-17): the ledger's name and base
   currency, the join date (the seat's; the acceptance date otherwise), the seat's name if any, the
   members' display names, the category matching (the user's categories whose codes the family has,
   and names that differ), and for a returning member the corrective amount (D-26). Invalid,
   expired, revoked, used and declined tokens, and a user who is an ACTIVE member of the ledger, get
   the same 404 body.
2. `POST /api/invites/accept` with the token and the category choices, in one transaction: lock the
   invite by its hash (`FOR UPDATE`) and check it again; claim the seat (set `user_sub`, the seat's
   join date, which may be in the past), add a member (MEMBER, ACTIVE, the acceptance date), or
   reactivate the LEFT membership of the same sub (the acceptance date, D-26); create or relink
   `Debt(L)`; merge categories (topic F);
   post the records from the join date and the opening balance, and the claimed seat's payments to
   the placeholder (topic E), and a returning member's correction; mark the invite used. No owner
   confirmation (D-17); the members page shows who accepted and when.
3. `POST /api/invites/decline` with the token consumes it (`declined_at`), and owners see the invite
   as declined. The browser erases the token either way.

**Rate limiting.** The pinned Caddy has no rate-limit directive, and its image belongs to the auth
repository (current-state, section 13). The api counts lookups and acceptances in memory: at most 10
per sub per 10 minutes and 100 per minute in all, answered with 429 through `ApiExceptionHandler`.
One api instance runs, as the sessions already assume. With 256-bit tokens this is abuse control;
guessing is out of reach anyway.

**Satisfies** D-17 (every point), D-18, D-11's acceptance-screen matching, and Keycloak unchanged.

### H. The change journal

**Options.** Row-level database triggers that copy old and new rows into an audit table; an event
store from which records are rebuilt; or an application-written journal of field changes.

**Recommendation: the application writes it**, in the transaction of the change:

```sql
CREATE TABLE family_record_change (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id            BIGINT NOT NULL,
    record_id            BIGINT NOT NULL REFERENCES family_record,
    changed_by_member_id BIGINT NOT NULL,
    changed_at           TIMESTAMPTZ NOT NULL,
    action               VARCHAR(7) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    changes              JSONB NOT NULL,   -- [{"field": "shares.17", "old": "50.00", "new": "40.00"}, …]
    FOREIGN KEY (ledger_id, changed_by_member_id) REFERENCES ledger_member (ledger_id, id)
);
```

- Fields are the family fields: type, date, category, payer, original amount and currency, base
  amount, split method, each share, comment. Members and categories are stored as ids and named
  when read, so a FORMER member reads "Former member" everywhere at once (D-20). The private side
  of a payment (the account) is never written here (D-16).
- Every member reads the journal of every record (D-16); a share shows its last editor and time from
  `family_share`.
- D-20's erasure (as amended): when a member becomes FORMER, the comment text they wrote is replaced
  with null in the record and wherever the journal holds it: the new value of each of their own
  changes of the comment, and the old value of the change that replaced one of theirs. A comment
  value's author is the member of the change that wrote it, so the journal alone finds every copy.

Triggers are rejected: they don't know the member without a per-transaction setting, they would log
the private columns too, and a record's change would be several row events. An event store is more
than the MVP needs.

**Satisfies** D-16 and D-20's journal part.

### I. Ledger selection in the API and the UI

**Options.** The ledger in the path, in a header, or in the session (current-state, section 11).

**Recommendation: the path, and only for family resources.**

- Personal endpoints stay as they are and always mean the personal ledger: `/api/entries`,
  `/api/accounts`, `/api/reports/*` and the rest. There is exactly one personal ledger per user, so
  nothing is ambiguous, and F2 changes no URL.
- Family resources live under `/api/family-ledgers`: GET (the user's ledgers) and POST (create); and
  under `/api/family-ledgers/{ledgerId}/`: `members`, `invites`, `categories`, `records` (with
  `{recordId}` and `{recordId}/changes`), `settlements`, `balances`, `reports/cash-flow`, `settings`.
  `/api/invites/lookup`, `/api/invites/accept` and `/api/invites/decline` take the token instead.
- Headers are rejected because the ledger would be invisible in URLs, links and the path patterns
  that the isolation tests' coverage records; a session value because all tabs share it.

**UI.** A switcher in the header of `App.tsx`: "My ledger" and each family ledger by name. Family
pages are routes under `/family/:ledgerId` (records, balances, members, categories, settings), so a
link or a reload keeps the ledger. The personal routes stay. The entry form's "Family" switch shows a
family selector only for a user with more than one family ledger (D-5). Posted entries show a badge
in the list and open read-only, with a link to the family record.

**Satisfies** D-5, A3, C6.

### J. Migration and rollout, F2 to F7

Every migration is additive (D-22). F2 has no visible change and ships as its own deploy; the
recommendation is to **split it into F2a and F2b, each its own deploy**, so that the backfill meets
production data with no code change in the same release.

**Production sizing.** On 2026-09-29 production holds one sub, in `users` and `user_settings`, with
10 accounts, 15 categories, no counterparties, no journal entries and no import batches, so no
SharedExpense entry either ([current-state](../family-budget/current-state.md), section 15). V5's
backfill there creates one ledger and one member and fills `ledger_id` on 25 rows.

**F2a and F2b are deployed separately** (question 11): F2a's backfill meets production data with no
code change in the same release, and F2b's refactor of every query ships with no migration.

**F2a, the schema (V5), with no change to the application code.**

`V5__ledgers_and_membership.sql`:

1. `ledger` and `ledger_member` as in topic B, their triggers, and `personal_ledger_id(sub)`.
2. The backfill: `personal_ledger_id` for every sub in `users`, `user_settings`, `account`,
   `category`, `counterparty`, `journal_entry`, `import_batch` and the manual rates of
   `exchange_rate`. The member's display name is `users.display_name`, or empty.
3. `ledger_id BIGINT REFERENCES ledger` on `account`, `category`, `counterparty`, `journal_entry` and
   `import_batch`; filled from `personal_ledger_id(user_id)`; then `SET NOT NULL`.
4. `UNIQUE (ledger_id, code)` on `account` and `category`; `UNIQUE (ledger_id, id)` on `account`,
   `category`, `counterparty` and `import_batch`; the unique indexes `counterparty (ledger_id,
   lower(name))` and `journal_entry (ledger_id, external_ref) WHERE external_ref IS NOT NULL`;
   `journal_entry (ledger_id, entry_date DESC)`; the composite foreign keys `journal_entry
   (ledger_id, payee_id) → counterparty (ledger_id, id)` and `(ledger_id, import_batch_id) →
   import_batch (ledger_id, id)`.

   The composite foreign keys of V5 are these two and topic B's `ledger_member (ledger_id,
   ledger_type) → ledger (id, type)`. None of them covers a posting: `posting` has no `ledger_id`,
   and its account, category and counterparty are checked by `posting_check_references` (item 6). In
   particular no foreign key covers a posting's category, so F4's family-category exception (topic C)
   stays a trigger rule.
5. The transition triggers: fill a missing `ledger_id` from `user_id`; check that `user_id` is the
   personal ledger's member; `forbid_ledger_id_change` on the five tables; and, on `DELETE FROM
   users`, delete the sub's personal ledger and its member, so that today's `deleteAll` and the
   runbook's "Delete a user" leave no sub behind without being changed.
6. `posting_check_references` compares `ledger_id` too, after the `user_id` checks.

Nothing is dropped or renamed. The code of `9287f0e` runs unchanged against V5, which is what the
rollback relies on.

Other files: `deploy/pg-backup/finance.conf` (`CHECK_LEDGERS` counts `app.ledger`; a new
`CHECK_MEMBERS`), the runbook (`tables` 14 and `migration` 5 in the restore test; "Delete a user"
prints the ledgers too), CLAUDE.md's project map.

Tests:

- `DataIsolationApiTests` gets stronger: it records which endpoint handled each of the second user's
  requests, from the application's handler mapping, and fails for every mapped endpoint without such
  a request, instead of comparing the OpenAPI paths with the hand-kept list `CHECKED`
  (current-state, contradiction 3).
- `LedgerSchemaTests`: each D-4 constraint (a second personal ledger for a sub, a second member in a
  personal ledger, a sub twice in one ledger, a type change); an insert without `ledger_id` gets the
  personal ledger, and a new sub gets one with one OWNER member; a `user_id` that doesn't match the
  ledger is refused; `ledger_id` never changes; a posting across ledgers is refused; deleting the
  `users` row removes the personal ledger.
- A new `LedgerBackfillMigrationTests`: migrate a fresh database to version 4 (`Flyway.target`),
  write rows as the current code does for three subs (a full ledger, settings only, manual rates
  only), migrate to 5, and check one PERSONAL ledger with one OWNER member per sub and every row's
  `ledger_id`.
- `LedgerApiTest.OWNED_ROWS` gains `ledger` and `ledger_member` (by the member's sub), so
  `UserDataApiTests` and `DataIsolationApiTests` prove that delete-all leaves nothing. `ApiTests`'
  parallel first requests create exactly one personal ledger.

**F2b, scoping by ledger, with no migration.**

- New: `ledger/access/LedgerScope`, `LedgerAccess`, `LedgerType`, `MemberRole`, and an argument
  resolver for the personal scope next to `CurrentUserResolver`.
- `OwnedRepository` → `LedgerScopedRepository`; `AccountRepository`, `LedgerCategoryRepository`,
  `CounterpartyRepository`, `JournalEntryRepository`, `ImportBatchRepository` by ledger id; the
  records `Account`, `LedgerCategory`, `Counterparty`, `JournalEntry` and `ImportBatch` get
  `ledgerId` (and keep `userId` until the cleanup).
- Services: `AccountService`, `CategoryService`, `CounterpartyService`, `EntryService`,
  `SettingsService`, `StarterLedger` (provisions through `personal_ledger_id`), `UserDataService`
  (deletes by ledger, then the ledger and the member), `report/ReportService`, `rates/RateService`
  (postings by ledger, manual rates by user), `importer/ImportService` and `ImportRunner`
  (`--user-sub` resolves the personal ledger), `demo/DemoLedgerService`.
- The ten controllers in `ledger/api/` pass the scope; `security/CurrentUserResolver` provisions the
  ledger.
- Tests: `EntryServiceTests`, `LedgerRepositoryTests`, `ReportServiceTests`,
  `BaseCurrencyReportTests`, `ImportServiceTests`, `ImportRunnerTests`, `StarterLedgerTests`,
  `DemoDataApiTests`, a new `LedgerAccessTests`. `DataIsolationApiTests` keeps every assertion as it
  is: that it passes unchanged is the proof of "no visible change".

**Size.** F2a: about 250 lines of SQL and 300 of tests, one session. F2b: about 40 files and 800 to
1200 changed lines, mostly mechanical, one or two sessions. As one stage, F2 would put the first data
migration and a refactor of every query into one deploy.

**Rollback.** F2a: the previous image runs against V5 as it is. F2b: back to the F2a image. From F3
on, the older image ignores the family tables; if a family feature had reached production, a
rollback past it would also need the dump taken before the update (the runbook's "Roll an update
back").

**F3 to F7.** The plan holds with these changes:

- **F3** (V6: the `ledger` split columns, `category.user_id` nullable, `account.family_ledger_id`,
  the family category rule in the trigger) and the feature switch (D-25). It also takes D-20's
  membership part (FORMER, the owner
  passed on, a family ledger without members deleted), because from F3 a membership holds a sub that
  "Delete all my data" must remove. The confirmation screen's list of family ledgers can stay in F6.
- **F4 split into F4a and F4b.** F4a: records, shares, the posting service, family expenses in the
  base currency (C1), the members' balances (D1), read-only posted rows (C6), the change journal
  (C3), and the members without an account as payers; `UNALLOCATED` becomes a system account (D-8).
  F4b: marking a personal entry as family (C2), incomes (C5), other currencies (C7), settlements (D2,
  D-24), and the payment edits of D-14.
- **F5**: invites, seat claiming and returning members (D-26). It is the first stage in which
  another real person's data meets the owner's, so the privacy policy's new text (H2) is ready before
  F5 starts, even if F6 publishes it.
- **F6**: leaving, removal and the detach (D-19), and the rest as planned, minus D-20's membership
  part.
- **F7**: the switch goes on in production, then the check with two real accounts.
- **The feature switch (D-25).** Stages merge into `main` as they are ready. Family features sit
  behind a configuration switch that is off in production until F7: the family endpoints answer 404
  and the family pages are hidden. Tests run with it on. Their migrations may reach production
  early; each is additive (D-22), so a fix to production never ships half a feature.
- **The Excel import into a family ledger** (D-21) is its own stage after F7.

### K. Test strategy

**Isolation, three users.** A new `FamilyIsolationApiTests` next to `DataIsolationApiTests`, which
stays for personal ledgers:

- Alice and Bob share family ledger A; Alice alone owns family ledger B; Carol belongs to neither.
  Each writes a personal ledger with planted markers (`ALICE_PRIVATE`, …) in account names, category
  names, memos and counterparties. A and B get records of every type, settlements, invites, a member
  without an account, a claimed seat and a change journal.
- For every family endpoint (`everyEndpointIsCheckedHere`, since F2a, fails for a mapped endpoint
  that no cross-user request reached): Bob on B, and Carol on A and B, get 404 word for word as for
  a ledger id that doesn't exist, for reads and writes alike.
- No answer to Bob or Carol contains another user's markers. Bob's answers on A show family data
  only: Alice's display name, family categories, records, shares, balances; never her accounts,
  personal categories, memos or payment accounts.
- Bob's personal reads are the same before and after Alice's family activity, except for the
  entries linked to A in his ledger; those are compared with what the posting service should have
  written. Carol's personal reads don't change at all.
- **The cross-ledger path** (D-8): after each family operation, every row that changed in Bob's
  personal ledger is a journal entry with a `system_owned` link or his own payment's, and its
  postings touch only `Debt(A)`, `UNALLOCATED` with a category of A, `OPENING_BALANCE` or the
  placeholder account. His cards, other accounts and personal categories are unchanged. PUT and
  DELETE of a posted entry answer 409, and changing it past the service fails at the trigger.
- Invites: tokens of B used by Carol, of a personal ledger, expired, revoked, used, declined and
  random all get one answer; Bob can't claim a second seat in A; the rate limit answers 429.
- Detach and return: after Bob leaves A, or Alice removes him, no row of his personal ledger
  references A; after he returns, D-10 holds, with the corrective entry if one was needed.
- `UserDataApiTests` and the isolation tests: Alice's delete-all leaves Bob's personal rows
  unchanged, turns her into FORMER in A, and deletes B (no member with an account left).

**Invariants**, checked by one helper after every family test, in SQL:

- For every family ledger, the members' balances add up to zero.
- For every ACTIVE member with an account, the displayed balance of `Debt(L)` in their personal
  ledger equals their family balance, today and on each record's date.
- Every posted entry balances (the triggers), and every record's shares add up to its amount.
- A randomized test runs a few hundred operations (records, edits, deletes, settlements, joins,
  seat claims, leaves) from a fixed seed and checks the invariants after each, like `DemoLedgerTests`
  checks every day of two years.

**Elsewhere.** Plain unit tests of the split (rounding and remainder) and of the posted entries per
record type, next to `EntryBuilderTests`; `LedgerSchemaTests` for every new constraint and trigger;
an architecture test for `CrossLedgerWriter`; frontend tests for the switcher, the read-only entry,
the invite page's token handling (fragment removed, `localStorage` kept and cleared), and the
split preview.

### L. Risks and questions

**Risks.**

- **The first data migration.** V5 changes every production row. Mitigations: F2a without code
  changes, the backfill test from version 4, the backup before every update, and the restore test's
  new counts. The owner's ledger is small until the import.
- **The cross-ledger write path** is the one place where one user's action writes into another
  user's ledger. A bug there is a privacy or integrity incident. Mitigations: one class, a whitelist
  of accounts, the trigger backstop, the architecture test and the D-8 isolation test.
- **D-10 drift.** Any change to records, shares, join dates or statuses that skips `repost` breaks
  it. The invariant helper runs after every test; the integrity report could check it in production
  too.
- **The category merge on joining** rewrites the joining member's own postings. It is consented on
  the acceptance screen. Leaving copies the categories back as personal ones (D-19), but doesn't undo
  a rename chosen at joining.
- **In-memory state** (sessions, the refresh lock, now the rate limiter) assumes one api instance.
- **Long-lived work.** F3 to F6 reach production only as migrations and as code behind the switch
  (D-25), which is off until F7; F7's check with two real accounts is the first time two real people
  meet in the data.
- **Personal base currencies differ from the family's.** `Debt(L)` is in the family's currency, so a
  member with another base currency sees it revalued in their reports (ADR 0002). That is correct,
  but it will surprise.

**Open questions.** None: the owner answered all eleven on 2026-09-29 (below).

### Resolved questions

The F1 review's answers, as amended decisions in
[requirements.md](../family-budget/requirements.md). The topics above include them.

| # | Question | Decision | Decision in requirements |
| --- | --- | --- | --- |
| 1 | A new member with a join date in the past: re-split existing records? | No. A past join date only when claiming a seat; a new member joins on the acceptance date; existing records are never re-split. | D-18 |
| 2 | A member who left is invited again? | The same membership is reactivated, with the acceptance date as join date, and one corrective entry if the balances differ. | D-26 |
| 3 | Does a LEFT member count as a member with an account, and what happens to a deleted ledger's categories on their postings? | LEFT members never count. Leaving detaches them, so their postings no longer use the family's categories. | D-19 |
| 4 | May a member keep using family categories after leaving? | No: the detach copies them into the personal ledger and re-points the postings. | D-19 |
| 5 | May share entries post to the member's own `UNALLOCATED`? | Yes; the service finds it by code, and from F4a it is a system account. | D-8 |
| 6 | Who records the payee's side of a settlement? | The side whose paying account is known records with it; the other side's part goes to their placeholder account. | D-24 |
| 7 | The owner's existing `FAMILY_DEBT`? | Stays separate from every family ledger's debt account; no transfer, since production holds no real entries. | D-21 |
| 8 | Who gets the rounding remainder? | The member with the largest share; on a tie the payer (the recipient for income), then by join order. | D-12 |
| 9 | Does declining use up the token? | Yes. | D-17 |
| 10 | A family expense in the family ledger without a paying account? | The payer chooses one, the last used preselected, or "Specify later", which posts to the placeholder account. | D-14 |
| 11 | F2 as two deploys, and F3 to F6 off production until F7? | Yes, F2a and F2b deploy separately; F3 to F6 merge into `main` behind a switch that stays off until F7. | D-22, D-25 |

## Consequences

- Every ledger-scoped row gets a ledger, and access follows membership (A, B, C). Until the cleanup
  migration, `user_id` and `ledger_id` both exist and are checked against each other.
- The family ledger has its own record, share, invite and journal tables (D, G, H); personal ledgers
  get ordinary entries with a link (E), so personal reports, delete-all and the integrity check keep
  working as they are.
- One package may write into another user's ledger, under a whitelist, a trigger and its own tests
  (E, K). It also detaches a member who leaves (E).
- Family categories are shared rows, and the posting trigger learns one exception for them (C, F).
- The invite token lives in the browser's `localStorage` across the login, and Keycloak is not
  changed (G).
- The plan gains F2a/F2b and F4a/F4b, D-20's membership part moves to F3, and family features wait
  behind a switch until F7 (J).

## Rejected alternatives

Each topic above names its rejected options: the ledger derived from `user_id` or kept only in family
tables (A), invariants in the service alone (B), row level security and per-query membership joins
(C), family journal entries with member accounts, and the hybrid (D), synchronised or linked category
copies (F), a public endpoint that keeps the token in the HTTP session (G), audit triggers and an event
store (H), and a header or a session for the current ledger (I).
