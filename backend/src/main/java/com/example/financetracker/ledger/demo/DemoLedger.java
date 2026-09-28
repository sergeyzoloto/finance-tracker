package com.example.financetracker.ledger.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
 * The demo ledger: six months of an invented person's money, ending on the day it is loaded, for visitors who want to
 * see the app with data in it. Everything in it is made up for the purpose: no real people, companies or brands, and
 * nothing from the owner's own ledger in data/private.
 * <p>
 * The person is paid a salary in euros and does some freelance work paid in US dollars, which they exchange into
 * euros now and then. They pay rent and bills from the current account, save every month, use a credit card that is
 * paid off every month, share the grocery bills with a partner through the family budget (rule 7), and lent a friend
 * money that is partly paid back. The ledger starts with opening balances and has at least one entry of every kind.
 * <p>
 * Plain Java: the entries are commands, which {@code EntryService} validates and writes like any other. Apart from
 * the dates, the ledger is the same every time: the same entries, amounts and memos. Each monthly entry falls on a
 * fixed number of days before the same day of an earlier month, at most 27, so every calendar month the ledger covers
 * in full has each monthly entry exactly once, whatever day it is loaded on.
 */
public final class DemoLedger {

    /** The base currency the demo sets. */
    public static final String BASE_CURRENCY = "EUR";
    /** The one other currency in the demo. */
    public static final String FOREIGN_CURRENCY = "USD";
    /** How many months of entries the ledger has, this month included. */
    static final int MONTHS = 6;

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

    private static final class Plan {

        private final LocalDate today;
        private final Ids ids;
        private final List<EntryCommand> entries = new ArrayList<>();
        /** What the partner pays back each month: their share of the month before's groceries. */
        private BigDecimal partnersShare = BigDecimal.ZERO;

        Plan(LocalDate today, Ids ids) {
            this.today = today;
            this.ids = ids;
        }

        List<EntryCommand> build() {
            // What each month's card payment clears: the opening balance, then the charges of the month before.
            BigDecimal cardOwed = new BigDecimal("312.40");

            for (int month = 0; month < MONTHS; month++) {
                BigDecimal charges = BigDecimal.ZERO;
                BigDecimal share = BigDecimal.ZERO;
                Slot slot = new Slot(MONTHS - 1 - month, month);

                expense(slot.day(27), SPORTS_CLUB, "Membership", CURRENT, EUR, "32.00", "LEISURE");
                income(slot.day(25), EMPLOYER, "Salary", CURRENT, EUR, "2480.00", "SALARY");
                expense(slot.day(24), LANDLORD, "Rent", CURRENT, EUR, "1080.00", "HOUSING");
                transfer(slot.day(24), "Credit card bill", CURRENT, CARD, cardOwed, null);
                transfer(slot.day(23), "Monthly savings", CURRENT, SAVINGS, amount("550.00"), null);
                expense(slot.day(22), null, "Monthly transit pass", CURRENT, EUR, "64.00", "TRANSPORT");
                share = share.add(shared(slot.day(21), "Weekly shop", slot.pick(
                        "86.40", "112.75", "94.20", "78.95", "131.60", "102.30")));
                charges = charges.add(expense(slot.day(19), null, "Dinner out", CARD, EUR, slot.pick(
                        "42.50", "36.90", "58.00", "47.30", "39.80", "61.40"), "EATING_OUT"));
                transfer(slot.day(18), "Cash withdrawal", CURRENT, CASH, amount("50.00"), null);
                if (partnersShare.signum() > 0) {
                    transfer(slot.day(17), "Partner's share of the groceries", FAMILY_BUDGET, CURRENT, partnersShare,
                            null);
                }
                expense(slot.day(15), MARKET, "Vegetables and bread", CASH, EUR, slot.pick(
                        "18.60", "23.40", "16.75", "21.90", "25.30", "19.45"), "GROCERIES");
                expense(slot.day(13), ENERGY, "Electricity and heating", CURRENT, EUR, slot.pick(
                        "96.40", "104.10", "88.75", "79.30", "72.60", "81.20"), "UTILITIES");
                charges = charges.add(expense(slot.day(12), null, "Streaming subscription", CARD, EUR, "11.99",
                        "SUBSCRIPTIONS"));
                expense(slot.day(11), null, "Design app subscription", DOLLARS, USD, "15.00", "SUBSCRIPTIONS");
                share = share.add(shared(slot.day(9), "Weekly shop", slot.pick(
                        "64.10", "71.85", "58.40", "92.70", "67.25", "74.90")));
                expense(slot.day(7), CAFE, "Coffee and cake", CASH, EUR, slot.pick(
                        "7.80", "11.20", "6.40", "9.60", "8.90", "12.10"), "EATING_OUT");
                expense(slot.day(6), null, "Internet and phone", CURRENT, EUR, "42.50", "UTILITIES");
                charges = charges.add(expense(slot.day(4), null, "Lunch with colleagues", CARD, EUR, slot.pick(
                        "18.40", "22.10", "16.90", "24.60", "19.80", "21.30"), "EATING_OUT"));
                expense(slot.day(1), null, "Account fee", CURRENT, EUR, "3.95", "BANK_FEES");

                charges = charges.add(occasional(slot));
                cardOwed = charges;
                partnersShare = share;
            }
            // The ledger starts on the first day of its first month, so its months are whole from the start.
            LocalDate start = entries.stream().map(EntryCommand::entryDate).min(Comparator.naturalOrder()).orElseThrow()
                    .withDayOfMonth(1);
            List<EntryCommand> monthly = List.copyOf(entries);
            entries.clear();
            opening(start, CURRENT, EUR, "2480.00");
            opening(start, SAVINGS, EUR, "6500.00");
            opening(start, CASH, EUR, "85.00");
            opening(start, CARD, EUR, "-312.40");
            opening(start, DOLLARS, USD, "640.00");
            entries.addAll(monthly);
            // Stable: on the first day, the opening balances come first.
            entries.sort(Comparator.comparing(EntryCommand::entryDate));
            return List.copyOf(entries);
        }

        /** The entries that don't recur every month, with what they charge to the credit card. */
        private BigDecimal occasional(Slot slot) {
            BigDecimal charges = BigDecimal.ZERO;
            switch (slot.monthsBack()) {
                case 5 -> income(slot.day(14), CLIENT, "Website project, first milestone", DOLLARS, USD, "1150.00",
                        "FREELANCE");
                case 4 -> {
                    add(new LoanGivenCommand(slot.day(20), null, "Lent to Robin for a new laptop", account(CURRENT),
                            counterparty(FRIEND), EUR, amount("300.00")));
                    add(new CurrencyExchangeCommand(slot.day(10), null, "Dollars to euros", account(DOLLARS), USD,
                            amount("1000.00"), account(CURRENT), EUR, amount("851.40")));
                    expense(slot.day(8), null, "Pharmacy", CASH, EUR, "12.35", "HEALTH");
                }
                case 3 -> {
                    income(slot.day(20), null, "Birthday present from family", CURRENT, EUR, "100.00",
                            "GIFTS_RECEIVED");
                    charges = charges.add(expense(slot.day(16), DEPARTMENT_STORE, "Jacket and shirt", CARD, EUR,
                            "114.85", "CLOTHING"));
                    income(slot.day(14), CLIENT, "Website project, second milestone", DOLLARS, USD, "900.00",
                            "FREELANCE");
                    // A refund: the same category with the opposite sign (rule 5).
                    charges = charges.add(expense(slot.day(5), DEPARTMENT_STORE, "Shirt returned", CARD, EUR,
                            "-24.95", "CLOTHING"));
                    income(slot.day(0), null, "Quarterly interest", SAVINGS, EUR, "14.20", "INTEREST");
                }
                case 2 -> {
                    charges = charges.add(expense(slot.day(20), null, "Train tickets", CARD, EUR, "128.60", "TRAVEL"));
                    expense(slot.day(18), null, "Hotel, three nights", DOLLARS, USD, "486.00", "TRAVEL");
                    expense(slot.day(16), null, "Museum tickets", DOLLARS, USD, "42.00", "LEISURE");
                    // One payment split over two categories: raw postings, as the Advanced tab writes them.
                    add(new ManualCommand(slot.day(10), counterparty(DEPARTMENT_STORE),
                            "Birthday present and household things", List.of(
                                    new PostingLine(account(CARD), EUR, amount("-86.40"), null, null),
                                    new PostingLine(account(UNALLOCATED), EUR, amount("54.90"), category("GIFTS"),
                                            null),
                                    new PostingLine(account(UNALLOCATED), EUR, amount("31.50"),
                                            category("OTHER_EXPENSES"), null))));
                    charges = charges.add(amount("86.40"));
                    add(new LoanRepaidCommand(slot.day(3), null, "Robin paid back half", account(CURRENT),
                            counterparty(FRIEND), EUR, amount("150.00")));
                }
                case 1 -> {
                    expense(slot.day(20), null, "Dental check-up", CURRENT, EUR, "65.00", "HEALTH");
                    income(slot.day(14), CLIENT, "Website project, final milestone", DOLLARS, USD, "1375.00",
                            "FREELANCE");
                    add(new CurrencyExchangeCommand(slot.day(8), null, "Dollars to euros", account(DOLLARS), USD,
                            amount("1200.00"), account(CURRENT), EUR, amount("1024.60")));
                    expense(slot.day(3), null, "Bike repair", CASH, EUR, "38.00", "TRANSPORT");
                }
                case 0 -> {
                    add(new LoanRepaidCommand(slot.day(5), null, "Robin paid back some more, in cash", account(CASH),
                            counterparty(FRIEND), EUR, amount("100.00")));
                    income(slot.day(0), null, "Quarterly interest", SAVINGS, EUR, "15.35", "INTEREST");
                }
                default -> throw new IllegalStateException("No month " + slot.monthsBack());
            }
            return charges;
        }

        private void opening(LocalDate date, String account, String currency, String amount) {
            add(new OpeningBalanceCommand(date, "Balance when the ledger started", account(account), currency,
                    amount(amount), null));
        }

        /** @return what the expense charges to the credit card if it is paid with it */
        private BigDecimal expense(LocalDate date, String payee, String memo, String account, String currency,
                String amount, String category) {
            add(new ExpenseCommand(date, payee == null ? null : counterparty(payee), memo, account(account), currency,
                    amount(amount), category(category)));
            return account.equals(CARD) ? amount(amount) : BigDecimal.ZERO;
        }

        private void income(LocalDate date, String payee, String memo, String account, String currency,
                String amount, String category) {
            add(new IncomeCommand(date, payee == null ? null : counterparty(payee), memo, account(account), currency,
                    amount(amount), category(category)));
        }

        private void transfer(LocalDate date, String memo, String from, String to, BigDecimal amount,
                String counterparty) {
            add(new TransferCommand(date, null, memo, account(from), account(to), EUR, amount,
                    counterparty == null ? null : counterparty(counterparty)));
        }

        /**
         * Groceries paid from the current account and shared with the partner.
         *
         * @return the partner's share, as {@link SharedExpenseCommand} computes it (rule 7)
         */
        private BigDecimal shared(LocalDate date, String memo, String total) {
            SharedExpenseCommand command = new SharedExpenseCommand(date, counterparty(SUPERMARKET), memo,
                    account(CURRENT), EUR, amount(total), category("GROCERIES"), SHARE_RATIO);
            add(command);
            return command.otherShare(SHARE_RATIO);
        }

        private void add(EntryCommand command) {
            entries.add(command);
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

        /**
         * One month of the ledger: the days up to 27 days before the same day of the month, {@code monthsBack}
         * months ago.
         *
         * @param index 0 for the oldest month
         */
        private final class Slot {

            private final int monthsBack;
            private final int index;

            Slot(int monthsBack, int index) {
                this.monthsBack = monthsBack;
                this.index = index;
            }

            int monthsBack() {
                return monthsBack;
            }

            /** {@code daysBack} days before this month's anchor, from 0 to 27. */
            LocalDate day(int daysBack) {
                if (daysBack < 0 || daysBack > 27) {
                    throw new IllegalArgumentException("Days back must be 0 to 27, not " + daysBack);
                }
                return today.minusMonths(monthsBack).minusDays(daysBack);
            }

            /** This month's amount of those given for each month, oldest first. */
            String pick(String... amounts) {
                return amounts[index];
            }
        }
    }
}
