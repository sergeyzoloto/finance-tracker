package com.example.financetracker.ledger.importer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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
 */
@Component
@Profile("import")
class ImportRunner implements ApplicationRunner {

    static final String USAGE = """
            Usage: --user-sub=<Keycloak sub> --accounts=<file> --categories=<file> --transactions=<file>
                   [--opening-balances=<file>] [--commit] [--report=<file>]
            Without --commit it is a dry run, which saves nothing.""";

    private static final Set<String> OPTIONS = Set.of("user-sub", "accounts", "categories", "transactions",
            "opening-balances", "commit", "report");

    private final ImportService imports;
    private final ConfigurableApplicationContext context;

    ImportRunner(ImportService imports, ConfigurableApplicationContext context) {
        this.imports = imports;
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
            String openingBalances = value(args, "opening-balances", false);
            String reportOption = value(args, "report", false);
            Path reportFile = reportOption != null
                    ? Path.of(reportOption)
                    : transactions.toAbsolutePath().normalize().resolveSibling("import-report.md");

            ImportRequest request = new ImportRequest(value(args, "user-sub", true),
                    file(Path.of(value(args, "accounts", true))), file(Path.of(value(args, "categories", true))),
                    file(transactions), openingBalances == null ? null : file(Path.of(openingBalances)),
                    args.containsOption("commit"));
            ImportReport report = imports.run(request);
            Files.writeString(reportFile, ImportReportMarkdown.render(report));

            out.printf("%s: %d entries, %d row errors, %d warnings, %d skipped rows. Report: %s%n",
                    switch (report.outcome()) {
                        case DRY_RUN -> "Dry run, nothing saved";
                        case COMMITTED -> "Committed";
                        case ABORTED -> "Aborted because of row errors, nothing saved";
                    },
                    report.entriesWritten(), report.errors().size(), report.warnings().size(),
                    report.skipped().size(), reportFile);
            return report.errors().isEmpty() ? 0 : 2;
        } catch (UsageException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 1;
        } catch (IOException e) {
            err.println("Cannot read or write a file: " + e);
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

    private static ImportFile file(Path path) throws IOException {
        return new ImportFile(path.getFileName().toString(), Files.readAllBytes(path));
    }

    private static final class UsageException extends RuntimeException {

        UsageException(String message) {
            super(message);
        }
    }
}
