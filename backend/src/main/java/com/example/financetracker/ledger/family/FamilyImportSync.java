package com.example.financetracker.ledger.family;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.example.financetracker.ledger.ConflictException;
import com.example.financetracker.ledger.NotFoundException;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.ImportSync.Known;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the import of a family ledger's records reads and writes to recognise its rows again (D-48, D-86; V13): the
 * stable reference, the content hash, the record's version as the import left it, and who imported it. There is no
 * endpoint: the import (D3b) calls it, in the transaction of its own writes, as the member who imports; the rules for a
 * known row are {@link ImportSync}'s.
 */
@Service
public class FamilyImportSync {

    private final JdbcClient jdbc;

    FamilyImportSync(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The records of the family ledger that an import wrote under these references, deleted ones too, by reference.
     */
    @Transactional(readOnly = true)
    public Map<String, Known> known(LedgerScope family, Collection<String> references) {
        Map<String, Known> known = new HashMap<>();
        if (references.isEmpty()) {
            return known;
        }
        jdbc.sql("""
                SELECT id, external_ref, content_hash, imported_version, version, deleted_at IS NOT NULL AS deleted
                FROM family_record
                WHERE ledger_id = :ledgerId AND external_ref IN (:references)""")
                .param("ledgerId", family.ledgerId()).param("references", references)
                .query(row -> {
                    known.put(row.getString("external_ref"), new Known(row.getLong("id"),
                            row.getString("content_hash"), row.getInt("imported_version"), row.getInt("version"),
                            row.getBoolean("deleted")));
                });
        return known;
    }

    /**
     * Records that the import wrote the record from the source row with this reference and content: its reference, the
     * hash, its version as it is now (so that a later change in the app shows), the importing member (the one who acts)
     * and the time. Called right after the import created the record, or changed it for an {@link ImportSync.Outcome#EDITED}
     * row; the record's version is not changed.
     *
     * @param family the family ledger, as the member who imports
     * @throws NotFoundException if the family ledger has no such record
     * @throws ConflictException if another record of the family ledger has the reference already
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stamp(LedgerScope family, long recordId, String reference, String contentHash) {
        Long other = jdbc.sql("""
                SELECT id FROM family_record
                WHERE ledger_id = :ledgerId AND external_ref = :reference AND id <> :recordId""")
                .param("ledgerId", family.ledgerId()).param("reference", reference).param("recordId", recordId)
                .query(Long.class).optional().orElse(null);
        if (other != null) {
            throw new ConflictException("Record %d has the import reference %s already".formatted(other, reference));
        }
        int stamped = jdbc.sql("""
                UPDATE family_record
                SET external_ref = :reference, content_hash = :hash, imported_version = version,
                    imported_by_member_id = :memberId, imported_at = now()
                WHERE id = :recordId AND ledger_id = :ledgerId""")
                .param("reference", reference).param("hash", contentHash).param("memberId", family.memberId())
                .param("recordId", recordId).param("ledgerId", family.ledgerId())
                .update();
        if (stamped != 1) {
            throw new NotFoundException("Record %d not found".formatted(recordId));
        }
    }
}
