package com.example.financetracker.ledger.report;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountNotFoundException;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reports over the ledger, computed from postings on every call (rule 13). Callers pass the user id, the Keycloak
 * "sub" claim (rule 11), and every query is scoped by it. The figures of each report come from one SQL statement, so
 * they are read from one snapshot and add up without a surrounding transaction.
 * <p>
 * Dates are inclusive. A balance "as of" a day includes every entry dated that day.
 */
@Service
public class ReportService {

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final UserSettingsRepository settings;

    ReportService(JdbcClient jdbc, AccountRepository accounts, UserSettingsRepository settings) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.settings = settings;
    }

    /**
     * The displayed balance of every account that isn't archived, in each currency it has postings in by
     * {@code asOf}, zero included, and in its default currency even before it has any. Ordered by account code and
     * currency.
     */
    public List<AccountBalance> balances(String userId, LocalDate asOf) {
        return jdbc.sql("""
                WITH amounts AS (
                    SELECT p.account_id, p.currency, sum(p.amount) AS total
                    FROM journal_entry e
                    JOIN posting p ON p.entry_id = e.id
                    WHERE e.user_id = :userId AND e.entry_date <= :asOf
                    GROUP BY p.account_id, p.currency
                    UNION ALL
                    SELECT id, default_currency, 0
                    FROM account
                    WHERE user_id = :userId AND default_currency IS NOT NULL
                )
                SELECT a.id AS account_id, a.code AS account_code, a.name AS account_name, a.type AS account_type,
                       t.currency, CASE a.type WHEN 'ASSET' THEN sum(t.total) ELSE -sum(t.total) END AS balance
                FROM account a
                JOIN amounts t ON t.account_id = a.id
                WHERE a.user_id = :userId AND a.archived_at IS NULL
                GROUP BY a.id, t.currency
                ORDER BY a.code, t.currency""")
                .param("userId", userId)
                .param("asOf", asOf)
                .query(AccountBalance.class)
                .list();
    }

    /**
     * The displayed balance of an account that requires a counterparty (rule 8), per counterparty and currency, as of
     * {@code asOf}. Counterparties whose balance is zero are left out. Ordered by counterparty name and currency.
     *
     * @throws AccountNotFoundException if the user has no account with this code
     * @throws IllegalArgumentException if the account doesn't require a counterparty
     */
    public List<CounterpartyBalance> counterpartyBalances(String userId, String accountCode, LocalDate asOf) {
        Account account = accounts.findByUserIdAndCode(userId, accountCode)
                .orElseThrow(() -> new AccountNotFoundException(accountCode));
        if (!account.requiresCounterparty()) {
            throw new IllegalArgumentException(
                    "Account %s has no balances per counterparty: it doesn't require one".formatted(accountCode));
        }
        return jdbc.sql("""
                SELECT c.id AS counterparty_id, c.name AS counterparty_name, p.currency,
                       CASE a.type WHEN 'ASSET' THEN sum(p.amount) ELSE -sum(p.amount) END AS balance
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                JOIN counterparty c ON c.id = p.counterparty_id
                WHERE e.user_id = :userId AND a.user_id = :userId AND a.id = :accountId AND e.entry_date <= :asOf
                GROUP BY a.id, c.id, p.currency
                HAVING sum(p.amount) <> 0
                ORDER BY c.name, p.currency""")
                .param("userId", userId)
                .param("accountId", account.id())
                .param("asOf", asOf)
                .query(CounterpartyBalance.class)
                .list();
    }

    /**
     * Income and expenses per month, category and currency, from the categorized postings of entries dated
     * {@code from} to {@code to}. Postings without a category don't count: not the paying side of an expense, and not
     * transfers, loans, exchanges or opening balances. Ordered by month, category type, category code and currency.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    public List<CashFlowRow> cashFlow(String userId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        // The date is truncated as a timestamp without time zone. A bare date would be cast to timestamptz, and the
        // month would then depend on the session's time zone.
        return jdbc.sql("""
                SELECT date_trunc('month', e.entry_date::timestamp)::date AS month,
                       c.code AS category_code, c.name AS category_name, c.type AS category_type, p.currency,
                       CASE c.type WHEN 'EXPENSE' THEN sum(p.amount) ELSE -sum(p.amount) END AS total
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN category c ON c.id = p.category_id
                WHERE e.user_id = :userId AND e.entry_date BETWEEN :from AND :to
                GROUP BY month, c.id, p.currency
                ORDER BY month, c.type, c.code, p.currency""")
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query(ReportService::cashFlowRow)
                .list();
    }

    /**
     * Assets minus liabilities per currency, as of {@code asOf}, in every currency the user's ASSET and LIABILITY
     * accounts have postings in. Archived accounts count: archiving hides an account, it doesn't take its money away.
     * Ordered by currency.
     */
    public List<NetWorth> netWorth(String userId, LocalDate asOf) {
        // Liabilities are displayed as minus the sum of their postings (rule 4), so assets − liabilities is the sum of
        // the postings to both.
        return jdbc.sql("""
                SELECT p.currency,
                       coalesce(sum(p.amount) FILTER (WHERE a.type = 'ASSET'), 0) AS assets,
                       coalesce(-sum(p.amount) FILTER (WHERE a.type = 'LIABILITY'), 0) AS liabilities,
                       sum(p.amount) AS net_worth
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.user_id = :userId AND a.user_id = :userId AND e.entry_date <= :asOf
                  AND a.type IN ('ASSET', 'LIABILITY')
                GROUP BY p.currency
                ORDER BY p.currency""")
                .param("userId", userId)
                .param("asOf", asOf)
                .query(NetWorth.class)
                .list();
    }

    /**
     * The displayed balance of the user's shared account per currency, as of {@code asOf}. That is the account the
     * user's settings name, or else FAMILY_DEBT, as for shared expenses (rule 7). Currencies that are settled, with a
     * balance of zero, are left out, and so is everything when the user has no shared account. Ordered by currency.
     */
    public List<SharedSettlement> sharedSettlement(String userId, LocalDate asOf) {
        Optional<Long> sharedAccountId = settings.findById(userId)
                .map(UserSettings::sharedAccountId)
                .or(() -> accounts.findByUserIdAndCode(userId, AccountRole.SHARED.defaultCode()).map(Account::id));
        if (sharedAccountId.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.id AS account_id, a.code AS account_code, p.currency,
                       CASE a.type WHEN 'ASSET' THEN sum(p.amount) ELSE -sum(p.amount) END AS balance,
                       CASE WHEN sum(p.amount) < 0 THEN 'USER_OWES' ELSE 'USER_IS_OWED' END AS direction
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.user_id = :userId AND a.user_id = :userId AND a.id = :accountId AND e.entry_date <= :asOf
                GROUP BY a.id, p.currency
                HAVING sum(p.amount) <> 0
                ORDER BY p.currency""")
                .param("userId", userId)
                .param("accountId", sharedAccountId.get())
                .param("asOf", asOf)
                .query(SharedSettlement.class)
                .list();
    }

    /**
     * The currencies in which the user's ledger doesn't add up, over all dates and all accounts, archived ones
     * included. It is empty for a sound ledger. The triggers of V2 keep every entry balanced, so a violation means
     * that data was written past them.
     * <p>
     * The two figures take different paths to the postings. The posting sum reaches them through the user's entries,
     * and assets − liabilities − equity through the user's accounts.
     */
    public List<IntegrityViolation> integrityCheck(String userId) {
        return jdbc.sql("""
                WITH by_entry AS (
                    SELECT p.currency, sum(p.amount) AS posting_sum
                    FROM journal_entry e
                    JOIN posting p ON p.entry_id = e.id
                    WHERE e.user_id = :userId
                    GROUP BY p.currency
                ), by_account AS (
                    SELECT p.currency,
                           coalesce(sum(p.amount) FILTER (WHERE a.type = 'ASSET'), 0)
                               - coalesce(-sum(p.amount) FILTER (WHERE a.type = 'LIABILITY'), 0)
                               - coalesce(-sum(p.amount) FILTER (WHERE a.type = 'EQUITY'), 0) AS balance_sheet_gap
                    FROM account a
                    JOIN posting p ON p.account_id = a.id
                    WHERE a.user_id = :userId
                    GROUP BY p.currency
                )
                SELECT currency, coalesce(posting_sum, 0) AS posting_sum,
                       coalesce(balance_sheet_gap, 0) AS balance_sheet_gap
                FROM by_entry FULL JOIN by_account USING (currency)
                WHERE coalesce(posting_sum, 0) <> 0 OR coalesce(balance_sheet_gap, 0) <> 0
                ORDER BY currency""")
                .param("userId", userId)
                .query(IntegrityViolation.class)
                .list();
    }

    private static CashFlowRow cashFlowRow(ResultSet row, int rowNum) throws SQLException {
        return new CashFlowRow(YearMonth.from(row.getObject("month", LocalDate.class)), row.getString("category_code"),
                row.getString("category_name"), CategoryType.valueOf(row.getString("category_type")),
                row.getString("currency"), row.getBigDecimal("total"));
    }
}
