package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static com.example.financetracker.ledger.importer.ImportFixtures.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import org.junit.jupiter.api.Test;

/**
 * The files read into rows (D3b, D4b): semicolons, dd.MM.yyyy, D-235's header, IDs (D-86) and the app's (D-236), the
 * accounts and categories the rows name and the file's refusal (D-228, D-229), and the partner's rows as the import page
 * filters them (D-140).
 */
class WorkbookTests {

    private static ImportFile csv(String name, String text) {
        return new ImportFile(name, ("﻿" + text).getBytes(StandardCharsets.UTF_8));
    }

    /** D4b: a transactions file is recognised by D-235's 32 columns, in order; any other header is refused, naming them. */
    @Test
    void aTransactionsFileIsRecognisedByItsHeader() {
        assertThat(Workbook.transactions(file("transactions.csv")).errors()).isEmpty();
        String expected = String.join(";", ExcelRow.HEADER);
        var old = catchThrowableOfType(TransactionsFileRefused.class, () -> Workbook.transactions(csv("t.csv",
                "ID;Date;Exp. Item;Sum;Currency;Counteragent;Comment;Debit;Credit;Family;CUR_DEBIT;SUM_DEBIT;CUR_CREDIT;"
                        + "SUM_CREDIT;LOAN_CA\r\n1;02.01.2024;Оклад;1;EUR;;;A;B;;;;;;\r\n")));
        assertThat(old.violations()).singleElement().asString().contains(expected).contains("D-235");
        // The same columns in another order are another header.
        List<String> swapped = new java.util.ArrayList<>(ExcelRow.HEADER);
        java.util.Collections.swap(swapped, 0, 1);
        assertThat(catchThrowableOfType(TransactionsFileRefused.class, () -> Workbook.transactions(csv("t.csv",
                String.join(";", swapped) + "\r\n")))).isNotNull();
        // An accounts file is no transactions file either.
        assertThat(catchThrowableOfType(TransactionsFileRefused.class, () -> Workbook.transactions(csv("a.csv",
                "Статьи баланса;Актив/пассив;Line\r\nКошелёк;ASSET;CASH\r\n")))).isNotNull();
    }

    /** D-228, D-246, D-261: the accounts and categories come from the rows, the workbook's typos fixed. */
    @Test
    void theAccountsAndCategoriesComeFromTheRows() {
        Workbook.FileChart chart = Workbook.chart(Workbook.transactions(file("transactions.csv")).rows());
        assertThat(chart.accounts()).extracting(Workbook.AccountRow::code).containsExactly("CASH", "CREDITOR_DEBT",
                "EURO_ACCOUNT", "FAMILY_DEBT", "GEMEENTE_FUND", "LOANS_ASSET", "NORTH_CARD", "UNALLOCATED",
                "USD_ACCOUNT");
        assertThat(chart.accounts()).filteredOn(a -> a.code().equals("UNALLOCATED"))
                .extracting(Workbook.AccountRow::type, Workbook.AccountRow::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(AccountType.EQUITY, ImportFixtures.UNALLOCATED));
        assertThat(chart.accounts()).filteredOn(a -> a.code().equals("LOANS_ASSET"))
                .extracting(Workbook.AccountRow::requiresCounterparty).containsExactly(true);
        assertThat(chart.categories()).extracting(Workbook.CategoryRow::code).containsExactly("BENEFIT", "COMMISSION",
                "FOOD", "FX_GAIN", "GIFTS", "OTHERINC", "PAYCHECK");
        // D-261: a name in another case is the same name; the spelling most rows use is kept.
        byte[] csv = ImportFixtures.withField(bytes("transactions.csv"), "17", 7, "КОШЕЛЁК");
        Workbook.FileChart cased = Workbook.chart(Workbook.transactions(new ImportFile("t.csv", csv)).rows());
        assertThat(cased.accounts()).filteredOn(a -> a.code().equals("CASH")).singleElement()
                .satisfies(a -> {
                    assertThat(a.name()).isEqualTo("Кошелёк");
                    assertThat(a.spellings()).containsExactlyInAnyOrder("Кошелёк", "КОШЕЛЁК");
                });
    }

    /** D-229 and D-262: the whole file is refused, naming codes and IDs, never a name or an amount. */
    @Test
    void codesWithTwoNamesOrTwoTypesAndNamesOfTwoCodesRefuseTheFile() {
        byte[] csv = bytes("transactions.csv");
        // Row 17's Debit names the cash account otherwise: two names of CASH.
        byte[] twoNames = ImportFixtures.withField(csv, "17", 7, "Кошелёк-секрет");
        assertThat(refusal(twoNames)).containsExactly("the account CASH has 2 names in the file, first in the IDs "
                + "7, 17 (D-229)");
        // Row 2's item typed as an income: two types of FOOD.
        byte[] twoTypes = ImportFixtures.withField(csv, "2", 16, "INCOME");
        assertThat(refusal(twoTypes)).containsExactly("the category FOOD has the types INCOME and EXPENSE in the "
                + "file, first in the IDs 2, 3 (D-229)");
        // Row 25's debit side named as the cash but coded otherwise: a name of two codes.
        byte[] sharedName = ImportFixtures.withField(csv, "25", 18, "WALLET");
        assertThat(refusal(sharedName)).contains("the accounts CASH and WALLET have one name, and rows name them by "
                + "it (D-262)");
        byte[] badType = ImportFixtures.withField(ImportFixtures.withField(csv, "25", 20, "EQUITY"), "25", 18, "X");
        assertThat(refusal(badType)).anySatisfy(r -> assertThat(r).startsWith("the account X has the type EQUITY"));
        assertThat(List.of(twoNames, twoTypes, sharedName)).allSatisfy(f -> assertThat(String.join(" ", refusal(f)))
                .doesNotContain("секрет", "Кошелёк", "1000"));
    }

    private static List<String> refusal(byte[] csv) {
        return catchThrowableOfType(TransactionsFileRefused.class,
                () -> Workbook.chart(Workbook.transactions(new ImportFile("t.csv", csv)).rows())).violations();
    }

    /** D-236, D-244: a row the app made has its own ID, which no Excel ID collides with. */
    @Test
    void aRowTheAppMadeKeepsItsId() {
        byte[] csv = ImportFixtures.withField(ImportFixtures.withField(bytes("transactions.csv"), "1", 0, "app-7"),
                "2", 0, "app-f7");
        var sheet = Workbook.transactions(new ImportFile("t.csv", csv));
        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).filteredOn(TransactionRow::isApp).extracting(TransactionRow::excelId)
                .containsExactly("app-7", "app-f7");
        byte[] twice = ImportFixtures.withField(csv, "3", 0, "app-7");
        assertThat(Workbook.transactions(new ImportFile("t.csv", twice)).errors()).extracting(Problem::message)
                .containsExactly("the ID is in an earlier row too (D-86)");
    }

    @Test
    void transactionsHaveAPermanentIdAndAContentHash() {
        var sheet = Workbook.transactions(file("transactions.csv"));
        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).hasSize(25);
        TransactionRow first = sheet.rows().getFirst();
        assertThat(first.id()).isEqualTo(1);
        assertThat(first.date()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(first.sum()).isEqualByComparingTo("1000");
        assertThat(first.contentHash()).matches("[0-9a-f]{64}");
        // The same row with another formatting of its numbers has the same hash; an edit changes it.
        byte[] reformatted = ImportFixtures.withField(bytes("transactions.csv"), "1", 3, "1000");
        byte[] edited = ImportFixtures.withField(bytes("transactions.csv"), "1", 3, "1000.01");
        assertThat(Workbook.transactions(new ImportFile("t.csv", reformatted)).rows().getFirst().contentHash())
                .isEqualTo(first.contentHash());
        assertThat(Workbook.transactions(new ImportFile("t.csv", edited)).rows().getFirst().contentHash())
                .isNotEqualTo(first.contentHash());
    }

    @Test
    void anIdIsAPositiveWholeNumberOnceAndAnErrorNeverHoldsAValue() {
        byte[] csv = bytes("transactions.csv");
        byte[] broken = ImportFixtures.withField(ImportFixtures.withField(csv, "2", 0, "x2"), "3", 0, "1");
        broken = ImportFixtures.withField(broken, "4", 3, "secret 12,5");
        var sheet = Workbook.transactions(new ImportFile("t.csv", broken));
        assertThat(sheet.errors()).extracting(Problem::row, Problem::message).containsExactly(
                org.assertj.core.groups.Tuple.tuple(3, "ID: not a positive whole number"),
                org.assertj.core.groups.Tuple.tuple(4, "the ID is in an earlier row too (D-86)"),
                org.assertj.core.groups.Tuple.tuple(5, "Sum: not a number"));
        assertThat(sheet.errors()).allSatisfy(p -> assertThat(p.message()).doesNotContain("secret", "x2"));
    }

    @Test
    void thePartnersRowsAreReadWithTheSideOnUnallocated() {
        var sheet = Workbook.partner(new ImportFile("p.csv", ImportFixtures.partnerRows()));
        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).extracting(PartnerRow::id).containsExactly(101L, 102L, 103L, 104L, 105L, 108L, 109L);
        PartnerRow dollars = sheet.rows().get(5);
        assertThat(dollars.expense()).isTrue();
        assertThat(dollars.currency()).isEqualTo("RUB");
        assertThat(dollars.amount()).isEqualByComparingTo("230.75");
        assertThat(sheet.rows().get(2).expense()).isFalse();
        assertThat(sheet.rows().get(4).amount().signum()).isZero();
    }

    /** D-140: a row or a column the page's filter never lets through refuses the whole file, naming rows only. */
    @Test
    void thePartnersFileIsRefusedWholeForARowOrAColumnTheFilterNeverLetsThrough() {
        String header = "ID;Date;Exp. Item;Family;Side;Currency;Sum\r\n";
        var personal = catchThrowableOfType(PartnerFileRefused.class, () -> Workbook.partner(csv("p.csv", header
                + "1;01.02.2024;Продукты;да;Debit;EUR;5\r\n2;02.02.2024;Кафе-секрет;;Debit;EUR;7\r\n")));
        assertThat(personal.violations()).containsExactly(
                "row 3 of the partner's file isn't a family row (Family = \"да\")");
        var noItem = catchThrowableOfType(PartnerFileRefused.class, () -> Workbook.partner(csv("p.csv", header
                + "1;01.02.2024;-;да;Debit;EUR;5\r\n")));
        assertThat(noItem.violations()).containsExactly("row 2 of the partner's file has no item (\"-\")");
        var comment = catchThrowableOfType(PartnerFileRefused.class, () -> Workbook.partner(csv("p.csv",
                "ID;Date;Exp. Item;Family;Side;Currency;Sum;Comment\r\n1;01.02.2024;Продукты;да;Debit;EUR;5;secret\r\n")));
        assertThat(comment.violations()).containsExactly("the partner's file has a column the import doesn't take: "
                + "only ID, Date, Exp. Item, Family, Side, Currency, Sum (and CODE_ITEM, TYPE) leave the laptop");
        assertThat(List.of(personal, noItem, comment)).allSatisfy(e -> assertThat(String.join(" ", e.violations()))
                .doesNotContain("secret", "Кафе"));
    }

    @Test
    void aPartnersRowWithoutUnallocatedIsAnErrorOfItsRow() {
        var sheet = Workbook.partner(csv("p.csv", "ID;Date;Exp. Item;Family;Side;Currency;Sum\r\n"
                + "1;01.02.2024;Продукты;да;;EUR;5\r\n"));
        assertThat(sheet.errors()).extracting(Problem::message).containsExactly("Side is empty");
    }
}
