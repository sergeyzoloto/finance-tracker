package com.example.financetracker.ledger;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One run of the importer over one file; the entries it wrote point back to it.
 *
 * @param userId the sub of the personal ledger's member, until the cleanup migration (ADR 0003, topic A)
 */
@Table("import_batch")
public record ImportBatch(@Id Long id, String userId, Long ledgerId, String fileName, String fileSha256, boolean dryRun,
        @ReadOnlyProperty Instant startedAt, Instant finishedAt, Json report) {
}
