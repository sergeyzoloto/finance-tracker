package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.CurrencyExchangeCommand;
import com.example.financetracker.ledger.domain.EntryCommand;
import com.example.financetracker.ledger.domain.ExpenseCommand;
import com.example.financetracker.ledger.domain.IncomeCommand;
import com.example.financetracker.ledger.domain.LoanGivenCommand;
import com.example.financetracker.ledger.domain.LoanRepaidCommand;
import com.example.financetracker.ledger.domain.ManualCommand;
import com.example.financetracker.ledger.domain.OpeningBalanceCommand;
import com.example.financetracker.ledger.domain.PostingLine;
import com.example.financetracker.ledger.domain.SharedExpenseCommand;
import com.example.financetracker.ledger.domain.TransferCommand;

/**
 * The demo ledger: three years of an invented person's money (UX-17, D-688 to D-690), ending on the day it is loaded,
 * for visitors who want to see the app with data in it. Everything in it is made up for the purpose: no real people,
 * companies or brands, and nothing from the owner's own ledger in data/private.
 * <p>
 * The person is paid a salary in euros and does freelance projects paid in US dollars, which they exchange into euros
 * now and then. They pay rent and bills from the current account, save what is left every month, use a credit card
 * that is paid off every month, share the grocery bills with a partner through the family budget (rule 7), take a
 * summer trip every year, partly paid in dollars, and once lent a friend money that is partly paid back. The ledger
 * starts with opening balances and has at least one entry of every kind.
 * <p>
 * The years differ (D-690): the salary rises every April, the rent every September, the transit pass, the sports club
 * and the streaming subscription now and then; electricity and heating follow the calendar, higher from November to
 * March; every other amount follows the calendar month and is a few per cent higher than the same month a year
 * earlier, so no month repeats it. The occasional entries recur by calendar date every year (the trip, a birthday
 * present, the quarterly interest, freelance projects in different months each year), and the loan and the split
 * purchase happen once, in the last year, whose months have no month a year later to compare with.
 * <p>
 * Plain Java: the entries are commands, which {@code EntryService} validates and writes like any other. The same day
 * gives the same ledger; another day may give other amounts, since they follow the calendar. Each monthly entry falls
 * on a fixed number of days before the same day of an earlier month, at most 27, so every calendar month the ledger
 * covers in full has each monthly entry exactly once, whatever day it is loaded on. With {@value #MONTHS} months it
 * covers in full the two calendar years before the year it is loaded in.
 */
public final class DemoLedger {

    /** The base currency the demo sets. */
    public static final String BASE_CURRENCY = "EUR";
    /** The one other currency in the demo. */
    public static final String FOREIGN_CURRENCY = "USD";
    /** How many months of entries the ledger has, this month included (D-689). */
    public static final int MONTHS = 36;

    /** Accounts the demo adds to the starter ledger's. */
    public static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("CREDIT_CARD", "Credit card", AccountType.LIABILITY, BASE_CURRENCY),
            new DemoAccount("USD_ACCOUNT", "US dollar account", AccountType.ASSET, FOREIGN_CURRENCY));

    /** Categories the demo adds to the starter ledger's. */
    public static final List<DemoCategory> CATEGORIES = List.of(
            new DemoCategory("FREELANCE", "Freelance work", CategoryType.INCOME),
            new DemoCategory("SUBSCRIPTIONS", "Subscriptions", CategoryType.EXPENSE),
            new DemoCategory("TRAVEL", "Travel", CategoryType.EXPENSE));

    private static final String EMPLOYER = "Employer";
    private static final String LANDLORD = "Landlord";
    private static final String SUPERMARKET = "Supermarket";
    private static final String MARKET = "Farmers' market";
    private static final String CAFE = "Corner café";
    private static final String ENERGY = "Energy supplier";
    private static final String SPORTS_CLUB = "Sports club";
    private static final String CLIENT = "Freelance client";
    private static final String DEPARTMENT_STORE = "Department store";
    private static final String FRIEND = "Robin";

    /** The demo's counterparties: descriptions rather than names, and one first name for the friend. */
    public static final List<DemoCounterparty> COUNTERPARTIES = List.of(
            new DemoCounterparty(EMPLOYER, Counterparty.Kind.ORGANIZATION),
            new DemoCounterparty(LANDLORD, Counterparty.Kind.PERSON),
            new DemoCounterparty(SUPERMARKET, Counterparty.Kind.MERCHANT),
            new DemoCounterparty(MARKET, Counterparty.Kind.MERCHANT),
            new DemoCounterparty(CAFE, Counterparty.Kind.MERCHANT),
            new DemoCounterparty(ENERGY, Counterparty.Kind.ORGANIZATION),
            new DemoCounterparty(SPORTS_CLUB, Counterparty.Kind.ORGANIZATION),
            new DemoCounterparty(CLIENT, Counterparty.Kind.ORGANIZATION),
            new DemoCounterparty(DEPARTMENT_STORE, Counterparty.Kind.MERCHANT),
            new DemoCounterparty(FRIEND, Counterparty.Kind.PERSON));

    private static final String CURRENT = "CURRENT_ACCOUNT";
    private static final String SAVINGS = "SAVINGS_ACCOUNT";
    private static final String CASH = "CASH";
    private static final String CARD = "CREDIT_CARD";
    private static final String DOLLARS = "USD_ACCOUNT";
    private static final String FAMILY_BUDGET = "FAMILY_DEBT";
    private static final String UNALLOCATED = "UNALLOCATED";
    private static final String EUR = BASE_CURRENCY;
    private static final String USD = FOREIGN_CURRENCY;
    /** The partner's share of the groceries (rule 7). */
    private static final BigDecimal SHARE_RATIO = new BigDecimal("0.50");

    /** By the Aprils since the entry (0 for this year's level): the salary. */
    private static final String[] SALARY = {"2760.00", "2680.00", "2590.00", "2510.00"};
    /** By the Septembers since the entry: the rent. */
    private static final String[] RENT = {"1140.00", "1110.00", "1080.00", "1050.00"};
    /** By the calendar years since the entry: the transit pass and the sports club, which rise now and then. */
    private static final String[] TRANSIT = {"69.00", "66.00", "66.00", "63.00"};
    private static final String[] SPORTS = {"34.00", "32.00", "32.00", "29.50"};
    /** By the Junes since the entry: the streaming subscription. */
    private static final String[] STREAMING = {"13.99", "12.99", "11.99", "11.99"};
    /**
     * By the calendar years since the entry: the price level of everything else, about 4.5 % a year, so that every
     * other amount is higher than the same month's a year earlier.
     */
    private static final String[] PRICES = {"1.000", "0.955", "0.915", "0.875"};
    /** Electricity and heating by calendar month, January first: higher from November to March. */
    private static final String[] ENERGY_BY_MONTH = {"131.40", "122.80", "104.60", "83.20", "69.50", "61.30", "58.70",
            "60.20", "67.90", "86.40", "109.80", "126.50"};
    /** Each of the variable amounts by calendar month, January first, at this year's prices. */
    private static final String[] FIRST_SHOP = {"104.30", "96.80", "101.20", "98.40", "107.60", "112.90", "94.70",
            "91.20", "103.80", "99.60", "108.40", "131.70"};
    private static final String[] SECOND_SHOP = {"72.60", "68.90", "74.10", "77.30", "70.80", "79.40", "66.20",
            "63.90", "75.70", "71.50", "78.60", "92.30"};
    private static final String[] DINNER = {"38.40", "41.90", "47.60", "44.20", "52.80", "58.30", "61.70", "55.40",
            "46.10", "43.80", "39.60", "68.90"};
    private static final String[] MARKET_SPEND = {"16.40", "17.20", "19.80", "22.60", "25.10", "27.40", "28.90",
            "26.70", "24.30", "21.50", "18.90", "17.60"};
    private static final String[] CAFE_SPEND = {"9.80", "8.60", "10.40", "11.20", "12.90", "13.60", "14.10", "12.40",
            "10.70", "9.30", "8.90", "11.80"};
    private static final String[] LUNCH = {"19.80", "21.40", "22.60", "18.90", "23.70", "20.60", "16.80", "17.90",
            "24.30", "22.10", "21.70", "26.40"};

    /** Freelance projects by the calendar years since the entry: in different months each year (D-690). */
    private static final List<List<Project>> PROJECTS = List.of(
            List.of(new Project(Month.FEBRUARY, "Website project, first milestone", "1150.00"),
                    new Project(Month.JUNE, "Website project, second milestone", "900.00"),
                    new Project(Month.SEPTEMBER, "Website project, final milestone", "1375.00")),
            List.of(new Project(Month.MARCH, "Online shop, design", "980.00"),
                    new Project(Month.JULY, "Online shop, build", "1240.00"),
                    new Project(Month.NOVEMBER, "Online shop, launch", "760.00")),
            List.of(new Project(Month.JANUARY, "Booking app, prototype", "1020.00"),
                    new Project(Month.MAY, "Booking app, first release", "1310.00"),
                    new Project(Month.OCTOBER, "Booking app, support", "640.00")),
            List.of(new Project(Month.APRIL, "Newsletter templates", "870.00"),
                    new Project(Month.AUGUST, "Photo gallery site", "1190.00"),
                    new Project(Month.DECEMBER, "Small fixes for a client", "520.00")));

    /** The summer trip by the calendar years since it: somewhere else each year (D-690). */
    private static final String[] TRIPS = {"the coast", "the mountains", "a city by the river", "the lakes"};

    /** Euros per dollar of the year's exchanges, by the calendar years since them. */
    private static final String[] DOLLAR_PRICE = {"0.862", "0.874", "0.903", "0.921"};

    private DemoLedger() {
    }

    /**
     * The demo's entries, oldest first: the opening balances on the first day of the month about {@value #MONTHS}
     * months before {@code today} in which the other entries start, and the last entries on {@code today}.
     *
     * @param ids the ids of the user's accounts, categories and counterparties, which must include the starter
     *        ledger's and the demo's own
     */
    public static List<EntryCommand> entries(LocalDate today, Ids ids) {
        return new Plan(today, ids).build();
    }

    /** The first day of the demo loaded on {@code today}: the first of the month of its earliest monthly entry. */
    public static LocalDate firstDay(LocalDate today) {
        return today.minusMonths(MONTHS - 1).minusDays(27).withDayOfMonth(1);
    }

    /** The ids of the user's rows that the demo's entries refer to. */
    public interface Ids {

        long account(String code);

        long category(String code);

        long counterparty(String name);
    }

    public record DemoAccount(String code, String name, AccountType type, String defaultCurrency) {
    }

    public record DemoCategory(String code, String name, CategoryType type) {
    }

    public record DemoCounterparty(String name, Counterparty.Kind kind) {
    }

    private record Project(Month month, String memo, String amount) {
    }

    /** An entry of the plan, or one whose amount depends on the balances before it, made once they are known. */
    private record Item(LocalDate date, int seq, Function<Map<Long, BigDecimal>, EntryCommand> make) {
    }

    private static final class Plan {

        private final LocalDate today;
        private final LocalDate start;
        private final Ids ids;
        private final List<Item> items = new ArrayList<>();

        Plan(LocalDate today, Ids ids) {
            this.today = today;
            this.start = firstDay(today);
            this.ids = ids;
        }

        List<EntryCommand> build() {
            opening(CURRENT, EUR, "2480.00");
            opening(SAVINGS, EUR, "6500.00");
            opening(CASH, EUR, "85.00");
            opening(CARD, EUR, "-312.40");
            opening(DOLLARS, USD, "1450.00");
            // What the partner owed for the groceries when the ledger started, so its first month is like the others.
            opening(FAMILY_BUDGET, EUR, "84.20");
            for (int month = 0; month < MONTHS; month++) {
                monthly(new Slot(MONTHS - 1 - month));
            }
            for (int year = start.getYear(); year <= today.getYear(); year++) {
                yearly(year);
            }
            once();

            // In date order, the plan's order within a day; the amounts that depend on balances made on the way.
            items.sort(Comparator.comparing(Item::date).thenComparing(Item::seq));
            Map<Long, BigDecimal> balances = new HashMap<>();
            List<EntryCommand> entries = new ArrayList<>();
            for (Item item : items) {
                EntryCommand command = item.make().apply(balances);
                if (command != null) {
                    apply(balances, command);
                    entries.add(command);
                }
            }
            return List.copyOf(entries);
        }

        /** One month's entries, each a fixed number of days before the slot's day (D-689's months). */
        private void monthly(Slot slot) {
            LocalDate membership = slot.day(27);
            expense(membership, SPORTS_CLUB, "Membership", CURRENT, EUR, SPORTS[years(membership)], "LEISURE");
            LocalDate payday = slot.day(25);
            income(payday, EMPLOYER, "Salary", CURRENT, EUR, SALARY[since(payday, MonthDay.of(4, 1))], "SALARY");
            LocalDate rent = slot.day(24);
            expense(rent, LANDLORD, "Rent", CURRENT, EUR, RENT[since(rent, MonthDay.of(9, 1))], "HOUSING");
            // The card's bill clears what it owes; savings take what the current account holds above a buffer.
            computed(slot.day(24), balances -> {
                BigDecimal owed = balance(balances, CARD).negate();
                return owed.signum() > 0 ? transfer(slot.day(24), "Credit card bill", CURRENT, CARD, owed) : null;
            });
            computed(slot.day(23), balances -> transfer(slot.day(23), "Monthly savings", CURRENT, SAVINGS,
                    clamp(floorTo(balance(balances, CURRENT).subtract(new BigDecimal("3200")), "50"), "200", "1500")));
            LocalDate pass = slot.day(22);
            expense(pass, null, "Monthly transit pass", CURRENT, EUR, TRANSIT[years(pass)], "TRANSPORT");
            shared(slot.day(21), "Weekly shop", varied(slot.day(21), FIRST_SHOP, 1));
            expense(slot.day(19), null, "Dinner out", CARD, EUR, varied(slot.day(19), DINNER, 2), "EATING_OUT");
            // Cash topped up to about 120.
            computed(slot.day(18), balances -> transfer(slot.day(18), "Cash withdrawal", CURRENT, CASH,
                    clamp(ceilTo(new BigDecimal("120").subtract(balance(balances, CASH)), "10"), "20", "200")));
            // The partner pays back their share of the groceries since the last time (rule 7).
            computed(slot.day(17), balances -> {
                BigDecimal owed = balance(balances, FAMILY_BUDGET);
                return owed.signum() > 0
                        ? transfer(slot.day(17), "Partner's share of the groceries", FAMILY_BUDGET, CURRENT, owed)
                        : null;
            });
            expense(slot.day(15), MARKET, "Vegetables and bread", CASH, EUR, varied(slot.day(15), MARKET_SPEND, 3),
                    "GROCERIES");
            LocalDate energy = slot.day(13);
            expense(energy, ENERGY, "Electricity and heating", CURRENT, EUR, varied(energy, ENERGY_BY_MONTH, 4),
                    "UTILITIES");
            LocalDate streaming = slot.day(12);
            expense(streaming, null, "Streaming subscription", CARD, EUR, STREAMING[since(streaming, MonthDay.of(6, 1))],
                    "SUBSCRIPTIONS");
            expense(slot.day(11), null, "Design app subscription", DOLLARS, USD, "15.00", "SUBSCRIPTIONS");
            shared(slot.day(9), "Weekly shop", varied(slot.day(9), SECOND_SHOP, 5));
            expense(slot.day(7), CAFE, "Coffee and cake", CASH, EUR, varied(slot.day(7), CAFE_SPEND, 6), "EATING_OUT");
            expense(slot.day(6), null, "Internet and phone", CURRENT, EUR, "42.50", "UTILITIES");
            expense(slot.day(4), null, "Lunch with colleagues", CARD, EUR, varied(slot.day(4), LUNCH, 7),
                    "EATING_OUT");
            expense(slot.day(0), null, "Account fee", CURRENT, EUR, "3.95", "BANK_FEES");
        }

        /** The entries that recur once a year, on the same calendar day, within the ledger's days (D-690). */
        private void yearly(int year) {
            int back = today.getYear() - year;
            for (Project project : PROJECTS.get(Math.min(back, PROJECTS.size() - 1))) {
                LocalDate date = LocalDate.of(year, project.month(), 14);
                if (within(date)) {
                    income(date, CLIENT, project.memo(), DOLLARS, USD, project.amount(), "FREELANCE");
                }
            }
            on(year, 2, 9, date -> expense(date, null, "Pharmacy", CASH, EUR, grown(date, "13.80", 1), "HEALTH"));
            on(year, 3, 14, date -> income(date, null, "Birthday present from family", CURRENT, EUR,
                    new String[] {"120.00", "100.00", "100.00", "75.00"}[Math.min(back, 3)], "GIFTS_RECEIVED"));
            on(year, 4, 6, date -> expense(date, null, "Bike service", CASH, EUR, grown(date, "38.00", 2),
                    "TRANSPORT"));
            on(year, 4, 19, date -> expense(date, DEPARTMENT_STORE, "Spring shoes", CURRENT, EUR,
                    grown(date, "64.90", 3), "CLOTHING"));
            on(year, 5, 23, date -> expense(date, null, "Birthday present for a friend", CARD, EUR,
                    grown(date, "45.00", 4), "GIFTS"));
            String trip = TRIPS[Math.min(back, TRIPS.length - 1)];
            on(year, 6, 24, date -> expense(date, null, "Train tickets to " + trip, CARD, EUR,
                    grown(date, "128.60", 5), "TRAVEL"));
            on(year, 7, 3, date -> expense(date, null, "Hotel, " + trip + ", three nights", DOLLARS, USD,
                    grown(date, "486.00", 6), "TRAVEL"));
            on(year, 7, 5, date -> expense(date, null, "Museum tickets", DOLLARS, USD, grown(date, "42.00", 7),
                    "LEISURE"));
            on(year, 9, 12, date -> expense(date, null, "Dental check-up", CURRENT, EUR, grown(date, "65.00", 1),
                    "HEALTH"));
            on(year, 10, 16, date -> expense(date, DEPARTMENT_STORE, "Jacket and shirt", CURRENT, EUR,
                    grown(date, "114.85", 2), "CLOTHING"));
            // A refund: the same category with the opposite sign (rule 5).
            on(year, 10, 27, date -> expense(date, DEPARTMENT_STORE, "Shirt returned", CURRENT, EUR,
                    "-" + grown(date, "24.95", 3), "CLOTHING"));
            on(year, 12, 15, date -> expense(date, null, "Presents for the holidays", CARD, EUR,
                    grown(date, "95.00", 4), "GIFTS"));
            // The savings' interest at each quarter's end, on what they hold.
            for (int quarterEnd : new int[] {3, 6, 9, 12}) {
                LocalDate date = LocalDate.of(year, quarterEnd, 1).withDayOfMonth(
                        LocalDate.of(year, quarterEnd, 1).lengthOfMonth());
                if (within(date)) {
                    computed(date, balances -> incomeCommand(date, null, "Quarterly interest", SAVINGS, EUR,
                            balance(balances, SAVINGS).multiply(new BigDecimal("0.0035"))
                                    .setScale(2, RoundingMode.HALF_UP).toPlainString(), "INTEREST"));
                }
            }
            // Dollars to euros now and then, keeping enough dollars for the summer.
            for (int month : new int[] {2, 5, 8, 11}) {
                LocalDate date = LocalDate.of(year, month, 12);
                if (within(date)) {
                    BigDecimal price = new BigDecimal(DOLLAR_PRICE[Math.min(back, DOLLAR_PRICE.length - 1)])
                            .add(new BigDecimal(month).movePointLeft(4));
                    computed(date, balances -> {
                        BigDecimal sold = floorTo(balance(balances, DOLLARS).subtract(new BigDecimal("1100")), "100");
                        return sold.compareTo(new BigDecimal("500")) < 0 ? null
                                : new CurrencyExchangeCommand(date, null, "Dollars to euros", account(DOLLARS), USD,
                                        sold, account(CURRENT), EUR, sold.multiply(price)
                                                .setScale(2, RoundingMode.HALF_UP));
                    });
                }
            }
        }

        /** The entries that happen once, in the last year (D-690). */
        private void once() {
            Slot lent = new Slot(9);
            fixed(lent.day(20), new LoanGivenCommand(lent.day(20), null, "Lent to Robin for a new laptop",
                    account(CURRENT), counterparty(FRIEND), EUR, amount("300.00")));
            Slot half = new Slot(7);
            fixed(half.day(3), new LoanRepaidCommand(half.day(3), null, "Robin paid back half", account(CURRENT),
                    counterparty(FRIEND), EUR, amount("150.00")));
            Slot more = new Slot(1);
            fixed(more.day(5), new LoanRepaidCommand(more.day(5), null, "Robin paid back some more, in cash",
                    account(CASH), counterparty(FRIEND), EUR, amount("100.00")));
            // One payment split over two categories: raw postings, as the Advanced tab writes them.
            Slot split = new Slot(4);
            fixed(split.day(10), new ManualCommand(split.day(10), counterparty(DEPARTMENT_STORE),
                    "Birthday present and household things", List.of(
                            new PostingLine(account(CARD), EUR, amount("-86.40"), null, null),
                            new PostingLine(account(UNALLOCATED), EUR, amount("54.90"), category("GIFTS"), null),
                            new PostingLine(account(UNALLOCATED), EUR, amount("31.50"), category("OTHER_EXPENSES"),
                                    null))));
        }

        /** How many times {@code day} of the year came after {@code date}, up to today: 0 for this year's level. */
        private int since(LocalDate date, MonthDay day) {
            int count = 0;
            for (int year = date.getYear(); year <= today.getYear(); year++) {
                LocalDate at = day.atYear(year);
                if (at.isAfter(date) && !at.isAfter(today)) {
                    count++;
                }
            }
            return Math.min(count, 3);
        }

        /** The calendar years from {@code date}'s to today's. */
        private int years(LocalDate date) {
            return Math.min(today.getYear() - date.getYear(), 3);
        }

        /**
         * A variable amount: its calendar month's, at the price level of its year, and up to 3 % more by a small
         * deterministic step of the year and month, so that it is 1.5 % to 8 % above the same month's a year earlier.
         */
        private String varied(LocalDate date, String[] byMonth, int salt) {
            return grown(date, byMonth[date.getMonthValue() - 1], salt);
        }

        private String grown(LocalDate date, String amount, int salt) {
            int step = Math.floorMod(date.getYear() * 31 + date.getMonthValue() * 7 + salt * 13, 7);
            return new BigDecimal(amount).multiply(new BigDecimal(PRICES[years(date)]))
                    .multiply(BigDecimal.ONE.add(new BigDecimal(step).multiply(new BigDecimal("0.005"))))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        private boolean within(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(today);
        }

        private void on(int year, int month, int day, Consumer<LocalDate> add) {
            LocalDate date = LocalDate.of(year, month, day);
            if (within(date)) {
                add.accept(date);
            }
        }

        private void opening(String account, String currency, String amount) {
            fixed(start, new OpeningBalanceCommand(start, "Balance when the ledger started", account(account),
                    currency, amount(amount), null));
        }

        private void expense(LocalDate date, String payee, String memo, String account, String currency,
                String amount, String category) {
            fixed(date, new ExpenseCommand(date, payee == null ? null : counterparty(payee), memo, account(account),
                    currency, amount(amount), category(category)));
        }

        private void income(LocalDate date, String payee, String memo, String account, String currency,
                String amount, String category) {
            fixed(date, incomeCommand(date, payee, memo, account, currency, amount, category));
        }

        private IncomeCommand incomeCommand(LocalDate date, String payee, String memo, String account,
                String currency, String amount, String category) {
            return new IncomeCommand(date, payee == null ? null : counterparty(payee), memo, account(account),
                    currency, amount(amount), category(category));
        }

        private TransferCommand transfer(LocalDate date, String memo, String from, String to, BigDecimal amount) {
            return new TransferCommand(date, null, memo, account(from), account(to), EUR, amount, null);
        }

        /** Groceries paid from the current account and shared with the partner (rule 7). */
        private void shared(LocalDate date, String memo, String total) {
            fixed(date, new SharedExpenseCommand(date, counterparty(SUPERMARKET), memo, account(CURRENT), EUR,
                    amount(total), category("GROCERIES"), SHARE_RATIO));
        }

        private void fixed(LocalDate date, EntryCommand command) {
            items.add(new Item(date, items.size(), balances -> command));
        }

        private void computed(LocalDate date, Function<Map<Long, BigDecimal>, EntryCommand> make) {
            items.add(new Item(date, items.size(), make));
        }

        private BigDecimal balance(Map<Long, BigDecimal> balances, String code) {
            return balances.getOrDefault(account(code), BigDecimal.ZERO);
        }

        /** What a command does to the plan's own accounts; the categories' side isn't followed. */
        private void apply(Map<Long, BigDecimal> balances, EntryCommand command) {
            switch (command) {
                case ExpenseCommand c -> balances.merge(c.accountId(), c.amount().negate(), BigDecimal::add);
                case IncomeCommand c -> balances.merge(c.accountId(), c.amount(), BigDecimal::add);
                case TransferCommand c -> {
                    balances.merge(c.fromAccountId(), c.amount().negate(), BigDecimal::add);
                    balances.merge(c.toAccountId(), c.amount(), BigDecimal::add);
                }
                case SharedExpenseCommand c -> {
                    balances.merge(c.accountId(), c.total().negate(), BigDecimal::add);
                    balances.merge(account(FAMILY_BUDGET), c.otherShare(SHARE_RATIO), BigDecimal::add);
                }
                case LoanGivenCommand c -> balances.merge(c.fromAccountId(), c.amount().negate(), BigDecimal::add);
                case LoanRepaidCommand c -> balances.merge(c.toAccountId(), c.amount(), BigDecimal::add);
                case CurrencyExchangeCommand c -> {
                    balances.merge(c.fromAccountId(), c.fromAmount().negate(), BigDecimal::add);
                    balances.merge(c.toAccountId(), c.toAmount(), BigDecimal::add);
                }
                case ManualCommand c -> c.postings().forEach(p -> balances.merge(p.accountId(), p.amount(),
                        BigDecimal::add));
                case OpeningBalanceCommand c -> balances.merge(c.accountId(), c.amount(), BigDecimal::add);
                default -> throw new IllegalStateException("No demo entry is a " + command.kind());
            }
        }

        private long account(String code) {
            return ids.account(code);
        }

        private long category(String code) {
            return ids.category(code);
        }

        private long counterparty(String name) {
            return ids.counterparty(name);
        }

        private static BigDecimal amount(String amount) {
            return new BigDecimal(amount);
        }

        private static BigDecimal floorTo(BigDecimal amount, String step) {
            BigDecimal unit = new BigDecimal(step);
            return amount.divide(unit, 0, RoundingMode.FLOOR).multiply(unit);
        }

        private static BigDecimal ceilTo(BigDecimal amount, String step) {
            BigDecimal unit = new BigDecimal(step);
            return amount.divide(unit, 0, RoundingMode.CEILING).multiply(unit);
        }

        private static BigDecimal clamp(BigDecimal amount, String low, String high) {
            return amount.max(new BigDecimal(low)).min(new BigDecimal(high)).setScale(2);
        }

        /**
         * One month of the ledger: the days up to 27 days before the same day of the month, {@code monthsBack}
         * months ago.
         */
        private final class Slot {

            private final int monthsBack;

            Slot(int monthsBack) {
                this.monthsBack = monthsBack;
            }

            /** {@code daysBack} days before this month's anchor, from 0 to 27. */
            LocalDate day(int daysBack) {
                if (daysBack < 0 || daysBack > 27) {
                    throw new IllegalArgumentException("Days back must be 0 to 27, not " + daysBack);
                }
                return today.minusMonths(monthsBack).minusDays(daysBack);
            }
        }
    }
}
