package com.example.financetracker.ledger.importer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.access.LedgerAccess;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The importer on the command line, with the Spring profile "import" (CLAUDE.md shows the command). It runs one
 * import, writes the report as Markdown, and exits:
 * <ul>
 * <li>0: no row errors; a commit saved everything;
 * <li>1: wrong arguments or a file that can't be read or written;
 * <li>2: row errors; nothing was saved.
 * </ul>
 * The report goes next to the transactions file as import-report.md, unless --report names another file.
 * <p>
 * The entries go into the personal ledger of the sub that --user-sub names, which {@link LedgerAccess} creates if the
 * sub has none yet, as it does on a user's first request; the family rows into the family budget and the partner's seat
 * that --family-ledger-id and --seat-member-id name (D-141), of which the sub is a member. The partner's transactions
 * file is filtered here as the import page filters it in the browser (D-140, {@link PartnerFilter}): only his family
 * rows, and of them only the columns the mapping needs.
 */
@Component
@Profile("import")
class ImportRunner implements ApplicationRunner {

    static final String USAGE = """
            Usage: --user-sub=<Keycloak sub> --accounts=<file> --categories=<file> --transactions=<file>
                   --daily-balance=<file> --daily-change=<file>
                   [--partner-transactions=<file> --family-ledger-id=<id> --seat-member-id=<id>]
                   [--commit] [--report=<file>]
            Without --commit it is a dry run, which saves nothing.""";

    private static final Set<String> OPTIONS = Set.of("user-sub", "accounts", "categories", "transactions",
            "daily-balance", "daily-change", "partner-transactions", "family-ledger-id", "seat-member-id", "commit",
            "report");

    private final ImportService imports;
    private final LedgerAccess ledgers;
    private final ConfigurableApplicationContext context;

    ImportRunner(ImportService imports, LedgerAccess ledgers, ConfigurableApplicationContext context) {
        this.imports = imports;
        this.ledgers = ledgers;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(args, System.out, System.err);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int execute(ApplicationArguments args, PrintStream out, PrintStream err) {
        try {
            for (String option : args.getOptionNames()) {
                // Spring's own properties can be passed as options too.
                if (!OPTIONS.contains(option) && !option.startsWith("spring.")) {
                    throw new UsageException("Unknown option --" + option);
                }
            }
            if (!args.getNonOptionArgs().isEmpty()) {
                throw new UsageException("Unexpected arguments: " + String.join(" ", args.getNonOptionArgs()));
            }
            if (args.containsOption("commit") && !args.getOptionValues("commit").isEmpty()) {
                throw new UsageException("--commit takes no value");
            }
            Path transactions = Path.of(value(args, "transactions", true));
            String partner = value(args, "partner-transactions", false);
            String familyLedgerId = value(args, "family-ledger-id", false);
            String seatMemberId = value(args, "seat-member-id", false);
            String reportOption = value(args, "report", false);
            Path reportFile = reportOption != null
                    ? Path.of(reportOption)
                    : transactions.toAbsolutePath().normalize().resolveSibling("import-report.md");

            String userSub = value(args, "user-sub", true);
            ImportFile accounts = file(Path.of(value(args, "accounts", true)));
            ImportFile partnerRows = null;
            if (partner != null) {
                String unallocated = Workbook.accounts(accounts).rows().stream()
                        .filter(a -> a.code().equals("UNALLOCATED")).map(Workbook.AccountRow::name).findFirst()
                        .orElseThrow(() -> new UsageException("The accounts file has no UNALLOCATED"));
                partnerRows = new ImportFile("partner-family-rows.csv",
                        PartnerFilter.filter(file(Path.of(partner)), unallocated));
            }
            ImportRequest request = new ImportRequest(accounts, file(Path.of(value(args, "categories", true))),
                    file(transactions), file(Path.of(value(args, "daily-balance", true))),
                    file(Path.of(value(args, "daily-change", true))), partnerRows, number(familyLedgerId),
                    number(seatMemberId), args.containsOption("commit"));
            ImportReport report = imports.run(ledgers.provisionPersonal(userSub), request);
            Files.writeString(reportFile, ImportReportMarkdown.render(report));

            var totals = report.totals();
            out.printf("%s: %d created, %d updated, %d unchanged, %d missing, %d conflicts, %d row errors, "
                    + "%d warnings; reconciliation %s. Report: %s%n",
                    switch (report.outcome()) {
                        case DRY_RUN -> "Dry run, nothing saved";
                        case COMMITTED -> "Committed";
                        case ABORTED -> "Aborted because of row errors, nothing saved";
                    },
                    totals.created(), totals.updated(), totals.unchanged(), totals.missing(), totals.conflicts(),
                    report.errors().size(), report.warnings().size(), report.reconciliation() == null ? "not run"
                            : report.reconciliation().passed() ? "passed" : "FAILED", reportFile);
            return report.errors().isEmpty() ? 0 : 2;
        } catch (UsageException | IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 1;
        } catch (IOException e) {
            err.println("Cannot read or write a file: " + e);
            return 1;
        } catch (RuleViolationException e) {
            err.println("Refused: " + String.join("; ", e.violations()));
            return 1;
        } catch (NotFoundException | ConflictException e) {
            err.println("Refused: " + e.getMessage());
            return 1;
        }
    }

    private static String value(ApplicationArguments args, String option, boolean required) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty()) {
            if (required) {
                throw new UsageException("Missing --" + option);
            }
            return null;
        }
        if (values.size() > 1 || values.getFirst().isBlank()) {
            throw new UsageException("--" + option + " needs exactly one value");
        }
        return values.getFirst();
    }

    private static Long number(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new UsageException("Not a number: " + value);
        }
    }

    private static ImportFile file(Path path) throws IOException {
        return new ImportFile(path.getFileName().toString(), Files.readAllBytes(path));
    }

    private static final class UsageException extends RuntimeException {

        UsageException(String message) {
            super(message);
        }
    }
}
