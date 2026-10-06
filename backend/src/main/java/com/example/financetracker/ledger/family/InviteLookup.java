package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyInviteView.InviteKind;

/**
 * What a pending invite shows the signed-in user who holds its link, before they accept or decline it (D-17, D-11):
 * the family ledger's name and base currency, who invites, which seat and its opening balance, from which date their
 * shares are posted, the family's categories, and what becomes of the user's own categories. Nothing else of the family ledger: no member id,
 * sub, email address, account, record or other member's category. The only personal categories in it are the
 * caller's own.
 *
 * @param seatName the name of the member without an account whose place a claim takes; null for a new member
 * @param joinDate the day from which the family's records are posted to the user: a claim's join date, or today
 * @param categories the family's categories that aren't archived
 * @param merges the user's categories that merge into the family's category with their code and type, which keeps its
 *        name (D-11 as amended after the F4e review)
 * @param keptPrivate the user's categories with a family category's code but of the other type: they stay private
 * @param mayBring the user's categories, not archived, whose code the family doesn't have: they may bring them
 * @param displayName the user's account name, to prefill the name the other members will see (D-3)
 * @param returning whether the user was a member who left and comes back by this invite (D-26; additive, F6a)
 * @param entriesAfterReturn for a returning member, their own entries on their former debt account dated after the
 *        join date that belong to no record of the family budget, oldest first: while there is one, accepting answers
 *        409 {@code ENTRIES_AFTER_RETURN}, and they move or delete them first (D-37); null otherwise (additive, F6b)
 * @param openingBalances for a claim, the seat's opening balance in each currency whose amount isn't 0, the main
 *        currency first (D-31, D-35, D-45; additive, F8a): one pair of lines each in the one opening entry; empty when
 *        there is nothing to take on; null for a new member
 * @param corrections for a returning member, the correction in each currency whose amount isn't 0, the main currency
 *        first (D-26, D-39, D-45; additive, F8a); null otherwise
 */
public record InviteLookup(String ledgerName, String baseCurrency, String invitedBy, InviteKind kind, String seatName,
        LocalDate joinDate, Instant expiresAt, List<FamilyCategory> categories, List<CategoryMerge> merges,
        List<CategoryKept> keptPrivate, List<CategoryChoice> mayBring, String displayName,
        boolean returning, List<EntryAfterReturn> entriesAfterReturn, List<CurrencyAmount> openingBalances,
        List<CurrencyAmount> corrections) {

    /**
     * One of the user's own entries on their former debt account, dated after the join date (D-37).
     *
     * @param amount what it adds to the debt that account shows, in {@code currency}: positive when it adds to what
     *        they owe
     */
    public record EntryAfterReturn(long entryId, LocalDate date, BigDecimal amount, String currency, String memo) {
    }

    /** A category of the family, without its id. */
    public record FamilyCategory(String code, String name, String type) {
    }

    /** One of the user's categories, which merges into the family category {@code familyName}. */
    public record CategoryMerge(long categoryId, String code, String name, String familyName, String type) {
    }

    /** One of the user's categories, of {@code type}, beside a family category of {@code familyType}. */
    public record CategoryKept(long categoryId, String code, String name, String type, String familyType) {
    }

    /** One of the user's categories that they may bring into the family's dictionary. */
    public record CategoryChoice(long categoryId, String code, String name, String type) {
    }
}
