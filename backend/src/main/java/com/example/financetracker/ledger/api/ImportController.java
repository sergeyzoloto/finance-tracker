package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.util.Objects;

import com.example.financetracker.ledger.importer.ImportFile;
import com.example.financetracker.ledger.importer.ImportReport;
import com.example.financetracker.ledger.importer.ImportRequest;
import com.example.financetracker.ledger.importer.ImportService;
import com.example.financetracker.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Excel importer (CLAUDE.md, "How to run the importer") for the signed-in user, with the files uploaded instead
 * of read from disk.
 */
@RestController
class ImportController {

    /** import_batch.file_name. */
    private static final int FILE_NAME_MAX_LENGTH = 255;

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * Imports the Excel ledger's CSV exports. A dry run writes everything, reports, and rolls back; a commit saves
     * everything, or nothing if any row has an error. The report says which happened in {@code outcome}: DRY_RUN,
     * COMMITTED, or ABORTED for a commit with row errors.
     *
     * @param openingBalances optional, with the columns line, currency, amount and date
     * @param dryRun false to commit
     */
    @PostMapping(path = "/api/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importLedger(CurrentUser user, @RequestPart MultipartFile accounts,
            @RequestPart MultipartFile categories, @RequestPart MultipartFile transactions,
            @RequestPart(required = false) MultipartFile openingBalances,
            @RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
        return imports.run(new ImportRequest(user.id(), file(accounts), file(categories), file(transactions),
                openingBalances == null ? null : file(openingBalances), !dryRun));
    }

    /** The file with its name as the browser sent it, without a path, for the report and the import batch. */
    private static ImportFile file(MultipartFile part) throws IOException {
        String name = StringUtils.getFilename(StringUtils.cleanPath(
                Objects.requireNonNullElse(part.getOriginalFilename(), "")));
        name = name == null || name.isBlank() ? part.getName() : name;
        return new ImportFile(name.length() > FILE_NAME_MAX_LENGTH ? name.substring(0, FILE_NAME_MAX_LENGTH) : name,
                part.getBytes());
    }
}
