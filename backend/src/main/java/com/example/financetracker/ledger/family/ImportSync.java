package com.example.financetracker.ledger.family;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How an import recognises a row of its source that it wrote before (D-48, D-86), in plain Java. The source's rows keep
 * their IDs for good: an ID is never renumbered, and the number of a deleted row is never used again (D-86). So a row is
 * recognised by its stable reference ({@code family_record.external_ref}, such as {@code xls:<file>:<ID>}) and never by
 * its content, which an edit changes; the content's hash, the version of the record as the import left it and the
 * member who imported it (V13) tell what to do with a row that is known:
 * <ul>
 * <li>no record has the reference: the row is {@link Outcome#NEW};
 * <li>the record was deleted in the app: it stays deleted, as the numbers of deleted rows are never reused, and the
 * import only reports it ({@link Outcome#DELETED});
 * <li>the row's content hash is the one imported last: {@link Outcome#UNCHANGED}, whatever was changed in the app since;
 * <li>the hash differs, the source row was edited, and the record's version is still the one the import left, so
 * nobody changed it in the app: {@link Outcome#EDITED}, which the import may write over, and stamp again;
 * <li>the hash differs and the record changed in the app too: {@link Outcome#CONFLICT}, which the import reports and
 * leaves alone, for a member to decide.
 * </ul>
 */
public final class ImportSync {

    private ImportSync() {
    }

    /** What an import does with a row of its source. */
    public enum Outcome {
        NEW, UNCHANGED, EDITED, CONFLICT, DELETED
    }

    /**
     * A record an import wrote earlier, as it stands.
     *
     * @param contentHash the hash of the source row's content at the last import
     * @param importedVersion the record's version right after that import wrote it
     * @param version the record's version now
     * @param deleted whether the record is deleted in the app
     */
    public record Known(long recordId, String contentHash, int importedVersion, int version, boolean deleted) {
    }

    /**
     * What to do with a row of the source.
     *
     * @param known the record with the row's reference, or null if there is none
     * @param contentHash the hash of the row's content now ({@link #contentHash})
     */
    public static Outcome recognise(Known known, String contentHash) {
        if (known == null) {
            return Outcome.NEW;
        }
        if (known.deleted()) {
            return Outcome.DELETED;
        }
        if (known.contentHash().equals(contentHash)) {
            return Outcome.UNCHANGED;
        }
        return known.version() == known.importedVersion() ? Outcome.EDITED : Outcome.CONFLICT;
    }

    /**
     * The SHA-256 of a source row's business content, in lowercase hex: each field in order, with its length so that no
     * two lists of fields give the same bytes, a missing field as its own marker. The caller names the fields that
     * define the row's meaning (date, accounts, amounts, category and the like), and never the ones that only describe
     * the file, so that re-exporting an unchanged row gives the same hash.
     */
    public static String contentHash(String... fields) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always there", e);
        }
        for (String field : fields) {
            byte[] bytes = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
            digest.update((bytes == null ? "-" : bytes.length + ":").getBytes(StandardCharsets.UTF_8));
            if (bytes != null) {
                digest.update(bytes);
            }
            digest.update((byte) ';');
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
