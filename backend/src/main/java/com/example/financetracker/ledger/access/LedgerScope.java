package com.example.financetracker.ledger.access;

import java.util.Objects;

/**
 * A ledger that a user may read and write, because they are an ACTIVE member of it (ADR 0003, topic C). Only
 * {@link LedgerAccess} makes one, after checking the membership. Services and repositories that read or write ledger
 * rows take a LedgerScope, never a raw ledger id or a sub, and scope every query by {@link #ledgerId()}: anything
 * outside that ledger reads as missing (rule 11).
 */
public final class LedgerScope {

    private final long ledgerId;
    private final LedgerType type;
    private final long memberId;
    private final MemberRole role;
    private final String userId;

    LedgerScope(long ledgerId, LedgerType type, long memberId, MemberRole role, String userId) {
        this.ledgerId = ledgerId;
        this.type = Objects.requireNonNull(type, "type");
        this.memberId = memberId;
        this.role = Objects.requireNonNull(role, "role");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public long ledgerId() {
        return ledgerId;
    }

    public LedgerType type() {
        return type;
    }

    /** The user's membership in the ledger, which family rows will refer to instead of the sub (D-3). */
    public long memberId() {
        return memberId;
    }

    public MemberRole role() {
        return role;
    }

    /**
     * The Keycloak "sub" of the member. Until the cleanup migration after F7, the rows of a personal ledger also carry
     * it as their {@code user_id} (ADR 0003, topic A), and a personal ledger's settings and manual rates are its
     * member's, keyed by it.
     */
    public String userId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LedgerScope scope && ledgerId == scope.ledgerId && type == scope.type
                && memberId == scope.memberId && role == scope.role && userId.equals(scope.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ledgerId, type, memberId, role, userId);
    }

    @Override
    public String toString() {
        return "LedgerScope[ledgerId=%d, type=%s, memberId=%d, role=%s]".formatted(ledgerId, type, memberId, role);
    }
}
