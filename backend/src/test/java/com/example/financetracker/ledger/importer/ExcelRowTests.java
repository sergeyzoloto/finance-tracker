package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.importer.ExcelRow.Item;
import com.example.financetracker.ledger.importer.ExcelRow.Side;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The row writer (D4a, D-237): each row's 32 columns, the 17 computed ones by Excel's rules. The expected lines are
 * what a reference of those rules makes of the typed columns (invented accounts and amounts), the reference that
 * reproduces every row of both members' exports of 2026-10-21 but where Excel's CSV cut a typed number to 10
 * significant digits.
 */
class ExcelRowTests {

    private static final Map<String, Side> ACCOUNTS = Map.of(
            "Свободный остаток", new Side("Свободный остаток", "UNALLOCATED", ExcelRow.LIABILITIES),
            "Евро-счёт", new Side("Евро-счёт", "EURO_ACCOUNT", ExcelRow.ASSET),
            "Кошелёк", new Side("Кошелёк", "CASH", ExcelRow.ASSET),
            "Долларовый счёт", new Side("Долларовый счёт", "USD_ACCOUNT", ExcelRow.ASSET),
            "Долг перед банком", new Side("Долг перед банком", "CREDITOR_DEBT", ExcelRow.LIABILITIES),
            "Общий семейный бюджет", new Side("Общий семейный бюджет", "FAMILY_DEBT", ExcelRow.LIABILITIES),
            "Займы друзьям", new Side("Займы друзьям", "LOANS_ASSET", ExcelRow.ASSET));
    private static final Map<String, Item> ITEMS = Map.of(
            "Продукты", new Item("Продукты", "FOOD", "EXPENSE"),
            "Оклад", new Item("Оклад", "PAYCHECK", "INCOME"),
            "Прочие поступления", new Item("Прочие поступления", "OTHERINC", "INCOME"),
            "-", Item.NONE);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "an expense|1;02.01.2024;Продукты;20.01;EUR;Лавка;на неделю;Свободный остаток;Евро-счёт;;;;;;;FOOD;EXPENSE;UNALLOCATED;EURO_ACCOUNT;LIABILITIES;ASSET;UNALLOCATED_EUR;EURO_ACCOUNT_EUR;-20.01;-20.01;EUR;0;0;0;0;;FOOD",
            "a family expense|2;03.01.2024;Продукты;20.01;EUR;Лавка;Лавка;Свободный остаток;Евро-счёт;да;;;;;;FOOD;EXPENSE;UNALLOCATED;EURO_ACCOUNT;LIABILITIES;ASSET;UNALLOCATED_EUR;EURO_ACCOUNT_EUR;-10.005;-20.01;EUR;-10.005;0;0;0;;FOOD",
            "a family income|3;05.01.2024;Прочие поступления;100;EUR;;;Евро-счёт;Свободный остаток;да;;;;;;OTHERINC;INCOME;EURO_ACCOUNT;UNALLOCATED;ASSET;LIABILITIES;EURO_ACCOUNT_EUR;UNALLOCATED_EUR;100;50;EUR;0;50;0;0;;OTHERINC",
            "a transfer in one currency|4;08.01.2024;-;50;EUR;-;-;Кошелёк;Евро-счёт;;;;;;;NULL;CASHFLOW;CASH;EURO_ACCOUNT;ASSET;ASSET;CASH_EUR;EURO_ACCOUNT_EUR;50;-50;EUR;0;0;0;0;;NULL",
            "two currencies, an asset from an asset|5;09.01.2024;-;9;EUR;;;Долларовый счёт;Евро-счёт;;USD;10.5;;;;NULL;CASHFLOW;USD_ACCOUNT;EURO_ACCOUNT;ASSET;ASSET;USD_ACCOUNT_USD;EURO_ACCOUNT_EUR;10.5;-9;USD;0;0;1.5;0;;NULL",
            "two currencies, an asset from a liability (the sign turned)|6;10.01.2024;-;5;EUR;;;Кошелёк;Долг перед банком;;RUB;500;;;Банк;NULL;CASHFLOW;CASH;CREDITOR_DEBT;ASSET;LIABILITIES;CASH_RUB;CREDITOR_DEBT_EUR;500;5;RUB;0;0;-495;5;EUR;NULL",
            "two currencies, a family expense from an asset|7;11.01.2024;Продукты;3.3;EUR;Рынок;Рынок;Свободный остаток;Евро-счёт;да;RUB;300.12;;;;FOOD;EXPENSE;UNALLOCATED;EURO_ACCOUNT;LIABILITIES;ASSET;UNALLOCATED_RUB;EURO_ACCOUNT_EUR;-150.06;-3.3;RUB;-150.06;0;296.82;0;;FOOD",
            "two currencies, a liability from a liability|8;15.01.2024;-;10;USD;;;Общий семейный бюджет;Общий семейный бюджет;;EUR;9;;;;NULL;CASHFLOW;FAMILY_DEBT;FAMILY_DEBT;LIABILITIES;LIABILITIES;FAMILY_DEBT_EUR;FAMILY_DEBT_USD;-9;10;EUR;0;0;-1;0;;NULL",
            "two currencies, Sum on the debit side|12;16.01.2024;-;10.5;USD;;;Долларовый счёт;Евро-счёт;;;;EUR;9;;NULL;CASHFLOW;USD_ACCOUNT;EURO_ACCOUNT;ASSET;ASSET;USD_ACCOUNT_USD;EURO_ACCOUNT_EUR;10.5;-9;USD;0;0;1.5;0;;NULL",
            "a loan given|9;12.01.2024;-;40;EUR;;;Займы друзьям;Общий семейный бюджет;;;;;;Друг;NULL;CASHFLOW;LOANS_ASSET;FAMILY_DEBT;ASSET;LIABILITIES;LOANS_ASSET_EUR;FAMILY_DEBT_EUR;40;40;EUR;0;0;0;-40;EUR;NULL",
            "a loan to a loan|10;13.01.2024;-;8.83;RUB;;;Долг перед банком;Займы друзьям;;;;;;Друг;NULL;CASHFLOW;CREDITOR_DEBT;LOANS_ASSET;LIABILITIES;ASSET;CREDITOR_DEBT_RUB;LOANS_ASSET_RUB;-8.83;-8.83;RUB;0;0;0;0;RUB;NULL",
            "nine decimals, never rounded, and the app's ID|app-11;14.01.2024;Продукты;133.333333333;RUB;;;Свободный остаток;Кошелёк;да;;;;;;FOOD;EXPENSE;UNALLOCATED;CASH;LIABILITIES;ASSET;UNALLOCATED_RUB;CASH_RUB;-66.6666666665;-133.333333333;RUB;-66.6666666665;0;0;0;;FOOD"})
    void theColumnsAreExcelsOfTheTypedOnes(String what, String line) {
        List<String> expected = Arrays.asList(line.split(";", -1));
        ExcelRow row = row(expected.subList(0, 15));
        assertThat(row.columns()).as(what).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "an expense|1;02.01.2024;Продукты;20.01;EUR;Лавка;на неделю;Свободный остаток;Евро-счёт;;;;;;",
            "a family income|3;05.01.2024;Прочие поступления;100;EUR;;;Евро-счёт;Свободный остаток;да;;;;;",
            "two currencies|6;10.01.2024;-;5;EUR;;;Кошелёк;Долг перед банком;;RUB;500;;;Банк",
            "both sides named|7;11.01.2024;-;9;EUR;;;Долларовый счёт;Евро-счёт;;USD;10.5;EUR;9;",
            "LOAN_CA NULL|10;13.01.2024;-;8.83;RUB;;;Долг перед банком;Займы друзьям;;;;;;NULL"})
    void theHashesAreTheImports(String what, String typed) {
        // Through the import's own reading of the written line: the content hash it stores (D-139), D-181's side hash
        // and D-151's upload hash.
        ExcelRow row = row(Arrays.asList(typed.split(";", -1)));
        TransactionRow read = readBack(row);
        assertThat(row.contentHash()).as(what).isEqualTo(read.contentHash());
        assertThat(row.sideHash()).as(what).isEqualTo(MemberPlanner.sideHash(read));
        assertThat(row.uploadHash()).as(what).isEqualTo(MemberPlanner.uploadHash(read, Map.of(
                "свободный остаток", new Chart.AccountRef(1, "UNALLOCATED", null, false))));
    }

    /** The row as the import reads it back from the file the export writes. */
    private static TransactionRow readBack(ExcelRow row) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransactionsCsv.write(List.of(new ExcelRow("1", row.date(), row.item(), row.sum(), row.currency(),
                row.counteragent(), row.comment(), row.debit(), row.credit(), row.family(), row.debitCurrency(),
                row.debitSum(), row.creditCurrency(), row.creditSum(), row.loanCounterparty())), out);
        Workbook.Sheet<TransactionRow> sheet = Workbook.transactions(new ImportFile("t.csv", out.toByteArray()));
        assertThat(sheet.errors()).isEmpty();
        return sheet.rows().getFirst();
    }

    /** A row from its 15 typed columns, the accounts and the item named as in {@link #ACCOUNTS} and {@link #ITEMS}. */
    static ExcelRow row(List<String> typed) {
        List<String> t = new ArrayList<>(typed);
        return new ExcelRow(t.get(0), ExcelValues.date(t.get(1)), ITEMS.get(t.get(2)), new BigDecimal(t.get(3)),
                t.get(4), t.get(5), t.get(6), ACCOUNTS.get(t.get(7)), ACCOUNTS.get(t.get(8)),
                t.get(9).equals(TransactionRow.FAMILY_YES), t.get(10).isEmpty() ? null : t.get(10),
                t.get(11).isEmpty() ? null : new BigDecimal(t.get(11)), t.get(12).isEmpty() ? null : t.get(12),
                t.get(13).isEmpty() ? null : new BigDecimal(t.get(13)), t.get(14));
    }
}
