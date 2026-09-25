package com.example.financetracker.ledger;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/** One run of the importer over one file; the entries it wrote point back to it. */
@Table("import_batch")
public record ImportBatch(@Id Long id, String userId, String fileName, String fileSha256, boolean dryRun,
        @ReadOnlyProperty Instant startedAt, Instant finishedAt, Json report) {
}
