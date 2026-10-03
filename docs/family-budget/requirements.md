The sections below were agreed in stage F0 and are copied here verbatim from the F1 task.

Amended after the F1 review on 2026-09-29: Background, D-7, D-8, D-12, D-14, D-17, D-18, D-19, D-20, D-21, D-22, new D-24 to D-26, stories D2 and the planned stages.

Amended after the F2a deploy on 2026-09-29: D-3 (the display name in a family ledger) and D-19 (detached links never block deleting personal entries).

Amended after the F2b review on 2026-09-29: the planned stages (F3 split into F3a and F3b; family categories in personal ledgers move to F4a) and a new section "Later". For F3a: D-12 (custom shares stored as basis points).

Amended after the F3a review on 2026-09-29: D-3 (a member with an account changes their own display name).

Amended after the F3b deploy on 2026-09-30: new D-27 (the start date), the planned stages (F4 split into F4a, F4b and F4c) and "Later".

Amended after the F4a review on 2026-09-30: D-12 (how shares are rounded) and D-18 (who shares records of which dates).

Amended after the review of F4a's parts 5 and 6 on 2026-09-30: the planned stages (F6) and "Later".

Amended after the F4b review on 2026-09-30: the planned stages (F4c split into F4c, F4d and F4e) and "Later".

Amended after the F4d review on 2026-10-01: new D-28 (the settlement lock) and "Later".

Amended after the F4e review on 2026-10-01: D-11 (categories of a joining member) and the planned stages (F5 and F6).

Amended after the F5 deploy on 2026-10-01: new D-29 to D-34 (decided by the PM), the planned stages (F6 split into F6a and F6b) and "Later".

Amended after the F6a deploy on 2026-10-02: new D-35 to D-37 (decided by the PM), D-19 (the archived family ledger, replaced by D-36), the planned stages (F6b's list, F7's prerequisite) and "Later".

Amended after the OPS-1 deploy on 2026-10-02: D-35 clarified, new D-38 and D-39 (each decided by the PM), the planned stages (F6 split into F6b and F6c; F7's prerequisite settled by D-38) and "Later".

Amended after the F6b deploy on 2026-10-02: the planned stages (F6b confirmed as built, the privacy policy's draft reviewed, F6c's plan).

Amended after the F7 deploy on 2026-10-02: new D-41 (decided by the PM) and the planned stages (F7b).

Amended after F7b's refused deploy on 2026-10-03: D-41 extended (decided by the PM): `switch on` refuses while a page fails.

Amended after the F6c deploy on 2026-10-02: new D-40 (decided by the PM), the planned stages (F6c confirmed as built, F7's way of switching and its check in production).

## Background
The app is a personal finance tracker with double-entry bookkeeping: React SPA, Spring Boot BFF (confidential Keycloak client finance-tracker, tokens server-side), PostgreSQL with Flyway, deployed at https://app.finance-nl.com. Today every owned row belongs to one user (user_id = Keycloak sub), access to another user's object returns 404, and DataIsolationApiTests fails when an endpoint is not covered by an isolation check (since F2a; before, it failed only for an endpoint missing from a hand-kept list). OwnedRepository leaves out unscoped methods but does not stop unscoped native SQL, and ten classes use JdbcClient. There is no ledgers table today; a user's ledger is the set of rows keyed by their sub. Migration V5 introduces the ledger and membership tables. The existing SharedExpense entry kind splits an expense by user_settings.default_share_ratio and posts the partner's part to the FAMILY_DEBT liability ("Debt to family budget"); the partner is not a user.

The goal is a family budget shared by several users. Members record family expenses and incomes, see who owes whom, and record settlements, while personal ledgers stay fully private from each other.

## Decisions already taken
Do not reopen these. Flag only real conflicts with the code.

Model and access
- D-1. The family budget is a settlement mechanism between members' personal ledgers, not a separate pot of money. Family-marked expenses and incomes are split into shares. Each member's share is posted into their personal ledger, and the difference goes to that member's "Debt to family budget" liability. The members' balances in a family ledger always sum to zero, and "who owes whom" is read from those balances. A real joint bank account is out of scope, but the model must not prevent a family-owned account from becoming a payer later.
- D-2. A family budget is a ledger of type SHARED. A personal ledger is a ledger of type PERSONAL with exactly one member. Access to any ledger-scoped row is decided by active membership in that ledger, not by the row's user_id. One access model covers both types.
- D-3. A membership row holds: the ledger, a nullable user sub, a display name, a role (OWNER or MEMBER; several owners are allowed), a status (ACTIVE, LEFT, FORMER), a join date (family records dated on or after it are posted to this member) and a left date. Every family row references a membership row, never a sub; the sub appears only in the membership table. A member without an account is a membership row with a null sub. Claiming that seat through an invite sets the sub. Deleting a user's data nulls the sub and replaces the name with "Former member". In a family ledger, the display name the other members see is chosen on the invite acceptance screen; it is prefilled from the account's name and can be edited. Afterwards, a member with an account changes their own display name at any time; owners rename only members without an account.
- D-4. Invariants: each user has exactly one personal ledger; a personal ledger has exactly one member and cannot receive members or invites; a sub appears at most once per ledger. Enforce them in the service layer and in the database.
- D-5. A user can belong to several family ledgers from the start. The UI has a ledger switcher. When a personal entry is marked as family, a family-ledger selector appears only if the user has more than one.

Posting
- D-6. The family record is the source of truth. It holds: type (expense or income), date, family category, payer or recipient member, original amount and currency, amount in the family base currency, share amount per member, family comment, author, and last editor with time. Share amounts are stored, not recomputed from the rule.
- D-7. Posting is stored, not computed at read time. A single posting service writes each member's share into their personal ledger: every member with an account whose join date is not after the record date, the payer included. The payer's own payment ("card to family budget") is an ordinary personal entry of the payer, linked to the family record. Worked example, 50/50, in the base currency:
  - Expense: the payer pays 100 by card. Payer: card −100, share expense 50, debt to family −50. Partner: share expense 50, debt to family +50.
  - Income: a member receives 1000, half of which belongs to the partner. Recipient: card +1000, income 500, debt to family +500. Partner: income 500, debt to family −500.
  - The debt figures in this example are displayed balances. Postings on the liability carry the opposite sign.
- D-8. The posting service is the only code path allowed to write into another user's personal ledger, and it may write only system rows:
  - share entries;
  - entries on the per-family "Debt to family budget" account;
  - an opening-balance entry at the join date;
  - entries on the system account "Payments without a specified account";
  - postings on the member's own UNALLOCATED account, which carries the family category of a share. It finds UNALLOCATED by code. From F4a, UNALLOCATED is a system account: it can be renamed but not archived or deleted.
  The cross-ledger writer also performs the detach operation of D-19 and the corrective entry of D-26. It never touches another user's cards, other accounts or personal categories. Posted rows are read-only in the personal UI and API and change only through the family record. This exception to OwnedRepository must be explicit, narrow and covered by its own isolation tests.
- D-9. The link between a personal entry and a family record is stored separately and has a type: a payment for a family record, or a settlement. A contribution to a joint account may be added as a third type later.
- D-10. A member's "Debt to family budget" balance in their personal ledger always equals their balance in the family ledger, counted from the join date and including the opening-balance entry.

Categories
- D-11. Each family ledger has its own category dictionary. A family category is a single object shown in every member's personal category list; there are no synchronised copies. Members may also use a family category for their purely personal entries.
  - At creation, the creator chooses which of their categories form the initial dictionary.
  - When a member joins, their categories with the same code merge into the family ones automatically. On a name conflict, the joining member picks the name on the acceptance screen. They may bring more of their categories; the rest stay private.
  - Amended after the F4e review (decided by the owner on 2026-10-01; replaces the bullet above): when a joining member has a personal category with a family category's code, it merges into the family category and the family category's name stays. Renaming a family category stays an owner's right (D-15). The acceptance screen shows each such merge in advance. The joining member may also bring other personal categories into the family dictionary; the rest stay private.
  - Any member can add a family category, including while entering a record. Only owners rename or archive.
  - A family category can be deleted only if no entry in any member's ledger or in the family ledger uses it; otherwise it can only be archived.
  - Balance-sheet accounts are never shared.

Split rule
- D-12. Each family ledger has a default split rule: equal shares, or custom percentages summing to 100. The custom percentages are stored and sent by the API as integer basis points summing to exactly 10000; the interface shows and accepts percentages with two decimals and converts them with integer arithmetic. It applies to expenses and incomes, and only to new records. A record can override it with percentages, with amounts, or "entirely on one member". Amounts are rounded to the currency's minor unit. The remainder goes to the member with the largest share; on a tie, to the payer (the recipient for income), then by join order. So the shares always sum to the record amount. As built in F4a and kept after its review: each share is cut down to the minor unit, and the remainder, never negative, goes as above; 10.01 split 50/50 gives the payer 5.01. HALF_UP stays the rule for single amounts, such as conversions.

Currency
- D-13. A family ledger has a base currency that cannot change after its first record. Shares, balances and debts are kept in the base currency and fixed at entry. A record keeps the original amount and currency plus the base amount.
  - If the payment card is in the base currency, the base amount is the actual card charge.
  - Otherwise the base amount defaults to the ECB rate for the record date and can be edited.
  - Cross-currency personal entries go through FX_EXCHANGE as they do today.
  - Settlements follow the same rules.

Roles and editing
- D-14. Editing and deleting records:
  - Payment fields (amount, currency, date, payer) of a record paid by a member with an account can be edited only by that payer. The linked personal entry changes in the same transaction.
  - For a record paid by a member without an account, the author and the owners may edit every field.
  - Family fields (category, shares, comment) can be edited by the author and by any owner.
  - A member with an account can name only themselves as payer; any member can name a member without an account as payer.
  - When a member with an account enters a family expense in the family ledger as the payer, they choose the paying account, with the last used one preselected. They may instead choose "Specify later", which posts the payment to "Payments without a specified account".
  - The payer deletes a record, together with its shares and the linked payment. Deleting the linked personal entry deletes the family record after a warning. Records paid by a member without an account are deleted by their author or by an owner.
- D-15. Owners manage members and invites, the split rule, and the renaming and archiving of family categories. Members add records, edit their own, and add family categories.
- D-16. The MVP includes a change journal. For every family record it stores who changed which fields, when, and the old and new values; it is visible to all members. A posted share shows who changed it last and when. Changes to the private side of a payment (for example, which card was used) are not journaled in the family.

Invites
- D-17. Invites are by link only.
  - The token has at least 128 random bits; only its hash is stored. It is single-use, expires after 72 hours by default (the owner can choose up to 7 days) and can be revoked.
  - An invite carries the ledger and, optionally, the seat of a member without an account to claim, with that seat's join date (D-18).
  - Invalid, expired, revoked and used tokens get the same response.
  - Invite details are shown only after sign-in.
  - The acceptance screen explains that shares will be posted into the user's personal ledger from the join date and that other members will see their display name; it also handles category matching (D-11).
  - There is no owner confirmation after acceptance; owners see who accepted and when.
  - The invite link carries the token in the URL fragment (/invite#token), so the token never reaches server logs. The SPA keeps the token in localStorage together with its expiry across sign-in and registration. localStorage is shared by all tabs, including the one opened by the verification email. The SPA erases the token after any outcome (accepted, declined, invalid or expired) and sends it to the API only in request bodies. Token lookup is rate limited in memory in the API. Declining an invite consumes the token.
- D-18. Claiming a seat:
  - Family records dated on or after the join date are posted to the new member, including records entered before they joined.
  - Their balance before the join date arrives as one opening-balance entry dated on the join date.
  - Their payments from the join date, recorded while they had no account, are posted against "Payments without a specified account"; they can reassign these to their own accounts.
  - The join date cannot be changed after joining.
  - A user who is already in the ledger cannot claim another seat. Members are never merged.
  - A join date in the past is allowed only when claiming a seat. For a new member, the join date is the acceptance date. Existing records are never re-split.
  - Who shares records of which dates (after the F4a review): members without an account share records of any date on or after the family ledger's start date (D-27); members with an account share from their join date.

Leaving and deletion
- D-19. Leaving (or being removed) with a non-zero balance is allowed after a warning.
  - The membership becomes LEFT and access to the family ledger ends. Nothing more is posted to that user; everything already posted stays.
  - Leaving or being removed detaches the member:
    - the family categories used in their postings are copied into their personal category list, and their postings are re-pointed to the copies;
    - their links to family records are marked detached;
    - posted entries become ordinary, editable personal entries;
    - their "Debt to family budget" balance stays in their ledger.
  - The detached link rows stay as the family's history, marked detached, but they never block deleting the member's personal entries, one at a time or through "Delete all my data" (D-20). Either the link's reference to the personal entry is set to null when the entry is deleted, or the link holds no foreign key to it. Implemented in F4a.
  - When an owner removes a member, the detach runs through the cross-ledger writer (D-8). After the detach, no row of a LEFT member references the family ledger.
  - LEFT members never count as "members with an account" in D-19 or D-20.
  - Records where a LEFT or FORMER member has a share or is the payer are frozen.
  - A settlement after leaving is recorded by each side in their own ledger.
  - The last owner cannot leave while other members with accounts remain. A family ledger with no members with accounts left is deleted (D-36, which replaces the archived state of F6a).
- D-20. "Delete all my data":
  - The personal ledger is deleted entirely as today, including posted shares and payments.
  - In each family ledger the membership becomes FORMER ("Former member", null sub). Amounts, dates, categories and shares stay, and the affected records are frozen. The user's comments are erased, including their comment text in the change journal's old and new values; their name in the journal is replaced, and their invites are revoked.
  - If the user was the last owner, ownership passes to the member with an account who joined earliest. If no member with an account remains, the family ledger is deleted.
  - The confirmation screen lists the affected family ledgers and their balances.
  - The privacy policy will state what other members see, and that records affecting other members' balances are kept without the name and comments.

Legacy and rollout
- D-21. The existing SharedExpense kind, user_settings.shared_account_id and default_share_ratio, and the FAMILY_DEBT account stay valid, and existing entries keep working in reports. Creating new entries of that kind from the UI is removed once family posting ships. A converter for old FAMILY_DEBT entries is out of scope. In the starter seed, FAMILY_DEBT is named "Family budget"; it stays separate from every family ledger's debt account. The MVP has no transfer mechanism: production holds no real entries, and the real import will go straight into the new model. The Excel import is postponed until family posting ships; it will then import rows with Family = "да" directly into a family ledger, with the partner as a member without an account.
- D-22. Migrations are additive in every stage: no drops or renames of existing columns or tables. Relaxing a NOT NULL constraint counts as compatible, because nothing is dropped and the previous image keeps working. Removing what becomes redundant is a separate, later migration. The access refactor (F2) ships as its own production deploys (F2a, then F2b) with no visible behaviour change, so rollback means reverting the application image. A ledger's type never changes, and the database enforces this.
- D-23. The demo data generator will create a family ledger with a fictional partner without an account.

Added after the F1 review (2026-09-29)
- D-24. Settlements: the side whose paying account is known records the settlement with that account. The other side's part is posted to that member's "Payments without a specified account" for them to reassign, so D-10 holds immediately.
- D-25. Feature switch: family features sit behind a configuration switch that stays off in production until F7. While it is off, the family endpoints answer 404 and the family pages are hidden. Tests run with the switch on. Stages merge into main as they are ready, and their migrations may reach production early.
- D-26. Returning members: inviting a LEFT member again reactivates the same membership, with the acceptance date as the join date. If their personal debt balance differs from their family balance, one corrective entry dated on the join date fixes the difference; the acceptance screen shows it. Categories are matched by code again, as at the first join.

Added after the F3b deploy (2026-09-30)
- D-27. The start date: a family ledger has a start date, chosen at creation (today by default, earlier allowed, never in the future). It is also the creator's join date. A record dated before it answers 409 naming the rule. Family ledgers created before F4a get their creation date as start date. The create endpoint takes the start date as an optional additive field.

Added after the F4d review (2026-10-01)
- D-28. The settlement lock (replaces F4d's behaviour of moving the other side's part back to its placeholder): nobody's action ever changes another member's accounts (D-8).
  - Once the other side of a settlement, a member with an account, has put its part on one of its accounts, the settlement's date and amount can no longer be changed, and the settlement can no longer be deleted. Both answer 409, naming that member and saying that they can move their part back to "Specify later" to allow it.
  - While the other side's part waits under "Specify later", the recorder changes and deletes the settlement as before.
  - Nothing changes for members without an account: a side without an account has no part to put anywhere.

Added after the F5 deploy (2026-10-01)
- D-29. Invites a user can't use (decided by the PM; with D-17 and D-18). A valid token that the signed-in user can't use gets a 409 of its own, not D-17's one answer for a token that lets nobody in: the user is an ACTIVE member already (the ledger's creator included), a member who left (until returning members, D-26, make that the way back), or the invite claims a seat another invite has taken meanwhile. Declining answers the same, so that a member who opens a link meant for someone else can't use it up. Lookup, accept and decline together are rate limited per user and per client address, at most 10 attempts a minute and 50 an hour each. The per-address limit holds only once it is shown that the client address the api sees in production is the browser's real one, not a proxy's and not one the client picks.
- D-30. A joining member's category of the other type (decided by the PM). A personal category with a family category's code but the other type stays private beside the family category. Bringing it into the family is 422 `CATEGORY`, and the acceptance screen lists it as staying private.
- D-31. Records before a claimed member's join date (decided by the PM). They stay editable by whoever D-14 lets edit them. Every re-post keeps the opening balances of the members who joined after the start date in step with them, so D-10 holds.
- D-32. A member who took a seat, in the records of their time without an account (decided by the PM). They stay in that seat's records dated before their join date, as payer, receiver or with a share. The payment fields of those records are theirs (D-14); naming an account for one of them is 422 `PAYMENT`, since its payment is in their opening balance, not in an entry.
- D-33. Merged categories and leaving (decided by the PM). The personal categories that merge into family categories, at creation and when a member joins, are deleted, not archived. So leaving or being removed (D-19) can't unarchive them: for every family category that the leaving member's personal ledger refers to, the detach creates a personal category with the same code and type and the family category's current name, and moves those references to it.
- D-34. A claim's opening balance on the invite (decided by the PM). Before accepting a claim, the invite shows the seat's opening balance, the balance before the join date, as an amount in the family budget's currency, since the person who accepts takes it on. The lookup, the invite page and the "Invite links" section of the privacy policy's draft list exactly the same things.

## MVP user stories
- A1 Create a family budget with a name and a base currency.
- A2 Set a default split rule (equal or custom percentages).
- A3 Belong to several family budgets; switch between ledgers.
- A4 Family category dictionary as in D-11.
- B1 Add a member without an account by name.
- B2 Create a single-use, expiring invite link with a join date, optionally for a seat.
- B3 Open the link, sign in or register, accept or decline; claim a seat with its history as in D-18.
- B4 See unused and accepted invites; revoke unused ones.
- B5 Leave a budget; an owner removes a member; history stays (D-19).
- C1 Add a family expense in the family ledger with payer and split (rule or manual).
- C2 Mark a personal entry as family; shares are posted to all members.
- C3 See all family records with author and last change; edit according to D-14; change journal.
- C4 Other members never see my accounts, personal categories or personal ledger.
- C5 Family income, split like an expense.
- C6 My share of other members' family records appears in my personal ledger, read-only.
- C7 Records in a currency other than the base currency, as in D-13.
- D1 See who owes whom.
- D2 Record a settlement as in D-24 and reflect it in the personal ledger.
- E1 Family report by category and month, with each member's contribution.
- E3 Personal reports can separate family shares.
- H1 Demo data creates a family with a fictional partner without an account.
- H2 "Delete all my data" handles family data as in D-20; the privacy policy is updated.

Added after the F6a deploy (2026-10-02), to be built in F6b
- D-35. A claimed seat's records (decided by the PM; refines D-31 and D-32). A claimed seat takes part in records from the seat's own start, the date it was added, not from the claim's date. The claim's date only divides what posts into the claimer's personal ledger: before it, the opening balance; from it, entries. Moving a record across the claim's date, either way, or adding one dated before it, keeps the seat in the record and moves its effect between the opening balance and the entries. A new member (not a claim) still takes part from the day they join. Reason: the owner's Excel import puts years of records into a budget with a partner without an account; if the partner claims the seat later, records found or corrected afterwards must still include them.
- D-36. The last member with an account leaving (decided by the PM; replaces F6a's gap 3, the archived family ledger). When the last member with an account leaves, the family budget is deleted, as D-20 deletes it when the last member with an account deletes their data. There is no archived state, since nobody could read it or invite into it. The confirmation says the budget and its records will be deleted.
- D-37. A return with entries of its own on the debt account (decided by the PM). Accepting answers 409 with its own code while the returning member's debt account holds entries dated after the join date that belong to no record. The invite page says which entries, and that they can be moved or deleted first. Otherwise those entries would break D-10 from their date, and gap 6 of the F6a report (a returned member's older entries on the debt account change only through the family budget) would make them uneditable.
- H4 Old SharedExpense entries remain valid; the UI stops creating them.

Added after the OPS-1 deploy (2026-10-02), to be built in F6b
- D-35 clarified (decided by the PM). D-35's intent is that a claim changes nothing about who takes part, only where the claimer's postings go: before the claim's date, the opening balance; from it, entries. Its wording "the date it was added" was wrong: a seat without an account takes part from the budget's start date (D-18 as amended), and a claimed seat keeps doing so. A claimed member who leaves and returns follows D-39.
- D-38 (with D-29; decided by the PM). The per-address limit applies only to public addresses. A private, loopback or link-local address after Tomcat's resolution means the real client is unknown: IPv6 clients arrive through docker-proxy as the `edge` bridge's gateway. For such an address only the per-user limit applies. This settles F7's prerequisite about IPv6 clients; IPv6 in Docker goes to "Later", for a maintenance window.
- D-39 (with D-26; decided by the PM). A returning member takes part from their return date only, as a new member does. Adding them to a record dated before it is 422 `JOINED_AFTER`. Records that already include them keep them, and their effect before the return goes into the correction, as built.

Added after the F6c deploy (2026-10-02), decided by the PM
- D-40 (E3). A member's personal cash-flow line for a family category shows the whole category: their own entries in it plus their shares. E3's check compares that line with both together. Showing the family part separately goes to "Later".

Added after the F7 deploy (2026-10-02), decided by the PM
- D-41 (with D-25). Family budgets don't stay switched on while the published privacy policy is unreachable in production. The way back is `deploy.sh switch off`; switching on again waits for a deploy whose `finish` shows the policy. Extended after F7b's refused deploy (2026-10-03, decided by the PM): family budgets aren't switched on while a page of the deploy scripts' list fails; `deploy.sh switch on` checks the pages in its preflight and refuses, changing nothing, on any failure. `switch off` checks them too and reports a failure, but never refuses because of it, since the way back must always work.

## Planned stages
- F1: analysis and ADR 0003 (done).
- F2a: migration V5, stronger isolation tests, these documents; deployed on its own.
- F2b: LedgerScope and LedgerAccess, every query scoped by ledger_id, an architecture test; deployed on its own.
- F3a: migration V6 and the backend: family ledgers, members without accounts, split rule, family categories in the family ledger, the feature switch (D-25), and the membership part of D-20.
- F3b: the interface: the ledger switcher and the family pages.
- F4a: the backend of family expense records and posting: family records, shares, the posting service, expenses in the base currency, balances, read-only posted rows, the change journal, the start date (D-27); UNALLOCATED becomes a system account. Also family categories in personal ledgers: merging the creator's chosen categories into the family ones at creation, the posting trigger's exception for family categories, and family categories in personal category lists and reports. Until then a family category lives only in its family ledger.
- F4b: the interface of family expense records.
- F4c: the payer's side, backend and interface: edits of the payment fields (D-14), moving a payment to an account, the payer's own payment entry through the personal endpoints, and marking a new personal expense as family (C2). Until F4c, a wrong payment field is fixed by deleting the record and entering it again.
- F4d: settlements (D2, D-24) and family incomes (C5).
- F4e: other currencies (C7, D-13).
- F5: invites, seat claiming, returning members (D-26). The privacy policy text is ready before F5 starts.
- F6: leaving, removal and detach; delete-my-data for family data; the published privacy policy; the demo family; family reports and the personal-report filter; removing old SharedExpense creation from the UI. With the demo family, revisit the demo's personal twin of a starter category merged into a family budget (accepted after the review of F4a's parts 5 and 6).
- After the F4e review (2026-10-01): F5 is invites and taking a seat (D-17, D-18). Returning members (D-26) and making another member an owner move to F6, next to leaving and removal. The privacy policy's draft is docs/family-budget/privacy-draft.md, which F6 publishes.
- After the F5 deploy (2026-10-01, decided by the PM): F6 is split in two. F6a is the membership's lifecycle (leaving, removal and detach, the rule back to equal shares, returning members, making another member an owner) and what "Delete all my data" shows of the family budgets. F6b is the privacy policy's publication, the demo family (H1), the family report (E1, and E3's check), H4, the owner's remarks on the interface, and the switcher's placeholder right after accepting an invite.
- After the F6a deploy (2026-10-02, decided by the PM): the owner confirmed F6a as built (ADR 0003 topic J), gaps 1, 2 and 4 to 9 of the F6a report; gap 3 is replaced by D-36. F6b, in this order: D-35 to D-37; the switcher's placeholder right after accepting an invite or leaving; the members table's actions at 375 px; then the privacy policy's publication, the demo family (H1), the family report (E1, and E3's check) and H4.
- After the OPS-1 deploy (2026-10-02, decided by the PM): F6 is split further. F6b: D-35 to D-39, the switcher's placeholder right after accepting an invite or leaving, the members table's actions at 375 px, and OPS-1's follow-ups (a read-only database role for the deploy scripts' checks, `deploy.sh adopt`). F6c, next: the privacy policy's publication, the demo family (H1), the family report (E1, and E3's check), H4 and the owner's remarks from the manual check.
- After the F6b deploy (2026-10-02): the owner confirmed F6b as built (ADR 0003 topic J, "F6b as built"), items 2 to 8 of the F6b report, and reviewed the privacy policy's draft (docs/family-budget/privacy-draft.md as of `7a60020`), which F6c publishes. F6c's plan, with the gaps it decides, is ADR 0003 topic J's "F6c plan".
- After the F6c deploy (2026-10-02, decided by the PM): the owner confirmed F6c as built (ADR 0003 topic J, "F6c as built"): the report's shape, the demo family's content, the twins merged into the demo family rather than into the family budget they come from, H4 for creation only with the API unchanged, the legal questions kept out of the published policy, and the mapping of F6b's items (D-35 to D-39) onto what was built. F7's way of switching: a command, `deploy.sh switch on|off`, never a hand edit of `.env`; the way back is `switch off`, which leaves the data and makes the family endpoints answer 404 again. F7's check in production: done with the owner's two test accounts, never his own account, whose personal ledger is kept for his Excel import; it ends with the family budget deleted (D-36) and "Delete all my data" for both test accounts.
- F7: the switch goes on in production, followed by a check with two real accounts.
  - Prerequisite: the client address of IPv6 clients (F6a's residual risk: with an AAAA record for app.finance-nl.com and Docker's IPv6 off, every IPv6 client could reach the api as one address) is settled before the switch goes on, so that D-29's per-address limit counts the browser's real address for them too. Settled by D-38 (2026-10-02): such an address is the bridge's gateway, a private one, for which only the per-user limit applies.
- After the F7 deploy (2026-10-02, decided by the PM): F7's deploy passed every check of `deploy.sh run`, but the privacy policy answered 403 (the deploy scripts' `umask 077` wrote the changed `privacy.html` with mode 600, which the web image kept and nginx couldn't read); `finish` recorded the browser checks as passed by mistake, the switch went on, and by D-41 it was switched off again. F7b, a stage of its own: the web image readable whatever the checkout's modes, with CI building it from a copy with the server's modes; page checks in the deploy scripts (`run`, `verify`, `finish`, `rollback.sh`), and `finish` recording the browser checks as failed, without asking, when a page fails; git commands that write the working tree under `umask 022`, everything else under 077; `switch`'s text. Its checks are `deploy/checks/F7b.sql`. F7's production check moves to F7b's checklist, after its `finish` and `switch on`. F7's report claimed that the `edge` network's subnet check proves D-29's per-address limit counts real clients; it doesn't (with Docker's IPv6 off, IPv6 clients arrive as the bridge's gateway, D-38's case), and the check is dropped from the checklists.
- After F7: the Excel import, with Family rows going into a family ledger.

## Later
Found along the way; not part of a stage yet.
- Row buttons in category lists. The entry form's .actions flex rule also applies to td.actions, so the personal and family category lists stack their row buttons vertically. It predates F3b.
- Sequential scans on posting. F2b's EXPLAIN showed the balance, integrity and rates statements reading all postings and hash-joining them with the ledger's entries, since posting has no ledger column; the same statements filtered by user_id had the same plan before F2b. Revisit after the Excel import, or when demo ledgers from open registration pile up. The options: a ledger_id on posting, checked by a trigger against its entry's; or statements that start from the ledger's entries and reach the postings through their index.
- Minimal transfers (called D3 in the F4b review). "Who owes whom" pairs the largest debtor with the largest creditor, then the next, as a display only; the model fixes each member's balance, not the transfers. The fewest transfers that settle every balance stay for later.
- The family paths (after the F4d review). The activity's paths `/family/{id}/expenses…` hold expenses, incomes and settlements; rename them to `/family/{id}/activity…`, with redirects from the old paths, which personal entries and the journal link to.
- The personal entry list on a phone. At 375 px it is wider than the screen, with or without family rows: 573 px for a month of the demo without them, 664 px with them (found in F4a's walk-through).
- Invites for a taken seat (after the F5 deploy). When an invite takes a seat, the other pending invites for that seat stay pending until they expire; accepting one answers 409 (D-29). Revoking them when the seat is taken stays for later.
- A long invite history. The owners' invite list keeps every invite, newest first; folding the old ones away stays for later.
- Making an owner a member again (gap 7 of the F6a report). Today an owner stops being one only by leaving or being removed; an endpoint that makes an owner a member again stays for later.
- One address for a household. The per-address rate limit of invites (D-29) counts every device behind one NAT together, such as a household's router.
- IPv6 in Docker (after the OPS-1 deploy, D-38). With Docker's IPv6 on for the `edge` network, IPv6 clients would reach Caddy with their own address, and the per-address limit would count them too. It changes the auth server's host, so it waits for a maintenance window.
- Separating a member's own entries from their shares in a family category's personal cash-flow line (after the F6c deploy, D-40).
