package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.domain.EntryKind.CURRENCY_EXCHANGE;
import static com.example.financetracker.ledger.domain.EntryKind.EXPENSE;
import static com.example.financetracker.ledger.domain.EntryKind.INCOME;
import static com.example.financetracker.ledger.domain.EntryKind.LOAN_GIVEN;
import static com.example.financetracker.ledger.domain.EntryKind.LOAN_REPAID;
import static com.example.financetracker.ledger.domain.EntryKind.OPENING_BALANCE;
import static com.example.financetracker.ledger.domain.EntryKind.SHARED_EXPENSE;
import static com.example.financetracker.ledger.domain.EntryKind.TRANSFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.Account;
import com.example.financetracker.ledger.AccountRepository;
import com.example.financetracker.ledger.Counterparty;
import com.example.financetracker.ledger.CounterpartyRepository;
import com.example.financetracker.ledger.LedgerCategory;
import com.example.financetracker.ledger.LedgerCategoryRepository;
import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.importer.ImportReport.FxRow;
import com.example.financetracker.ledger.importer.ImportReport.Outcome;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import com.example.financetracker.ledger.report.AccountBalance;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ImportService} against real PostgreSQL, over the synthetic workbook in {@code src/test/resources/import/}:
 * 26 journal rows in March 2024 with the quirks of the real exports (a byte order mark, CRLF, quoted fields, no-break
 * spaces in numbers, formula columns). Every name and amount in it is invented. Row 10 names an account the accounts
 * file doesn't have; tests that commit leave it out. Every test runs as a fresh user.
 */
class ImportServiceTests extends IntegrationTest {

    static final String TRANSACTIONS = "transactions.csv";

    @Autowired
    private ImportService imports;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private LedgerCategoryRepository categories;
    @Autowired
    private CounterpartyRepository counterparties;
    @Autowired
    private JdbcClient jdbc;

    private final String user = UUID.randomUUID().toString();

    @Test
    void dryRunReportsEveryRowAndSavesNothing() throws IOException {
        ImportReport report = imports.run(request(fixture(TRANSACTIONS), null, false));

        assertThat(report.outcome()).isEqualTo(Outcome.DRY_RUN);
        assertThat(report.entriesByKind()).containsExactly(entry(EXPENSE, 8), entry(INCOME, 4), entry(TRANSFER, 5),
                entry(SHARED_EXPENSE, 2), entry(LOAN_GIVEN, 2), entry(LOAN_REPAID, 2), entry(CURRENCY_EXCHANGE, 1));
        assertThat(report.errors()).containsExactly(new Problem(TRANSACTIONS, 10,
                "Credit 'Карта «Несуществующая»' is not in the accounts file"));
        assertThat(report.warnings()).containsExactly(
                new Problem(TRANSACTIONS, 6, "family split of Sum 845.55: the own part is 422.77, and the workbook's "
                        + "(minus DEBIT_CHANGE) is 422.78; the family's part is 422.78"),
                new Problem(TRANSACTIONS, 17, "CODE_ITEM is FOOD, and Exp. Item 'Подарки' has the code PRESENTS"),
                new Problem(TRANSACTIONS, 23, "LOAN_CA is empty on a row with LOANS_ASSET, which requires a "
                        + "counterparty: 'Unassigned' is used (Sum 2.50 RUB on 26/03/2024)"));
        assertThat(report.skipped()).containsExactly(
                new Problem("categories.csv", 9, "Line NULL means \"no category\""),
                new Problem(TRANSACTIONS, 26, "an FX gain with Sum 0"));
        assertThat(report.zeroFxRowsSkipped()).isEqualTo(1);
        assertThat(report.fxRowsToReview()).containsExactly(new FxRow(27, LocalDate.of(2024, 3, 31),
                money("12.34"), "EUR", "Евро-счёт", "Свободный остаток", "Переоценка"));
        assertThat(report.referenceData()).isEqualTo(new ImportReport.ReferenceCounts(13, 0, 8, 0, 15));
        assertThat(report.balances())
                .extracting(AccountBalance::accountCode, AccountBalance::currency, AccountBalance::balance)
                .containsExactly(
                        tuple("CASH", "RUB", money("-3349.00")),
                        tuple("CREDITOR_DEBT", "RUB", money("20000.00")),
                        tuple("EAST_CREDIT", "RUB", money("0.00")),
                        tuple("EURO_ACCOUNT", "EUR", money("86.54")),
                        // The family owes the user its parts of the two shared expenses.
                        tuple("FAMILY_DEBT", "RUB", money("-1422.78")),
                        tuple("FX_EXCHANGE", "EUR", money("100.00")),
                        tuple("FX_EXCHANGE", "RUB", money("-10000.00")),
                        tuple("GEMEENTE_FUND", "RUB", money("250.00")),
                        tuple("LOANS_ASSET", "RUB", money("1502.50")),
                        tuple("NORTH_CARD", "RUB", money("89295.45")),
                        tuple("RESERVE", "RUB", money("10000.00")),
                        tuple("SOUTH_SAVINGS", "RUB", money("26.97")),
                        tuple("UNALLOCATED", "EUR", money("-13.46")),
                        tuple("UNALLOCATED", "RUB", money("69148.70")));
        assertThat(report.integrityViolations()).isEmpty();

        assertThat(accounts.findAllByUserIdOrderByCode(user)).isEmpty();
        assertThat(categories.findAllByUserIdOrderByName(user)).isEmpty();
        assertThat(counterparties.findAllByUserIdOrderByName(user)).isEmpty();
        assertThat(count("journal_entry")).isZero();
        assertThat(count("import_batch")).isZero();
    }

    @Test
    void aCommitWithARowErrorSavesNothing() throws IOException {
        ImportReport report = imports.run(request(fixture(TRANSACTIONS), null, true));

        assertThat(report.outcome()).isEqualTo(Outcome.ABORTED);
        assertThat(report.errors()).extracting(Problem::row).containsExactly(10);
        assertThat(accounts.findAllByUserIdOrderByCode(user)).isEmpty();
        assertThat(count("journal_entry")).isZero();
        assertThat(count("import_batch")).isZero();
    }

    @Test
    void aCommitWritesTheAccountsAndCategoriesByTheRules() throws IOException {
        ImportReport report = imports.run(request(withoutRow(fixture(TRANSACTIONS), 10), null, true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(report.errors()).isEmpty();
        assertThat(accounts.findAllByUserIdOrderByCode(user))
                .extracting(Account::code, Account::type, Account::requiresCounterparty, Account::isSystem)
                .containsExactly(
                        tuple("CASH", AccountType.ASSET, false, false),
                        tuple("CREDITOR_DEBT", AccountType.LIABILITY, true, false),
                        tuple("EAST_CREDIT", AccountType.LIABILITY, false, false),
                        tuple("EURO_ACCOUNT", AccountType.ASSET, false, false),
                        tuple("FAMILY_DEBT", AccountType.LIABILITY, false, false),
                        tuple("FX_EXCHANGE", AccountType.EQUITY, false, true),
                        tuple("GEMEENTE_FUND", AccountType.ASSET, false, false),
                        tuple("LOANS_ASSET", AccountType.ASSET, true, false),
                        tuple("NORTH_CARD", AccountType.ASSET, false, false),
                        tuple("OPENING_BALANCE", AccountType.EQUITY, false, true),
                        tuple("RESERVE", AccountType.EQUITY, false, false),
                        tuple("SOUTH_SAVINGS", AccountType.ASSET, false, false),
                        tuple("UNALLOCATED", AccountType.EQUITY, false, false));
        assertThat(categories.findAllByUserIdOrderByName(user))
                .extracting(LedgerCategory::code, LedgerCategory::type)
                .containsExactlyInAnyOrder(
                        tuple("PAYCHECK", CategoryType.INCOME),
                        tuple("DEPOSIT_INTEREST", CategoryType.INCOME),
                        tuple("FX_GAIN", CategoryType.INCOME),
                        tuple("FOOD", CategoryType.EXPENSE),
                        tuple("CAFE", CategoryType.EXPENSE),
                        tuple("COMMISSION", CategoryType.EXPENSE),
                        tuple("TRANSPORT", CategoryType.EXPENSE),
                        tuple("PRESENTS", CategoryType.EXPENSE));
        // Payees are merchants, borrowers people; a name is one counterparty whatever its case or role.
        assertThat(counterparties.findAllByUserIdOrderByName(user))
                .extracting(Counterparty::name, Counterparty::kind)
                .contains(
                        tuple("Unassigned", null),
                        tuple("Банк «Надёжный»", Counterparty.Kind.MERCHANT),
                        tuple("Кафе «Мост»", Counterparty.Kind.MERCHANT),
                        tuple("Пётр", Counterparty.Kind.PERSON))
                .hasSize(15);
    }

    @Test
    void aCommitWritesEachRowAsTheRulesBuildIt() throws IOException {
        imports.run(request(withoutRow(fixture(TRANSACTIONS), 10), null, true));

        // Rule 7 with the workbook's split: the family's part is minus FAMILY_EXP, the own part the rest.
        assertThat(postings("2024-03-05", SHARED_EXPENSE)).containsExactly(
                tuple("NORTH_CARD", "RUB", money("-845.55"), null, null),
                tuple("UNALLOCATED", "RUB", money("422.77"), "CAFE", null),
                tuple("FAMILY_DEBT", "RUB", money("422.78"), null, null));
        // A refund keeps its sign.
        assertThat(postings("2024-03-08", EXPENSE)).containsExactly(
                tuple("CASH", "RUB", money("120.00"), null, null),
                tuple("UNALLOCATED", "RUB", money("-120.00"), "FOOD", null));
        // So does an income reversal.
        assertThat(postings("2024-03-19", INCOME)).containsExactly(
                tuple("UNALLOCATED", "RUB", money("15.20"), "DEPOSIT_INTEREST", null),
                tuple("SOUTH_SAVINGS", "RUB", money("-15.20"), null, null));
        assertThat(postings("2024-03-07", LOAN_GIVEN)).containsExactly(
                tuple("CASH", "RUB", money("-3000.00"), null, null),
                tuple("LOANS_ASSET", "RUB", money("3000.00"), null, "Пётр"));
        // LOAN_CA "пётр" finds the same borrower.
        assertThat(postings("2024-03-20", LOAN_REPAID)).containsExactly(
                tuple("LOANS_ASSET", "RUB", money("-1000.00"), null, "Пётр"),
                tuple("NORTH_CARD", "RUB", money("1000.00"), null, null));
        // A repayment entered with a negative Sum.
        assertThat(postings("2024-03-25", LOAN_REPAID)).containsExactly(
                tuple("CASH", "RUB", money("500.00"), null, null),
                tuple("LOANS_ASSET", "RUB", money("-500.00"), null, "Пётр"));
        assertThat(postings("2024-03-26", LOAN_GIVEN)).containsExactly(
                tuple("NORTH_CARD", "RUB", money("-2.50"), null, null),
                tuple("LOANS_ASSET", "RUB", money("2.50"), null, "Unassigned"));
        assertThat(postings("2024-03-27", TRANSFER)).containsExactly(
                tuple("CREDITOR_DEBT", "RUB", money("-20000.00"), null, "Банк «Надёжный»"),
                tuple("NORTH_CARD", "RUB", money("20000.00"), null, null));
        // Rule 9: 10 000 RUB for 100 EUR.
        assertThat(postings("2024-03-12", CURRENCY_EXCHANGE)).containsExactly(
                tuple("NORTH_CARD", "RUB", money("-10000.00"), null, null),
                tuple("FX_EXCHANGE", "RUB", money("10000.00"), null, null),
                tuple("FX_EXCHANGE", "EUR", money("-100.00"), null, null),
                tuple("EURO_ACCOUNT", "EUR", money("100.00"), null, null));

        assertThat(jdbc.sql("""
                        SELECT e.entry_date, p.name AS payee, e.memo
                        FROM journal_entry e LEFT JOIN counterparty p ON p.id = e.payee_id
                        WHERE e.user_id = ? AND e.entry_date IN ('2024-03-01', '2024-03-03', '2024-03-05', '2024-03-15')
                        ORDER BY e.entry_date""")
                .param(user)
                .query((row, rowNum) -> tuple(row.getObject(1, LocalDate.class), row.getString(2), row.getString(3)))
                .list())
                .containsExactly(
                        tuple(LocalDate.of(2024, 3, 1), "ООО «Ромашка»", null),
                        tuple(LocalDate.of(2024, 3, 3), "Метро", "Проездной на неделю"),
                        tuple(LocalDate.of(2024, 3, 5), "Кафе «Грот»", "Ужин, \"с друзьями\""),
                        tuple(LocalDate.of(2024, 3, 15), null, "На отпуск"));
        // Rows 3 and 13 are identical: two entries, told apart by the occurrence in their external refs.
        List<String> refs = jdbc.sql("""
                        SELECT external_ref FROM journal_entry
                        WHERE user_id = ? AND entry_date = '2024-03-02' ORDER BY external_ref""")
                .param(user).query(String.class).list();
        assertThat(refs).hasSize(2);
        assertThat(refs.get(0)).matches("xls:[0-9a-f]{64}:1");
        assertThat(refs.get(1)).isEqualTo(refs.get(0).replaceAll(":1$", ":2"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM journal_entry e JOIN import_batch b ON b.id = e.import_batch_id
                        WHERE e.user_id = ? AND b.file_name = 'transactions.csv' AND NOT b.dry_run
                          AND b.finished_at IS NOT NULL""")
                .param(user).query(Integer.class).single()).isEqualTo(24);
    }

    @Test
    void aSecondRunSkipsWhatTheFirstImported() throws IOException {
        byte[] transactions = withoutRow(fixture(TRANSACTIONS), 10);
        imports.run(request(transactions, fixture("opening-balances.csv"), true));

        ImportReport again = imports.run(request(transactions, fixture("opening-balances.csv"), true));

        assertThat(again.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(again.entriesWritten()).isZero();
        assertThat(again.errors()).isEmpty();
        // Their warnings were reported by the first run.
        assertThat(again.warnings()).isEmpty();
        assertThat(again.referenceData()).isEqualTo(new ImportReport.ReferenceCounts(0, 0, 0, 0, 0));
        assertThat(again.skipped()).filteredOn(skip -> skip.message().endsWith("imported before"))
                .extracting(Problem::row)
                .containsExactly(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 26,
                        null, null);
        assertThat(count("journal_entry")).isEqualTo(26);
    }

    @Test
    void openingBalancesAreOneEntryPerDate() throws IOException {
        ImportReport report = imports.run(request(withoutRow(fixture(TRANSACTIONS), 10),
                fixture("opening-balances.csv"), true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(report.entriesByKind()).containsEntry(OPENING_BALANCE, 2);
        // As displayed (rule 4): liabilities and equity are posted with the opposite sign.
        assertThat(postings("2024-02-29", OPENING_BALANCE)).containsExactly(
                tuple("CASH", "RUB", money("15000.00"), null, null),
                tuple("NORTH_CARD", "RUB", money("120000.00"), null, null),
                tuple("EAST_CREDIT", "RUB", money("-3000.00"), null, null),
                tuple("RESERVE", "RUB", money("-50000.00"), null, null),
                tuple("GEMEENTE_FUND", "RUB", money("1000.00"), null, null),
                tuple("EURO_ACCOUNT", "EUR", money("200.00"), null, null),
                tuple("LOANS_ASSET", "RUB", money("700.00"), null, "Unassigned"),
                tuple("OPENING_BALANCE", "RUB", money("-83700.00"), null, null),
                tuple("OPENING_BALANCE", "EUR", money("-200.00"), null, null));
        assertThat(postings("2024-01-31", OPENING_BALANCE)).containsExactly(
                tuple("CASH", "RUB", money("500.00"), null, null),
                tuple("OPENING_BALANCE", "RUB", money("-500.00"), null, null));
        assertThat(report.warnings()).contains(new Problem("opening-balances.csv", 8,
                "LOANS_ASSET requires a counterparty, and the file names none: 'Unassigned' is used"));
        assertThat(report.skipped()).contains(new Problem("opening-balances.csv", 9, "the amount is 0"));
        assertThat(report.integrityViolations()).isEmpty();
    }

    @Test
    void referenceDataIsUpdatedByCode() throws IOException {
        imports.run(request(withoutRow(fixture(TRANSACTIONS), 10), null, true));
        String renamed = new String(fixture("accounts.csv"), StandardCharsets.UTF_8).replace("Отложено", "На потом");

        ImportReport report = imports.run(new ImportRequest(user, file("accounts.csv", renamed.getBytes(StandardCharsets.UTF_8)),
                file("categories.csv", fixture("categories.csv")), file(TRANSACTIONS, headerOnly()), null, true));

        assertThat(report.outcome()).isEqualTo(Outcome.COMMITTED);
        assertThat(report.referenceData()).isEqualTo(new ImportReport.ReferenceCounts(0, 1, 0, 0, 0));
        assertThat(accounts.findByUserIdAndCode(user, "RESERVE")).get()
                .extracting(Account::name).isEqualTo("На потом");
    }

    @Test
    void aCategoryKeepsItsType() throws IOException {
        categories.save(new LedgerCategory(null, user, "FOOD", "Продукты", CategoryType.INCOME, null));

        ImportReport report = imports.run(request(fixture(TRANSACTIONS), null, false));

        assertThat(report.errors()).contains(new Problem("categories.csv", 5,
                "the category FOOD is INCOME, and a category's type can't change to EXPENSE"));
    }

    private ImportRequest request(byte[] transactions, byte[] openingBalances, boolean commit) throws IOException {
        return new ImportRequest(user, file("accounts.csv", fixture("accounts.csv")),
                file("categories.csv", fixture("categories.csv")), file(TRANSACTIONS, transactions),
                openingBalances == null ? null : file("opening-balances.csv", openingBalances), commit);
    }

    static ImportFile file(String name, byte[] content) {
        return new ImportFile(name, content);
    }

    static byte[] fixture(String name) throws IOException {
        return new ClassPathResource("import/" + name).getContentAsByteArray();
    }

    /** The file without one spreadsheet row; the header is row 1. No field of the fixture spans lines. */
    static byte[] withoutRow(byte[] csv, int row) {
        List<String> lines = new ArrayList<>(List.of(new String(csv, StandardCharsets.UTF_8).split("\r\n")));
        lines.remove(row - 1);
        return (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] headerOnly() throws IOException {
        return (new String(fixture(TRANSACTIONS), StandardCharsets.UTF_8).split("\r\n")[0] + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The postings of the user's entries of the kind on the date: account, currency, amount, category, counterparty. */
    private List<Tuple> postings(String date, EntryKind kind) {
        return jdbc.sql("""
                        SELECT a.code, p.currency, p.amount, c.code AS category, cp.name AS counterparty
                        FROM journal_entry e
                        JOIN posting p ON p.entry_id = e.id
                        JOIN account a ON a.id = p.account_id
                        LEFT JOIN category c ON c.id = p.category_id
                        LEFT JOIN counterparty cp ON cp.id = p.counterparty_id
                        WHERE e.user_id = ? AND e.entry_date = ? AND e.kind = ?
                        ORDER BY e.id, p.line_no""")
                .params(user, LocalDate.parse(date), kind.name())
                .query((row, rowNum) -> tuple(row.getString(1), row.getString(2),
                        Money.normalize(row.getBigDecimal(3)), row.getString(4), row.getString(5)))
                .list();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?").param(user).query(Integer.class)
                .single();
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
