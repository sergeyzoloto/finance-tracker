package com.example.financetracker.ledger.api;

import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilyInviteService;
import com.example.financetracker.ledger.family.FamilyInviteView;
import com.example.financetracker.ledger.family.FamilyInviteView.InviteKind;
import com.example.financetracker.ledger.family.FamilyLedgerView;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.InviteLookup;
import com.example.financetracker.security.CurrentUser;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invites (B2, B3, B4; D-17; ADR 0003, topics G and I). Owners create, list and revoke a family ledger's invites under
 * {@code /api/family-ledgers/{ledgerId}/invites}, which answer like the ledger's other paths: 404 for anyone who
 * isn't an ACTIVE member, 409 for a member who isn't an owner (D-15). The signed-in user who holds a link looks it up,
 * accepts or declines it under {@code /api/invites}, with the token in the request body only, never in a URL; those
 * three are rate limited per user and per client address ({@link InviteRateLimit}, 429).
 * <p>
 * The token is in the answer to the creation, as the link {@code <app.public-url>/invite#<token>}, and nowhere else:
 * the fragment never reaches a server, so it stays out of every access log.
 * <p>
 * Only while the feature switch is on (D-25, {@link FamilySwitch}): otherwise these paths are unknown and answer 404.
 */
@RestController
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class FamilyInviteController {

    /**
     * @param kind a new member, or a claim of {@code seatMemberId}'s place from {@code joinDate} (D-18)
     * @param lifetimeHours how long the link works: 1 to 168 hours, 72 if left out (D-17)
     */
    record NewInvite(@NotNull InviteKind kind, Long seatMemberId, LocalDate joinDate,
            @Min(1) @Max(FamilyInviteService.MAX_HOURS) Integer lifetimeHours) {
    }

    /** The invite, and its link with the token: this answer only. */
    record CreatedInvite(@JsonUnwrapped FamilyInviteView invite, String link) {
    }

    /**
     * An invite's token, from the link's fragment. Not validated here: a validation error would carry the rejected
     * value into Spring's DEBUG log; a missing or malformed token is an unknown one (LedgerInvites).
     */
    record InviteToken(String token) {

        /** Without the token: Spring MVC logs the body it read at DEBUG. */
        @Override
        public String toString() {
            return "InviteToken[token=…]";
        }
    }

    /**
     * @param displayName the name the other members will see (D-3)
     * @param categoryIds the user's own categories to bring into the family's dictionary (D-11)
     */
    record Acceptance(String token, @NotBlank @Size(max = 100) String displayName,
            List<@NotNull Long> categoryIds) {

        /** Without the token: Spring MVC logs the body it read at DEBUG. */
        @Override
        public String toString() {
            return "Acceptance[token=…, displayName=%s, categoryIds=%s]".formatted(displayName, categoryIds);
        }
    }

    private final LedgerAccess access;
    private final FamilyInviteService invites;
    private final InviteRateLimit limit;
    private final String publicUrl;

    FamilyInviteController(LedgerAccess access, FamilyInviteService invites, InviteRateLimit limit,
            @Value("${app.public-url}") String publicUrl) {
        this.access = access;
        this.invites = invites;
        this.limit = limit;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** Owners only. The answer holds the link, with its token, once. */
    @PostMapping("/api/family-ledgers/{ledgerId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedInvite create(CurrentUser user, @PathVariable long ledgerId, @Valid @RequestBody NewInvite invite) {
        LedgerScope owner = access.owner(user.id(), ledgerId);
        if ((invite.kind() == InviteKind.CLAIM) != (invite.seatMemberId() != null)) {
            throw new IllegalArgumentException(invite.kind() == InviteKind.CLAIM
                    ? "A claim names the member without an account whose place it takes (seatMemberId)"
                    : "An invite for a new member names no seat");
        }
        FamilyInviteService.CreatedInvite created = invites.create(owner, invite.seatMemberId(), invite.joinDate(),
                invite.lifetimeHours());
        return new CreatedInvite(created.invite(), publicUrl + "/invite#" + created.token());
    }

    /** Owners only: every invite, newest first, with its status; never a token. */
    @GetMapping("/api/family-ledgers/{ledgerId}/invites")
    List<FamilyInviteView> list(CurrentUser user, @PathVariable long ledgerId) {
        return invites.list(access.owner(user.id(), ledgerId));
    }

    /** Owners only: a pending invite stops working. */
    @DeleteMapping("/api/family-ledgers/{ledgerId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentUser user, @PathVariable long ledgerId, @PathVariable long inviteId) {
        invites.revoke(access.owner(user.id(), ledgerId), inviteId);
    }

    /** What the invite shows before it is accepted (D-17). */
    @PostMapping("/api/invites/lookup")
    InviteLookup lookup(CurrentUser user, LedgerScope personal, HttpServletRequest request,
            @Valid @RequestBody InviteToken token) {
        limit.attempt(user.id(), request.getRemoteAddr());
        return invites.lookup(personal, token.token(), user.name());
    }

    /** Joins the family budget, and answers it as the new member sees it. */
    @PostMapping("/api/invites/accept")
    FamilyLedgerView accept(CurrentUser user, LedgerScope personal, HttpServletRequest request,
            @Valid @RequestBody Acceptance acceptance) {
        limit.attempt(user.id(), request.getRemoteAddr());
        return invites.accept(personal, acceptance.token(), acceptance.displayName().strip(),
                acceptance.categoryIds() == null ? List.of() : acceptance.categoryIds());
    }

    /** Declines the invite, which uses it up. */
    @PostMapping("/api/invites/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(CurrentUser user, LedgerScope personal, HttpServletRequest request,
            @Valid @RequestBody InviteToken token) {
        limit.attempt(user.id(), request.getRemoteAddr());
        invites.decline(personal, token.token());
    }
}
