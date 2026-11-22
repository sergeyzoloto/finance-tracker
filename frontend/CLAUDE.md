# Frontend

React 19, react-router 7, Vite, TypeScript, Node 24 (`.nvmrc`; `engines` `>=24 <25`). No state library and no chart library: pages load with `useApi` and write with `useMutation`; the charts are HTML and CSS. The per-file catalog is `docs/claude/frontend-map.md`: grep it for the screen or module you need instead of reading it whole. Test coverage per area: `docs/claude/test-catalog.md`.

## Rules

- Money: amounts are decimal strings as the API sends them. All arithmetic goes through big.js in strict mode, never JS numbers, rounding HALF_UP (`money.ts`). `formatMoney` formats; `toChartNumber` is the one conversion to a JS number, for bar heights only. Never add amounts of different currencies on the client.
- Dates: no screen reads the browser's clock for a date. Today is `useToday()` (from `MeProvider`, `/api/me`'s `today`). Use `DateInput` in place of `<input type="date">`; show dates with `formatDate` / `formatInstant` in the user's format (`dates.ts`).
- Screen text: never "debit" or "credit" (users pick a direction; only Advanced shows signed amounts); no decision number (`D-nnn`) in any string, template or JSX text (`screenTexts.test.ts`); the family feature says "family budget", never "ledger".
- The family UI exists only while `familyLedgersOn(me)`; with the switch off there is no switcher, no family request and no family route (`/family/…` redirects to `/`).
- Charts: where there is no data, show no empty chart and no columns of empty or zero sums (e.g. a year-earlier comparison with nothing in it). The dashboard's charts are horizontal bars; only the daily-spending columns stay vertical.
- Page state that a user may share or reload (period, scope, filters, page) lives in the URL only.
- Layout: nothing scrolls sideways at 375 px; under 40rem tables become blocks of labelled lines. Check 375, 1024 and 1280 px.
- Theming: three themes (Blue, Light, Dark) as CSS custom properties in `index.css`. Every colour in the stylesheet and components is one of those tokens; no hex or `rgb(` outside the three blocks, and each block must meet WCAG contrast (`themeGuards.test.ts`). The theme choice is the browser's (`localStorage` and the `ft_theme` cookie), never the server's.
- `useApi` takes an optional `guard` for shapes whose nested fields a screen reads; an answer it refuses is the error `UNEXPECTED_ANSWER`, never data. Wrap pages and widgets that could throw in render in `Safe`.
- `public/privacy.html` is static HTML without script; its "Family budgets" section matches `docs/family-budget/privacy-draft.md` word for word (`privacy.test.ts`). Changing what the app stores in the browser means updating the policy's list.

## Tests

Vitest (jsdom, locale en-US, `TZ=UTC`) with Testing Library; `testMe.tsx` supplies a signed-in user and `testLedger.ts` reference data. Before committing a change to a `package.json` or lock file read `.claude/rules/frontend-deps.md`.
