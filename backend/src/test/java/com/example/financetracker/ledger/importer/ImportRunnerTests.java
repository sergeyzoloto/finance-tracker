package com.example.financetracker.ledger.importer;

import static com.example.financetracker.ledger.importer.ImportServiceTests.fixture;
import static com.example.financetracker.ledger.importer.ImportServiceTests.withoutRow;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.example.financetracker.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The command line of {@link ImportRunner}, run on the test fixture in a temporary directory. The runner is called
 * directly: its {@code run} would exit the JVM.
 */
class ImportRunnerTests extends IntegrationTest {

    @Autowired
    private ImportService imports;

    @TempDir
    private Path dir;

    private final String user = UUID.randomUUID().toString();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void copyFixture() throws IOException {
        for (String name : new String[] {"accounts.csv", "categories.csv", "transactions.csv"}) {
            Files.write(dir.resolve(name), fixture(name));
        }
    }

    @Test
    void aDryRunWritesTheReportNextToTheTransactions() throws IOException {
        int exitCode = execute("--user-sub=" + user, "--accounts=" + dir.resolve("accounts.csv"),
                "--categories=" + dir.resolve("categories.csv"), "--transactions=" + dir.resolve("transactions.csv"));

        // The fixture's row 10 names an unknown account.
        assertThat(exitCode).isEqualTo(2);
        String report = Files.readString(dir.resolve("import-report.md"));
        assertThat(report)
                .startsWith("# Import report\n\n**Dry run:**")
                .contains("| Entries written, then rolled back | 24 |")
                .contains("| transactions.csv | 10 | Credit 'Карта «Несуществующая»' is not in the accounts file |")
                .contains("| transactions.csv | 26 | an FX gain with Sum 0 |")
                .contains("| UNALLOCATED | Свободный остаток | EQUITY | RUB | 69148.70 |")
                .contains("OK: in every currency the postings sum to zero");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(
                "Dry run, nothing saved: 24 entries, 1 row errors, 3 warnings, 2 skipped rows. Report: %s%n"
                        .formatted(dir.resolve("import-report.md")));
    }

    @Test
    void aCommitWithoutRowErrorsExitsWithZero() throws IOException {
        Files.write(dir.resolve("transactions.csv"), withoutRow(fixture("transactions.csv"), 10));
        Files.write(dir.resolve("opening.csv"), fixture("opening-balances.csv"));
        Path report = dir.resolve("reports").resolve("first.md");
        Files.createDirectories(report.getParent());

        int exitCode = execute("--user-sub=" + user, "--accounts=" + dir.resolve("accounts.csv"),
                "--categories=" + dir.resolve("categories.csv"), "--transactions=" + dir.resolve("transactions.csv"),
                "--opening-balances=" + dir.resolve("opening.csv"), "--commit", "--report=" + report);

        assertThat(exitCode).isZero();
        assertThat(Files.readString(report)).startsWith("# Import report\n\n**Committed:**")
                .contains("| OPENING_BALANCE | 2 |");
        assertThat(dir.resolve("import-report.md")).doesNotExist();
    }

    @Test
    void wrongArgumentsExitWithOneAndShowTheUsage() {
        assertThat(execute("--accounts=a.csv")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Missing --transactions\n" + ImportRunner.USAGE);

        err.reset();
        assertThat(execute("--user-sub=" + user, "--accounts=a", "--categories=c", "--transactions=t", "--commit=no"))
                .isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("--commit takes no value\n");

        err.reset();
        assertThat(execute("--user-sub=" + user, "--acounts=a")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Unknown option --acounts\n");

        err.reset();
        assertThat(execute("--user-sub=" + user, "--accounts=" + dir.resolve("missing.csv"),
                "--categories=c", "--transactions=" + dir.resolve("transactions.csv"))).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Cannot read or write a file: ");
        assertThat(dir.resolve("import-report.md")).doesNotExist();
    }

    private int execute(String... args) {
        return new ImportRunner(imports, null).execute(new DefaultApplicationArguments(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }
}
