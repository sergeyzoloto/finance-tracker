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
 * @param openingBalance for a claim, the seat's family balance before the join date, in the base currency, which the
 *        user takes on as their opening balance (D-18, D-34): positive when the seat owes the family, negative when the
 *        family owes it; null for a new member (additive, F6a)
 * @param returning whether the user was a member who left and comes back by this invite (D-26; additive, F6a)
 * @param correction for a returning member, the one correction dated on the join date that makes their debt to the
 *        family budget show their family balance: their balance before the join date less what their former debt
 *        account shows (D-26), positive when it adds to what they owe; null otherwise (additive, F6a)
 */
public record InviteLookup(String ledgerName, String baseCurrency, String invitedBy, InviteKind kind, String seatName,
        LocalDate joinDate, Instant expiresAt, List<FamilyCategory> categories, List<CategoryMerge> merges,
        List<CategoryKept> keptPrivate, List<CategoryChoice> mayBring, String displayName, BigDecimal openingBalance,
        boolean returning, BigDecimal correction) {

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
