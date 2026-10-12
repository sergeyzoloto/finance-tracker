package com.example.financetracker.ledger.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The command line's filter of the partner's transactions file (D-78, D-140), the same as the import page's
 * ({@code frontend/src/importFilter.ts}, whose tests check the browser's): only his family rows with an item, and of
 * them only the ID, the date, the item, the flag and the UNALLOCATED side.
 */
class PartnerFilterTests {

    @Test
    void onlyHisFamilyRowsWithAnItemAndOnlyTheSideOnUnallocatedLeave() {
        String filtered = new String(ImportFixtures.partnerRows(), StandardCharsets.UTF_8);
        assertThat(ImportFixtures.lines(ImportFixtures.partnerRows())).containsExactly(
                "ID;Date;Exp. Item;Family;Side;Currency;Sum",
                "101;01.02.2024;Продукты;да;Debit;EUR;40.02",
                "102;02.02.2024;Продукты;да;Debit;EUR;-10.00",
                "103;03.02.2024;Пособие;да;Credit;EUR;60",
                "104;04.02.2024;Транспорт;да;Debit;RUB;500.5",
                "105;05.02.2024;Продукты;да;Debit;EUR;-",
                "108;08.02.2024;Транспорт;да;Debit;RUB;230.75",
                "109;08.03.2024;Продукты;да;Debit;EUR;8.00");
        for (String secret : List.of("his note", "Лавка", "Кафе", "private", "Фонд", "Восток", "Друг", "Кошелёк")) {
            assertThat(filtered).as(secret).doesNotContain(secret);
        }
    }

    @Test
    void aFieldWithASemicolonIsQuoted() {
        assertThat(PartnerFilter.quoted("a;b")).isEqualTo("\"a;b\"");
        assertThat(PartnerFilter.quoted("say \"x\"")).isEqualTo("\"say \"\"x\"\"\"");
        assertThat(PartnerFilter.quoted("plain")).isEqualTo("plain");
    }
}
