package com.example.financetracker.ledger.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.importer.ExportService;
import com.example.financetracker.ledger.importer.ExportSummary;
import com.example.financetracker.ledger.importer.TransactionsCsv;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The export of the signed-in user's ledger as one transactions file in the import's format (D4a, D-235 to D-243),
 * read only: his personal entries and his own family rows, never another member's records (D-239). The period is by
 * the records' dates, both ends included; both left out for the whole period (D-241).
 */
@RestController
@RequestMapping("/api/export")
class ExportController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ExportService exports;

    ExportController(ExportService exports) {
        this.exports = exports;
    }

    /**
     * How many rows the file of the period has, the dates of its first and last, what it skips as no single row by
     * kind, its file name, and D-242's check per account and currency.
     */
    @GetMapping("/summary")
    ExportSummary summary(LedgerScope ledger, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return exports.export(ledger, from, to).summary();
    }

    /**
     * The transactions file of the period (D-235, D-240): UTF-8 with a byte order mark, semicolons, CRLF, all 32
     * columns, as an attachment named {@code Transactions_<from>_<to>.csv} (D-241).
     */
    @GetMapping(path = "/transactions", produces = "text/csv")
    void transactions(LedgerScope ledger, @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to, HttpServletResponse response) throws IOException {
        ExportService.Export export = exports.export(ledger, from, to);
        response.setContentType(CSV.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(export.summary().fileName()).build().toString());
        TransactionsCsv.write(export.rows(), response.getOutputStream());
    }
}
