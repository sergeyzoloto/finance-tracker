package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountNotFoundException;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;
import com.example.financetracker.ledger.rates.RateService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports over the ledger, computed from postings on every call (rule 13). Callers pass the user id, the Keycloak
 * "sub" claim (rule 11), and every query is scoped by it. The figures of each report come from one SQL statement, so
 * they are read from one snapshot and add up without a surrounding transaction.
 * <p>
 * Dates are inclusive. A balance "as of" a day includes every entry dated that day.
 * <p>
 * Balances, net worth and cash flow also come in the user's base currency ({@code *InBase}), converted with the rates
 * of {@link RateService}: an amount on a day at the latest rate on or before that day. A figure that needs a rate that
 * doesn't exist is null, and says which rate is missing. Those reports read the postings and the rates in two
 * statements, in one transaction with a snapshot that both see.
 */
@Service
public class ReportService {

    private static final String FX_EXCHANGE = AccountRole.FX_EXCHANGE.defaultCode();

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final UserSettingsRepository settings;
    private final SettingsService settingsService;
    private final RateService rates;

    ReportService(JdbcClient jdbc, AccountRepository accounts, UserSettingsRepository settings,
            SettingsService settingsService, RateService rates) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.settings = settings;
        this.settingsService = settingsService;
        this.rates = rates;
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
     * {@link #balances} in the user's base currency: one row per account, each of its currencies converted at the rate
     * on {@code asOf}. Ordered by account code.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ConvertedBalance> balancesInBase(String userId, LocalDate asOf) {
        String base = settingsService.get(userId).baseCurrency();
        List<AccountBalance> balances = balances(userId, asOf);
        RateBook book = rates.rateBook(userId, currencies(balances.stream().map(AccountBalance::currency), base),
                asOf, asOf);
        Map<Long, List<AccountBalance>> byAccount = balances.stream()
                .collect(Collectors.groupingBy(AccountBalance::accountId, LinkedHashMap::new, Collectors.toList()));
        return byAccount.values().stream().map(rows -> {
            ConvertedSum balance = new ConvertedSum(book, base);
            rows.forEach(row -> balance.add(row.balance(), row.currency(), asOf));
            AccountBalance account = rows.getFirst();
            return new ConvertedBalance(account.accountId(), account.accountCode(), account.accountName(),
                    account.accountType(), base, balance.total(), balance.missing().toList());
        }).toList();
    }

    /**
     * {@link #netWorth} in the user's base currency as of {@code asOf}, with the unrealized revaluation of what is held
     * or owed and the realized result of exchanges (see {@link ConvertedNetWorth}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedNetWorth netWorthInBase(String userId, LocalDate asOf) {
        String base = settingsService.get(userId).baseCurrency();
        // Per day and currency: the postings to ASSET and to LIABILITY accounts, archived ones included, and those to
        // FX_EXCHANGE.
        List<DaySum> sums = jdbc.sql("""
                SELECT CASE a.type WHEN 'EQUITY' THEN 'EXCHANGE' ELSE a.type END AS part, e.entry_date AS day,
                       p.currency, sum(p.amount) AS amount
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.user_id = :userId AND a.user_id = :userId AND e.entry_date <= :asOf
                  AND (a.type IN ('ASSET', 'LIABILITY') OR (a.type = 'EQUITY' AND a.code = :fxExchange))
                GROUP BY 1, 2, 3
                ORDER BY 2, 1, 3""")
                .param("userId", userId)
                .param("asOf", asOf)
                .param("fxExchange", FX_EXCHANGE)
                .query(DaySum.class)
                .list();
        LocalDate first = sums.isEmpty() ? asOf : sums.getFirst().day();
        RateBook book = rates.rateBook(userId, currencies(sums.stream().map(DaySum::currency), base), first, asOf);

        ConvertedSum assets = new ConvertedSum(book, base);
        ConvertedSum liabilities = new ConvertedSum(book, base);
        ConvertedSum revaluation = new ConvertedSum(book, base);
        ConvertedSum realized = new ConvertedSum(book, base);
        Map<String, BigDecimal> assetBalances = new TreeMap<>();
        Map<String, BigDecimal> liabilityBalances = new TreeMap<>();
        for (DaySum sum : sums) {
            switch (sum.part()) {
                case "ASSET" -> assetBalances.merge(sum.currency(), sum.amount(), BigDecimal::add);
                case "LIABILITY" -> liabilityBalances.merge(sum.currency(), sum.amount(), BigDecimal::add);
                default -> realized.add(sum.amount().negate(), sum.currency(), sum.day());
            }
            if (!sum.part().equals("EXCHANGE")) {
                // Each posting at the rate on its own day, taken off the balance at the rate on asOf below.
                revaluation.add(sum.amount().negate(), sum.currency(), sum.day());
            }
        }
        assetBalances.forEach((currency, balance) -> {
            assets.add(balance, currency, asOf);
            revaluation.add(balance, currency, asOf);
        });
        liabilityBalances.forEach((currency, balance) -> {
            liabilities.add(balance.negate(), currency, asOf);
            revaluation.add(balance, currency, asOf);
        });

        Set<String> held = new TreeSet<>();
        Stream.of(assetBalances, liabilityBalances).forEach(balances -> balances.forEach((currency, balance) -> {
            if (balance.signum() != 0 && !currency.equals(base)) {
                held.add(currency);
            }
        }));
        if (!held.isEmpty()) {
            held.add(base);
        }
        List<RateBook.Rate> used = held.stream()
                .filter(currency -> !currency.equals(RateBook.EURO))
                .flatMap(currency -> book.rate(currency, asOf).stream())
                .toList();
        return new ConvertedNetWorth(base, assets.total(), liabilities.total(),
                ConvertedSum.difference(assets.total(), liabilities.total()), revaluation.total(), realized.total(),
                used, missing(assets, liabilities, revaluation, realized));
    }

    /**
     * {@link #cashFlow} in the user's base currency, each posting converted at the rate on its own day, and per month
     * the realized result of exchanges and the change of the unrealized revaluation (see
     * {@link ConvertedCashFlow.ExchangeResult}).
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedCashFlow cashFlowInBase(String userId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        String base = settingsService.get(userId).baseCurrency();
        // Per day and currency: categorized postings (CATEGORY) as cashFlow adds them up, postings to FX_EXCHANGE
        // (EXCHANGE), and postings in other currencies than the base currency to ASSET and LIABILITY accounts
        // (HOLDING), which are what is revalued. HOLDING before the period is one sum per currency, without a day.
        List<FlowSum> sums = jdbc.sql("""
                SELECT 'CATEGORY' AS part, e.entry_date AS day, p.currency, c.code AS category_code,
                       c.name AS category_name, c.type AS category_type,
                       CASE c.type WHEN 'EXPENSE' THEN sum(p.amount) ELSE -sum(p.amount) END AS amount
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN category c ON c.id = p.category_id
                WHERE e.user_id = :userId AND e.entry_date BETWEEN :from AND :to
                GROUP BY e.entry_date, p.currency, c.id
                UNION ALL
                SELECT CASE a.type WHEN 'EQUITY' THEN 'EXCHANGE' ELSE 'HOLDING' END,
                       CASE WHEN e.entry_date < :from THEN NULL ELSE e.entry_date END, p.currency, NULL, NULL, NULL,
                       sum(p.amount)
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.user_id = :userId AND a.user_id = :userId AND e.entry_date <= :to
                  AND ((a.type IN ('ASSET', 'LIABILITY') AND p.currency <> :base)
                       OR (a.type = 'EQUITY' AND a.code = :fxExchange AND e.entry_date >= :from))
                GROUP BY 1, 2, 3""")
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .param("base", base)
                .param("fxExchange", FX_EXCHANGE)
                .query(FlowSum.class)
                .list();
        // The day before the period: where the first month's revaluation starts from.
        RateBook book = rates.rateBook(userId, currencies(sums.stream().map(FlowSum::currency), base),
                from.minusDays(1), to);

        record Category(YearMonth month, CategoryType type, String code) {
        }
        Map<Category, ConvertedSum> totals = new TreeMap<>(Comparator.comparing(Category::month)
                .thenComparing(category -> category.type().name()).thenComparing(Category::code));
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, BigDecimal> held = new TreeMap<>();
        Map<YearMonth, List<FlowSum>> byMonth = new TreeMap<>();
        for (FlowSum sum : sums) {
            if (sum.part().equals("CATEGORY")) {
                CategoryType type = CategoryType.valueOf(sum.categoryType());
                totals.computeIfAbsent(new Category(YearMonth.from(sum.day()), type, sum.categoryCode()),
                        category -> new ConvertedSum(book, base)).add(sum.amount(), sum.currency(), sum.day());
                names.put(sum.categoryCode(), sum.categoryName());
            } else if (sum.day() == null) {
                held.merge(sum.currency(), sum.amount(), BigDecimal::add);
            } else {
                byMonth.computeIfAbsent(YearMonth.from(sum.day()), month -> new ArrayList<>()).add(sum);
            }
        }
        List<ConvertedCashFlow.Row> rows = totals.entrySet().stream()
                .map(e -> new ConvertedCashFlow.Row(e.getKey().month(), e.getKey().code(), names.get(e.getKey().code()),
                        e.getKey().type(), e.getValue().total(), e.getValue().missing().toList()))
                .toList();

        List<ConvertedCashFlow.ExchangeResult> results = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); !month.isAfter(YearMonth.from(to)); month = month.plusMonths(1)) {
            LocalDate start = month.atDay(1).isBefore(from) ? from : month.atDay(1);
            LocalDate end = month.atEndOfMonth().isAfter(to) ? to : month.atEndOfMonth();
            ConvertedSum realized = new ConvertedSum(book, base);
            ConvertedSum unrealized = new ConvertedSum(book, base);
            // The revaluation at the end less the one at the start: the balances at the end at the rates at the end,
            // less the balances at the start at the rates at the start, less the month's postings at their days' rates.
            held.forEach((currency, balance) -> unrealized.add(balance.negate(), currency, start.minusDays(1)));
            for (FlowSum sum : byMonth.getOrDefault(month, List.of())) {
                if (sum.part().equals("EXCHANGE")) {
                    realized.add(sum.amount().negate(), sum.currency(), sum.day());
                } else {
                    unrealized.add(sum.amount().negate(), sum.currency(), sum.day());
                    held.merge(sum.currency(), sum.amount(), BigDecimal::add);
                }
            }
            held.forEach((currency, balance) -> unrealized.add(balance, currency, end));
            results.add(new ConvertedCashFlow.ExchangeResult(month, realized.total(), unrealized.total(),
                    missing(realized, unrealized)));
        }
        return new ConvertedCashFlow(base, rows, results);
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

    /** The currencies of the amounts, and the one they are converted to. */
    private static Set<String> currencies(Stream<String> amounts, String base) {
        Set<String> currencies = amounts.collect(Collectors.toCollection(HashSet::new));
        currencies.add(base);
        return currencies;
    }

    private static List<MissingRate> missing(ConvertedSum... figures) {
        MissingRate.Days days = new MissingRate.Days();
        for (ConvertedSum figure : figures) {
            days.addAll(figure.missing());
        }
        return days.toList();
    }

    /** The sum of postings on one day in one currency, for a part of a report. */
    private record DaySum(String part, LocalDate day, String currency, BigDecimal amount) {
    }

    /** As {@link DaySum}, with the category of a categorized posting; {@code day} is null for "before the period". */
    private record FlowSum(String part, LocalDate day, String currency, String categoryCode, String categoryName,
            String categoryType, BigDecimal amount) {
    }

    private static CashFlowRow cashFlowRow(ResultSet row, int rowNum) throws SQLException {
        return new CashFlowRow(YearMonth.from(row.getObject("month", LocalDate.class)), row.getString("category_code"),
                row.getString("category_name"), CategoryType.valueOf(row.getString("category_type")),
                row.getString("currency"), row.getBigDecimal("total"));
    }
}
