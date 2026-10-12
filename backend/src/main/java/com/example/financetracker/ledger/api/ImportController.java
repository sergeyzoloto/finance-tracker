package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.util.Objects;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.importer.ImportFile;
import com.example.financetracker.ledger.importer.ImportReport;
import com.example.financetracker.ledger.importer.ImportRequest;
import com.example.financetracker.ledger.importer.ImportService;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Excel importer (D3b; CLAUDE.md, "How to run the importer") for the signed-in user, with the files uploaded
 * instead of read from disk.
 */
@RestController
class ImportController {

    /** import_batch.file_name. */
    private static final int FILE_NAME_MAX_LENGTH = 255;
    /**
     * The name the partner's rows go by, whatever the browser calls them: his export's file names carry his first
     * name, which stays off the server (D-75, D-143).
     */
    static final String PARTNER_FILE = "partner-family-rows.csv";

    private final ImportService imports;

    ImportController(ImportService imports) {
        this.imports = imports;
    }

    /**
     * Imports the owner's Excel export: his personal rows into his personal ledger, and, with a family budget and the
     * partner's seat in it (D-141), his family rows and the partner's family rows into that budget (D-82); then
     * reconciles his balances with his daily balance file. A dry run writes everything, reports, and rolls back; a
     * commit saves everything, or nothing if any row has an error. Rows imported before are recognised by their IDs:
     * unchanged ones stay, edited ones are written over unless changed in the app (D-139), and missing ones are only
     * reported (D-138). The report says what happened in {@code outcome}: DRY_RUN, COMMITTED, or ABORTED for a commit
     * with row errors.
     *
     * @param dailyBalance the owner's daily balance file: the opening balances and the reconciliation
     * @param dailyChange the owner's daily change file: the monthly family lumps (D-83)
     * @param partnerTransactions the partner's family rows, as the import page filters them in the browser (D-140):
     *        exactly the columns ID, Date, Exp. Item, Family, Side, Currency and Sum, and only rows with Family = "да"
     *        and an item; any other column or row refuses the whole request (422). Needs {@code familyLedgerId}
     * @param familyLedgerId the family budget the family rows go into; another user's answers 404
     * @param seatMemberId the partner's seat in it, a member without an account; with {@code familyLedgerId}
     * @param dryRun false to commit
     */
    @PostMapping(path = "/api/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportReport importLedger(LedgerScope ledger, @RequestPart MultipartFile accounts,
            @RequestPart MultipartFile categories, @RequestPart MultipartFile transactions,
            @RequestPart MultipartFile dailyBalance, @RequestPart MultipartFile dailyChange,
            @RequestPart(required = false) MultipartFile partnerTransactions,
            @RequestParam(required = false) Long familyLedgerId, @RequestParam(required = false) Long seatMemberId,
            @RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
        return imports.run(ledger, new ImportRequest(file(accounts), file(categories), file(transactions),
                file(dailyBalance), file(dailyChange),
                partnerTransactions == null ? null : new ImportFile(PARTNER_FILE, partnerTransactions.getBytes()),
                familyLedgerId, seatMemberId, !dryRun));
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
