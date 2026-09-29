The sections below were agreed in stage F0 and are copied here verbatim from the F1 task.

Amended after the F1 review on 2026-09-29: Background, D-7, D-8, D-12, D-14, D-17, D-18, D-19, D-20, D-21, D-22, new D-24 to D-26, stories D2 and the planned stages.

Amended after the F2a deploy on 2026-09-29: D-3 (the display name in a family ledger) and D-19 (detached links never block deleting personal entries).

## Background
The app is a personal finance tracker with double-entry bookkeeping: React SPA, Spring Boot BFF (confidential Keycloak client finance-tracker, tokens server-side), PostgreSQL with Flyway, deployed at https://app.finance-nl.com. Today every owned row belongs to one user (user_id = Keycloak sub), access to another user's object returns 404, and DataIsolationApiTests fails when an endpoint is not covered by an isolation check (since F2a; before, it failed only for an endpoint missing from a hand-kept list). OwnedRepository leaves out unscoped methods but does not stop unscoped native SQL, and ten classes use JdbcClient. There is no ledgers table today; a user's ledger is the set of rows keyed by their sub. Migration V5 introduces the ledger and membership tables. The existing SharedExpense entry kind splits an expense by user_settings.default_share_ratio and posts the partner's part to the FAMILY_DEBT liability ("Debt to family budget"); the partner is not a user.

The goal is a family budget shared by several users. Members record family expenses and incomes, see who owes whom, and record settlements, while personal ledgers stay fully private from each other.

## Decisions already taken
Do not reopen these. Flag only real conflicts with the code.

Model and access
- D-1. The family budget is a settlement mechanism between members' personal ledgers, not a separate pot of money. Family-marked expenses and incomes are split into shares. Each member's share is posted into their personal ledger, and the difference goes to that member's "Debt to family budget" liability. The members' balances in a family ledger always sum to zero, and "who owes whom" is read from those balances. A real joint bank account is out of scope, but the model must not prevent a family-owned account from becoming a payer later.
- D-2. A family budget is a ledger of type SHARED. A personal ledger is a ledger of type PERSONAL with exactly one member. Access to any ledger-scoped row is decided by active membership in that ledger, not by the row's user_id. One access model covers both types.
- D-3. A membership row holds: the ledger, a nullable user sub, a display name, a role (OWNER or MEMBER; several owners are allowed), a status (ACTIVE, LEFT, FORMER), a join date (family records dated on or after it are posted to this member) and a left date. Every family row references a membership row, never a sub; the sub appears only in the membership table. A member without an account is a membership row with a null sub. Claiming that seat through an invite sets the sub. Deleting a user's data nulls the sub and replaces the name with "Former member". In a family ledger, the display name the other members see is chosen on the invite acceptance screen; it is prefilled from the account's name and can be edited.
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
  - Any member can add a family category, including while entering a record. Only owners rename or archive.
  - A family category can be deleted only if no entry in any member's ledger or in the family ledger uses it; otherwise it can only be archived.
  - Balance-sheet accounts are never shared.

Split rule
- D-12. Each family ledger has a default split rule: equal shares, or custom percentages summing to 100. It applies to expenses and incomes, and only to new records. A record can override it with percentages, with amounts, or "entirely on one member". Amounts are rounded to the currency's minor unit. The remainder goes to the member with the largest share; on a tie, to the payer (the recipient for income), then by join order. So the shares always sum to the record amount.

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
  - The last owner cannot leave while other members with accounts remain. A family ledger with no members with accounts left is archived.
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
- H4 Old SharedExpense entries remain valid; the UI stops creating them.

## Planned stages
- F1: analysis and ADR 0003 (done).
- F2a: migration V5, stronger isolation tests, these documents; deployed on its own.
- F2b: LedgerScope and LedgerAccess, every query scoped by ledger_id, an architecture test; deployed on its own.
- F3: family ledgers, members without accounts, split rule, family categories, ledger switcher, the feature switch (D-25), and the membership part of D-20.
- F4a: family records, shares, the posting service, expenses in the base currency, balances, read-only posted rows, the change journal; UNALLOCATED becomes a system account.
- F4b: marking personal entries as family, incomes, other currencies, settlements (D-24), payment edits.
- F5: invites, seat claiming, returning members (D-26). The privacy policy text is ready before F5 starts.
- F6: leaving, removal and detach; delete-my-data for family data; the published privacy policy; the demo family; family reports and the personal-report filter; removing old SharedExpense creation from the UI.
- F7: the switch goes on in production, followed by a check with two real accounts.
- After F7: the Excel import, with Family rows going into a family ledger.
