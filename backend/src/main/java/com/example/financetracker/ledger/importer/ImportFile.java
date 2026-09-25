package com.example.financetracker.ledger.importer;

/**
 * A file handed to the importer, from the command line or an upload.
 *
 * @param name the file's name, for the report
 */
public record ImportFile(String name, byte[] content) {

    String sha256() {
        return ExcelValues.sha256(content);
    }
}
