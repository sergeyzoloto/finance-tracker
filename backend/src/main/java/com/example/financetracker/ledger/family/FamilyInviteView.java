package com.example.financetracker.ledger.family;

import java.time.Instant;
import java.time.LocalDate;

import com.example.financetracker.ledger.family.FamilyRecordView.MemberRef;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An invite to a family ledger as its owners see it (B4; D-17): never its token, which only the answer to its creation
 * holds. A declined invite says only when (D-17); nothing about who declined it is kept.
 *
 * @param seat the member without an account whose place a claim takes; null for a new member
 * @param joinDate a claim's join date; for an accepted invite of a new member, the day they joined; else null
 * @param acceptedBy the member it let in, as they are named now; only for an accepted invite
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FamilyInviteView(long id, InviteKind kind, MemberRef seat, LocalDate joinDate, MemberRef createdBy,
        Instant createdAt, Instant expiresAt, InviteStatus status, MemberRef acceptedBy, Instant acceptedAt,
        Instant declinedAt, Instant revokedAt) {

    /** For a new member, or to take the place of a member without an account (D-18). */
    public enum InviteKind {
        NEW_MEMBER, CLAIM
    }

    /** Where an invite stands: only a PENDING one can be accepted, declined or revoked. */
    public enum InviteStatus {
        PENDING, ACCEPTED, DECLINED, REVOKED, EXPIRED
    }
}
