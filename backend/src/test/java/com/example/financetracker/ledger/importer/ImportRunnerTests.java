package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.ImportFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.family.FamilyLedgerService;
import com.example.financetracker.ledger.family.SplitRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The command line of {@link ImportRunner}, run on the synthetic export in a temporary directory, with the partner's
 * raw transactions file, which it filters as the import page does (D-140). The runner is called directly: its
 * {@code run} would exit the JVM.
 */
class ImportRunnerTests extends IntegrationTest {

    private static final List<String> FILES = List.of("accounts.csv", "categories.csv", "transactions.csv",
            "daily-balance.csv", "daily-change.csv", "partner-transactions.csv");

    @Autowired
    private ImportService imports;
    @Autowired
    private LedgerAccess ledgers;
    @Autowired
    private FamilyLedgerService families;

    @TempDir
    private Path dir;

    private final String user = UUID.randomUUID().toString();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private long family;
    private long seat;

    @BeforeEach
    void copyTheExportAndCreateTheBudget() throws IOException {
        for (String name : FILES) {
            Files.write(dir.resolve(name), bytes(name));
        }
        var personal = ledgers.provisionPersonal(user);
        family = families.create(personal, "Home", "EUR", LocalDate.of(2024, 1, 1), "Owner", SplitRule.EQUAL,
                List.of()).id();
        seat = families.addMember(ledgers.member(user, family), "Sam").id();
    }

    private String[] args(String... more) {
        List<String> all = new java.util.ArrayList<>(List.of("--user-sub=" + user,
                "--accounts=" + dir.resolve("accounts.csv"), "--categories=" + dir.resolve("categories.csv"),
                "--transactions=" + dir.resolve("transactions.csv"), "--daily-balance=" + dir.resolve("daily-balance.csv"),
                "--daily-change=" + dir.resolve("daily-change.csv")));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    @Test
    void aDryRunWithTheFamilyBudgetWritesTheReportNextToTheTransactions() throws IOException {
        int exitCode = execute(args("--partner-transactions=" + dir.resolve("partner-transactions.csv"),
                "--family-ledger-id=" + family, "--seat-member-id=" + seat));

        assertThat(exitCode).isZero();
        String report = Files.readString(dir.resolve("import-report.md"));
        assertThat(report).startsWith("# Import report\n\n**Dry run:**")
                .contains("| Created | 33 |")
                .contains("| OWNER_LUMP | EXPENSE | 2 |")
                .contains("**Passed.**")
                .contains("an FX gain with Sum 0")
                .contains("partner's transactions: 7 rows")
                // His comments, counterparties and personal rows never reach the import.
                .doesNotContain("his note", "Кафе", "private", "Восток");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(("Dry run, nothing saved: 33 created, 0 updated, "
                + "0 unchanged, 0 missing, 0 conflicts, 0 row errors, 2 warnings; reconciliation passed. Report: %s%n")
                .formatted(dir.resolve("import-report.md")));
    }

    @Test
    void upToLeavesOutTheRowsAfterItAndSaysSo() throws IOException {
        // D-165: the owner's 3 rows and the partner's 7 after 2024-01-31.
        int exitCode = execute(args("--partner-transactions=" + dir.resolve("partner-transactions.csv"),
                "--family-ledger-id=" + family, "--seat-member-id=" + seat, "--up-to=2024-01-31"));

        assertThat(exitCode).isZero();
        assertThat(Files.readString(dir.resolve("import-report.md")))
                .contains("- Import up to: 2024-01-31 (D-165); rows after it, neither imported nor missing: OWNER 3 of "
                        + "25 PARTNER 7 of 7\n")
                .contains("| Missing from the files (D-138) | 0 |");
    }

    @Test
    void aCommitOfThePersonalRowsAloneExitsWithZero() throws IOException {
        Files.write(dir.resolve("transactions.csv"), ImportFixtures.onlyRows(bytes("transactions.csv"),
                ImportFixtures.PERSONAL_IDS));
        Path report = dir.resolve("reports").resolve("first.md");
        Files.createDirectories(report.getParent());

        assertThat(execute(args("--commit", "--report=" + report))).isZero();
        assertThat(Files.readString(report)).startsWith("# Import report\n\n**Committed:**")
                .contains("| OWNER_PERSONAL | OPENING_BALANCE | 1 |");
        assertThat(dir.resolve("import-report.md")).doesNotExist();
    }

    @Test
    void rowErrorsExitWithTwo() {
        // The owner's family rows without a family budget can't be imported.
        assertThat(execute(args())).isEqualTo(2);
    }

    @Test
    void wrongArgumentsAndRefusalsExitWithOne() {
        assertThat(execute("--accounts=a.csv")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Missing --transactions\n" + ImportRunner.USAGE);

        err.reset();
        assertThat(execute(args("--commit=no"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("--commit takes no value\n");

        err.reset();
        assertThat(execute("--user-sub=" + user, "--opening-balances=a")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Unknown option --opening-balances\n");

        err.reset();
        assertThat(execute(args("--up-to=31.08.2026"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("--up-to is a date as yyyy-MM-dd: 31.08.2026\n");

        err.reset();
        assertThat(execute(args("--family-ledger-id=" + family))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("A family budget and the partner's seat come together");

        err.reset();
        assertThat(execute(args("--partner-transactions=" + dir.resolve("partner-transactions.csv"),
                "--family-ledger-id=" + family, "--seat-member-id=999999999"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Refused: Member 999999999 not found");
        assertThat(dir.resolve("import-report.md")).doesNotExist();
    }

    private int execute(String... args) {
        return new ImportRunner(imports, ledgers, null).execute(new DefaultApplicationArguments(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
