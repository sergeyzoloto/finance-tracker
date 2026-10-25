package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.importer.ImportFixtures.withoutRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.example.financetracker.Answers;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * D4a's export (D-235 to D-243) over D3c's family (src/test/resources/import/d3c/, invented): the owner's first import,
 * the partner's claim and his own import, then each member's transactions file. Each exports his own rows as he
 * imported them, never the other's; a file imported back changes nothing; D-242 holds against the balances.
 */
class ExportApiTests extends MemberImportApiTest {

    private static final String HEADER = "ID;Date;Exp. Item;Sum;Currency;Counteragent;Comment;Debit;Credit;Family;"
            + "CUR_DEBIT;SUM_DEBIT;CUR_CREDIT;SUM_CREDIT;LOAN_CA;CODE_ITEM;TYPE;CODE_DEBIT;CODE_CREDIT;TYPE_DEBIT;"
            + "TYPE_CREDIT;COUNT_DEBIT;COUNT_CREDIT;DEBIT_CHANGE;CREDIT_CHANGE;CUR_FAMILY;FAMILY_EXP;FAMILY_INC;"
            + "CHECK_CORR;LOAN_SUM;LOAN_CUR;EXP_ITEM_CODE";
    /** The typed columns, ID to LOAN_CA; the numbers among them are compared as numbers. */
    private static final int TYPED = 15;
    private static final Set<Integer> NUMBERS = Set.of(3, 11, 13);

    /** His rows refused by D-178 and D-179 (MemberImportApiTests), left out of his file. */
    private static final Set<String> REFUSED = Set.of("111", "112");

    /** Both members' imports committed, as D3b-run, the claim and D3c-run made them. */
    private void imported() throws IOException {
        ownersFirstImport();
        claim();
        JsonNode his = ok(memberImport(hisFile(), null, "false"));
        assertThat(his.get("errors")).isEmpty();
        assertThat(his.get("outcome").asText()).isEqualTo("COMMITTED");
    }

    private byte[] hisFile() throws IOException {
        return withoutRows(d3c("partner-transactions.csv"), REFUSED);
    }

    private MvcTestResult transactions(String user, String query) {
        return get(user, "/api/export/transactions" + (query == null ? "" : "?" + query));
    }

    private Answers.Csv file(String user, String query) throws IOException {
        MvcTestResult result = transactions(user, query);
        assertThat(result).hasStatusOk();
        return Answers.csv(result);
    }

    private JsonNode summary(String user, String query) throws IOException {
        return ok(get(user, "/api/export/summary" + (query == null ? "" : "?" + query)));
    }

    /** The data lines by ID, in the file's order. */
    private static Map<String, List<String>> byId(Answers.Csv csv) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        csv.lines().subList(1, csv.lines().size()).forEach(line -> rows.put(line.getFirst(), line));
        return rows;
    }

    /** A fixture's rows by ID: its typed columns. */
    private static Map<String, List<String>> fixtureRows(byte[] file) throws IOException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (CSVParser parser = CSVFormat.RFC4180.builder().setDelimiter(';').get()
                .parse(new java.io.StringReader(new String(file, StandardCharsets.UTF_8).substring(1)))) {
            parser.stream().skip(1).forEach(record -> rows.put(record.get(0), record.toList()));
        }
        return rows;
    }

    /** Each typed column where the exported row isn't the fixture's, as "ID column"; numbers by their value. */
    private static Set<String> differences(Map<String, List<String>> exported, Map<String, List<String>> fixture) {
        Set<String> differences = new TreeSet<>();
        exported.forEach((id, row) -> {
            List<String> original = fixture.get(id);
            for (int i = 1; i < TYPED; i++) {
                String a = row.get(i).strip();
                String b = original.get(i).strip();
                boolean same = NUMBERS.contains(i) && !a.isEmpty() && !b.isEmpty()
                        ? new BigDecimal(a).compareTo(new BigDecimal(b.replace(" ", ""))) == 0 : a.equals(b);
                if (!same) {
                    differences.add(id + " " + HEADER.split(";")[i]);
                }
            }
        });
        return differences;
    }

    private static Set<String> excelIds(Map<String, List<String>> rows) {
        Set<String> ids = new TreeSet<>();
        rows.keySet().stream().filter(id -> !id.startsWith("app-")).forEach(ids::add);
        return ids;
    }

    /**
     * Each member's file holds his own rows of his export as he imported them, typed columns and all, and nothing of
     * the other's (D-239); what differs is what the ledger doesn't keep, each with its reason.
     */
    @Test
    void eachMemberExportsHisOwnRowsAsHeImportedThem() throws IOException {
        imported();
        Map<String, List<String>> owners = byId(file(owner, null));
        Map<String, List<String>> his = byId(file(partner, null));

        // The owner's: every row of his file but 12 (D-84: an income the partner's rows carry); his D-83 lump of
        // January 2024 is a row the app made.
        assertThat(excelIds(owners)).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                "13", "14", "15");
        assertThat(owners.keySet()).filteredOn(id -> id.startsWith("app-")).allSatisfy(id -> {
            assertThat(id).startsWith("app-f");
            List<String> lump = owners.get(id);
            assertThat(lump.subList(7, 10)).containsExactly("Свободный остаток", "Общий семейный бюджет", "");
            assertThat(lump.get(2)).isEqualTo("Без детализации");
        });
        assertThat(differences(owners, fixtureRows(d3c("owner-transactions.csv")))).isEmpty();

        // His: every row of his file but 105, a benefit row, checked and never posted (D-179).
        assertThat(excelIds(his)).containsExactlyInAnyOrder("101", "102", "103", "104", "106", "107", "108", "109",
                "110", "113", "114", "115", "116");
        assertThat(his.keySet()).noneMatch(id -> id.startsWith("app-"));
        assertThat(differences(his, fixtureRows(hisFile()))).containsExactlyInAnyOrder(
                // D-188: his settlement rows on another date than the owner's settlements they match.
                "108 Date", "109 Date",
                // D-74: the family record holds 8.004 to the cent.
                "113 Sum",
                // Which side carries Sum isn't kept, and D-151's hash can't tell: Sum on the credit side.
                "103 Sum", "103 Currency", "103 CUR_DEBIT", "103 SUM_DEBIT", "103 CUR_CREDIT", "103 SUM_CREDIT",
                // The Family flag on a settlement row is ignored (D-178) and not kept.
                "116 Family");

        // Nothing of the other's: his accounts, payees and notes aren't in the owner's file, nor the owner's in his.
        assertThat(owners.values()).allSatisfy(row -> assertThat(row).doesNotContain("Карта «Восток»", "Наличные",
                "Лавка «Угол»", "Фонд", "Кафе", "his note", "private"));
        assertThat(his.values()).allSatisfy(row -> assertThat(row).doesNotContain("Евро-счёт", "Кошелёк",
                "Долларовый счёт", "Работа", "Рынок"));
        // Every line has the 32 columns.
        assertThat(owners.values()).allSatisfy(row -> assertThat(row).hasSize(32));
        assertThat(his.values()).allSatisfy(row -> assertThat(row).hasSize(32));
    }

    /** D-238: a member's file imported back into his own ledger changes nothing. */
    @Test
    void anExportImportedBackChangesNothing() throws IOException {
        imported();
        // The rows the app made aren't the import's (D-236): taken out, as D4a's rehearsal does.
        byte[] owners = withoutApp(file(owner, null));
        JsonNode report = ok(ownerImport(null, null, null));
        JsonNode again = ok(upload(owner, "owner", owners, null, seat, null, null));
        assertThat(again.get("errors")).isEmpty();
        assertThat(again.get("totals").get("created").asInt()).isZero();
        assertThat(again.get("totals").get("updated").asInt()).isZero();
        assertThat(again.get("totals").get("conflicts").asInt()).isZero();
        assertThat(again.get("missing")).isEmpty();
        assertThat(report.get("totals").get("unchanged").asInt()).isPositive();

        JsonNode hisAgain = ok(memberImport(withoutApp(file(partner, null)), null, null));
        assertThat(hisAgain.get("errors")).isEmpty();
        assertThat(hisAgain.get("totals").get("created").asInt()).isZero();
        assertThat(hisAgain.get("totals").get("conflicts").asInt()).isZero();
        assertThat(hisAgain.get("missing")).isEmpty();
        // Only 113's side, whose row said 8.004 and whose record holds 8.00 (D-74): his side's hash was made of the
        // former, so the side is written again, with the same account and amount (D-181).
        assertThat(hisAgain.get("totals").get("updated").asInt()).isEqualTo(1);
        assertThat(count(hisAgain, "MEMBER_FAMILY", "PAYMENT_SIDE")).startsWith("c0 u1 ");
    }

    /** The file without the app's rows, as the five-file import reads one. */
    private static byte[] withoutApp(Answers.Csv csv) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write('﻿');
            for (List<String> line : csv.lines()) {
                if (!line.getFirst().startsWith("app-")) {
                    writer.write(String.join(";", line.stream().map(v -> v.contains(";") || v.contains("\"")
                            ? '"' + v.replace("\"", "\"\"") + '"' : v).toList()) + "\r\n");
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * D-242: for every account and currency, the balance at the end less the balance the day before is what the
     * exported rows, the other members' records and the skipped ones move, against the balances report; the other
     * member's records move the owner's debt to the family budget, and nothing of them is in his file.
     */
    @Test
    void theRowsAndTheOthersRecordsMoveTheBalances() throws IOException {
        imported();
        for (String user : List.of(owner, partner)) {
            for (String[] period : List.of(new String[] {null, null}, new String[] {"2024-02-01", "2024-02-29"},
                    new String[] {"2024-02-12", "2024-02-12"})) {
                String query = period[0] == null ? null : "from=" + period[0] + "&to=" + period[1];
                JsonNode summary = summary(user, query);
                assertThat(summary.get("check").get("ok").asBoolean()).as(query).isTrue();
                Map<String, BigDecimal> before = period[0] == null ? Map.of()
                        : balances(user, LocalDate.parse(period[0]).minusDays(1));
                Map<String, BigDecimal> after = balances(user, period[1] == null ? LocalDate.of(2099, 12, 31)
                        : LocalDate.parse(period[1]));
                for (JsonNode account : summary.get("check").get("accounts")) {
                    String key = account.get("account").asText() + " " + account.get("currency").asText();
                    BigDecimal change = after.getOrDefault(key, BigDecimal.ZERO)
                            .subtract(before.getOrDefault(key, BigDecimal.ZERO));
                    BigDecimal parts = new BigDecimal(account.get("exported").asText())
                            .add(new BigDecimal(account.get("others").asText()))
                            .add(new BigDecimal(account.get("skipped").asText()));
                    assertThat(parts).as("%s %s", query, key).isEqualByComparingTo(change);
                }
            }
        }
        // The partner's records move the owner's debt to the family budget: his shares of them, never exported.
        JsonNode owners = summary(owner, null);
        JsonNode debt = find(owners.get("check").get("accounts"), "account", "FAMILY_DEBT_" + family);
        assertThat(new BigDecimal(debt.get("others").asText())).isNotZero();
        // His claim's opening balance is no record of his: skipped, and counted.
        assertThat(summary(partner, null).get("skipped").findValuesAsText("kind")).contains("FAMILY_OPENING");
    }

    private Map<String, BigDecimal> balances(String user, LocalDate asOf) throws IOException {
        Map<String, BigDecimal> balances = new HashMap<>();
        ok(get(user, "/api/reports/balances?asOf=" + asOf)).forEach(b -> balances.put(
                b.get("accountCode").asText() + " " + b.get("currency").asText(), new BigDecimal(b.get("balance").asText())));
        return balances;
    }

    /** D-241: the period by the record's date, both ends included; an empty one is the header alone. */
    @Test
    void thePeriodIncludesBothEnds() throws IOException {
        imported();
        assertThat(excelIds(byId(file(owner, "from=2024-02-12&to=2024-02-12")))).containsExactly("6", "7");
        assertThat(excelIds(byId(file(owner, "from=2024-02-10&to=2024-02-12")))).containsExactly("5", "6", "7");
        JsonNode month = summary(owner, "from=2024-02-10&to=2024-02-12");
        assertThat(month.get("rows").asInt()).isEqualTo(3);
        assertThat(month.get("first").asText()).isEqualTo("2024-02-10");
        assertThat(month.get("last").asText()).isEqualTo("2024-02-12");
        assertThat(month.get("fileName").asText()).isEqualTo("Transactions_2024-02-10_2024-02-12.csv");

        Answers.Csv empty = file(owner, "from=2030-01-01&to=2030-01-31");
        assertThat(empty.lines()).containsExactly(List.of(HEADER.split(";")));
        JsonNode none = summary(owner, "from=2030-01-01&to=2030-01-31");
        assertThat(none.get("rows").asInt()).isZero();
        assertThat(none.get("first").isNull()).isTrue();
        assertThat(none.get("fileName").asText()).isEqualTo("Transactions_2030-01-01_2030-01-31.csv");
    }

    /**
     * D-240: UTF-8 with a byte order mark, semicolons, CRLF, the header exactly, dates as dd.MM.yyyy and numbers with a
     * point, without separators or exponent; an attachment named for the period (D-241).
     */
    @Test
    void theFileIsInTheWorkbooksFormat() throws IOException {
        imported();
        MvcTestResult result = transactions(owner, null);
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv;charset=UTF-8");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Transactions_2024-01-02_2024-03-10.csv\"");
        Answers.Csv csv = Answers.csv(result);
        assertThat(csv.byteOrderMark()).isTrue();
        assertThat(csv.crlf()).isTrue();
        assertThat(String.join(";", csv.lines().getFirst())).isEqualTo(HEADER);
        for (List<String> line : csv.lines().subList(1, csv.lines().size())) {
            assertThat(line.get(1)).matches("[0-3][0-9]\\.[01][0-9]\\.20[0-9]{2}");
            for (int column : List.of(3, 11, 13, 23, 24, 26, 27, 28, 29)) {
                if (!line.get(column).isEmpty()) {
                    assertThat(line.get(column)).as(HEADER.split(";")[column]).matches("-?[0-9]+(\\.[0-9]*[1-9])?");
                }
            }
        }
        // Excel IDs ascending, then the app's.
        List<String> ids = csv.lines().stream().skip(1).map(List::getFirst).toList();
        List<String> excel = ids.stream().filter(id -> !id.startsWith("app-")).toList();
        assertThat(excel).isSortedAccordingTo((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        assertThat(ids.subList(0, excel.size())).isEqualTo(excel);
        // A user without a single record: the header alone, named for no period.
        String carol = newUser();
        MvcTestResult nothing = transactions(carol, null);
        assertThat(nothing.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"Transactions.csv\"");
        assertThat(Answers.csv(nothing).lines()).hasSize(1);
    }

    @Test
    void aPeriodHasBothEndsInOrder() throws IOException {
        for (String query : List.of("from=2024-02-01", "to=2024-02-01", "from=2024-02-02&to=2024-02-01")) {
            assertThat(transactions(owner, query)).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(owner, "/api/export/summary?" + query)).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * D-236: a personal entry made in the app is {@code app-<entry id>}, a family record {@code app-f<record id>}; the
     * family expense the owner paid is his row, and only his.
     */
    @Test
    void rowsMadeInTheAppHaveTheAppsIds() throws IOException {
        imported();
        long payee = newCounterparty(owner, "Bakery; corner 5");
        long entry = newExpense(owner, "2024-03-20", "12.50", payee, "for the weekend").get("id").asLong();
        long food = find(ok(get(owner, "/api/family-ledgers/" + family + "/categories")), "code", "FOOD")
                .get("id").asLong();
        long record = body(post(owner, records().replaceAll("/$", ""), """
                {"date": "2024-03-21", "categoryId": %d, "amount": "30.02", "payerMemberId": %d, "paymentAccountId": %d,
                 "split": {"method": "PERCENT", "shares": [{"memberId": %d, "basisPoints": 5000},
                 {"memberId": %d, "basisPoints": 5000}]}}""".formatted(food, ownerMember,
                accountId(owner, "EURO_ACCOUNT"), ownerMember, seat)), HttpStatus.CREATED).get("id").asLong();

        Map<String, List<String>> owners = byId(file(owner, null));
        List<String> expense = owners.get("app-" + entry);
        assertThat(expense.subList(1, 15)).containsExactly("20.03.2024", "Groceries", "12.5", "EUR",
                "Bakery; corner 5", "for the weekend", "Свободный остаток", "Кошелёк", "", "", "", "", "", "");
        List<String> paid = owners.get("app-f" + record);
        assertThat(paid.subList(1, 15)).containsExactly("21.03.2024", "Продукты", "30.02", "EUR", "", "",
                "Свободный остаток", "Евро-счёт", "да", "", "", "", "", "");
        assertThat(paid.get(26)).isEqualTo("-15.01");
        // The app's rows after the Excel IDs, by date.
        List<String> ids = new ArrayList<>(owners.keySet());
        assertThat(ids.indexOf("app-" + entry)).isLessThan(ids.indexOf("app-f" + record));
        // The partner shares it, and it isn't his row.
        assertThat(byId(file(partner, null)).keySet()).doesNotContain("app-f" + record);
        assertThat(summary(owner, null).get("check").get("ok").asBoolean()).isTrue();
        assertThat(summary(partner, null).get("check").get("ok").asBoolean()).isTrue();
    }
}
