package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static com.example.financetracker.ledger.importer.ImportFixtures.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.domain.AccountType;
import com.example.financetracker.ledger.importer.ImportReport.Problem;
import org.junit.jupiter.api.Test;

/**
 * The export's files read into rows (D3b): semicolons, dd.MM.yyyy, the real headers, IDs (D-86), the partner's rows
 * as the import page filters them (D-140), and the daily files up to the cutoff (D-146).
 */
class WorkbookTests {

    private static ImportFile csv(String name, String text) {
        return new ImportFile(name, ("﻿" + text).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theAccountsFileHasTheRealHeaderAndNamesUniqueInAnyCase() {
        var sheet = Workbook.accounts(file("accounts.csv"));
        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).extracting(Workbook.AccountRow::code).contains("CASH", "GEMEENTE_FUND", "UNALLOCATED");
        assertThat(sheet.rows()).filteredOn(a -> a.code().equals("RESERVE")).extracting(Workbook.AccountRow::type)
                .containsExactly(AccountType.EQUITY);
        var twice = Workbook.accounts(csv("a.csv", "Статьи баланса;Актив/пассив;Line\r\nКошелёк;ASSET;CASH\r\n"
                + "кошелёк;ASSET;CASH2\r\n"));
        assertThat(twice.errors()).extracting(Problem::row, Problem::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "the name is in an earlier row too"));
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
                + "only ID, Date, Exp. Item, Family, Side, Currency, Sum leave the laptop");
        assertThat(List.of(personal, noItem, comment)).allSatisfy(e -> assertThat(String.join(" ", e.violations()))
                .doesNotContain("secret", "Кафе"));
    }

    @Test
    void aPartnersRowWithoutUnallocatedIsAnErrorOfItsRow() {
        var sheet = Workbook.partner(csv("p.csv", "ID;Date;Exp. Item;Family;Side;Currency;Sum\r\n"
                + "1;01.02.2024;Продукты;да;;EUR;5\r\n"));
        assertThat(sheet.errors()).extracting(Problem::message).containsExactly("Side is empty");
    }

    /** D-146 and D-62: the daily files up to the cutoff, scientific notation exactly, padding and garbage after it unread. */
    @Test
    void theDailyFilesAreReadUpToTheCutoff() {
        List<Problem> errors = new java.util.ArrayList<>();
        DailyFile balance = DailyFile.read(file("daily-balance.csv"), LocalDate.of(2024, 3, 8), errors);
        assertThat(errors).isEmpty();
        assertThat(balance.values().firstKey()).isEqualTo(LocalDate.of(2023, 12, 31));
        assertThat(balance.values().lastKey()).isEqualTo(LocalDate.of(2024, 3, 8));
        assertThat(balance.accountColumns()).containsEntry("GEMENTE_FUND_EUR",
                new DailyFile.Column("GEMEENTE_FUND", "EUR"));
        assertThat(balance.value(LocalDate.of(2023, 12, 31), "RESERVE_EUR")).isEqualByComparingTo("1100");
        // Without the cutoff the padding's garbage is read, and is an error.
        List<Problem> all = new java.util.ArrayList<>();
        DailyFile.read(file("daily-balance.csv"), null, all);
        assertThat(all).extracting(Problem::message).containsExactly("CASH_EUR: not a number");

        DailyFile change = DailyFile.read(file("daily-change.csv"), LocalDate.of(2024, 3, 8), errors);
        assertThat(errors).isEmpty();
        assertThat(change.lumpColumns()).containsOnlyKeys("PL_family_Sam_RUB", "PL_family_Sam_EUR", "PL_family_Sam_USD")
                .containsValues("RUB", "EUR", "USD");
        assertThat(change.accountColumns()).doesNotContainKey("PL_family_Sam_RUB");
        assertThat(change.value(LocalDate.of(2024, 1, 31), "PL_family_Sam_RUB")).isEqualByComparingTo("333.335");
        assertThat(change.value(LocalDate.of(2023, 12, 31), "FAMILY_DEBT_USD"))
                .isEqualByComparingTo(new BigDecimal("-0.0000000000000142109"));
    }

    @Test
    void aDailyFilesColumnIsDateOrAnAccountAndACurrency() {
        List<Problem> errors = new java.util.ArrayList<>();
        DailyFile file = DailyFile.read(csv("b.csv", "Date;CASH_EUR;Total\r\n31.12.2023;1;2\r\n"), null, errors);
        assertThat(errors).extracting(Problem::message)
                .containsExactly("a column is neither Date nor an account's code and currency (CODE_CUR)");
        assertThat(file.isEmpty()).isTrue();
        assertThat(Set.of(DailyFile.read(csv("b.csv", "Month;Year;Date;CASH_EUR\r\n12;2023;31.12.2023;5\r\n"), null,
                errors).accountColumns().keySet())).containsExactly(Set.of("CASH_EUR"));
    }
}
