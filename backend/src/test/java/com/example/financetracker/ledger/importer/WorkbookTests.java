package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import com.example.financetracker.ledger.importer.Workbook.AccountRow;
import com.example.financetracker.ledger.importer.Workbook.Sheet;
import org.junit.jupiter.api.Test;

/** Reading the CSV files into rows, without a database. Names are invented. */
class WorkbookTests {

    @Test
    void accountTypesFollowRule4AndCodeTyposAreFixed() {
        Sheet<AccountRow> sheet = Workbook.accounts(file("""
                ﻿Balance sheet items,Актив/пассив,Line\r
                "Wallet, old",ASSET,CASH\r
                Free money,LIABILITIES,UNALLOCATED\r
                Set aside,LIABILITIES,RESERVE\r
                Family budget,LIABILITIES,FAMILY_DEBT\r
                Borrowed,LIABILITIES,CREDITOR_DEBT\r
                Lent,ASSET,LOANS_ASSET\r
                Town fund, ASSET ,GEMENTE_FUND\r
                """));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows())
                .extracting(AccountRow::row, AccountRow::name, AccountRow::code, AccountRow::type,
                        AccountRow::requiresCounterparty)
                .containsExactly(
                        tuple(2, "Wallet, old", "CASH", AccountType.ASSET, false),
                        tuple(3, "Free money", "UNALLOCATED", AccountType.EQUITY, false),
                        tuple(4, "Set aside", "RESERVE", AccountType.EQUITY, false),
                        tuple(5, "Family budget", "FAMILY_DEBT", AccountType.LIABILITY, false),
                        tuple(6, "Borrowed", "CREDITOR_DEBT", AccountType.LIABILITY, true),
                        tuple(7, "Lent", "LOANS_ASSET", AccountType.ASSET, true),
                        tuple(8, "Town fund", "GEMEENTE_FUND", AccountType.ASSET, false));
    }

    @Test
    void badRowsAreErrorsAndTheRestIsRead() {
        Sheet<AccountRow> sheet = Workbook.accounts(file("""
                Balance sheet items,Актив/пассив,Line
                Wallet,ASSET,CASH
                Card,EQUITY,CARD
                Second wallet,ASSET,CASH
                ,,
                Wallet,ASSET,
                Savings,ASSET,SAVINGS
                """));

        assertThat(sheet.rows()).extracting(AccountRow::code).containsExactly("CASH", "SAVINGS");
        assertThat(sheet.errors()).containsExactly(
                new Problem("test.csv", 3, "Актив/пассив is 'EQUITY', not ASSET or LIABILITIES"),
                new Problem("test.csv", 4, "the code 'CASH' is in an earlier row too"),
                new Problem("test.csv", 6, "Line is empty; the name 'Wallet' is in an earlier row too"));
        assertThat(sheet.skipped()).containsExactly(new Problem("test.csv", 5, "the row is empty"));
    }

    @Test
    void theNullCategoryIsSkipped() {
        var sheet = Workbook.categories(file("""
                Статьи движения средств,Доходы/расходы,Line
                Salary,INCOME,SALARY
                -,CASHFLOW,NULL
                Bank fees,EXPENSE,COMMISION
                """));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).extracting(Workbook.CategoryRow::code).containsExactly("SALARY", "COMMISSION");
        assertThat(sheet.skipped()).containsExactly(new Problem("test.csv", 3, "Line NULL means \"no category\""));
    }

    @Test
    void aFileWithoutARequiredColumnHasNoRows() {
        var sheet = Workbook.categories(file("""
                Статьи движения средств,Line
                Salary,SALARY
                """));

        assertThat(sheet.rows()).isEmpty();
        assertThat(sheet.errors()).containsExactly(new Problem("test.csv", null, "missing columns: Доходы/расходы"));
    }

    @Test
    void aFileThatIsNotUtf8HasNoRows() {
        var sheet = Workbook.accounts(new ImportFile("test.csv", new byte[] {'L', 'i', (byte) 0xE9, '\n'}));

        assertThat(sheet.errors()).containsExactly(new Problem("test.csv", null, "the file is not UTF-8 text"));
    }

    @Test
    void identicalTransactionsAreNumberedInTheirExternalRefs() {
        String header = String.join(",", TransactionRow.COLUMNS);
        String row = "01/03/2024,Food,\"1 234.50 \",RUB,Shop,Shop,Free money,Wallet,,,,,,,,,,,,";
        String sameRowFormattedAnotherWay = "01/03/2024,Food,1234.5,RUB,Shop , Shop,Free money,Wallet,,,,,,,,,,,,";
        var sheet = Workbook.transactions(file(header + "\n" + row + "\n" + sameRowFormattedAnotherWay + "\n"
                + row.replace("Shop,Shop", "Shop,Other") + "\n"));

        assertThat(sheet.errors()).isEmpty();
        var refs = sheet.rows().stream().map(TransactionRow::externalRef).toList();
        assertThat(refs.get(0)).matches("xls:[0-9a-f]{64}:1");
        assertThat(refs.get(1)).isEqualTo(refs.get(0).replaceAll(":1$", ":2"));
        assertThat(refs.get(2)).endsWith(":1").isNotEqualTo(refs.get(0));
    }

    @Test
    void unreadableTransactionValuesAreRowErrors() {
        String header = String.join(",", TransactionRow.COLUMNS);
        var sheet = Workbook.transactions(file(header + "\n"
                + "2024-03-01,Food,12 x,RUB,Shop,Shop,Free money,,yes,,,,,,,,,,\n"));

        assertThat(sheet.errors()).containsExactly(new Problem("test.csv", 2,
                "Date: '2024-03-01' is not a date in the format dd/MM/yyyy; Sum: '12 x' is not a number; "
                        + "Credit is empty; Family is 'yes', not 'да' or empty"));
    }

    static ImportFile file(String content) {
        return new ImportFile("test.csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
