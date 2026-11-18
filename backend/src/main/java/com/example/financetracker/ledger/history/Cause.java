package com.example.financetracker.ledger.history;

/** Why a version was written (ADR 0006, 5.1). */
public enum Cause {
    EDIT, IMPORT, DEMO, PROVISION, LEAVE, RETURN, MERGE, RESTORE, BASELINE, RECONCILE;

    /** Who the version is attributed to: the system for what no user chose, the import, else the acting user. */
    String actorKind(boolean actorKnown) {
        return switch (this) {
            case PROVISION, LEAVE, RETURN, BASELINE, RECONCILE -> "SYSTEM";
            case IMPORT -> "IMPORT";
            default -> actorKnown ? "USER" : "SYSTEM";
        };
    }
}
