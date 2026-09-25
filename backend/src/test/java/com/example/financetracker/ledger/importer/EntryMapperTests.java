package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.importer.Chart.AccountRef;
import com.example.financetracker.ledger.importer.Chart.CategoryRef;
import com.example.financetracker.ledger.importer.EntryPlan.Line;
import com.example.financetracker.ledger.importer.EntryPlan.Party;
import com.example.financetracker.ledger.importer.Workbook.OpeningBalanceRow;
import org.junit.jupiter.api.Test;

/**
 * The rules for turning one row into one entry, on cases the test fixture of {@code ImportServiceTests} doesn't
 * reach; without Spring or a database. Names are invented.
 */
class EntryMapperTests {

    static final AccountRef WALLET = new AccountRef(1, "CASH", AccountType.ASSET, false);
    static final AccountRef CARD = new AccountRef(2, "CARD", AccountType.ASSET, false);
    static final AccountRef FREE = new AccountRef(3, "UNALLOCATED", AccountType.EQUITY, false);
    static final AccountRef FAMILY = new AccountRef(4, "FAMILY_DEBT", AccountType.LIABILITY, false);
    static final AccountRef LOANS = new AccountRef(5, "LOANS_ASSET", AccountType.ASSET, true);
    static final AccountRef CREDIT_CARD = new AccountRef(6, "CREDIT_CARD", AccountType.LIABILITY, false);
    static final AccountRef FX = new AccountRef(7, "FX_EXCHANGE", AccountType.EQUITY, false);
    static final AccountRef OPENING = new AccountRef(8, "OPENING_BALANCE", AccountType.EQUITY, false);
    static final CategoryRef FOOD = new CategoryRef(21, "FOOD", CategoryType.EXPENSE);
    static final CategoryRef SALARY = new CategoryRef(22, "SALARY", CategoryType.INCOME);

    static final Map<String, AccountRef> ACCOUNTS = Map.of("Wallet", WALLET, "Card", CARD, "Free money", FREE,
            "Family budget", FAMILY, "Lent", LOANS, "Credit card", CREDIT_CARD);
    static final EntryMapper MAPPER = new EntryMapper(new Chart(ACCOUNTS,
            List.of(WALLET, CARD, FREE, FAMILY, LOANS, CREDIT_CARD, FX, OPENING).stream()
                    .collect(Collectors.toMap(AccountRef::code, account -> account)),
            Map.of("Food", FOOD, "Salary", SALARY), FAMILY));

    @Test
    void anExpenseRefundSharedWithTheFamilyKeepsItsSign() {
        RowResult result = MAPPER.map(row(Map.of("Exp. Item", "Food", "Sum", "-845.55", "Debit", "Free money",
                "Credit", "Card", "Family", "да", "CUR_FAMILY", "RUB", "FAMILY_EXP", "422.78",
                "DEBIT_CHANGE", "422.78", "CODE_ITEM", "FOOD")));

        EntryPlan plan = ((RowResult.Entry) result).plan();
        assertThat(plan.kind()).isEqualTo(EntryKind.SHARED_EXPENSE);
        assertThat(plan.postings()).containsExactly(
                line(CARD, "RUB", "845.55"),
                new Line(FREE.id(), "RUB", money("-422.77"), FOOD.id(), null),
                line(FAMILY, "RUB", "-422.78"));
        assertThat(result.warnings()).containsExactly("family split of Sum -845.55: the own part is -422.77, and "
                + "the workbook's (minus DEBIT_CHANGE) is -422.78; the family's part is -422.78");
    }

    @Test
    void anExchangeWithACategoryPostsTheCategoryInItsOwnCurrency() {
        RowResult result = MAPPER.map(row(Map.of("Exp. Item", "Food", "Sum", "1000", "Debit", "Free money",
                "Credit", "Card", "CUR_DEBIT", "EUR", "SUM_DEBIT", "10.50")));

        EntryPlan plan = ((RowResult.Entry) result).plan();
        assertThat(plan.kind()).isEqualTo(EntryKind.EXPENSE);
        assertThat(plan.postings()).containsExactly(
                line(CARD, "RUB", "-1000"),
                line(FX, "RUB", "1000"),
                line(FX, "EUR", "-10.50"),
                new Line(FREE.id(), "EUR", money("10.50"), FOOD.id(), null));
    }

    @Test
    void rowsTheRulesDoNotCoverAreErrors() {
        assertThat(errors(Map.of("FAMILY_INC", "10")))
                .containsExactly("FAMILY_INC is 10.00: shared income is for manual review");
        assertThat(errors(Map.of("Exp. Item", "Food")))
                .containsExactly("Exp. Item 'Food' is a category, and neither Debit nor Credit is UNALLOCATED");
        assertThat(errors(Map.of("Exp. Item", "Salary", "Debit", "Card", "Credit", "Free money", "Family", "да",
                "CUR_FAMILY", "RUB", "FAMILY_EXP", "-50")))
                .containsExactly("Family is 'да', and Debit is not UNALLOCATED");
        assertThat(errors(Map.of("Exp. Item", "Food", "Debit", "Free money", "Family", "да", "CUR_FAMILY", "EUR",
                "FAMILY_EXP", "-50")))
                .containsExactly("CUR_FAMILY is EUR, and the UNALLOCATED posting is in RUB");
        assertThat(errors(Map.of("SUM_DEBIT", "100", "SUM_CREDIT", "99")))
                .containsExactly("the debit side's 100.00 and the credit side's 99.00 differ, in one currency RUB");
        assertThat(errors(Map.of("Debit", "Card", "Credit", "Card")))
                .containsExactly("Debit and Credit are the same account");
        assertThat(errors(Map.of("Debit", "Pocket", "Exp. Item", "Fuel")))
                .containsExactly("Debit 'Pocket' is not in the accounts file", "Exp. Item 'Fuel' is not in the categories file");
    }

    @Test
    void theSignOfTheLoanPostingTellsLendingFromRepayment() {
        EntryPlan lent = ((RowResult.Entry) MAPPER.map(row(Map.of("Debit", "Lent", "Credit", "Wallet",
                "LOAN_CA", "Friend")))).plan();
        EntryPlan repaid = ((RowResult.Entry) MAPPER.map(row(Map.of("Debit", "Lent", "Credit", "Wallet",
                "Sum", "-40", "LOAN_CA", "Friend")))).plan();

        assertThat(lent.kind()).isEqualTo(EntryKind.LOAN_GIVEN);
        assertThat(lent.postings()).containsExactly(line(WALLET, "RUB", "-100"),
                new Line(LOANS.id(), "RUB", money("100"), null, new Party("Friend", Counterparty.Kind.PERSON)));
        assertThat(repaid.kind()).isEqualTo(EntryKind.LOAN_REPAID);
        assertThat(repaid.postings()).containsExactly(line(WALLET, "RUB", "40"),
                new Line(LOANS.id(), "RUB", money("-40"), null, new Party("Friend", Counterparty.Kind.PERSON)));
    }

    @Test
    void aBorrowerOnARowWithoutLoansIsIgnoredWithAWarning() {
        RowResult result = MAPPER.map(row(Map.of("LOAN_CA", "Friend")));

        assertThat(((RowResult.Entry) result).plan().kind()).isEqualTo(EntryKind.TRANSFER);
        assertThat(result.warnings())
                .containsExactly("LOAN_CA 'Friend' is ignored: neither account requires a counterparty");
    }

    @Test
    void codeItemNullOrEmptyMatchesNoCategory() {
        assertThat(MAPPER.map(row(Map.of("CODE_ITEM", "NULL"))).warnings()).isEmpty();
        assertThat(MAPPER.map(row(Map.of())).warnings()).isEmpty();
        assertThat(MAPPER.map(row(Map.of("CODE_ITEM", "FOOD"))).warnings())
                .containsExactly("CODE_ITEM is FOOD, and Exp. Item '-' has no code");
        assertThat(MAPPER.map(row(Map.of("Exp. Item", "Food", "Debit", "Free money"))).warnings())
                .containsExactly("CODE_ITEM is empty, and Exp. Item 'Food' has the code FOOD");
    }

    @Test
    void payeeAndMemoComeFromCounteragentAndComment() {
        EntryPlan same = ((RowResult.Entry) MAPPER.map(row(Map.of("Counteragent", "Shop", "Comment", "Shop"))))
                .plan();
        EntryPlan different = ((RowResult.Entry) MAPPER.map(row(Map.of("Counteragent", "Shop",
                "Comment", "Weekly groceries")))).plan();
        EntryPlan commentOnly = ((RowResult.Entry) MAPPER.map(row(Map.of("Comment", "Cash for the trip")))).plan();

        assertThat(same.payee()).isEqualTo(new Party("Shop", Counterparty.Kind.MERCHANT));
        assertThat(same.memo()).isNull();
        assertThat(different.memo()).isEqualTo("Weekly groceries");
        assertThat(commentOnly.payee()).isNull();
        assertThat(commentOnly.memo()).isEqualTo("Cash for the trip");
    }

    @Test
    void openingBalancesArePostedAsDisplayedAgainstOpeningBalance() {
        LocalDate date = LocalDate.of(2024, 2, 29);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Line> lines = List.of(
                MAPPER.openingBalance(new OpeningBalanceRow(2, "CASH", "RUB", money("500"), date), errors, warnings),
                MAPPER.openingBalance(new OpeningBalanceRow(3, "CREDIT_CARD", "RUB", money("500"), date), errors,
                        warnings),
                MAPPER.openingBalance(new OpeningBalanceRow(4, "CARD", "EUR", money("20"), date), errors,
                        warnings));

        assertThat(errors).isEmpty();
        // The wallet and the credit card cancel out in RUB, which leaves nothing to post against OPENING_BALANCE.
        assertThat(MAPPER.openingEntry(date, lines).postings()).containsExactly(
                line(WALLET, "RUB", "500"),
                line(CREDIT_CARD, "RUB", "-500"),
                line(CARD, "EUR", "20"),
                line(OPENING, "EUR", "-20"));
        assertThat(MAPPER.openingBalance(new OpeningBalanceRow(5, "NOPE", "RUB", money("1"), date), errors,
                warnings)).isNull();
        assertThat(errors).containsExactly("there is no account with the code NOPE");
    }

    /** One journal row: 100 RUB from Wallet to Card on 1 March 2024, with the given columns changed. */
    static TransactionRow row(Map<String, String> changes) {
        Map<String, String> values = new HashMap<>(Map.of("Date", "01/03/2024", "Exp. Item", "-", "Sum", "100",
                "Currency", "RUB", "Debit", "Card", "Credit", "Wallet"));
        values.putAll(changes);
        String header = String.join(",", TransactionRow.COLUMNS);
        String line = TransactionRow.COLUMNS.stream()
                .map(column -> '"' + values.getOrDefault(column, "") + '"')
                .collect(Collectors.joining(","));
        var sheet = Workbook.transactions(WorkbookTests.file(header + "\n" + line + "\n"));
        assertThat(sheet.errors()).isEmpty();
        return sheet.rows().getFirst();
    }

    static List<String> errors(Map<String, String> changes) {
        return ((RowResult.Failed) MAPPER.map(row(changes))).errors();
    }

    static Line line(AccountRef account, String currency, String amount) {
        return new Line(account.id(), currency, money(amount), null, null);
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
