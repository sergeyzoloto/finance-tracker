# End-to-end suite

Playwright 1.63.0, Chromium only, Node 24; its own `package.json`, outside both images' build contexts. Full description: `e2e/README.md`. What each spec covers: `docs/claude/test-catalog.md` (grep for the spec's name).

- `npm run e2e:local` runs against `./dev.sh`'s stack (Vite on 5173, dev Keycloak on 8080, `testuser` as A and `testuser2` as B) and refuses any host but localhost. `npm run test:guards` runs the guards' own tests; run it alone, after `e2e:local`, never while it runs.
- Never run `npm run e2e:prod` or a Playwright `prod` project, and never touch `~/.config/finance-tracker/`. Only the owner runs the prod suite.
- Load the demo only with `loadDemoData(page)` of `fixtures.ts` (it requires a 2xx from `POST /api/demo-data`, then waits for the headline region to show "Net worth").
- Never look for a number with `toContainText`, `toContain`, `toMatch` or `hasText`: use `wholeNumber()` of `fixtures.ts` (`lib/spec-hygiene.test.ts` fails the build otherwise).
- Dates are typed and read through `dates.ts` helpers (`fillDate`, `expectDate`, `shownDay`), in the field's own pattern.
- Every response of 400 or more, console error or page error on the app's origin fails a spec unless the spec names it with its reason (`fixtures.ts`, `Watch`).
- Family specs share `family-steps.ts` (joins at least 31 s apart because of the per-minute invite limit); `09-payees` and the family steps of `07-dashboard` run only with `E2E_FAMILY=on`.
- One sign-in per account per run (`specs/sign-in.setup.ts`); `specs/cleanup.teardown.ts` deletes both accounts' data even after a failure; retries 0, workers 1. Spec file pattern: `(0[2-9]|1\d)-…`.
- A new spec for a behaviour change goes in `e2e/` with the stage; the report names what could not be automated and why.
