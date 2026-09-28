import dashboardScreenshot from './assets/demo-dashboard.webp'
import entriesScreenshot from './assets/demo-entries.webp'
import { LOGIN_URL, PRIVACY_URL, SOURCE_URL } from './links'

/**
 * The start page for visitors who aren't signed in, and for everyone while the backend is down: it needs nothing from
 * the backend. Signing in is a plain link to the backend's login, which leads to Keycloak's page, where visitors can
 * also register.
 */
export default function Landing() {
  return (
    <div className="landing">
      <header>
        <strong className="brand">Finance Tracker</strong>
        <a className="button primary" href={LOGIN_URL}>Sign in</a>
      </header>

      <main>
        <section className="hero">
          <h1>Your money, kept like a proper set of books</h1>
          <p className="lead">
            Finance Tracker is a personal finance tracker with double-entry bookkeeping underneath. Record what you
            earn, spend, lend and exchange, in any currency, and see where your money stands.
          </p>
          <p className="calls">
            <a className="button primary" href={LOGIN_URL}>Sign in or create an account</a>
            <a className="button" href={SOURCE_URL}>Source code on GitHub</a>
          </p>
          {/* Screenshots of a local instance with the demo data, which is invented. */}
          <figure>
            <img src={dashboardScreenshot} width={1092} height={618} alt={'The dashboard with the demo data in '
              + 'euros, the base currency: a net worth of €17,274.40 with what currency exchanges and rate changes did '
              + 'to it, the family budget owing €88.60, and a bar chart of income and expenses for each month from '
              + 'April to September 2026.'} />
            <figcaption>The dashboard with the demo data, in the base currency.</figcaption>
          </figure>
        </section>

        <section>
          <h2>What it does</h2>
          <div className="features">
            <div>
              <h3>Double-entry bookkeeping</h3>
              <p>
                Every entry is a set of postings that add up to zero in each currency, so the books always balance.
                The screens speak plainly — an expense, a transfer, a loan, a bill shared with a partner — and never
                ask you for debit and credit.
              </p>
            </div>
            <div>
              <h3>Many currencies</h3>
              <p>
                Any account can hold any currency. Reports convert everything to your base currency with the European
                Central Bank’s daily reference rates, or rates you enter yourself, and show what exchange rates did to
                your money.
              </p>
            </div>
            <div>
              <h3>Reports</h3>
              <p>
                Net worth, balances per account, income and expenses by month and category, open loans per person, and
                who owes whom in a shared household budget.
              </p>
            </div>
          </div>
          <figure>
            <img src={entriesScreenshot} width={1092} height={462} loading="lazy" alt={'The newest entries of the '
              + 'demo ledger, each with its date, payee, note, category, the accounts the money moved between and the '
              + 'amount: interest, a bank fee, lunch paid by credit card, a friend paying back part of a loan, and a '
              + 'weekly shop split between groceries and the family budget.'} />
            <figcaption>Entries, each read from its postings.</figcaption>
          </figure>
        </section>

        <section>
          <h2>Try it</h2>
          <ol className="steps">
            <li>
              <a href={LOGIN_URL}>Sign in or create an account</a>, with an email address or with Google or GitHub.
            </li>
            <li>
              Your ledger starts empty. Press <strong>Load demo data</strong> on the dashboard to fill it with six
              months of invented entries in euros and US dollars.
            </li>
            <li>
              Look around and change anything. <strong>Settings → Delete all my data</strong> empties your ledger
              again.
            </li>
          </ol>
        </section>

        <section>
          <h2>How it’s built</h2>
          <ul className="stack">
            <li><strong>React</strong> and TypeScript in the browser; amounts are exact decimals, never floating point.</li>
            <li>
              <strong>Spring Boot</strong> as a backend-for-frontend: it signs you in and keeps the tokens in its
              session, so they never reach the browser.
            </li>
            <li><strong>PostgreSQL</strong>, whose triggers enforce the ledger’s rules as a backstop.</li>
            <li><strong>Keycloak</strong> for sign-in, with OpenID Connect and PKCE.</li>
            <li><strong>Docker</strong> containers behind <strong>Caddy</strong> on a <strong>Hetzner</strong> server in Germany.</li>
            <li>
              <strong>Per-user data isolation</strong>, backed by tests that try every endpoint as one user against
              another’s data.
            </li>
          </ul>
        </section>
      </main>

      <footer className="app-footer">
        <p>A personal portfolio project, provided as is, without any warranty.</p>
        <p>
          <a href={PRIVACY_URL}>Privacy policy</a> · <a href={SOURCE_URL}>Source code</a>
        </p>
      </footer>
    </div>
  )
}
