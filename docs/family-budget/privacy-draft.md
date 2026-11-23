# Privacy policy: family budgets

> Reviewed by the owner on 2026-10-02 (the draft as of `7a60020`), and published in F6c as the
> section "Family budgets" of the policy at `/privacy` (`frontend/public/privacy.html`), after "What
> data is processed". This file keeps the section's text as published, word for word
> (`frontend/src/privacy.test.ts` compares them), so its history shows every change since the review.

## Family budgets

A family budget is a budget you keep with other people, such as a partner or your children. You can
belong to several. Each member keeps their own personal budget, which stays private. What the family
spends and earns together is recorded in the family budget, and each member's share of it appears in
their personal budget.

## What other members of a family budget see

Everyone who is a member of a family budget sees, for that budget:

- every member's display name: the name you choose when you join, which you can change at any time,
  and the names given to members without an account;
- the family records: expenses, incomes and settlements between members, with their dates, amounts
  (and the original amount and currency, with the exchange rate used), who paid or received them, and
  each member's share;
- the family categories, such as "Groceries";
- the family comments written on records;
- each member's balance in the family budget, and who owes whom;
- the family report: the family's expenses and incomes by month and category, with each member's
  share and what they paid or received;
- the change journal: who added, changed or deleted which record, when, and what the values were
  before and after;
- where a member paid (the payee) for the family expenses and incomes they paid or received, only if
  that member chooses to show it in the budget's settings.

## What other members never see

Other members never see anything of your personal budget:

- your accounts, such as which card you paid with, and their balances;
- your personal categories;
- your personal entries, including the entries a family budget adds to your personal budget;
- your private notes on your own payments;
- where you paid (the payee), unless you choose to show it in the budget's settings;
- your email address and your account id.

## Invite links

An owner of a family budget invites people with a link. Whoever holds a link can use it once, and
only before it expires (after 72 hours, unless the owner chose a shorter or longer time of up to 7
days). The owner can revoke a link before it is used. Keep a link you receive to yourself.

Before you accept or decline, the invitation shows you:

- the family budget's name and currency;
- the display name of the owner who invites you;
- whether you join as a new member or take the place of a member without an account, and that
  member's name;
- the date from which the family budget's records are added to your personal budget, and until when
  the link works;
- for a place, its balance in the family budget before that date, which becomes your opening
  balance;
- if you were a member before and come back, the one correction that brings what your personal budget
  shows as your balance with the family budget in line with the family budget's;
- the family budget's categories;
- which of your own categories will merge into the family budget's, which stay private, and which you
  may bring along;
- if you come back, the entries of your own on your earlier balance with the family budget that are
  dated after the day you come back, with their dates, amounts and notes: you move or delete them
  before you accept;
- the name of your account, as a suggestion for the name the other members will see.

Nothing else about the family budget or its members is shown before you accept: no member's name
but the owner's and the place's, and no amount of the family budget's but that opening balance or
correction.

The secret part of the link is never sent to our server with the address of a page, so it doesn't
appear in server logs. We store only a hash of it, from which the link can't be rebuilt. To show you
the invitation, you have to sign in first.

When you accept, the owners see your display name and when you accepted. When you decline, they see
only that the invite was declined, and when; nothing about you is stored.

## Members without an account

A family budget can include people who don't use Finance Tracker, such as a child. Their names are
entered by the budget's owners, and other members record expenses, incomes and settlements for them.
If you enter someone's name, enter only what they would be happy for the other members to see: a
first name or a nickname is enough.

Such a person can later be invited to take their place in the budget. From the date chosen in the
invite, their shares then appear in their own personal budget, and their balance before that date
arrives as one opening balance. They keep their place in the records of before that date, which
can still be changed, and such a change moves their opening balance.

## Leaving, removal and "Delete all my data"

When you leave a family budget, or an owner removes you:

- you no longer see the family budget, and nothing more is added to your personal budget;
- what was already added stays in your personal budget as your own entries, which you can change or
  delete; the family categories they use become personal categories of yours, with the names they
  have at that moment; your balance with the family budget stays in your personal budget as an
  account of yours;
- the family records stay in the family budget, because they make up the other members' balances.
  The other members keep seeing your display name in them, marked as a member who left, and the
  records that involve you can no longer be changed;
- if the family budget split its expenses by fixed percentages and you had a share, it goes back to
  equal shares, and its change journal says that this happened when you left;
- invite links you created that weren't used yet stop working.

The last owner of a family budget hands ownership to another member first. When the last member with
an account leaves, the family budget is deleted, with its records; the confirmation says so before.

If an owner invites you again, you come back in your earlier place, from the day you accept. The
family budget's categories you use become the family's again, and one correction on that day brings
your balance with the family budget in your personal budget in line with the family budget's: for
example, if you changed or deleted entries it had added, or records of before you left changed while
you were away. The invitation shows the correction before you accept. Entries of your own on that
balance dated after the day you come back aren't part of the family budget: the invitation lists them,
and you move or delete them first.

Before you confirm "Delete all my data", the screen lists every family budget you are in: your role
and balance there, and what will happen to it, such as which member becomes its owner, or that it
will be deleted. Of family budgets you left, it says only how many there are.

When you use "Delete all my data", your personal budget is deleted entirely. In each family budget:

- the records that affect other members' balances stay, with their amounts, dates, categories and
  shares, but your name in them is replaced by "Former member";
- the comments you wrote are erased, also from the change journal;
- invite links you created and that weren't used yet stop working;
- if it split its expenses by fixed percentages and you had a share, it goes back to equal shares;
- if you were its last owner, the member with an account who joined earliest becomes its owner;
- if no other member with an account is left, the family budget is deleted.

Your login account at auth.finance-nl.com stays until you ask us to delete it, as the published policy
says.

## How long family records are kept

The records of a family budget are kept as long as the family budget exists, also after a member
leaves or deletes their data, since they make up the other members' balances. A family budget is
deleted when no member with an account is left in it, and with it its records, its categories and its
change journal.

## The demo family budget

Once family budgets are available, the demo data comes with a family budget, "Demo household", which
you share with Sam, an invented partner without an account. Nobody else is in it, and "Delete all my
data" deletes it with the rest.

> **Not published: questions for a legal review**, kept from the draft as reviewed: the legal basis for
> processing the names of members without an account, which other members enter; how long the records
> of a former member are kept; and whether the change journal counts as personal data of the members it
> names.

## Activity and notifications

> Outside the section "Family budgets": a section of its own after it, published with HIST-2 part 2 (ADR 0006, D-1121, D-1142, D-1151).
> `frontend/src/privacy.test.ts` compares it with the page word for word.

The Activity page lists what happened around your account: when you signed in, signed out or your session ended, when you changed your settings or your exchange rates and, in a family budget you belong to, changes to its members and settings. Each event holds the time, what happened and your account’s id, but not your email, your address or your browser.

Events about signing in, signing out and ended sessions are kept for 13 months and deleted after that. The other events are kept until you use “Delete all my data”, which erases every event you caused, or, for the events of a family budget, until the family budget is closed.

When another member of a family budget changes its records, imports into it, joins it, leaves it or takes a place in it, the other members who have an account get a notification. A notification appears in the app only, as a badge in the header and a list; nothing is sent by email or in any other way. It holds no content of the change, only that something happened, who did it and how many changes there were; its text is made when you read it. A member who is removed and had no account, no share and no record naming them is deleted at once, and their notification about the removal goes with them.

Your notifications are deleted when you use “Delete all my data”, and all notifications of a family budget are deleted when it is closed. When you leave a family budget you no longer see its notifications. A notification another member received about a change you made stays for them, with your name replaced by “Former member” once you have deleted your data. After you leave a family budget, the notifications the other members received about you keep your name until you delete your data, and the notifications you received stop showing. Notifications are not deleted after a set time: they stay until one of those things happens.

## History and sign-in events

> Outside the section "Family budgets": a section of its own after it, published with HIST-1 (ADR 0006, D-1025, D-1033).
> `frontend/src/privacy.test.ts` compares it with the page word for word.

When you add, change or delete an entry, an account, a category or a payee, the app keeps the earlier version: the
values as they were, when the change was made, and what caused it (you, an import, the demo or the system). It is kept
so that the app can show you what changed and, later, let you go back. Your history is kept until you use "Delete all my
data", which deletes it with the rest.

The history of a family record belongs to the family budget, like the record: it is kept as long as the family budget
exists, and it never holds your payment account, your private notes or the payees you did not choose to show. If you
leave a family budget, your name stays in its history while you are a member who left. If you delete your data, your
name in it becomes "Former member", and the comments you wrote are erased from the history as they are from the
records.

The app also records when you sign in, when you sign out and when your session ends without your signing out, with the
time and your account's id, but not your email, your address or your browser. These events are kept for 13 months and
deleted after that; "Delete all my data" deletes them at once. Restarting the app, which happens when it is updated,
ends every session without a record, so the next event after an update is a sign-in.

Backups keep copies for the periods stated under "How long it is kept".

## What the app keeps in the browser

> Outside the section "Family budgets": the policy's "Cookies", after its list of cookies (UX-19, D-821, D-824; every
> item since D-828). `frontend/src/privacy.test.ts` compares it with the page word for word.

The app also keeps a few things in your browser, on this device only:

- In session storage, while you sign in, the page to take you back to afterwards. It is removed as soon as you are
  back, and session storage ends when you close the tab.
- The theme you choose in Settings (Blue, Light or Dark) is kept in your browser’s local storage on this device, which
  is never sent to the server; the theme cookie above holds a copy for the login pages.
- In local storage, the code of an invite link you open, so that the app can show the invite after you sign in or
  register. It is erased once you accept or decline the invite or it turns out not to be valid, when you sign out, and once it is 7 days old the next time you open the app. It is sent to the server only to look up, accept or decline that invite.
- In local storage, for each family budget, the account you last paid a family expense from, received a family income
  into or used for your side of a settlement, or that you chose to specify it later, so that the next form suggests
  the same. It is sent to the server only as part of a record you save.
- In local storage, that you dismissed the hint about your browser’s time zone, with the two time zones it named, so
  that it isn’t shown again for them. It is never sent to the server.
- In session storage, the filters and period each page last showed, so that the app’s links lead back to them, until
  you close the tab; they are removed when you sign out.
- In local storage, whether you folded or unfolded the summary on the Entries page. It is never sent to the server.

What is in local storage stays until you clear your browser’s data for the site; “Delete all my data” doesn’t remove
it. Because all of this is strictly necessary or keeps a choice you made, the app doesn’t ask for your consent.
