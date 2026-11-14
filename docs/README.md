# Documentation

What each document is for. "Current" documents describe the system as it is and are kept up to date; ADRs keep their
original decision and add dated amendments; a snapshot is never updated.

| Document | What it is for | Kept |
| --- | --- | --- |
| [../README.md](../README.md) | The project in one page: features, architecture, getting started, the API's map, production | Current |
| [auth.md](auth.md) | Sign-in and access: the BFF login, tokens, roles, who reaches which ledger, Keycloak's pages, the dev realm | Current |
| [database-hosting.md](database-hosting.md) | Where the databases run and why, backups, rehearsals from a production dump, Supabase deferred | Current, with a dated decision |
| [adr/0001-double-entry-ledger.md](adr/0001-double-entry-ledger.md) | The double-entry journal and its thirteen rules | Decision, amended |
| [adr/0002-exchange-rates.md](adr/0002-exchange-rates.md) | Exchange rates and reports in the base currency | Decision, amended |
| [adr/0003-family-budget-membership-and-cross-ledger-posting.md](adr/0003-family-budget-membership-and-cross-ledger-posting.md) | Ledgers, membership-based access, family budgets and posting into members' ledgers, F1 to F7 | Decision, amended |
| [adr/0004-multi-currency-family-ledger.md](adr/0004-multi-currency-family-ledger.md) | Family records in any currency, F8 | Decision, amended |
| [adr/0005-excel-import-and-export.md](adr/0005-excel-import-and-export.md) | The Excel import and the export, D3b to D4c | Decision, as built |
| [family-budget/requirements.md](family-budget/requirements.md) | The decision log of the whole project (below), the planned stages and "Later", the open work | Current, appended |
| [family-budget/current-state.md](family-budget/current-state.md) | What the code did before the family budget, written in F1 (2026-09-28) | Frozen snapshot |
| [family-budget/privacy-draft.md](family-budget/privacy-draft.md) | The privacy policy's section "Family budgets", word for word as published in `frontend/public/privacy.html` (a test compares them) | Current |
| [images/](images/) | The README's banner and colour palette, hand-written SVG in the app's colours (`frontend/src/readmeAssets.test.ts` checks them) | Current |

**The decision log.** [family-budget/requirements.md](family-budget/requirements.md) holds every decision of the
project, D-1 onward, for all stages, not only the family budget's, despite its path; it stays there because many
links point at it. Each decision says who took it and in which stage, and the lines "Amended in …" at its top say
which stage added which.

Elsewhere in the repository:

- [../change_log.mdx](../change_log.mdx): every task, newest first: what changed, how it was verified, what is open.
- [../deploy/RUNBOOK.md](../deploy/RUNBOOK.md): production's procedures, the deployed revisions and the deploy
  checklists.
- [../e2e/README.md](../e2e/README.md): the end-to-end suite.
- [../CLAUDE.md](../CLAUDE.md): the rules for the agent that works on this repository, and a detailed project map.
