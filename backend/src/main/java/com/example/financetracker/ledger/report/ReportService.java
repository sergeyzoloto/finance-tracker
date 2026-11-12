package com.example.financetracker.ledger.report;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.SettingsService;
import com.example.financetracker.ledger.UserSettings;
import com.example.financetracker.ledger.UserSettingsRepository;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.family.FamilyBalances;
import com.example.financetracker.ledger.family.FamilyRecordService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.domain.AccountRole;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.rates.ConvertedSum;
import com.example.financetracker.ledger.rates.MissingRate;
import com.example.financetracker.ledger.rates.RateBook;
import com.example.financetracker.ledger.rates.RateService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports over a ledger, computed from postings on every call (rule 13). Callers pass the {@link LedgerScope} that
 * LedgerAccess resolved (rule 11), and every query is scoped by it: each ledger table that a query reads is filtered
 * by the ledger id, even where the triggers already guarantee that the rows reached through the ledger's entries are
 * the ledger's. The figures of each report come from one SQL statement, so they are read from one snapshot and add up
 * without a surrounding transaction.
 * <p>
 * Dates are inclusive. A balance "as of" a day includes every entry dated that day.
 * <p>
 * Balances, net worth and cash flow also come in the base currency of the personal ledger's member ({@code *InBase}),
 * converted with the rates that member sees ({@link RateService}): an amount on a day at the latest rate on or before
 * that day. A figure that needs a rate that doesn't exist is null, and says which rate is missing. Those reports read
 * the postings and the rates in two statements, in one transaction with a snapshot that both see.
 */
@Service
public class ReportService {

    private static final String FX_EXCHANGE = AccountRole.FX_EXCHANGE.defaultCode();

    private final JdbcClient jdbc;
    private final AccountRepository accounts;
    private final UserSettingsRepository settings;
    private final SettingsService settingsService;
    private final RateService rates;
    private final LedgerAccess access;
    private final FamilyRecordService records;
    private final FamilySwitch familySwitch;

    ReportService(JdbcClient jdbc, AccountRepository accounts, UserSettingsRepository settings,
            SettingsService settingsService, RateService rates, LedgerAccess access, FamilyRecordService records,
            FamilySwitch familySwitch) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.settings = settings;
        this.settingsService = settingsService;
        this.rates = rates;
        this.access = access;
        this.records = records;
        this.familySwitch = familySwitch;
    }

    /**
     * The displayed balance of every account that isn't archived, in each currency it has postings in by
     * {@code asOf}, zero included, and in its default currency even before it has any; and of every archived account
     * in each currency whose balance on {@code asOf} isn't zero, marked {@code archived} (UX-2, D-355: archiving hides
     * an account, it doesn't take its money away, so these are the accounts {@link #netWorth} counts). Ordered by
     * account code and currency. A member's debt account to a family budget of one of their ACTIVE memberships names that budget
     * (D-299), by {@code account.family_ledger_id}, never by its code or name; with the family switch off none does.
     */
    public List<AccountBalance> balances(LedgerScope ledger, LocalDate asOf) {
        return jdbc.sql("""
                WITH amounts AS (
                    SELECT p.account_id, p.currency, sum(p.amount) AS total
                    FROM journal_entry e
                    JOIN posting p ON p.entry_id = e.id
                    WHERE e.ledger_id = :ledgerId AND e.entry_date <= :asOf
                    GROUP BY p.account_id, p.currency
                    UNION ALL
                    SELECT id, default_currency, 0
                    FROM account
                    WHERE ledger_id = :ledgerId AND default_currency IS NOT NULL
                )
                SELECT a.id AS account_id, a.code AS account_code, a.name AS account_name, a.type AS account_type,
                       t.currency, CASE a.type WHEN 'ASSET' THEN sum(t.total) ELSE -sum(t.total) END AS balance,
                       f.id AS family_ledger_id, f.name AS family_ledger_name,
                       a.archived_at IS NOT NULL AS archived
                FROM account a
                JOIN amounts t ON t.account_id = a.id
                LEFT JOIN ledger f ON :familyOn AND f.id = a.family_ledger_id AND f.type = 'SHARED'
                    AND EXISTS (SELECT FROM ledger_member m
                                WHERE m.ledger_id = f.id AND m.user_sub = :sub AND m.status = 'ACTIVE')
                WHERE a.ledger_id = :ledgerId
                GROUP BY a.id, t.currency, f.id
                HAVING a.archived_at IS NULL OR sum(t.total) <> 0
                ORDER BY a.code, t.currency""")
                .param("ledgerId", ledger.ledgerId())
                .param("asOf", asOf)
                .param("familyOn", familySwitch.enabled())
                .param("sub", ledger.userId())
                .query(AccountBalance.class)
                .list();
    }

    /**
     * The displayed balance of an account that requires a counterparty (rule 8), per counterparty and currency, as of
     * {@code asOf}. Counterparties whose balance is zero are left out. Ordered by counterparty name and currency.
     *
     * @throws AccountNotFoundException if the ledger has no account with this code
     * @throws IllegalArgumentException if the account doesn't require a counterparty
     */
    public List<CounterpartyBalance> counterpartyBalances(LedgerScope ledger, String accountCode, LocalDate asOf) {
        Account account = accounts.findByCode(ledger, accountCode)
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
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId AND c.ledger_id = :ledgerId
                  AND a.id = :accountId
                  AND e.entry_date <= :asOf
                GROUP BY a.id, c.id, p.currency
                HAVING sum(p.amount) <> 0
                ORDER BY c.name, p.currency""")
                .param("ledgerId", ledger.ledgerId())
                .param("accountId", account.id())
                .param("asOf", asOf)
                .query(CounterpartyBalance.class)
                .list();
    }

    /**
     * {@link #counterpartyBalances} in the base currency (D-298): one row per counterparty, each of its currencies
     * converted at the rate on {@code asOf} and added up. Ordered by counterparty name.
     *
     * @throws AccountNotFoundException if the ledger has no account with this code
     * @throws IllegalArgumentException if the account doesn't require a counterparty
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ConvertedCounterpartyBalance> counterpartyBalancesInBase(LedgerScope ledger, String accountCode,
            LocalDate asOf) {
        List<CounterpartyBalance> balances = counterpartyBalances(ledger, accountCode, asOf);
        String base = settingsService.get(ledger.userId()).baseCurrency();
        RateBook book = rates.rateBook(ledger.userId(),
                currencies(balances.stream().map(CounterpartyBalance::currency), base), asOf, asOf);
        Map<Long, List<CounterpartyBalance>> byCounterparty = balances.stream().collect(
                Collectors.groupingBy(CounterpartyBalance::counterpartyId, LinkedHashMap::new, Collectors.toList()));
        return byCounterparty.values().stream().map(rows -> {
            ConvertedSum balance = new ConvertedSum(book, base);
            rows.forEach(row -> balance.add(row.balance(), row.currency(), asOf));
            CounterpartyBalance counterparty = rows.getFirst();
            return new ConvertedCounterpartyBalance(counterparty.counterpartyId(), counterparty.counterpartyName(),
                    base, balance.total(), balance.missing().toList(), balance.rates(), rows.stream()
                            .map(row -> new ConvertedCounterpartyBalance.Amount(row.currency(), row.balance()))
                            .toList());
        }).toList();
    }

    /**
     * Income and expenses per month, category and currency, from the categorized postings of entries dated
     * {@code from} to {@code to}. Postings without a category don't count: not the paying side of an expense, and not
     * transfers, loans, exchanges or opening balances. Ordered by month, category type, category code and currency.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    public List<CashFlowRow> cashFlow(LedgerScope ledger, LocalDate from, LocalDate to) {
        return cashFlow(ledger, from, to, CashFlowScope.ALL, null);
    }

    /**
     * {@link #cashFlow(LedgerScope, LocalDate, LocalDate)} of the entries of one scope (D-302), and for a family scope
     * optionally of one family budget's records only.
     *
     * @param familyLedgerId a family budget of the user's ACTIVE memberships, or null for all of them
     * @throws IllegalArgumentException if {@code from} is after {@code to}, for a family scope with the family switch
     *         off, and for a family budget with a scope that isn't a family one
     * @throws NotFoundException if {@code familyLedgerId} isn't a family budget the user is an ACTIVE member of
     */
    public List<CashFlowRow> cashFlow(LedgerScope ledger, LocalDate from, LocalDate to, CashFlowScope scope,
            Long familyLedgerId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        // The date is truncated as a timestamp without time zone. A bare date would be cast to timestamptz, and the
        // month would then depend on the session's time zone.
        return flowRows(ledger, from, to, scope, familyLedgerId, "date_trunc('month', e.entry_date::timestamp)::date")
                .stream()
                .map(row -> new CashFlowRow(YearMonth.from(row.bucket()), row.categoryCode(), row.categoryName(),
                        row.categoryType(), row.currency(), row.total(), row.familyLedgerId(),
                        row.familyLedgerName()))
                .toList();
    }

    /** The longest range of {@link #dailyCashFlow}, in days, both ends included (D-570). */
    public static final int MAX_DAILY_DAYS = 366;

    /**
     * {@link #cashFlow(LedgerScope, LocalDate, LocalDate, CashFlowScope, Long)} per day in place of per month (D-570,
     * UX-13): the same postings by the same classification and family categories, so that over any range and scope the
     * days' totals per category and currency add up exactly to the cash flow's. Ordered by day, category type, category
     * code and currency.
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}, for a range of more than
     *         {@value #MAX_DAILY_DAYS} days, and as {@link #cashFlow(LedgerScope, LocalDate, LocalDate, CashFlowScope,
     *         Long)} for the scope
     * @throws NotFoundException as {@link #cashFlow(LedgerScope, LocalDate, LocalDate, CashFlowScope, Long)}
     */
    public List<DailyCashFlowRow> dailyCashFlow(LedgerScope ledger, LocalDate from, LocalDate to,
            CashFlowScope scope, Long familyLedgerId) {
        checkDailyRange(from, to);
        return flowRows(ledger, from, to, scope, familyLedgerId, "e.entry_date").stream()
                .map(row -> new DailyCashFlowRow(row.bucket(), row.categoryCode(), row.categoryName(),
                        row.categoryType(), row.currency(), row.total(), row.familyLedgerId(),
                        row.familyLedgerName()))
                .toList();
    }

    /** D-570's range: not backwards, and at most {@value #MAX_DAILY_DAYS} days, both ends included. */
    private static void checkDailyRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAILY_DAYS) {
            throw new IllegalArgumentException(
                    "The daily cash flow covers at most %d days".formatted(MAX_DAILY_DAYS));
        }
    }

    /**
     * The categorized postings of the scope's entries dated {@code from} to {@code to}, summed per {@code bucket} (an
     * SQL expression of the entry's date {@code e}), category, family budget and currency: the one statement of both
     * {@link #cashFlow(LedgerScope, LocalDate, LocalDate, CashFlowScope, Long)} and {@link #dailyCashFlow}, so that
     * they classify alike (D-570).
     */
    private List<FlowRow> flowRows(LedgerScope ledger, LocalDate from, LocalDate to, CashFlowScope scope,
            Long familyLedgerId, String bucket) {
        List<Long> families = families(ledger);
        checkScope(scope, familyLedgerId, families);
        var statement = jdbc.sql("""
                SELECT %s AS bucket,
                       c.code AS category_code, c.name AS category_name, c.type AS category_type, p.currency,
                       CASE c.type WHEN 'EXPENSE' THEN sum(p.amount) ELSE -sum(p.amount) END AS total,
                       f.id AS family_ledger_id, f.name AS family_ledger_name
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN category c ON c.id = p.category_id
                LEFT JOIN ledger f ON f.id = c.ledger_id AND f.type = 'SHARED'
                %s
                WHERE e.ledger_id = :ledgerId AND %s AND e.entry_date BETWEEN :from AND :to AND %s
                GROUP BY bucket, c.id, p.currency, f.id
                ORDER BY bucket, c.type, c.code, p.currency, f.id NULLS FIRST""".formatted(bucket, scopeJoins(scope),
                        categories(families), scopeCondition(scope, familyLedgerId)))
                .param("ledgerId", ledger.ledgerId())
                .param("from", from)
                .param("to", to);
        if (!families.isEmpty()) {
            statement = statement.param("families", families);
        }
        if (familyLedgerId != null) {
            statement = statement.param("familyId", familyLedgerId);
        }
        return statement.query(ReportService::flowRow).list();
    }

    /**
     * Assets minus liabilities per currency, as of {@code asOf}, in every currency the ledger's ASSET and LIABILITY
     * accounts have postings in. Archived accounts count: archiving hides an account, it doesn't take its money away.
     * Ordered by currency.
     */
    public List<NetWorth> netWorth(LedgerScope ledger, LocalDate asOf) {
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
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId AND e.entry_date <= :asOf
                  AND a.type IN ('ASSET', 'LIABILITY')
                GROUP BY p.currency
                ORDER BY p.currency""")
                .param("ledgerId", ledger.ledgerId())
                .param("asOf", asOf)
                .query(NetWorth.class)
                .list();
    }

    /**
     * {@link #balances} in the base currency: one row per account, each of its currencies converted at the rate
     * on {@code asOf}. Ordered by account code.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ConvertedBalance> balancesInBase(LedgerScope ledger, LocalDate asOf) {
        String base = settingsService.get(ledger.userId()).baseCurrency();
        List<AccountBalance> balances = balances(ledger, asOf);
        RateBook book = rates.rateBook(ledger.userId(),
                currencies(balances.stream().map(AccountBalance::currency), base), asOf, asOf);
        Map<Long, List<AccountBalance>> byAccount = balances.stream()
                .collect(Collectors.groupingBy(AccountBalance::accountId, LinkedHashMap::new, Collectors.toList()));
        return byAccount.values().stream().map(rows -> {
            ConvertedSum balance = new ConvertedSum(book, base);
            rows.forEach(row -> balance.add(row.balance(), row.currency(), asOf));
            AccountBalance account = rows.getFirst();
            return new ConvertedBalance(account.accountId(), account.accountCode(), account.accountName(),
                    account.accountType(), base, balance.total(), balance.missing().toList(), balance.rates(),
                    account.familyLedgerId(), account.familyLedgerName(), account.archived());
        }).toList();
    }

    /**
     * {@link #netWorth} in the base currency as of {@code asOf}, with the unrealized revaluation of what is held
     * or owed and the realized result of exchanges (see {@link ConvertedNetWorth}).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedNetWorth netWorthInBase(LedgerScope ledger, LocalDate asOf) {
        String base = settingsService.get(ledger.userId()).baseCurrency();
        // Per day and currency: the postings to ASSET and to LIABILITY accounts, archived ones included, and those to
        // FX_EXCHANGE.
        List<DaySum> sums = jdbc.sql("""
                SELECT CASE a.type WHEN 'EQUITY' THEN 'EXCHANGE' ELSE a.type END AS part, e.entry_date AS day,
                       p.currency, sum(p.amount) AS amount
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId AND e.entry_date <= :asOf
                  AND (a.type IN ('ASSET', 'LIABILITY') OR (a.type = 'EQUITY' AND a.code = :fxExchange))
                GROUP BY 1, 2, 3
                ORDER BY 2, 1, 3""")
                .param("ledgerId", ledger.ledgerId())
                .param("asOf", asOf)
                .param("fxExchange", FX_EXCHANGE)
                .query(DaySum.class)
                .list();
        LocalDate first = sums.isEmpty() ? asOf : sums.getFirst().day();
        RateBook book = rates.rateBook(ledger.userId(), currencies(sums.stream().map(DaySum::currency), base), first,
                asOf);

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
        // Rounded once, from the unrounded figures (D-49).
        ConvertedSum netWorth = new ConvertedSum(book, base).addAll(assets, false).addAll(liabilities, true);
        return new ConvertedNetWorth(base, assets.total(), liabilities.total(), netWorth.total(), revaluation.total(),
                realized.total(), used, missing(assets, liabilities, revaluation, realized));
    }

    /**
     * {@link #cashFlow} in the base currency, each posting converted at the rate on its own day, and per month
     * the realized result of exchanges and the change of the unrealized revaluation (see
     * {@link ConvertedCashFlow.ExchangeResult}).
     *
     * @throws IllegalArgumentException if {@code from} is after {@code to}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedCashFlow cashFlowInBase(LedgerScope ledger, LocalDate from, LocalDate to) {
        return cashFlowInBase(ledger, from, to, CashFlowScope.ALL, null);
    }

    /**
     * {@link #cashFlowInBase(LedgerScope, LocalDate, LocalDate)} of one scope (D-302), as
     * {@link #cashFlow(LedgerScope, LocalDate, LocalDate, CashFlowScope, Long)}. What exchange rates did is no one
     * scope's: its {@code exchangeResults} are empty for every scope but {@link CashFlowScope#ALL}.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedCashFlow cashFlowInBase(LedgerScope ledger, LocalDate from, LocalDate to, CashFlowScope scope,
            Long familyLedgerId) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        boolean all = scope == CashFlowScope.ALL;
        String base = settingsService.get(ledger.userId()).baseCurrency();
        // Per day and currency: categorized postings (CATEGORY) as cashFlow adds them up, postings to FX_EXCHANGE
        // (EXCHANGE), and postings in other currencies than the base currency to ASSET and LIABILITY accounts
        // (HOLDING), which are what is revalued. HOLDING before the period is one sum per currency, without a day.
        List<Long> families = families(ledger);
        checkScope(scope, familyLedgerId, families);
        String exchange = !all ? "" : """
                UNION ALL
                SELECT CASE a.type WHEN 'EQUITY' THEN 'EXCHANGE' ELSE 'HOLDING' END,
                       CASE WHEN e.entry_date < :from THEN NULL ELSE e.entry_date END, p.currency, NULL, NULL, NULL,
                       sum(p.amount), NULL, NULL
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN account a ON a.id = p.account_id
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId AND e.entry_date <= :to
                  AND ((a.type IN ('ASSET', 'LIABILITY') AND p.currency <> :base)
                       OR (a.type = 'EQUITY' AND a.code = :fxExchange AND e.entry_date >= :from))
                GROUP BY 1, 2, 3""";
        var statement = jdbc.sql(categorySums(scope, familyLedgerId, families) + exchange)
                .param("ledgerId", ledger.ledgerId())
                .param("from", from)
                .param("to", to);
        if (all) {
            statement = statement.param("base", base).param("fxExchange", FX_EXCHANGE);
        }
        if (!families.isEmpty()) {
            statement = statement.param("families", families);
        }
        if (familyLedgerId != null) {
            statement = statement.param("familyId", familyLedgerId);
        }
        List<FlowSum> sums = statement.query(FlowSum.class).list();
        // The day before the period: where the first month's revaluation starts from.
        RateBook book = rates.rateBook(ledger.userId(), currencies(sums.stream().map(FlowSum::currency), base),
                from.minusDays(1), to);

        // A family category may share its code with one of the ledger's own (a family ledger created before F4a's
        // merge copied them), so the family budget is part of the key; the ledger's own come first.
        record Category(YearMonth month, CategoryType type, String code, long family) {
        }
        Map<Category, ConvertedSum> totals = new TreeMap<>(Comparator.comparing(Category::month)
                .thenComparing(category -> category.type().name()).thenComparing(Category::code)
                .thenComparingLong(Category::family));
        Map<String, String> names = new LinkedHashMap<>();
        Map<Long, String> familyNames = new LinkedHashMap<>();
        Map<String, BigDecimal> held = new TreeMap<>();
        Map<YearMonth, List<FlowSum>> byMonth = new TreeMap<>();
        for (FlowSum sum : sums) {
            if (sum.part().equals("CATEGORY")) {
                CategoryType type = CategoryType.valueOf(sum.categoryType());
                long family = sum.familyLedgerId() == null ? 0 : sum.familyLedgerId();
                totals.computeIfAbsent(new Category(YearMonth.from(sum.day()), type, sum.categoryCode(), family),
                        category -> new ConvertedSum(book, base)).add(sum.amount(), sum.currency(), sum.day());
                names.put(family + " " + sum.categoryCode(), sum.categoryName());
                if (sum.familyLedgerId() != null) {
                    familyNames.put(family, sum.familyLedgerName());
                }
            } else if (sum.day() == null) {
                held.merge(sum.currency(), sum.amount(), BigDecimal::add);
            } else {
                byMonth.computeIfAbsent(YearMonth.from(sum.day()), month -> new ArrayList<>()).add(sum);
            }
        }
        List<ConvertedCashFlow.Row> rows = totals.entrySet().stream()
                .map(e -> new ConvertedCashFlow.Row(e.getKey().month(), e.getKey().code(),
                        names.get(e.getKey().family() + " " + e.getKey().code()), e.getKey().type(),
                        e.getValue().total(), e.getValue().missing().toList(),
                        e.getKey().family() == 0 ? null : e.getKey().family(), familyNames.get(e.getKey().family()),
                        e.getValue().rates()))
                .toList();

        List<ConvertedCashFlow.ExchangeResult> results = new ArrayList<>();
        for (YearMonth month = YearMonth.from(from); all && !month.isAfter(YearMonth.from(to));
                month = month.plusMonths(1)) {
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
     * The CATEGORY part of {@link #cashFlowInBase(LedgerScope, LocalDate, LocalDate, CashFlowScope, Long)} and
     * {@link #dailyCashFlowInBase}: the scope's categorized postings per day, currency, category and family budget, as
     * {@link #flowRows} classifies them; {@link FlowSum}'s columns.
     */
    private static String categorySums(CashFlowScope scope, Long familyLedgerId, List<Long> families) {
        return """
                SELECT 'CATEGORY' AS part, e.entry_date AS day, p.currency, c.code AS category_code,
                       c.name AS category_name, c.type AS category_type,
                       CASE c.type WHEN 'EXPENSE' THEN sum(p.amount) ELSE -sum(p.amount) END AS amount,
                       f.id AS family_ledger_id, f.name AS family_ledger_name
                FROM journal_entry e
                JOIN posting p ON p.entry_id = e.id
                JOIN category c ON c.id = p.category_id
                LEFT JOIN ledger f ON f.id = c.ledger_id AND f.type = 'SHARED'
                %s
                WHERE e.ledger_id = :ledgerId AND %s AND e.entry_date BETWEEN :from AND :to AND %s
                GROUP BY e.entry_date, p.currency, c.id, f.id
                """.formatted(scopeJoins(scope), categories(families), scopeCondition(scope, familyLedgerId));
    }

    /**
     * {@link #dailyCashFlow} in the base currency (D-570): each posting converted at the rate on its own day, each
     * category's figure of a day rounded once; null with the missing rates where a rate is missing. No exchange results.
     * The postings and the rates are read in two statements inside one snapshot, as the other reports in the base
     * currency.
     *
     * @throws IllegalArgumentException as {@link #dailyCashFlow}
     * @throws NotFoundException as {@link #dailyCashFlow}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConvertedDailyCashFlow dailyCashFlowInBase(LedgerScope ledger, LocalDate from, LocalDate to,
            CashFlowScope scope, Long familyLedgerId) {
        checkDailyRange(from, to);
        String base = settingsService.get(ledger.userId()).baseCurrency();
        List<Long> families = families(ledger);
        checkScope(scope, familyLedgerId, families);
        var statement = jdbc.sql(categorySums(scope, familyLedgerId, families))
                .param("ledgerId", ledger.ledgerId())
                .param("from", from)
                .param("to", to);
        if (!families.isEmpty()) {
            statement = statement.param("families", families);
        }
        if (familyLedgerId != null) {
            statement = statement.param("familyId", familyLedgerId);
        }
        List<FlowSum> sums = statement.query(FlowSum.class).list();
        RateBook book = rates.rateBook(ledger.userId(), currencies(sums.stream().map(FlowSum::currency), base), from,
                to);

        // As in cashFlowInBase, the family budget is part of a category's key; the ledger's own come first.
        record Category(LocalDate day, CategoryType type, String code, long family) {
        }
        Map<Category, ConvertedSum> totals = new TreeMap<>(Comparator.comparing(Category::day)
                .thenComparing(category -> category.type().name()).thenComparing(Category::code)
                .thenComparingLong(Category::family));
        Map<String, String> names = new LinkedHashMap<>();
        Map<Long, String> familyNames = new LinkedHashMap<>();
        for (FlowSum sum : sums) {
            long family = sum.familyLedgerId() == null ? 0 : sum.familyLedgerId();
            totals.computeIfAbsent(new Category(sum.day(), CategoryType.valueOf(sum.categoryType()),
                    sum.categoryCode(), family), category -> new ConvertedSum(book, base))
                    .add(sum.amount(), sum.currency(), sum.day());
            names.put(family + " " + sum.categoryCode(), sum.categoryName());
            if (sum.familyLedgerId() != null) {
                familyNames.put(family, sum.familyLedgerName());
            }
        }
        List<ConvertedDailyCashFlow.Row> rows = totals.entrySet().stream()
                .map(e -> new ConvertedDailyCashFlow.Row(e.getKey().day(), e.getKey().code(),
                        names.get(e.getKey().family() + " " + e.getKey().code()), e.getKey().type(),
                        e.getValue().total(), e.getValue().missing().toList(),
                        e.getKey().family() == 0 ? null : e.getKey().family(), familyNames.get(e.getKey().family()),
                        e.getValue().rates()))
                .toList();
        return new ConvertedDailyCashFlow(base, rows);
    }

    /**
     * The displayed balance of the ledger's shared account per currency, as of {@code asOf}. That is the account the
     * user's settings name, or else FAMILY_DEBT, as for shared expenses (rule 7). Currencies that are settled, with a
     * balance of zero, are left out, and so is everything when the ledger has no shared account. Ordered by currency.
     */
    public List<SharedSettlement> sharedSettlement(LedgerScope ledger, LocalDate asOf) {
        Optional<Long> sharedAccountId = settings.findById(ledger.userId())
                .map(UserSettings::sharedAccountId)
                .or(() -> accounts.findByCode(ledger, AccountRole.SHARED.defaultCode()).map(Account::id));
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
                WHERE e.ledger_id = :ledgerId AND a.ledger_id = :ledgerId AND a.id = :accountId
                  AND e.entry_date <= :asOf
                GROUP BY a.id, p.currency
                HAVING sum(p.amount) <> 0
                ORDER BY p.currency""")
                .param("ledgerId", ledger.ledgerId())
                .param("accountId", sharedAccountId.get())
                .param("asOf", asOf)
                .query(SharedSettlement.class)
                .list();
    }

    /**
     * The currencies in which the ledger doesn't add up, over all dates and all accounts, archived ones
     * included. It is empty for a sound ledger. The triggers of V2 keep every entry balanced, so a violation means
     * that data was written past them.
     * <p>
     * The two figures take different paths to the postings. The posting sum reaches them through the ledger's entries,
     * and assets − liabilities − equity through the ledger's accounts.
     */
    public List<IntegrityViolation> integrityCheck(LedgerScope ledger) {
        List<IntegrityViolation> violations = new ArrayList<>(jdbc.sql("""
                WITH by_entry AS (
                    SELECT p.currency, sum(p.amount) AS posting_sum
                    FROM journal_entry e
                    JOIN posting p ON p.entry_id = e.id
                    WHERE e.ledger_id = :ledgerId
                    GROUP BY p.currency
                ), by_account AS (
                    SELECT p.currency,
                           coalesce(sum(p.amount) FILTER (WHERE a.type = 'ASSET'), 0)
                               - coalesce(-sum(p.amount) FILTER (WHERE a.type = 'LIABILITY'), 0)
                               - coalesce(-sum(p.amount) FILTER (WHERE a.type = 'EQUITY'), 0) AS balance_sheet_gap
                    FROM account a
                    JOIN posting p ON p.account_id = a.id
                    WHERE a.ledger_id = :ledgerId
                    GROUP BY p.currency
                )
                SELECT currency, coalesce(posting_sum, 0) AS posting_sum,
                       coalesce(balance_sheet_gap, 0) AS balance_sheet_gap
                FROM by_entry FULL JOIN by_account USING (currency)
                WHERE coalesce(posting_sum, 0) <> 0 OR coalesce(balance_sheet_gap, 0) <> 0
                ORDER BY currency""")
                .param("ledgerId", ledger.ledgerId())
                .query((row, n) -> new IntegrityViolation(row.getString("currency"), row.getBigDecimal("posting_sum"),
                        row.getBigDecimal("balance_sheet_gap")))
                .list());
        if (ledger.type() == LedgerType.PERSONAL) {
            for (LedgerScope family : access.families(ledger.userId())) {
                violations.addAll(familyDifferences(ledger, family));
            }
        }
        return violations;
    }

    /**
     * D-10 for one family membership, in each currency (D-45, ADR 0004): the displayed balance of the member's debt
     * account for the family ledger in that currency, which the posting service keeps, against their family balance in
     * it, which the records give (FamilyRecordService). A currency of the debt account in which the family has no
     * record counts with a family balance of 0. One row per currency that differs, the main currency first.
     */
    private List<IntegrityViolation> familyDifferences(LedgerScope personal, LedgerScope family) {
        FamilyBalances balances = records.balances(family);
        Map<String, BigDecimal> debts = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT p.currency, -sum(p.amount) AS balance
                FROM account a
                JOIN posting p ON p.account_id = a.id
                JOIN journal_entry e ON e.id = p.entry_id
                WHERE a.ledger_id = :ledgerId AND e.ledger_id = :ledgerId AND a.family_ledger_id = :familyId
                GROUP BY p.currency
                ORDER BY p.currency""")
                .param("ledgerId", personal.ledgerId()).param("familyId", family.ledgerId())
                .query(row -> {
                    debts.put(row.getString("currency"), row.getBigDecimal("balance"));
                });
        Set<String> currencies = new LinkedHashSet<>();
        balances.byCurrency().forEach(inCurrency -> currencies.add(inCurrency.currency()));
        currencies.addAll(debts.keySet());
        List<IntegrityViolation> differences = new ArrayList<>();
        String name = null;
        for (String currency : currencies) {
            BigDecimal familyBalance = balances.in(currency).members().stream()
                    .filter(FamilyBalances.MemberBalance::you).map(FamilyBalances.MemberBalance::balance).findFirst()
                    .orElse(BigDecimal.ZERO);
            BigDecimal debt = debts.getOrDefault(currency, BigDecimal.ZERO);
            if (debt.compareTo(familyBalance) != 0) {
                if (name == null) {
                    name = jdbc.sql("SELECT name FROM ledger WHERE id = :familyId")
                            .param("familyId", family.ledgerId()).query(String.class).single();
                }
                differences.add(new IntegrityViolation(currency, BigDecimal.ZERO, BigDecimal.ZERO, family.ledgerId(),
                        name, debt, familyBalance));
            }
        }
        return differences;
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
            String categoryType, BigDecimal amount, Long familyLedgerId, String familyLedgerName) {
    }

    /** A row of {@link #flowRows}: a category's total in one bucket (a month's first day, or a day) and currency. */
    private record FlowRow(LocalDate bucket, String categoryCode, String categoryName, CategoryType categoryType,
            String currency, BigDecimal total, Long familyLedgerId, String familyLedgerName) {
    }

    private static FlowRow flowRow(ResultSet row, int rowNum) throws SQLException {
        return new FlowRow(row.getObject("bucket", LocalDate.class), row.getString("category_code"),
                row.getString("category_name"), CategoryType.valueOf(row.getString("category_type")),
                row.getString("currency"), row.getBigDecimal("total"), row.getObject("family_ledger_id", Long.class),
                row.getString("family_ledger_name"));
    }

    /**
     * The family ledgers whose categories the ledger's postings may use (D-11, ADR 0003 topic F): its member's ACTIVE
     * memberships, for a personal ledger; none otherwise.
     */
    private List<Long> families(LedgerScope ledger) {
        if (ledger.type() != LedgerType.PERSONAL) {
            return List.of();
        }
        return access.families(ledger.userId()).stream().map(LedgerScope::ledgerId).toList();
    }

    /**
     * Refuses a scope the request can't have (D-302): a family scope with the family switch off, a family budget with
     * a scope that isn't a family one (400), and a family budget the user isn't an ACTIVE member of, answered like a
     * missing ledger, as LedgerAccess answers it (404).
     */
    private void checkScope(CashFlowScope scope, Long familyLedgerId, List<Long> families) {
        if (scope.family() && !familySwitch.enabled()) {
            throw new IllegalArgumentException("The scope %s is about family budgets, which are switched off"
                    .formatted(scope));
        }
        if (familyLedgerId == null) {
            return;
        }
        if (!scope.family()) {
            throw new IllegalArgumentException(
                    "'familyLedgerId' goes only with the scope FAMILY, FAMILY_MINE or FAMILY_OTHERS");
        }
        if (!families.contains(familyLedgerId)) {
            throw new NotFoundException("Ledger %d not found".formatted(familyLedgerId));
        }
    }

    /**
     * The joins a scope needs over the entries {@code e}: the entry's SHARE link {@code l}, of any member's ledger,
     * detached ones included, and its record {@code r}. An entry has at most one link ({@code entry_id} is unique).
     */
    private static String scopeJoins(CashFlowScope scope) {
        return scope == CashFlowScope.ALL ? "" : """
                LEFT JOIN family_entry_link l ON l.entry_id = e.id AND l.link_type = 'SHARE'
                LEFT JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id""";
    }

    /**
     * The condition of a scope on the joins of {@link #scopeJoins}: no SHARE link for a personal entry; for a share,
     * whether the record's payer (its receiver, for an income) is the member the link is of, the user in that budget.
     */
    private static String scopeCondition(CashFlowScope scope, Long familyLedgerId) {
        String family = familyLedgerId == null ? "" : " AND l.family_ledger_id = :familyId";
        return switch (scope) {
            case ALL -> "TRUE";
            case PERSONAL -> "l.id IS NULL";
            case FAMILY -> "l.id IS NOT NULL" + family;
            case FAMILY_MINE -> "l.id IS NOT NULL AND r.payer_member_id = l.member_id" + family;
            case FAMILY_OTHERS -> "l.id IS NOT NULL AND r.payer_member_id <> l.member_id" + family;
        };
    }

    /** The condition on a category {@code c} of the cash flow: the ledger's own, or of one of the families. */
    private static String categories(List<Long> families) {
        return families.isEmpty() ? "c.ledger_id = :ledgerId" : "(c.ledger_id = :ledgerId OR c.ledger_id IN (:families))";
    }
}
