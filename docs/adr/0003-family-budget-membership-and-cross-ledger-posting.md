# ADR 0003: Family budget: membership-based access and cross-ledger posting

**Status:** Accepted, 2026-09-29, after the F1 review (proposed 2026-09-28 in stage F1). The owner's
answers to the open questions are under [Resolved questions](#resolved-questions), and the topics
below include them. F2a's migration V5 (topic J) is deployed to production since 2026-09-29, from
`1f1662f`. Amended after that deploy: D-3's display name (topics B and G), detached links that never
block deleting personal entries (D-19, topic E), and the architecture test's exceptions (topic C).
F2b is deployed since 2026-09-29, from `ee9e498`. Amended after the F2b review: the exception rule
and raw ledger ids (topic C), and F3 split into F3a and F3b, with family categories in personal
ledgers moved to F4a (topic J). Amended for F3a: split shares in basis points (topics B, D and I),
409 rather than 403 for an owner's action (topic C), and `Debt(L)` with its column in F4a (topics E
and J). F3a is deployed since 2026-09-29, from `9bbf427`, with the feature switch off. Amended after
the F3a review: the fallback to `EQUAL` stands and is journaled from F4a (topics B and H), a member
changes their own display name (topics B and I), and delete-all's order once family categories reach
personal ledgers (topic J). F3b is deployed since 2026-09-30, from `5a7e490`, with the switch off. Amended
after that deploy: the start date of a family ledger (D-27, topics B and D) and F4 split into F4a, F4b
and F4c (topic J). F4a is deployed since 2026-09-30, from `71c3eb7` (parts 1 to 4) and `5f5af66`
(parts 5 and 6), with the switch off; the decisions after its reviews are in topics D, E, F and H.
F4b is deployed since 2026-09-30, from `4285d52`, with the switch off; the decisions after its review
are in topics I and J. F4c is deployed since 2026-09-30, from `e7cdeb1`, with the switch off; the
decisions after its review are in topics E and I. F4d is deployed since 2026-10-01, from `144ff6b`, with
the switch off; the decisions after its review, the settlement lock (D-28) among them, are in topics E
and I. F4e is deployed since 2026-10-01, from `31419bd`, with the switch off; the decisions after its
review are in topics E, F and J: D-11 amended for joining members, and returning members (D-26) and
making another member an owner moved from F5 to F6. F5, invites and taking a seat, is built as topic G's
"F5 as built" says, where it also names what differs from that topic's sketch. F5 is deployed since 2026-10-01, from
`e8f5ca0`, with the switch off; the decisions after it, D-29 to D-34, are in topics E, F and G, and F6 is split into
F6a and F6b, with the F6a plan in topic J. F6a is deployed since 2026-10-02, from `c26cff6`, with the switch off;
the owner confirmed it as built, and the decisions after it, D-35 to D-37 for F6b, are at the end of topic J. OPS-1,
the deploy scripts without an application change, is deployed since 2026-10-02, from `46dedcd`, with the images built
from `c26cff6`; the decisions after it (D-35 clarified, D-38 and D-39, F6 split into F6b and F6c) are in topics G and
J. F6b, D-35 to D-39, is built as topic J's "F6b as built" says. F6b is deployed since 2026-10-02, from `7a60020`, with
the switch off; the owner confirmed it as built, and F6c's plan is at the end of topic J. The requirements and decisions D-1 to D-39 are in
[docs/family-budget/requirements.md](../family-budget/requirements.md);
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
    split_rule    VARCHAR(6),            -- SHARED only: EQUAL or CUSTOM (F3a)
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
    display_name  VARCHAR(100) NOT NULL,  -- what other members see; chosen at acceptance (D-3)
    role          VARCHAR(6) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    status        VARCHAR(6) NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'FORMER')),
    join_date     DATE NOT NULL,
    left_date     DATE,
    share_bp      INTEGER CHECK (share_bp BETWEEN 0 AND 10000),  -- the default split's custom share (F3a)
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

V5 (F2a) creates both tables without `split_rule` and `share_bp`, which F3a's V6 adds with the
split rule.

**The default split rule (D-12), in basis points** (after the F2b review). `split_rule` is `EQUAL` or
`CUSTOM`. Under `CUSTOM`, every member that is not LEFT or FORMER has a `share_bp`, an integer from 0
to 10000, and those shares sum to exactly 10000; LEFT and FORMER members have none. Under `EQUAL`,
every `share_bp` is NULL, and the shares follow the members. A member added under `CUSTOM` gets 0
until an owner changes the rule. The API sends and accepts the shares as integers; percentages with
two decimals are the interface's business (topic I). Integers sum exactly, and 0.01 % is finer than
a household split needs. A deferred trigger checks the rule at commit; the service checks it first
and answers 422.

**F3a's details.** A member without an account whose custom share is above 0 can't be removed
(409) until an owner changes the rule. When a member with a custom share above 0 becomes FORMER
(D-20), the rule falls back to EQUAL: nobody else may decide what the others' shares become. The
owner confirmed it after the F3a review, and it holds for a LEFT member too once F6 brings leaving
and removal. From F4a, which brings the change journal (topic H), such a reset is journaled as a
system change that names the member who left. In a family ledger a display name is never blank and
is unique, case-insensitive, among the members that aren't FORMER (a unique index); every FORMER
member is "Former member". A member with an account changes their own display name (after the F3a
review, D-3; topic I), with the same checks; owners rename only members without an account. D-20's membership part is one database function, `release_family_memberships(sub)`,
which "Delete all my data" and the runbook's "Delete a user" both run: it locks each of the sub's
family ledgers in turn and reads the membership after the lock, so that two members deleting their
data at once see each other's changes.

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
  member id, role, and the member's sub. Until the cleanup migration, the rows of a personal ledger
  carry that sub as their `user_id`, and a personal ledger's settings and manual rates are keyed by
  it. Only `ledger/access/LedgerAccess` makes one:
  - `personal(sub)`: the sub's personal ledger (provisioned on first sight, as today);
  - `member(sub, ledgerId)`: the ledger if the sub has an ACTIVE membership in it, else
    `NotFoundException("Ledger n not found")`, the same answer as for a ledger that doesn't exist;
  - `owner(sub, ledgerId)`: as `member`, for an owner's action (D-15). A MEMBER gets 409 naming the
    rule rather than 404, since the ledger itself is visible to them. It comes with F3a, the first
    stage with an action only owners may take. Not 403: to the frontend a 403 means the account has
    no access to Finance Tracker at all (the client role `user` is missing). On `/api/me` it shows
    that screen with only a sign-out button, and any other call throws "Access denied. Please reload
    the page." without reading the problem's detail.
  - `provisionPersonal(sub)`: `personal`, after creating the ledger through `personal_ledger_id` if
    the sub has none: for provisioning and for the command-line importer (F2b).
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
(provisioning, settings, manual and ECB rates, delete-all). A new native query that forgets the
scope then fails the build instead of relying on review. The exception list is explicit and short,
with a reason for each entry, and every exception:

- takes the user id as a parameter, or handles no user's data at all;
- never reads the security context;
- is covered by the isolation tests.

F2b implements it as `ArchitectureTests` (ArchUnit), with five exceptions: `LedgerAccess`,
`UserDataService`, `UserSettingsRepository`, `ExchangeRateRepository` and `EcbRateLoader`. The
last writes the ECB's rates, which have no user, so it handles no user's data at all. The same test
checks that only `LedgerAccess` constructs a `LedgerScope` and that nothing in the service or domain
packages reads the security context.

**Raw ledger ids (after the F2b review).** A repository method that takes a raw ledger id
(`findByIdAndLedgerId` and the like, Spring Data's derived queries) may be called only from a default
method of the same repository that takes a `LedgerScope` and passes its ledger id. Services and
controllers call the scoped default methods, so a ledger id never travels outside a scope.
`ArchitectureTests` enforces it (F3a).

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
    share_bp             INTEGER,                          -- as entered, in basis points, for PERCENT and EQUAL
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
- Shares are split by `split_method` when written and stored (D-6, D-12). A record stores its share
  amounts; wherever it also keeps a percentage, that percentage is in basis points (`share_bp`, an
  integer out of 10000), as the ledger's default rule is (topic B). EQUAL and PERCENT: every
  share is T × p rounded HALF_UP to the currency's minor unit, with p = `share_bp` / 10000, and the remainder goes to the member
  with the largest share; on a tie to the payer (the recipient for income), then by join order. AMOUNT: as entered, and they must add up. ONE_MEMBER: one share
  of T.
  **As F4a implements it** (`ledger.family.ShareSplit`): every share is T × p cut down (not
  HALF_UP) to the minor unit, so that the shares never exceed T and the remainder is never
  negative; the whole remainder goes to the member with the largest share, on a tie to the payer,
  then by join order. So 10.01 split 50/50 gives the payer 5.01 and the other 5.00, as the F4a task
  requires; HALF_UP would give 5.01 to both and take the remainder of −0.01 from the payer, who would
  end at 5.00. D-12 says only "rounded", and "the remainder goes to" reads as a remainder that is
  added. A currency without a minor unit (JPY) splits in whole units. Under EQUAL a member with an
  account shares only records dated on or after their join date (D-7), and a member without an
  account shares any record of the ledger.
  **Kept after the F4a review** (2026-09-30), and D-12 says so; HALF_UP stays the rule for single
  amounts. D-18 as amended: members without an account share records of any date on or after the
  start date, members with an account from their join date. The default rule (`ledger.split_rule`, `ledger_member.share_bp`) applies to new records
  only; the participants are the members that are ACTIVE with `join_date` on or before the record's
  date.
- **The start date** (D-27, after the F3b deploy): `ledger.start_date`, chosen at creation (today by
  default, earlier allowed, never later), is also the creator's join date. A record dated before it
  answers 409 naming the rule. Family ledgers created before F4a get their creation date.
- **Frozen** is computed, not stored: a record is frozen when its payer or a member with a share is
  LEFT or FORMER (D-19, D-20). The service refuses changes to it with 409.
- A deleted record keeps its row (`deleted_at`), so the change journal and E1's history stay
  consistent; its posted rows are removed (topic E).
- **The base currency** (D-13) can't change once the ledger has a record (service check and trigger).
  `base_amount` is the card's charge when the card is in the base currency, else the ECB rate of the
  record's date (`RateBook`, without the user's manual rates, which are private), editable.
  **Decided for F4e** (2026-10-01, the owner's task; replaces "without the user's manual rates"): the
  ECB's rate on the record's date or the latest before it; where the ECB has no rate for the currency
  (RUB, which it no longer publishes), the acting member's own manual rate on or before the date; with
  no rate at all the request gives the base amount (422 `RATE_MISSING`). A base amount in the request
  always wins. The conversion rounds HALF_UP to the base currency's minor unit. Only the acting member's
  own manual rate is ever used, and the record shows the rate it used and its source to every member:
  as much as a base amount they typed would show. As built in topic E, "F4e as built: other
  currencies".
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
  "Debt to family budget: <family's name>". The legacy `FAMILY_DEBT` stays as it is (D-21). F4a adds
  the column and creates the accounts with the posting service, also for the members who joined
  before it (the creators of the family ledgers that F3a lets them create); V6 doesn't add them.
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
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id         BIGINT UNIQUE REFERENCES journal_entry ON DELETE SET NULL,  -- see below
    family_ledger_id BIGINT NOT NULL REFERENCES ledger,
    member_id        BIGINT NOT NULL REFERENCES ledger_member,
    record_id        BIGINT REFERENCES family_record,     -- NULL for OPENING_BALANCE
    link_type        VARCHAR(15) NOT NULL
        CHECK (link_type IN ('SHARE', 'PAYMENT', 'SETTLEMENT', 'OPENING_BALANCE', 'CORRECTION')),
    system_owned     BOOLEAN NOT NULL,                     -- written by the posting service
    detached_at      TIMESTAMPTZ,                          -- the member left (D-19)
    CHECK (entry_id IS NOT NULL OR detached_at IS NOT NULL)
);
CREATE UNIQUE INDEX ON family_entry_link (record_id, member_id, link_type) WHERE detached_at IS NULL;
CREATE UNIQUE INDEX ON family_entry_link (family_ledger_id, member_id)
    WHERE link_type = 'OPENING_BALANCE' AND detached_at IS NULL;
```

A link row never blocks deleting its personal entry (D-19, amended after F2a). While the member is
in the family, the posting service deletes a link it no longer wants together with its entry, and
the trigger below keeps everyone else from deleting a system-owned entry. After a detach, the entries
are the member's own: deleting one, or all their data (D-20), sets the detached link's `entry_id` to
NULL, and the link stays as the family's history. The alternative is a link without a foreign key to
the entry, which keeps the old id; F4a, which implements the table, chooses between the two, and a
test deletes a detached member's entries one by one and through delete-all.

**F4a's choice (V7): `entry_id` with `ON DELETE SET NULL`.** The foreign key keeps every link's entry
real while it has one, and the entry's deletion, by whoever may delete it, sets the reference to
NULL without a trigger; a link that keeps an id of nothing would need its own check that the id was
ever an entry of that member. `created_at` records when the link was written. The table's `CHECK
(entry_id IS NOT NULL OR detached_at IS NOT NULL)` above is left out: "Delete all my data" deletes
the member's entries first, and `release_family_memberships` detaches their links only afterwards.
The unique indexes are as above.

The entry's `kind` gets `FAMILY_SHARE`, `FAMILY_PAYMENT`, `FAMILY_SETTLEMENT`,
`FAMILY_OPENING` and `FAMILY_CORRECTION` as hints for the UI (rule 6). A contribution to a joint
account becomes another link type later.

**F4a as built.** `FamilyPostingService.post(family scope, record, payment)` is `repost`: the
writer's methods take the family ledger's `LedgerScope` of the member who acts, which the
architecture test's rule for SQL asks for, and find each member's personal ledger through their
membership. The payer with an account is always the member who acts (D-14), so their payment with
their own account is written in their own ledger; the database lets that account through only in the
ledger the writer names in `app.own_ledger`. Until F4c, the payment's fields don't change: a change of
the family fields re-posts the shares and keeps the payment, and the personal endpoints answer 409 for
the payment too (not only for the system-owned entries), naming the rule. Accepted after the F4a
review, as is leaving out the link check below. Posted entries have no memo:
a comment would copy family text into personal ledgers, which D-20's erasure then couldn't reach.

**F4c as built: payment edits** (D-14). `PATCH /records/{recordId}?version=` takes the payment fields
too: `date`, `amount`, `payerMemberId`, and for the payer with an account `paymentAccountId` or
`paymentLater`.

- Who: for a record paid by a member with an account, that payer alone changes the date, the amount,
  the account and the payer (an owner who didn't pay gets 409 naming the payer); for a payer without
  an account, the author or an owner. The family fields stay the author's and the owners'; a split
  sent with a new amount belongs to the payment's change, so the payer can send the new amounts that
  AMOUNT needs.
- The payer changes to a member without an account, whose payment is nobody's, or to the caller, who
  then names an account or "Specify later"; only the caller becomes a payer with an account (422
  `PAYER` otherwise). The old payer's payment entry goes.
- A new amount, date or payer splits the amount again by the stored split: `EQUAL` among the members
  who shared the record, less a member with an account who joined after the new date, plus one who
  joined after the old date and by the new one, so that their share entry appears or goes with the
  date; a member added since doesn't come in (D-18). `PERCENT` keeps the basis points, `ONE_MEMBER`
  the member; `AMOUNT` keeps the amounts for a new date or payer, and a new amount without new
  amounts is 422 `AMOUNTS_NEEDED`. A share of a member who joined after the new date, or a payer who
  did, is 422 `JOINED_AFTER`; a date before the start date 409 (D-27).
- The journal records `date`, `amount` and `payer` as it records the family fields. The account is
  never journaled, and a change of the account alone changes neither the record's version nor its
  editor (D-16).
- `FamilyPostingService.post` now re-posts the payer's payment with the record: with the account the
  payer names, or as it is (same account, same note) with the record's date and amount. A payment
  moved between "Specify later" and an account keeps its entry: `CrossLedgerWriter.replace` sets the
  link's `system_owned` to match, which V7's guard lets the writer do. No migration.
- The record's answer gains `canEditPayment` and `yourPayment`, the payer's own view of how they paid
  (their payment entry's id, and the account's id and name, or `later`), present only for the payer
  with an account and left out for everyone else. The expense page shows it instead of searching the
  payer's entries of the day (after the F4b review, topic I).
- The records' messages say "expense", as the screens do.

**F4c as built: the payer's personal entry and C2.**

- The payer's `FAMILY_PAYMENT` entry changes as a payment through `PATCH
  /api/entries/{id}/family-payment?version=` (the entry's version): `date`, `amount`, `accountId` or
  `later`, and `memo`, the payer's private note. `FamilyPaymentEntries` finds the entry in the
  caller's own personal ledger only, so anyone else's reads as missing (404), and hands the change to
  `FamilyRecordService.update` as the payer's, without a record version. `PUT` of it still answers
  409, and a share entry stays read-only (409 on the new path too). A new amount of an expense split by
  amounts is 422 `AMOUNTS_NEEDED` there: only the expense's page takes new amounts.
- `DELETE /api/entries/{id}?version=` of the payment deletes the expense as its payer, with every
  share (D-14), through the interface `ledger.FamilyPayments`, which exists only while the switch is
  on; with it off, the answer is the 409 it was. The interface's confirmation names the family budget
  and says that the other members' shares go too.
- C2: the personal editor's new expense has a "Family expense" switch while the switch is on and the
  user has a family budget; the budget selector only with more than one (D-5). It creates the record
  through `POST /api/family-ledgers/{ledgerId}/records`, as the family pages do: the user as payer,
  the entry's account, the family category, the split (the budget's rule by default), the family
  comment, and `privateNote`, the entry's memo, which only the payer's payment entry keeps (the
  posting service writes it; `CrossLedgerWriter` refuses a memo on anything else). The result is the
  payment and share entries, not an ordinary expense. Until F4e, an entry in another currency than the
  budget's can't be one, with a line that says why. Existing entries aren't converted.
- The payer's note is their own text in their own entry: it is not the family text that this topic
  keeps out of personal ledgers, and no family answer or journal holds it.

**Kept after the F4c review** (2026-10-01), as built:

- The payer's private note on their own payment entry (`privateNote`, the entry's memo), which only
  that entry holds.
- A new amount, date or payer of an expense split equally splits it again among the record's own
  members as of the new date: a member with an account comes in or drops out as the date crosses
  their join date, and a member added since doesn't come in (D-18).

**F4d as built: settlements** (D2, D-24). No migration: V7 already has the record type, the payee,
the link type `SETTLEMENT`, the kind `FAMILY_SETTLEMENT`, and a guard that lets the writer post a
settlement to the placeholder, and to an own account only in the acting member's ledger.

- `POST /api/family-ledgers/{ledgerId}/settlements` (topic I's `settlements`) records one: `date`,
  `amount` in the base currency, `payerMemberId`, `payeeMemberId`, `comment`, and for the recorder's
  own side `paymentAccountId` or `paymentLater`. It is a `family_record` of type `SETTLEMENT`,
  without category, split or shares; it is listed, read, changed and deleted through `/records` and
  `/records/{recordId}` like any record, and its answer adds `payee`.
- Who records one: a member with an account who pays or receives it; an owner, one between two
  members without an account (409 for anyone else, D-15). Naming a member with an account who isn't
  the caller is 422 `PAYER` or `PAYEE`; a side with an account who joined after the date is 422
  `JOINED_AFTER`; the same member on both sides is 422 `PAYEE`; before the start date 409 (D-27).
- The postings (`FAMILY_SETTLEMENT`, link `SETTLEMENT`): the payer's account −x and `Debt(L)` +x,
  the receiver's account +x and `Debt(L)` −x, so the payer's balance moves by x toward being owed
  and the receiver's the other way; paying more than is owed flips the balances. The recorder's side
  is on the account they name (theirs, `system_owned` false) or on their placeholder; the other
  side, if they have an account, always on their placeholder (D-24), which they move to an account
  through `PATCH /records/{recordId}` (`paymentAccountId` or `paymentLater`) or `PATCH
  /api/entries/{id}/family-payment` (`accountId` or `later`); a side without an account gets
  nothing. The balances follow topic D's formula, which F4a already computed.
- Changes: the side who recorded it (for one between members without an account, its author or an
  owner) changes the date, the amount and the comment, and deletes it, also by deleting their own
  side's entry; each side with an account changes only its own account. A category, a split, another
  payer or a note is 422; frozen (a side LEFT or FORMER) and stale are 409. The account is never
  journaled and alone changes neither version nor editor (D-16).
- **Only a member posts to their own account (D-8).** When the recorder changes the date or the
  amount and the other side has put its part on an account of theirs, that part goes back to their
  placeholder with the new date and amount, for them to put on an account again: the writer may not
  write the other member's account, and V7's guard allows an own account only in the acting
  member's ledger. Deleting the settlement deletes the other side's entry, on whatever account, as
  deleting an expense deletes every member's share.
- The journal: the creation with `date`, `amount`, `payer`, `payee` and `comment`; changes of
  `date`, `amount` and `comment`; the deletion. The journal's record summary gains `type`.
- `yourPayment` is each side's own: their entry and account, or `later`, in their answers only.

**Changed after the F4d review** (2026-10-01): the settlement lock (D-28) replaces the bullet "Only a
member posts to their own account" above. Once the other side, a member with an account, has put its
part on one of its accounts, the settlement's date and amount don't change and it isn't deleted: 409,
naming that member and saying that they can move their part back to "Specify later" to allow it. While
their part waits on their placeholder, the recorder changes and deletes it as before. So the posting
service never moves, rewrites or deletes another member's side on an account of theirs: nobody's
action changes another member's accounts (D-8). Nothing changes for members without an account.

**Kept after the F4d review**, as built: `POST /settlements` records one, and it is read, changed and
deleted through `/records`; a settlement side takes no private note.

**F4e as built: the settlement lock** (D-28). No migration. The record service refuses a settlement's
new date or amount and its deletion with 409 while its other side's link, the side that didn't record
it, isn't `system_owned` (their part is on an account of theirs); the recorder's own side and a side
without an account never lock it. The record's answer gains `lockedBy` (that member, additive), only
for who would otherwise change it, and `canEditPayment` and `canDelete` are false while it is set. The
posting service no longer moves such a side back to the placeholder: a side on its member's own account
is kept as it is when anyone else acts, and if a re-post would rewrite or delete it, it throws instead
(a bug, never a user's mistake). `CrossLedgerWriter.replace` and `delete` refuse an entry that isn't
`system_owned` unless its member acts. So no path writes another member's own account.

**F4d as built: incomes** (C5). No migration either.

- `POST /records` takes `type`: `EXPENSE`, the default, or `INCOME`. An income mirrors an expense:
  `payerMemberId` is the member who received it, its category is one of the family's INCOME
  categories (and an expense's an EXPENSE one: 422 `CATEGORY` either way, also on a change), the
  split methods are the same, and D-12's tie goes to the receiver.
- The postings, as this topic's table has them: the receiver's receipt (`FAMILY_PAYMENT`, link
  `PAYMENT`) is their account, or their placeholder for "Specify later", +T and `Debt(L)` −T; each
  share (`FAMILY_SHARE`) is UNALLOCATED −s with the income category and `Debt(L)` +s. So D-7's
  example holds: the receiver of 1000, split 50/50, ends owing 500 and the partner is owed 500. The
  personal cash flow counts the share as the family category's income.
- Edits and deletion as for expenses (D-14): the receiver with an account changes the date, the
  amount, the receiver and their account, also through their receipt's `PATCH
  /api/entries/{id}/family-payment`, with their private note (`privateNote`, the receipt's memo);
  for a receiver without an account, the author or an owner; the family fields by the author and
  the owners. Deleting the receipt deletes the income. `yourPayment` is the receiver's view of
  their receipt. The messages say "income" and "received".
- A personal entry's `family` gains `recordType` (additive), so that a receipt reads as one.

**F4e as built: other currencies** (C7, D-13). Migration V8, additive (D-22).

- **The original amount.** `POST /records` and `POST /settlements` take `currency` (additive) and
  `baseAmount` (additive); `amount` is the original amount in `currency`, which is the base currency
  when left out, as before. The forms send the paying, receiving or recording account's currency; the
  server doesn't take the currency from the account, since an account's `default_currency` is only a
  default (an account holds any currency, V2), and an existing isolation check pays a EUR income into an
  account whose default is USD without naming a currency. With a payer without an account or "Specify
  later", the form offers a currency picker.
- **The base amount.** In the base currency it is the original amount (a `BASE_AMOUNT` 422 for another
  one). Otherwise the request's `baseAmount` (source `ENTERED`), else the rate as decided in topic D
  (`ECB` or `MANUAL`), else 422 `RATE_MISSING`, "there is no exchange rate from RUB to EUR on or before
  2026-09-14: enter the amount in EUR, or add your own rate on the rates page". `RateService.recordRate`
  takes each currency's euro rate (the ECB's at any age, else the member's own), and `RecordRate`
  converts through the euro from the two rates, HALF_UP. V8 stores `base_rate` (base units per original
  unit, 12 decimals), `base_rate_source` (`ECB`, `MANUAL` or `ENTERED`) and `base_rate_date` (the older
  of the two euro rates' days): not derivable (rule 13), since rates are reloaded and manual ones change.
  A trigger keeps a record in the base currency without a rate and with its original amount as base
  amount, and one in another currency with its source.
- **What is in which currency.** Shares, balances and the debt accounts stay in the base currency. The
  payer's payment, the receiver's receipt and the recorder's side of a settlement are in the original
  currency; in another currency than the base they go through the member's `FX_EXCHANGE` as rule 9
  does: the account (or placeholder) and `FX_EXCHANGE` in the original currency, `FX_EXCHANGE` and
  `Debt(L)` in the base currency, the account's line first. V8's guard lets the writer post to
  `FX_EXCHANGE` only for a payment or a settlement side, only in the ledger it names as the acting
  member's own (`app.own_ledger`, now set for their "Specify later" too); `CrossLedgerWriter` checks the
  same before it writes.
- **Settlements.** The base amount settles. The other side's part waits on their placeholder in the
  base currency. Moving it to an account whose default currency isn't the base currency takes
  `accountAmount`, what went from or into it in that currency (422 `ACCOUNT_AMOUNT` without it, with it
  on "Specify later" or a base-currency account where it isn't the base amount); it lives only on their
  entry and in their own `yourPayment`, never in the record. While it is there, the lock (D-28) holds the
  recorder's date, amount, currency and base amount.
- **Edits.** A new original amount, currency or date converts the base amount again, unless the request
  gives `baseAmount`, which alone changes it too (by whoever edits the payment fields). A new currency
  needs its amount (422 `AMOUNT`). Each splits again by the stored split and posts again; a split by
  amounts needs new amounts whenever the base amount moves (`AMOUNTS_NEEDED`), also for a new date of a
  record in another currency. An account change keeps the record's currency, so it converts nothing.
- **Answers.** A record gains `originalAmount`, `originalCurrency`, and where converted `rate`,
  `rateSource` and `rateDate` (additive; the rate left out for `ENTERED` and in the base currency).
  `yourPayment` gains `amount` and `currency`, the side's own line. `GET /{ledgerId}/conversion?amount=
  &currency=&date=` (new) answers the base amount, rate and source a record of the caller would get,
  with the caller's own manual rates only; `baseAmount` null without a rate. The journal records
  `amount` (the base amount) as before and `originalAmount` ("9000.00 RUB") when the original amount or
  its currency changes outside the base currency; never an account or a side's own amount.
- **Integrity.** The integrity check's family row counts the debt account in the base currency only, and
  a posting to it in another currency is a difference too; the test invariants check the same.

**Kept after the F4e review** (2026-10-01), as built:

- A base amount converted at the acting member's own manual rate shows to every member as "manual rate",
  with the rate and its day; whose rate it was isn't said.
- The original currency comes from the request, the base currency when it is left out; the forms send
  the paying, receiving or recording account's currency. The server doesn't take it from the account.
- A change of the account alone, in the same currency, converts nothing: the record keeps its base
  amount and rate.
- The settlement lock (D-28) also covers the currency and the base amount, not only the date and the
  amount.
- The other side of a settlement names its own amount (`accountAmount`) only for an account whose
  currency isn't the base; on an account without a currency or in the base currency, its side is the
  base amount.

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
   **Changed after the F5 deploy** (D-33, decided by the PM): the personal categories that merge into
   family categories, at creation (F4a) and at joining (F5), are deleted, not archived, so there is
   nothing to unarchive. For every category of L that the member's personal ledger refers to, the
   detach creates a personal category with the same code and type and the family category's current
   name, and moves those references to it.
2. Their links to records of L get `detached_at`, and `system_owned` becomes false: the posted
   entries are ordinary personal entries, which the member may edit or delete.
3. `Debt(L)` stays with its balance and loses its `family_ledger_id`: an ordinary LIABILITY named
   after the family.

Afterwards no row of the member's personal ledger references L. The link rows, marked detached, stay
as the family side's record of what was posted, also after the member deletes those entries or all
their data: the link then loses its reference to the entry, never the other way round.

**Taking a seat (F5).** As built in topic G, "F5 as built": `join` posts a claimed seat's records from
the join date and its opening balance, and every re-post keeps the opening balances in step.

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
  As V7 implements it (F4a): `set_config('app.writer', …, true)`, which `CrossLedgerWriter` sets
  before and resets after each write, so the rest of the transaction runs without it. With it, only
  entries of the `FAMILY_*` kinds are written, and their postings go only to the accounts above; a
  payment's other account only in the ledger the writer names as the caller's own
  (`app.own_ledger`), since the payer with an account is always the caller (D-14). Without it, no
  entry of a `FAMILY_*` kind is written, no posting reaches a debt account, no link is written, and
  no debt account is created. A link's `entry_id` becomes NULL whoever deletes its entry.
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
  **Amended after the F4e review** (2026-10-01, D-11 as the owner decided it): a joining member's
  personal category with a family category's code merges into the family category, and the family
  category's name stays; there is no exception to D-15, and renaming a family category stays an
  owner's right. The acceptance screen shows each such merge in advance. The joining member may also
  bring other personal categories into the family dictionary; the rest stay private. How the merge
  is done (as at creation, or by archiving) is F5's, in topic G.
- A family category is deleted only if no posting in any ledger and no family record uses it,
  otherwise archived (the foreign keys are `ON DELETE RESTRICT` already).
- Reports: `cashFlow` and `cashFlowInBase` accept the categories that the ledger's own postings
  reference, instead of `c.user_id = :userId`; the triggers guarantee that those are the ledger's or a
  family's it is an ACTIVE member of.

**As built in F4a, parts 5 and 6** (2026-09-30):

- `GET /api/categories` adds `familyLedgerId` and `familyLedgerName` to a family category, and leaves
  both out for the ledger's own, so that a user without family budgets reads what they read before.
  The personal endpoints answer 409 to a rename or an archive of one ("… belongs to the family budget
  "Home"; rename or archive it there"); the owners change it in the family budget. For whom isn't
  an ACTIVE member, it is missing (404) as before.
- A personal entry may use one (`EntryService` looks it up through `LedgerAccess.families`). The cash
  flow, in each currency and in the base currency, counts it with `familyLedgerId` and
  `familyLedgerName`, beside a category of the ledger's own with the same code.
- The merge at creation: the chosen personal categories become family categories with their code,
  name and type, the creator's postings move to them, and the personal rows go. The entries keep
  their versions; only a posting's category changes. Family ledgers created before this weren't
  merged.
- The integrity check adds a row for each ACTIVE membership whose debt account doesn't show the
  member's family balance (D-10), with `familyLedgerId`, `familyLedgerName`, `debtBalance` and
  `familyBalance`.
- The demo loads into a ledger that has nothing of the user's own besides what family budgets posted
  there, and touches no family data. Its categories are always personal: a starter category merged
  into a family budget comes back as a personal one beside the family one with the same code, which
  the list marks; no demo entry uses a family category. The rule above, that a personal category
  may not take a family category's code, is for the categories users create.

**Kept after the review of parts 5 and 6** (2026-09-30):

- The isolation tests' exception reads as built: in the user's own view, the personal categories
  merged at creation are replaced by the family ones wherever they appear (the category list, the
  pickers, the cash flow), and nothing else of that view changes.
- Re-pointed entries keep their versions: the merge changes only a posting's category.
- The demo's personal twin of a merged starter category is accepted for now. F6 revisits it together
  with the demo family (H1).

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
   members' display names, the user's own display name for the other members, prefilled from the
   account's name (`users.display_name`) for the user to edit (D-3), the category matching (the
   user's categories whose codes the family has, and names that differ), and for a returning member
   the corrective amount (D-26). Invalid, expired, revoked, used and declined tokens, and a user who
   is an ACTIVE member of the ledger, get the same 404 body.
2. `POST /api/invites/accept` with the token, the chosen display name and the category choices, in
   one transaction: lock the invite by its hash (`FOR UPDATE`) and check it again; claim the seat
   (set `user_sub`, the seat's join date, which may be in the past), add a member (MEMBER, ACTIVE,
   the acceptance date), or reactivate the LEFT membership of the same sub (the acceptance date,
   D-26), each with the chosen display name; create or relink `Debt(L)`; merge categories (topic
   F); post the records from the join date and the opening balance, and the claimed seat's payments
   to the placeholder (topic E), and a returning member's correction; mark the invite used. No
   owner confirmation (D-17); the members page shows who accepted and when.
3. `POST /api/invites/decline` with the token consumes it (`declined_at`), and owners see the invite
   as declined. The browser erases the token either way.

**Rate limiting.** The pinned Caddy has no rate-limit directive, and its image belongs to the auth
repository (current-state, section 13). The api counts lookups and acceptances in memory: at most 10
per sub per 10 minutes and 100 per minute in all, answered with 429 through `ApiExceptionHandler`.
One api instance runs, as the sessions already assume. With 256-bit tokens this is abuse control;
guessing is out of reach anyway.

**Satisfies** D-17 (every point), D-18, D-11's acceptance-screen matching, and Keycloak unchanged.

**F5 as built** (2026-10-01; the owner's task decided the points where it differs from the sketch above).

- **Storage (V9).** `ledger_invite` as sketched: a claim is an invite with `seat_member_id` and
  `join_date`, a new member's has neither; `used_at` with `used_by_member_id`, `declined_at` and
  `revoked_at`, at most one of them and each final; `expires_at` after `created_at` and at most 7 days
  later; `token_hash` 32 bytes, unique. A trigger checks that the creator is an ACTIVE owner with an
  account, that a claim's seat is an ACTIVE member without one with a join date from the start date to
  today, and that nothing of an invite changes but its one end. The seat's foreign key is `ON DELETE
  CASCADE`: an owner who removes a member without an account removes the invites for their place.
  `release_family_memberships` revokes the member's pending invites and deletes a family ledger's
  invites with it (D-20).
- **The owners' endpoints** (`FamilyInviteController`, behind the switch): `POST
  /api/family-ledgers/{ledgerId}/invites` with `kind` (`NEW_MEMBER` or `CLAIM`), `seatMemberId` and
  `joinDate` for a claim, and `lifetimeHours` (1 to 168, 72 by default) answers 201 with the invite and
  `link`, `<app.public-url>/invite#<token>` (`APP_PUBLIC_URL`, production's address by default; local
  runs set theirs), the only answer that ever holds the token. `GET …/invites` lists every invite,
  newest first: `kind`, `seat`, `joinDate`, `createdBy`, `createdAt`, `expiresAt`, `status` (`PENDING`,
  `ACCEPTED`, `DECLINED`, `REVOKED`, `EXPIRED`), and `acceptedBy` with `acceptedAt`, `declinedAt` or
  `revokedAt`. `DELETE …/invites/{inviteId}` revokes a pending one (409 for any other). Owners only
  (409 for a member, 404 for anyone else, as for every family path). At most 20 pending invites per
  family ledger (409). A seat with an account, or FORMER, is 409; a missing join date, one before the
  start date or after today, or one for a new member is 422 `JOIN_DATE`.
- **The holder's endpoints**: `POST /api/invites/lookup`, `/accept` and `/decline`, the token in the
  body only. A token that is unknown, expired, revoked, used or declined, and a missing, blank or
  overlong one, gets one answer, 404 "This invite is not valid. Ask for a new one." The token isn't
  validated as a field: a validation error would carry the rejected value into Spring's DEBUG log, as
  would a request record's `toString`, which leaves the token out. **Differs from the sketch:** a
  valid token that the user can't use gets a 409 of its own, for lookup, accept and decline alike: an
  ACTIVE member already (the ledger's creator among them), a member who left (until F6 brings
  returning members, D-26), or a seat taken meanwhile by another invite for it. Decline needs the
  same, so that a member who opens a link meant for someone else doesn't use it up.
- **The lookup** answers the ledger's name and base currency, `invitedBy` (the creator's display
  name), `kind`, `seatName`, `joinDate` (the claim's, or today), `expiresAt`, the family's categories
  that aren't archived (code, name, type), `merges` (the user's categories with a family category's
  code and type, with both names), `keptPrivate` (the same code with the other type), `mayBring` (the
  user's categories, not archived, whose code the family doesn't have) and `displayName`, the account's
  name to prefill. **Differs from the sketch:** no other member's display name; and no ledger id,
  member id, sub, email address, account or record.
- **Accepting** locks the family ledger's row, then the invite's, and checks it again, so that of two
  acceptances of one token, or of two invites for one seat, the second gets the invalid answer or the
  409. Then, in one transaction: the membership (a claim's seat gets the sub, the name and the
  invite's join date; a new member joins today as a MEMBER, with a share of 0 under a CUSTOM rule); the
  invite used by it; the categories (D-11 as amended): the user's categories with a family category's
  code and type merge into it as at creation (their postings move, the personal row goes; the family's
  name stays), the brought ones become family categories with their postings, and one with a family
  code of the other type stays private and can't be brought (422 `CATEGORY`); then
  `FamilyPostingService.join`. A display name another member has is 409, as everywhere.
- **Access.** `ledger.access.LedgerInvites` finds an invite by its token's hash and lets its holder
  in, before there is a membership; it is an `ArchitectureTests` exception next to `LedgerAccess`, with
  its reason, and the only code that reads a family ledger for someone who isn't its member: what the
  lookup shows. After joining, `LedgerAccess.member` gives the scope as for any member.
- **Rate limit. Differs from the sketch:** per user and per client address (Tomcat takes it from
  `X-Forwarded-For` behind Caddy), each at most 10 attempts in any minute and 50 in any hour, over
  lookup, accept and decline together; only attempts let through count. 429 with `Retry-After`.
- **What a claim posts** (topic E, D-18): `FamilyPostingService.join` creates the member's `Debt(L)`,
  posts every record dated on or after the join date in which they have a share or which they paid,
  received or settled (their side on their "Payments without a specified account", in the record's
  original currency for a payment or receipt, through their `FX_EXCHANGE`, as the acting member), and
  their balance before the join date as one `FAMILY_OPENING` entry dated on it, `Debt(L)` −B and
  `OPENING_BALANCE` +B. A new member has only the debt account.
- **Opening balances stay in step** (found while building F5; not in the sketch): a record dated before
  a claimed member's join date stays a family record that its author, an owner or its payer may still
  change or delete, and its change moves that member's family balance before the join date. So every
  re-post (`post`) also re-posts the opening balance of each member with an account who joined after
  the start date: an equal one stays, a different one is replaced, one of 0 goes. D-10 holds from the
  join date on, which is what the test invariants and the integrity check compare.
- **The records of a member's time without an account** (not in the sketch): a member with an account
  who took a seat after a record's date, and is in it already (its payer, its receiver or with a share),
  stays in it through any change that keeps it before their join date: an equal split again keeps them,
  and their share or payment isn't `JOINED_AFTER`. They change their own payment fields of it (D-14),
  which moves their opening balance; naming an account for it is 422 `PAYMENT` (it is in the opening
  balance, with no entry). Nobody else with an account comes into a record dated before their join
  date, and a record moved before a member's join date still drops them, as F4c decided. A record of
  theirs moved from before their join date to after it is posted to their placeholder.
- **D-28 after a claim:** their settlement side posted on the placeholder is `system_owned` and locks
  nothing until they put it on an account of theirs. **D-14 after a claim:** records others entered keep
  their authors; the payment fields of what the seat paid or received are the new member's from now on.

**Decided after the F5 deploy** (2026-10-01, by the PM; requirements D-29 to D-34). The points above where F5
differed from the sketch stand, as decisions of their own:

- D-29: the 409s of their own for a valid token the user can't use (an ACTIVE member, the creator included; a
  member who left, until F6a makes an invite their way back, D-26; a seat another invite took), for decline
  too; the limits per user and per client address, 10 a minute and 50 an hour each, over lookup, accept and
  decline together, once F6a shows that the client address is the real one in production.
- D-30: a joining member's category with a family code of the other type stays private beside it; bringing
  it is 422 `CATEGORY`, and the acceptance screen lists it as staying private.
- D-31: the opening balances kept in step with the records before a claimed member's join date, which stay
  editable.
- D-32: a member who took a seat stays in that seat's records from before the join date, their payment fields
  theirs (D-14), an account for one 422 `PAYMENT`.
- D-33: merged categories deleted, not archived (topic E's detach says what leaving does instead).
- D-34 (new): before accepting a claim, the lookup and the invite page show the seat's opening balance as an
  amount in the family's base currency, and the privacy draft's "Invite links" section lists what the lookup
  shows, the same things. F5's lookup has no such amount; F6a adds it.

**Decided after the OPS-1 deploy** (2026-10-02, by the PM; requirements D-38):

- D-38 (with D-29): the per-address limit applies only to public addresses. A private, loopback or link-local address
  after Tomcat's resolution means the real client is unknown: IPv6 clients arrive through docker-proxy as the `edge`
  bridge's gateway, which Caddy puts in `X-Forwarded-For` and `RemoteIpValve` trusts as an internal proxy, so it is
  what the api sees. For such an address only the per-user limit applies. This settles F7's prerequisite about IPv6
  clients (topic J); IPv6 in Docker is "Later" in the requirements, for a maintenance window.

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
  amount, split method, each share, comment. F4a stores each as `{"field", "old", "new"}`, with
  `"member"` for a share (`{"field": "share", "member": 17, "old": "50.00", "new": "40.00"}`), and
  journals the creation with every field, each change of the family fields, and the deletion
  (without fields). Members and categories are stored as ids and named
  when read, so a FORMER member reads "Former member" everywhere at once (D-20). The private side
  of a payment (the account) is never written here (D-16).
- Every member reads the journal of every record (D-16); a share shows its last editor and time from
  `family_share`.
- **System changes** (after the F3a review). A change that no member made is journaled too, as a
  system change: the default split rule falling back to `EQUAL` when a member with a custom share
  above 0 becomes FORMER (D-20) or, from F6, LEFT (topic B). It names the member who left, by
  membership id like every member here, so it reads "Former member" once they are FORMER. Such a
  change belongs to the ledger, not to a record, and has no member as its author: F4a either lets
  `record_id` and `changed_by_member_id` be NULL for it, with an action of its own, or gives the
  ledger's changes a table of their own. **F4a (V7)** takes the first: `family_record_change` with
  `record_id` and `changed_by_member_id` NULL, the action `SPLIT_RULE_RESET`, and
  `about_member_id` naming the member who left; `release_family_memberships` writes it. Accepted
  after the F4a review, with the journal at `/journal?recordId=` (topic I) and D-20's erasure of
  comments already inside `release_family_memberships`.
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
  F3a implements the ledger itself as GET and PATCH `/api/family-ledgers/{ledgerId}` (name and base
  currency) and the split rule as PUT `/{ledgerId}/split-rule`, in place of `settings`, with
  `members` and `categories` as above. F4a adds `records` (GET, POST) and `records/{recordId}` (GET,
  PATCH and DELETE with `?version=`), `balances`, and `journal` (optionally `?recordId=`), which
  stands for `{recordId}/changes`. `LedgerAccess.member` answers only for family ledgers, so no
  family path reaches a personal ledger, not even the user's own.
- A member's own display name (after the F3a review, D-3): `PATCH /{ledgerId}/members/me` lets any
  ACTIVE member with an account change the name the others see, with the same validation and
  uniqueness as any display name (409 naming the rule on a clash). The path names no member id, so a
  member reaches only their own membership through it. Owners rename members without an account
  through `/{ledgerId}/members/{memberId}`, and nobody renames another member with an account.
- Headers are rejected because the ledger would be invisible in URLs, links and the path patterns
  that the isolation tests' coverage records; a session value because all tabs share it.

**UI.** A switcher in the header of `App.tsx`: "Personal", each family budget by name, and "New
family budget" (F3b; "My ledger" before). Family pages are routes under `/family/:ledgerId`
(records, balances, members, categories, settings), so a link or a reload keeps the ledger; F3b
has the overview, members, split rule, categories and settings, and creation at `/family/new`. The
screens say "family budget", never "ledger". While `/api/me` says the switch is off, the header has
no switcher and the family routes don't exist, so they end like any unknown path. The personal routes stay. The split rule's custom shares are
shown and entered as percentages with two decimals, and converted to and from the API's basis
points with integer arithmetic, never floating point: "33.33" is 3333, and 3333 is "33.33". The entry form's "Family" switch shows a
family selector only for a user with more than one family ledger (D-5). Posted entries show a badge
in the list and open read-only, with a link to the family record.

**Kept after the F4b review** (2026-09-30), as built:

- "Who owes whom" pairs the largest debtor with the largest creditor, then the next. It is a display
  only: the model fixes each member's balance, not the transfers. The fewest transfers that settle
  every balance (D3 in the review) stay "later" (requirements, "Later").
- The payment account list leaves out archived accounts, though the backend takes them.
- Money is formatted with the app's `formatMoney`, as on the personal pages.

**Changed after the F4b review:** the expense page no longer finds the payer's paying account among
the payer's entries of that day. From F4c the record's answer carries it in a field of its own,
present only for the payer with an account (topic E, "F4c as built").

**Changed after the F4c review** (2026-10-01): the expense page's split preview follows the record's
stored split (its method, and for equal shares its own members), not the family budget's current
rule, which applies only to new records (D-12). Fixed in F4d.

**F4d:** `POST /{ledgerId}/settlements` records a settlement, which is then a record like any other
under `records` (topic E, "F4d as built: settlements").

**F4d as built: the interface.**

- The family pages' "Expenses" tab is "Activity": expenses, incomes and settlements, newest first,
  each labelled from the reader's side ("Paid by you", "Received by Sam", "Sam paid you €36.20"), with
  "Add an expense", "Add an income" and "Record a settlement". Its paths stay `expenses` and
  `expenses/{recordId}`, which personal entries and the journal link to; a new income is at
  `incomes/new`, a new settlement at `settle`.
- Balances: who owes whom lists the reader's own debts first, and each one the reader may record
  (they pay or receive it, or they are an owner and neither side has an account) has "Settle up",
  which opens the settlement's form with `payer`, `payee` and `amount` in the URL.
- A settlement's page: the sentence, both members, the reader's own side ("Paid from" or "Received
  into", for their eyes only); the recorder changes the date, amount and comment, and is told that a
  new date or amount moves the other side's part back to their placeholder; the other side puts
  their part on an account there. An income's page mirrors an expense's, with "Received by" and
  "Received into".
- The personal editor's new income has "Family income", as a new expense has "Family expense" (C2):
  the budget, an income category, the split, the family comment, the memo as the private note. A
  receipt and a settlement side open with the reader's own fields (the account; for a receipt, and
  for a settlement's recorder, the date and amount; a receipt's note) and link to their record.
- The fix after the F4c review: an expense or income stored as equal shares opens its split as
  "Equal shares, as it is split now", previewed among its own members as the server splits it
  again, whatever the budget's rule is now; it isn't sent unless the user picks another split.

**Changed after the F4d review** (2026-10-01): a settlement's page no longer says that a new date or
amount moves the other side's part back to their placeholder. With the lock (D-28, topic E), the
recorder is told why the date, the amount and deleting are locked, and what the other side can do;
the other side's page and its personal entry offer "Specify later" to unlock it. The paths
`expenses…` become `activity…` later, with redirects (requirements, "Later").

**F4e as built: other currencies in the interface.**

- The expense and income forms, the settlement form and the edit forms on a record's page: the amount
  is in the paying or receiving account's currency when it has one (it decides, and no currency is
  picked); for a member without an account, "Specify later" or an account without a currency, a
  currency field. In another currency than the base, "Amount in EUR" shows the server's conversion
  (`GET /conversion`, the reader's own rates) with its rate and source, "1 USD = 0.851064 EUR, ECB rate
  of Sep 30, 2026"; the user may type it instead ("Use the exchange rate" goes back). The split preview
  is of the base amount. The request carries `currency` only outside the base currency and
  `baseAmount` only when typed, so requests in the base currency are as before.
- "Settle up" prefills what settles in the base currency; choosing an account in another currency
  moves that figure into "Amount in EUR" and asks for the amount paid in the account's currency.
- No rate: the field says why ("There is no exchange rate for RUB on or before …: enter the amount in
  EUR, or add your own rate on the Rates page"), saving waits for a typed base amount, and a 422
  `RATE_MISSING` lands at the field with the server's message.
- The other side of a settlement choosing an account in another currency, on the record's page or its
  own entry, is asked for the amount it got or paid there (`accountAmount`); its own side then shows
  "Rouble account, RUB 3,300.00", for its eyes only.
- Lists, the overview and a record's page show "$58.75 → €50.00" where the currencies differ, the base
  amount alone otherwise; the page adds the rate line. The journal says "the amount paid" ("$56.00 →
  $67.20") and "the amount in EUR".
- The personal editor's family option (C2) takes any currency: the entry's, converted as above.
- Amounts go through `minorUnits.ts` per currency; the rate is shown to six significant digits with
  big.js; nothing goes through floating point.

**F5 as built: invites in the interface.**

- The token (`invite.ts`, no React): `main.tsx` takes it from the fragment of `/invite#<token>` before
  anything else, the session's request included, removing the fragment from the address bar first
  (`history.replaceState`), and keeps it in `localStorage` for 7 days at most. A link opened in a tab
  that shows `/invite` already changes only the fragment: a `hashchange` takes it the same way and
  reloads the page (found in the walk-through). Without a session the app starts the sign-in from
  `/invite` as from any page; once it knows the user, a waiting token opens `/invite` wherever the app
  opened, so the tab that a sign-in or a registration's verification email ends in finds it too. With
  the switch off the token is erased and `/invite` ends on the dashboard. Logout erases it.
- `/invite` (`Invite.tsx`): who invites, to which family budget and in which currency, the place a
  claim takes and what of it becomes the user's, from which date the shares appear in the personal
  budget, the opening balance of a claim, what the other members will and won't see, the categories
  that merge (with both names), those that stay private, and those the user may bring; the name the
  others see, prefilled from the account; Accept and Decline. The token is erased after any outcome:
  accepted (the switcher's list loads again and the budget's overview opens), declined, invalid
  (the one message and "Back to Personal") or a 409 the user can't get past. A 409 on accepting is
  the name, shown at its field, unless a new lookup says the invite can't be used any more. A 429 keeps
  the token and offers to try again.
- The members page, for owners (`FamilyInvites.tsx`): "Invite someone new", and on each member without
  an account "Invite to take this place" with the join date (from the start date to today). The new
  link shows once, with a copy button and a line that it works once, for one person, until its expiry.
  The invites below, newest first, with their status in words, and Revoke for a pending one. Members
  see none of it.
- A claimed seat's `FAMILY_OPENING` entry opens read-only in the personal pages, saying what it is.

**F6a as built: the interface.**

- The members page: every member with an account has "Leave" on their own row; owners have "Remove" on every other
  ACTIVE member's row, with or without an account, and "Make owner" on an ACTIVE member with an account who isn't one
  (a browser confirmation). A member who left reads "Left on <date>", without actions. Leave and Remove open a
  confirmation below the table (`family.ts` `departureNotes`, no React): the member's balance in words, where it stays
  (for the reader, "Debt to family budget: <name>" as an account of theirs), what of their personal budget stays, the
  records that freeze, the split rule back to equal shares, and a budget that closes when nobody else has an account.
  The last owner is told to make another member an owner first (`lastOwner`), as the server's 409 `LAST_OWNER` says
  too. After leaving, the switcher's list loads again and the personal dashboard opens. The page loads the balances
  for the confirmations.
- The invite page: a claim says the place's balance before the join date in words ("Before Sep 15, 2026, Sam is owed
  €10.00. That becomes your opening balance …", D-34); a returning member's says they come back in their earlier place,
  and the correction in words, or that none is needed (D-26).
- "Invite to take this place" leaves the join date out of the request when it is the browser's today, so that the
  server's today counts, as the creation of a family budget does for its start date.
- "Delete all my data", with the switch on: before the confirmation, each family budget the user is in, with their role,
  their balance in words and what happens to it (`deletionNotes`: deleted, who becomes its owner, its rule back to equal
  shares, invites that stop working), and how many budgets they left. With the switch off the screen asks for nothing
  and is as before.
- `ApiError` carries a 409's `code` (additive). The members table keeps its actions in its horizontal scroll at 375 px,
  as before; the row buttons stack (requirements, "Later").

**Satisfies** D-5, A3, C6, C7.

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

**F2b, scoping by ledger, with no migration.** Implemented on `feature/family-budget` as described
below, with these details: the importer and the demo take the scope from their caller;
`UserDataService` also provisions the `users` row, so that the security package runs no SQL; the
rate queries that are about the person moved from `RateService` into `ExchangeRateRepository`.

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

- **F3 split into F3a and F3b** (after the F2b review). F3a is V6 and the backend: the `ledger` split
  columns (`split_rule`, `ledger_member.share_bp`), members without an account, `category.user_id`
  nullable for family categories in the family ledger, the family endpoints and the feature switch
  (D-25). `account.family_ledger_id` and the members' `Debt(L)` accounts move to F4a (topic E). It also takes D-20's membership
  part (FORMER, the owner passed on, a family ledger without members deleted), because from F3a a
  membership holds a sub that "Delete all my data" must remove. The confirmation screen's list of
  family ledgers can stay in F6. F3b is the interface: the ledger switcher and the family pages.
- **F4 split into F4a and F4b.** F4a: records, shares, the posting service, family expenses in the
  base currency (C1), the members' balances (D1), read-only posted rows (C6), the change journal
  (C3), and the members without an account as payers; `UNALLOCATED` becomes a system account (D-8).
  F4a also takes family categories in personal ledgers (topic F): merging a member's categories into
  the family ones by code, the posting trigger's family category rule (topic C), and family
  categories in personal category lists and reports. Until then a family category lives only in its
  family ledger.
  **Delete-all in F4a** (after the F3a review). Once a personal posting can carry a family category,
  "Delete all my data" must delete the user's personal entries, or at least their postings on
  family categories, before `release_family_memberships` runs; the alternative is to run the release
  after them. As F3a has it, the release runs first, and a family ledger in which the user was the
  only member with an account can't be deleted while the user's own postings still use its
  categories (the foreign keys refuse it). The runbook's "Delete a user" follows the same order. A
  test covers delete-all for a user with such postings.
  F4b: marking a personal entry as family (C2), incomes (C5), other currencies (C7), settlements (D2,
  D-24), and the payment edits of D-14.
  **F4 split again** (after the F3b deploy, 2026-09-30): F4a is the backend of family expense records
  and posting, as above, with the start date (D-27); F4b is their interface; F4c takes what the plan
  called F4b just above, backend and interface, and may be split again. Until F4c, a wrong payment
  field is fixed by deleting the record and entering it again.
  **F4c split** (after the F4b review, 2026-09-30): F4c is the payer's side: the payment edits of
  D-14, moving a payment to an account, the payer's own payment entry through the personal
  endpoints, and marking a new personal expense as family (C2). F4d is settlements (D2, D-24) and
  family incomes (C5); F4e other currencies (C7, D-13). F5, F6 and F7 stay as they are.
- **F5**: invites, seat claiming and returning members (D-26). It is the first stage in which
  another real person's data meets the owner's, so the privacy policy's new text (H2) is ready before
  F5 starts, even if F6 publishes it.
- **F6**: leaving, removal and the detach (D-19), and the rest as planned, minus D-20's membership
  part.
- **F5 and F6 after the F4e review** (2026-10-01): F5 is invites and taking a seat (D-17, D-18).
  Returning members (D-26) and making another member an owner move to F6, next to leaving and
  removal, which they follow from. The privacy policy's draft is
  [docs/family-budget/privacy-draft.md](../family-budget/privacy-draft.md), which F6 publishes.
- **F6 split after the F5 deploy** (2026-10-01, decided by the PM): F6a is the membership's lifecycle (leaving,
  removal and the detach, the rule back to EQUAL, returning members, new owners) and what "Delete all my data"
  shows; F6b the privacy policy's publication, the demo family (H1), the family report (E1, E3's check), H4,
  the owner's remarks on the interface and the switcher's placeholder right after accepting.
- **F7**: the switch goes on in production, then the check with two real accounts.
- **The feature switch (D-25).** Stages merge into `main` as they are ready. Family features sit
  behind a configuration switch that is off in production until F7: the family endpoints answer 404
  and the family pages are hidden. Tests run with it on. Their migrations may reach production
  early; each is additive (D-22), so a fix to production never ships half a feature.
- **The Excel import into a family ledger** (D-21) is its own stage after F7.

**F6a plan** (2026-10-01). What each operation of F6a does, from D-19, D-20, D-26 and D-33 and from this ADR's own
text, which each point names. Where the text leaves a gap, "F6a as built" below decides it.

1. **Leaving** (D-19, story B5).
   - Who: an ACTIVE member with an account, for themselves. The last owner can't leave while another member with
     an account remains (D-19): 409.
   - Rows, in one transaction that locks the family ledger's row first (topic B): the membership becomes LEFT
     with the leave date; it becomes a MEMBER, since an owner is ACTIVE (topic B's check), and loses its custom
     share, which only ACTIVE members have (topic B). The detach (topic E, as D-33 changed it), through
     `CrossLedgerWriter`: in the member's personal ledger, a personal category for each category of L that the
     ledger refers to, with those references moved to it; the member's links detached and no longer
     system-owned, so the posted entries are ordinary personal entries; `Debt(L)` without `family_ledger_id`, an
     ordinary liability that keeps its balance. Afterwards no row of their personal ledger references L (topic
     E, topic K).
   - Posts: nothing. Everything posted stays, as the member's own entries (D-19). No record changes; the records
     whose payer, payee or a member with a share above 0 is LEFT are frozen (topic D).
   - D-10: holds for every ACTIVE member as before, since nothing of theirs is posted or changed. It no longer
     applies to the member who left (topic K checks ACTIVE members). Their debt account showed their family
     balance when they left, and that balance can't change afterwards: every record that involves them is
     frozen.
   - Access: `LedgerAccess.member` needs an ACTIVE membership, so a LEFT member reads nothing of L (topic C), and
     their personal category list no longer shows L's categories (topic F).
   - The last member with an account may leave; the family ledger is then archived (D-19).
2. **Removal** by an owner (D-15, D-19), of another member with or without an account. With an account: as
   leaving, the detach running through `CrossLedgerWriter`, "the one case in which it writes into another
   user's ledger for something other than a record" (topic E). Without an account: LEFT; nothing posts, since
   such a member has no personal ledger, and the records that involve them freeze. D-10 as for leaving.
3. **The rule back to EQUAL** (topic B, "it holds for a LEFT member too once F6 brings leaving and removal";
   topic H). When the member who leaves or is removed has a custom share above 0, the rule becomes EQUAL, every
   share is cleared, and the journal gets a system change `SPLIT_RULE_RESET` about that member, as
   `release_family_memberships` does for D-20. Nothing posts, since the rule applies to new records only
   (D-12). A share of 0 just goes; the others still sum to 10000.
4. **Return** (D-26; topics B, E and G). An invite for a new member brings a LEFT member back: topic G's
   acceptance "reactivates the LEFT membership of the same sub". D-29's 409 for a member who left becomes this
   way back; the other 409s stay (an ACTIVE member; a claim, since members are never merged, D-18; a taken
   seat).
   - Rows: the same membership, ACTIVE again with the acceptance date as its join date (topic B's trigger allows
     exactly this change), the chosen display name, a MEMBER; `Debt(L)` linked again, "found by its code" (topic
     E); the user's categories matched by code as at the first join (topic F, D-11 as amended), D-33's copies
     among them; the invite used.
   - Posts: every record from the new join date, as for a claim (topic E), and one `CORRECTION` entry dated on
     the join date, `Debt(L)` −d and OPENING_BALANCE +d, where d is the family balance less the displayed balance
     of `Debt(L)` (topic E's table). The acceptance screen shows d beforehand (D-26).
   - D-10: holds from the new join date, by construction of d.
5. **Owners** (D-3: several owners; D-15). An owner makes another ACTIVE member with an account an owner. The
   last owner hands ownership to such a member before leaving (D-19); leaving as the last owner while one exists
   is 409 with a code of its own. Rows: the membership's role. Posts: nothing.
6. **What "Delete all my data" touches** (D-20: "The confirmation screen lists the affected family ledgers and
   their balances"; topic J left that list to F6). Before the user confirms, the screen lists every family
   budget they are in: its name, their role, their balance in its base currency, and what will happen (the
   ownership passing to the member with an account who joined earliest, named by display name; the budget
   deleted when no other member with an account remains; their pending invites revoked). One new read endpoint,
   for the signed-in user's own memberships only, behind the switch (D-25); with the switch off the screen is as
   it is now. The deletion stays `release_family_memberships` (F3a, F4a, F5), changed only where D-33 or this
   plan needs it. Nothing posts to anyone else, so D-10 holds for the other members.
7. **Tests** (topic K). `FamilyInvariants` learns LEFT members (no row of their personal ledger references L)
   and returned ones (D-10 from the new join date, with the correction); the balances of a family ledger still
   sum to 0 over all its members, LEFT and FORMER included. A randomized test from a fixed seed leaves, removes,
   returns, makes owners and deletes all data among its operations.

**F6a as built: leaving, removal, the rule back to EQUAL** (2026-10-01). As the plan says, with these decisions
where the text above left a gap (each to be confirmed after the F6a review):

- **Endpoints.** `DELETE /{ledgerId}/members/me` leaves: any ACTIVE member with an account (`LedgerAccess.member`).
  `DELETE /{ledgerId}/members/{memberId}`, F3a's removal of a member without an account, now removes any member, with
  or without an account: owners only (409 for a member, 404 for anyone else); one's own id is leaving. Both answer
  204. A member who left already, or a FORMER one, is 409; a member of another ledger 404. The last owner while
  another ACTIVE member with an account remains is 409 with `code` `LAST_OWNER`, a new additive property of a 409's
  problem detail (`ConflictException` with a code). An owner may remove another owner, who becomes a MEMBER.
- **A member without an account.** One whom no record names (payer, payee or a share, deleted records included) and
  whose custom share is 0 or none is deleted, as F3a did; any other becomes LEFT, so that the frozen records and the
  journal can name them. F3a's 409s for records and for a custom share above 0 are gone.
- **The detach** (`CrossLedgerWriter.detach`, called by `FamilyPostingService.detach` while the member is still
  ACTIVE): the links are detached first, as the writer; then, as an ordinary statement, the postings of the member's
  personal ledger move from each family category to its copy, which V7's guard allows once the entries aren't posted
  any more; then `Debt(L)` loses `family_ledger_id` and is no longer a system account, so that its owner can rename or
  archive it; its name stays. A copy is created only for a category the personal ledger refers to (D-33). Where the
  member has a personal category with the code and type already (the demo's personal twin of a merged starter
  category, topic F), the references move to it and it keeps its name; where the code is taken by a category of the
  other type (D-30), the copy's code gets `_2`, `_3` and so on. No migration.
- **Invites.** Leaving or being removed revokes the member's pending invites, as "Delete all my data" does (D-20),
  and those for a removed seat's place.
- **The leave date** is today (`ledger.Today`, below). `FamilyMemberView` gains `leftDate` (additive).
- **The last member with an account** leaving or removed archives the ledger (`ledger.archived_at`, which nothing reads
  yet): its members without an account stay ACTIVE, nobody sees it, and nobody can invite into it, since an invite's
  creator is an ACTIVE owner. `release_family_memberships` deletes it with the first "Delete all my data" of one of its
  LEFT members, as it deletes a family ledger without an ACTIVE member with an account.
- A LEFT member's display name stays taken (V6's unique index leaves out only FORMER members), since they may return
  under it (D-26).
- **"Today"** (the F5 follow-up). The start date (D-27), a new member's and a claim's join date (D-18) and the leave
  date come from `ledger.Today`, the date in the api's time zone, which is also the database session's (the JDBC
  driver gives the session the JVM's zone), so it is the `current_date` that V9's trigger and V7's start date use; in
  production the api runs in UTC (`eclipse-temurin:21-jre` without a `TZ`). Records have no upper bound on their date.
  Before F6a the services read `current_date` themselves: the same date, but not one a test could set. A browser a
  time zone ahead, such as Amsterdam's between midnight and 02:00 in summer, is already on the next day: the start
  date's form leaves its date out when it is the browser's today (F3b), and a claim's join date may now be left out
  too, for the server's today (it was 422 `JOIN_DATE`); the members page leaves it out the same way. No migration.
- **D-34.** The lookup gains `openingBalance` (additive): for a claim, the seat's family balance from the records before
  the join date, in the base currency, positive when the seat owes the family; null for a new member. The invite page
  shows it, and the privacy draft's "Invite links" lists what the lookup shows.
- **The client address** (D-29's condition). Production's path for `/api/*` is Caddy → `finance-tracker-api` directly;
  `finance-tracker-web` (`web.conf`) proxies nothing (the local image's `nginx.conf` does, with
  `$proxy_add_x_forwarded_for`). Caddy 2.11, without `trusted_proxies` in the auth server's Caddyfile, sets
  `X-Forwarded-For` to the address its connection came from and drops what a client sent. Tomcat's `RemoteIpValve`
  (`server.forward-headers-strategy: native`, Spring Boot's default internal proxies: 10/8, 172.16/12, 192.168/16,
  127/8, 169.254/16, 100.64/10 and IPv6 loopback and local) takes the right-most address that isn't a trusted proxy's,
  from a trusted peer only. The api publishes no port, so Caddy on `edge` is its only peer. Nothing changed:
  `ClientAddressTests` and `ClientAddressUntrustedPeerTests` pin it, through a real Tomcat. The sign-in's redirect URIs
  and the forward-headers strategy are untouched. F7's checklist checks the `edge` network's subnet and the limit from
  two places.
- **D-31, a record moved before the join date.** A record that involves a claimed member and is moved from on or after
  their join date to before it drops them from an equal split (F4c's rule, which F5 kept: "a record moved before a
  member's join date still drops them"), and refuses their share or payment otherwise (`JOINED_AFTER`); so that
  direction never changes their opening balance, while the other direction, and an amount changed before the join
  date, do. Left as it is; the report asks whether it should change.

**F6a as built: return, owners, what "Delete all my data" touches** (2026-10-01). As the plan says, with these
decisions (each to be confirmed after the F6a review):

- **Return.** No new endpoint: an invite for a new member (`kind` `NEW_MEMBER`) whose holder has a LEFT membership in
  the ledger brings it back (`LedgerInvites.rejoin`): ACTIVE, joined today, the chosen display name (their own former
  one isn't taken by themselves), a MEMBER, with a share of 0 under a custom rule; the invite used by it. A claim held
  by a member who left stays 409, with a message that says an invite as a new member brings them back; an ACTIVE member
  and a taken seat stay 409 too (D-29). The lookup gains `returning` and `correction` (additive): d as below, computed
  for the lookup's today. Declining works as for anyone.
- **The correction** is kept in step as the opening balances are (D-31): every re-post computes, for each ACTIVE member
  with an account who joined after the start date, their family balance from the records before their join date less
  what their debt account shows on the join date from entries that aren't posted for their membership now (in the base
  currency), and posts the difference on the join date: a `CORRECTION` (`FAMILY_CORRECTION`, `Debt(L)` −d,
  OPENING_BALANCE +d) for a member who returned (one with detached links), an `OPENING_BALANCE` as before for one who
  took a seat, whose debt account holds nothing else. So a change of a record before a returned member's join date,
  which isn't frozen any more, moves their correction, and D-10 holds from the join date.
- **Entries of records from the join date** (found in F6a's walk-through, a member leaving and returning on the same
  day): before the records are posted, `CrossLedgerWriter.reattach` attaches again the member's detached links of
  records dated on or after their new join date, while the entry is still of a family kind; the re-post then brings
  each in line with its record. Posted anew beside the old ones, those records would count twice in the member's
  personal reports (the debt account was right either way). Their own payment on an account of theirs stays theirs. Not
  counted, and not attached: an entry the member changed into an ordinary kind, or one on the debt account that
  belongs to no record, dated after the join date (the report asks about it).
- **`Debt(L)` linked again** (`CrossLedgerWriter.relinkDebt`): the liability that the member's detached links posted to,
  else the one with the code `FAMILY_DEBT_<ledger id>`, becomes the debt account again: `family_ledger_id` set, a system
  account, not archived, named "Debt to family budget: <the family's name now>" (a name the member gave it while away
  goes). With none, the posting creates a new one.
- **Entries of before leaving.** Once their debt account names the family budget again, those of a returned member's
  entries that post to it change only with the family budget: `PUT` and `DELETE` answer 409 ("Entry n posts to your
  debt to the family budget "Home", which changes only through the family budget"), since the correction counts them.
- **The categories** of a returning member merge as at the first join (D-11 as amended): their D-33 copies have the
  family's codes and merge back; a `_2` copy stays personal.
- **Owners.** `POST /{ledgerId}/members/{memberId}/owner` (new, owners only; 409 for a member, 404 for anyone else)
  makes an ACTIVE member with an account an owner and answers the member; 409 for one without an account, one who left,
  a FORMER one or an owner already. There is no way to make an owner a member again but their leaving (not asked for).
- **What "Delete all my data" touches.** `GET /api/me/family-memberships` (new, behind the switch): for each family
  ledger the caller is an ACTIVE member of, by name, `ledgerId`, `name`, `role`, `baseCurrency`, their `balance`, the
  `outcome` (`DELETED` when no other ACTIVE member with an account remains, `OWNERSHIP_PASSES` when they are its last
  owner, with `newOwner`, the display name of the ACTIVE member with an account who joined earliest, else `STAYS`),
  `pendingInvites` (theirs that the deletion revokes) and `splitRuleReset`; and `left`, how many family ledgers they left,
  of which it says nothing else, since a member who left reads nothing of the budget. It reads as
  `release_family_memberships` acts, which is unchanged: the personal ledger goes as a whole, so D-33's copies don't
  matter there.

**After the F6a deploy** (2026-10-02). The owner confirmed both "F6a as built" sections above, gaps 1, 2 and 4 to 9
of the F6a report: a member without an account whom nothing names is deleted; leaving revokes the member's pending
invites and removal those for the place; the debt account is an ordinary account while its member is away and a
system one again on return; the detach's reuse of a personal category with the code and type, and its `_2`; a
returned member's correction kept in step, with their older entries on the debt account changing only with the
family budget; no endpoint that makes an owner a member again ("Later" in the requirements); the deletion preview
naming only how many budgets the user left; a LEFT member's display name kept. Gap 3, the archived family ledger,
is replaced. Three decisions of the PM, built in F6b:

- **D-35** (refines D-31 and D-32): a claimed seat takes part in records from the seat's own start, the date it was
  added, not from the claim's date. The claim's date only divides what posts into the claimer's personal ledger:
  before it the opening balance, from it entries. A record moved across the claim's date, either way, or added
  before it, keeps the seat and moves its effect between the opening balance and the entries; the question the
  F6a report asked about D-31 is answered by this. A new member still takes part from the day they join.
- **D-36** (replaces gap 3): when the last member with an account leaves or is removed, the family ledger is deleted,
  as `release_family_memberships` deletes it for the last member with an account who deletes their data; there is
  no archived state. The confirmation says the budget and its records will be deleted.
- **D-37** (a return): accepting answers 409 with a code of its own while the returning member's debt account holds
  entries dated after the join date that belong to no record (the case F6a's correction leaves out). The invite page
  names them, and says they can be moved or deleted first.

F7's prerequisites gain one: the client address of IPv6 clients is settled before the switch goes on. That is F6a's
residual risk: should `app.finance-nl.com` get an AAAA record while Docker's IPv6 is off, every IPv6 client could
reach the api as the bridge's address, and share one per-address limit (D-29).

**After the OPS-1 deploy** (2026-10-02, decided by the PM):

- **D-35 clarified.** D-35's intent is that a claim changes nothing about who takes part, only where the claimer's
  postings go: before the claim's date, the opening balance; from it, entries. Its wording "the date it was added" was
  wrong: a seat without an account takes part from the budget's start date (D-18 as amended), and a claimed seat keeps
  doing so. A claimed member who leaves and returns follows D-39.
- **D-39** (with D-26): a returning member takes part from their return date only, as a new member does. Adding them to
  a record dated before it is 422 `JOINED_AFTER`. Records that already include them keep them, and their effect before
  the return goes into the correction, as built in F6a.
- **D-38** (topic G) settles F7's prerequisite about IPv6 clients: the bridge's gateway is a private address, for which
  only the per-user limit applies.
- **F6 split further.** F6b: D-35 to D-39, the switcher's placeholder right after accepting an invite or leaving, the
  members table's actions at 375 px, and OPS-1's follow-ups (a read-only database role for the deploy scripts' checks,
  `deploy.sh adopt`). F6c: the privacy policy's publication, the demo family (H1), the family report (E1, E3's check),
  H4 and the owner's remarks from the manual check. F7 stays as it is.

**F6b as built: D-35 to D-39** (2026-10-02). As the decisions above say, with these details (each to be confirmed after
the F6b review):

- **D-35, the claimed seat.** V10 adds `ledger_member.claimed_seat` (BOOLEAN NOT NULL DEFAULT FALSE, only in a SHARED
  ledger), which a trigger keeps whichever code writes the membership: false on insert, true when a seat gets its sub (a
  claim), false again when a LEFT member returns (D-39), and nothing else changes it, so the image before V10 marks its
  claims too. The fill: members with a sub whose latest used invite took their own seat; production has no family row.
  The claim's date stays the member's `join_date`, which only posting reads: records before it go into the opening
  balance, from it into entries (unchanged). `FamilyRecordService.joinedBy` lets a claimed seat take part in any record
  from the start date, as a seat without an account does: the rule's equal shares and custom shares, PERCENT, AMOUNT and
  ONE_MEMBER naming them, payer and receiver, settlement sides and `JOINED_AFTER` all follow it, and a record moved
  before the claim's date keeps them (F6a's drop is gone). Their own part of a record before the claim's date has no
  entry, so naming an account for it, or "Specify later", is 422 `PAYMENT` (D-32), for a new record, a change and a
  settlement alike; moved to the claim's date or later, their payment goes to "Specify later" as before. The equal
  re-split doesn't bring a claimed seat into a record they weren't in (a seat added after it) when it moves across the
  claim's date. `FamilyMemberView.claimedSeat` (additive) tells the interface.
- **D-36, no archived state.** V10's `delete_family_ledger(ledger)` deletes a family ledger with its invites, journal,
  links, records (shares cascade), categories and members, as `release_family_memberships` did inline, and refuses one
  with an ACTIVE member with an account, or a personal ledger; the release calls it now, otherwise unchanged.
  `FamilyMembershipService` calls it when the last member with an account leaves, after their detach, in the same
  transaction. `ledger.archived_at` (V5) stays, as D-22 wants; F6a's archive was its only writer, and nothing reads it.
  The deletion preview's `DELETED` is unchanged, and leaving now does what it says.
- **D-37.** The lookup of a returning member gains `entriesAfterReturn` (additive; null for anyone else): their own
  entries on the debt account their detach left them, dated after the join date (today), that the return doesn't attach
  again (an entry of a family kind linked to a live record dated from the join date is attached and re-posted), with
  the entry id, date, what it adds to the debt, currency and memo. Accepting answers 409 `ENTRIES_AFTER_RETURN` while one
  is left, before anything changes. Entries dated on the join date stay in the correction, as F6a counts them.
- **D-38.** `InviteRateLimit` counts per address only a public one: not private (10/8, 172.16/12, 192.168/16, fc00::/7),
  loopback, link-local, 100.64/10 (which Tomcat trusts as a proxy), unspecified, multicast, or anything that isn't an IP
  literal (never looked up). The per-user limit is unchanged. `ClientAddressUntrustedPeerTests` now shows the header
  ignored by the per-address limit's absence for its loopback peer.
- **D-39**, as built in F6a: a returning member's `join_date` is the return date and `claimed_seat` false, so they take
  part from it as a new member does (equal shares leave them out of an earlier record, and bring them in when it moves to
  the return date or later, F4c's rule); records that included them keep them (their "guests"), whose effect before the
  return goes into the correction.
- **The interface.** `expenseForm.takesPart` and `inOpeningBalance` mirror the rules; the record forms take a claimed
  seat's own record before the claim's date without an account, with a line why. The leave confirmation of the last
  member with an account says the budget and its records will be deleted ("Leave and delete the family budget"). The
  invite page lists D-37's entries, with links, and waits ("Check again"). The switcher lists a budget just joined, and
  drops one just left, before its list has loaded again (`useApi.update`; the update of a budget just left is a
  transition, committed with the router's navigation away from it, so no frame shows a placeholder). The members table
  becomes labelled blocks under 40rem, so nothing scrolls sideways at 375 px.

**After the F6b deploy** (2026-10-02). The owner confirmed "F6b as built" above, items 2 to 8 of the F6b report, as
F6b's change log entry lists them under "To confirm": "Specify later" is 422 `PAYMENT` too for a claimed seat's own
record before the claim's date; the equal re-split doesn't bring a claimed seat into a record they weren't in;
`ledger.archived_at` stays unused; D-37 lists the entries the return doesn't attach again, and leaves entries dated on
the join date to the correction; D-38's non-public addresses include 100.64/10 and multicast; `PGOPTIONS` stays beside
the role; `adopt` writes a run folder of its own. The owner also reviewed the privacy policy's draft,
[privacy-draft.md](../family-budget/privacy-draft.md) as of `7a60020`, which F6c publishes. F6b's deploy taught one rule
for deploy checklists (CLAUDE.md): a server command that asks a question stands alone in its block, its answer is typed,
never pasted, and `finish` comes only after the browser checks and the smoke test.

**F6c plan** (2026-10-02). From the requirements' E1, E3, H1 and H4 and this ADR's topics D, F and I. Where they leave a
gap, the plan decides, and the decisions are listed at its end for the owner to confirm.

- **E1, the family report** ("by category and month, with each member's contribution"; topic D: one statement over
  `family_record` and `family_share`, like `ReportService.cashFlow`). `GET /api/family-ledgers/{ledgerId}/report`,
  with optional `from` and `to` dates (inclusive; left out, no bound), read through `LedgerAccess.member` like every
  family read: ACTIVE members, owners or not, get it; a LEFT or FORMER member, an outsider and a personal ledger get
  the missing ledger's 404; with the switch off the path is unknown (404). In the family's base currency, from the
  records that aren't deleted (topic D: a deleted record keeps its row, and the report leaves it out, as the balances
  do). Its answer: the currency, the members (as the balances name them: display name, status, whether they have an
  account, `you`), one row per month (of the record's date) and category (expense and income categories, archived ones
  included) with the base amounts' total and, per member, their **share** and what they **paid** (an expense) or
  **received** (an income); and per member the period's totals: expense shares, expenses paid, income shares, incomes
  received, settlements paid and received, and the net change of their balance, with the sign of the balances. Over
  every record the nets are the balances (D-1). A member who left stays in the rows of their records, under their
  name; a FORMER one as "Former member" (D-20). New code: `ledger.family.FamilyReportService` (reads only),
  `FamilyReport`, and the endpoint in `FamilyRecordController`; no migration.
- **E3, its check.** E3 ("personal reports can separate family shares") is built since F4a parts 5 and 6: the personal
  cash flow marks each family category's line with `familyLedgerId` and `familyLedgerName`, and the dashboard shows
  those lines apart. F6c adds no endpoint for it, but its check, as a test against the records and the balances: for
  every member with an account, the personal cash flow's lines of the family's categories equal, month by month and
  category by category, that member's shares in E1's report of the months from their join date (while they use the
  family's categories only through the family budget); and E1's nets over all records equal the family balances.
- **H1, the demo family** (D-23). With the switch on, `POST /api/demo-data` loads the personal demo exactly as before,
  and then, in the same transaction and only through the services the API uses (no insert of a family row of its own):
  `FamilyLedgerService.create` makes the family budget "Demo household" in euros, starting on the demo's first day, with
  the user as its owner under their account's name, and with the demo's personal categories it brings: Groceries,
  Utilities, Travel and Other income, plus every personal category whose code and type are those of a family category
  of the user's other family budgets (the starter categories that `StarterLedger.restore` brings back beside a merged
  one). D-11's merge moves their postings and deletes the personal rows, so no twin of a family category remains (F6a's
  gap 5, topic J). `addMember` adds the invented partner "Sam" without an account (a member without an account takes
  part from the start date, D-18); `FamilyRecordService.create` and `settle` record six months of family expenses
  (paid by the user from their accounts, and by Sam), an income, one expense in US dollars with its base amount given,
  one split by percentages, and Sam's settlements to the user; the posting service posts them as for any member. The
  answer gains `familyLedgerId` (additive; left out with the switch off). With the switch off the demo is exactly as
  now. "Delete all my data" deletes the demo family with the rest (`release_family_memberships`, D-20: no other member
  with an account).
- **H4.** With the switch on, the personal expense form no longer offers "Split with family" (the old `SHARED_EXPENSE`,
  rule 7) for a new entry or for an expense that isn't one; "Family expense" (C2) takes its place. An existing shared
  expense still opens as one, shows its split and saves as before; its rows stay valid in every report (D-21). The API
  is unchanged: `POST /api/entries` still takes a `SHARED_EXPENSE`. With the switch off, nothing changes.
- **The privacy policy.** The reviewed draft's sections become the published policy's section "Family budgets"
  (`frontend/public/privacy.html`), after "What data is processed", with a line that family budgets aren't available
  yet and the section describes them ahead of time (until F7 nobody can create one), and its new "last updated" date.
  The draft's text is compared with what is built through F6b and fixed where it differs: the last member with an
  account leaving deletes the budget (D-36), a return lists the member's own entries after it (D-37), and the report
  (E1) and the demo family (H1) of F6c. The published policy's other sections change only where family budgets make
  them untrue ("No other user of the app can see it") and in "How long it is kept". `privacy-draft.md` keeps the text
  as published, so that its diff against `7a60020` is every change the owner re-reads.
- **The owner's remarks from the manual check:** none reached this task; F6c's report says so.
- **With the switch off** the app does what production does now: the report's path answers 404 like every family path,
  the demo creates no family and its answer has no new field, and the expense form keeps "Split with family". Only the
  published privacy policy changes for everyone.
- **To confirm** (the plan's own decisions): the report's shape (share and paid or received per member, settlements in
  the totals only, months of the record's date, dates rather than months as bounds); E3's check as defined above; the
  demo family's content (its name, Sam, the four categories, the records, the base amount given for the dollar expense
  so that it needs no rate); the twins merged into the demo family rather than into the family budget they come from;
  H4 for creation only, with the API unchanged; the draft's last note (questions for a legal review) kept in the draft
  and not published.

**F6c as built: the family report and the demo family** (2026-10-02). As the plan says, with these details (each to be
confirmed after the F6c review):

- **E1.** `GET /api/family-ledgers/{ledgerId}/report?from=&to=` (`FamilyRecordController`, `FamilyReportService`, one
  statement over the records and shares of the period, `REPEATABLE READ`): `FamilyReport` with `currency`, `from`,
  `to`, `members` (as the balances: id, display name, status, `hasAccount`, `you`), `rows` (`month`, `categoryId`,
  `categoryName`, `categoryType`, `archived`, `total`, and per member with a share or a payment `share` and `paid`, which
  is what they received for an income) and `totals` per member (`expenseShares`, `expensesPaid`, `incomeShares`,
  `incomesReceived`, `settlementsPaid`, `settlementsReceived`, `net`). Rows by month, expenses before incomes, then by
  category name. A row's `total` is what its payers paid, which its shares add up to. `from` after `to` is a 400.
- **E3's check** is `LedgerApiTest.checkFamilyReport`, run by the report's tests and after every operation of
  `FamilyRecordRandomTests` (with both members' personal cash flow) and `FamilyLifecycleRandomTests` (the report against
  the balances, and 404 for everyone who isn't ACTIVE). **Found:** the check's personal part holds only for a member who
  uses the family's categories through the family budget alone. A member whose personal categories merged into the
  family's (D-11) keeps their own entries in them, and the personal cash flow's line of such a category holds those
  entries and the shares together: the line is marked as the family's, but the shares aren't apart from the member's
  own purchases. The demo shows it (its groceries). Separating them would need a part of each family line that the
  shares make up (an additive field of the cash flow, and a line of the dashboard); not built, for the owner to decide.
- **H1.** `DemoFamily` (plain Java, like `DemoLedger`) plans the records; `DemoLedgerService` records them through
  `FamilyLedgerService.create` and `addMember`, and `FamilyRecordService.create` and `settle`, only while the switch is
  on. 15 records (six shops paid by the user from the current account, six water bills paid by Sam, a weekend away of
  240.00 dollars with 205.10 euros given, a party's food split 60/40 and paid by Sam, the sale of a bike received by the
  user) and Sam's 3 settlements, 26 entries in the user's ledger. The user's name is their account's (`CurrentUser`),
  "Me" without one; Sam is "Sam (partner)" if the user is called Sam. `DemoLedgerView.familyLedgerId` is left out with
  the switch off. Tests that loaded the demo with the switch on and assumed no family rows now count them
  (`DemoDataApiTests`, `UserDataApiTests`, `FamilyCategoryApiTests`, whose twin is gone); `FamilySwitchOffApiTests`
  keeps the demo's numbers of before with the switch off.

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
