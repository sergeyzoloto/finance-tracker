package com.example.financetracker.ledger.api;

import static com.example.financetracker.ledger.api.AccountController.NOT_BLANK;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.api.CurrencyCode;
import com.example.financetracker.ledger.CategoryService;
import com.example.financetracker.ledger.CategoryView;
import com.example.financetracker.ledger.RuleViolationException;
import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.api.CategoryController.CategoryPatch;
import com.example.financetracker.ledger.api.CategoryController.NewCategory;
import com.example.financetracker.ledger.family.FamilyLedgerService;
import com.example.financetracker.ledger.family.FamilyLedgerView;
import com.example.financetracker.ledger.family.FamilyMemberView;
import com.example.financetracker.ledger.family.FamilyMembershipService;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.family.SplitRule;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Family ledgers (ADR 0003, topic I): the ledger's id is in the path, and {@link LedgerAccess} lets in only its
 * ACTIVE members. Anyone else gets 404, the answer for a ledger that doesn't exist, for reads and writes alike; a
 * member who isn't an owner gets 409 for what only owners may do (D-15). No answer holds a sub or an email address.
 * <p>
 * Only while the feature switch is on (D-25, {@link FamilySwitch}): otherwise these paths are unknown and answer 404.
 */
@RestController
@RequestMapping("/api/family-ledgers")
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class FamilyLedgerController {

    /**
     * @param displayName the name the other members will see (D-3)
     * @param splitRule the default split rule; EQUAL if left out. Under CUSTOM the creator's share is 10000
     * @param categoryIds the creator's own categories whose code, name and type start the family's dictionary
     * @param startDate the first day a record may be dated, and the creator's join date (D-27); today if left out,
     *        never later
     */
    record NewFamilyLedger(@NotBlank @Size(max = 100) String name, @NotNull @CurrencyCode String baseCurrency,
            @NotBlank @Size(max = 100) String displayName, SplitRule splitRule, List<@NotNull Long> categoryIds,
            LocalDate startDate) {
    }

    /** Rename the ledger or change its base currency; fields left out stay as they are. */
    record FamilyLedgerPatch(@Size(max = 100) @Pattern(regexp = NOT_BLANK, message = "must not be blank") String name,
            @CurrencyCode String baseCurrency) {
    }

    /** @param shares under CUSTOM, one per ACTIVE member, summing to 10000; under EQUAL, none */
    record SplitRuleRequest(@NotNull SplitRule rule, List<@Valid @NotNull Share> shares) {
    }

    /** @param share in basis points: 2500 is 25.00 % */
    record Share(@NotNull Long memberId, @NotNull @Min(0) @Max(10_000) Integer share) {
    }

    /** A member without an account (B1), or a new name for one or for oneself. */
    record MemberName(@NotBlank @Size(max = 100) String displayName) {
    }

    private final LedgerAccess access;
    private final FamilyLedgerService families;
    private final CategoryService categories;
    private final FamilyMembershipService memberships;

    FamilyLedgerController(LedgerAccess access, FamilyLedgerService families, CategoryService categories,
            FamilyMembershipService memberships) {
        this.access = access;
        this.families = families;
        this.categories = categories;
        this.memberships = memberships;
    }

    /** The family ledgers the user is an ACTIVE member of, by name, for the ledger switcher. */
    @GetMapping
    List<FamilyLedgerView> list(CurrentUser user) {
        return access.families(user.id()).stream().map(families::get)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
    }

    /** A new family ledger, with the user as its owner. A category that isn't the user's own answers 404. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FamilyLedgerView create(LedgerScope personal, @Valid @RequestBody NewFamilyLedger ledger) {
        return families.create(personal, ledger.name().strip(), ledger.baseCurrency(), ledger.startDate(),
                ledger.displayName().strip(),
                ledger.splitRule() == null ? SplitRule.EQUAL : ledger.splitRule(),
                ledger.categoryIds() == null ? List.of() : ledger.categoryIds());
    }

    @GetMapping("/{ledgerId}")
    FamilyLedgerView get(CurrentUser user, @PathVariable long ledgerId) {
        return families.get(access.member(user.id(), ledgerId));
    }

    /** Owners only. */
    @PatchMapping("/{ledgerId}")
    FamilyLedgerView update(CurrentUser user, @PathVariable long ledgerId,
            @Valid @RequestBody FamilyLedgerPatch patch) {
        return families.update(access.owner(user.id(), ledgerId), patch.name() == null ? null : patch.name().strip(),
                patch.baseCurrency());
    }

    /** Owners only. Returns the members with their new shares; shares that don't fit the rule answer 422. */
    @PutMapping("/{ledgerId}/split-rule")
    List<FamilyMemberView> setSplitRule(CurrentUser user, @PathVariable long ledgerId,
            @Valid @RequestBody SplitRuleRequest request) {
        LedgerScope owner = access.owner(user.id(), ledgerId);
        Map<Long, Integer> shares = new LinkedHashMap<>();
        for (Share share : request.shares() == null ? List.<Share>of() : request.shares()) {
            if (shares.put(share.memberId(), share.share()) != null) {
                throw RuleViolationException.of(List.of(FamilyLedgerService.duplicate(share.memberId())));
            }
        }
        return families.setSplitRule(owner, request.rule(), shares);
    }

    /** Every member, by join date: display name, role, status, join date, whether they have an account, share. */
    @GetMapping("/{ledgerId}/members")
    List<FamilyMemberView> members(CurrentUser user, @PathVariable long ledgerId) {
        return families.members(access.member(user.id(), ledgerId));
    }

    /** Owners only: a member without an account, who joins today. */
    @PostMapping("/{ledgerId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    FamilyMemberView addMember(CurrentUser user, @PathVariable long ledgerId,
            @Valid @RequestBody MemberName member) {
        return families.addMember(access.owner(user.id(), ledgerId), member.displayName().strip());
    }

    /** Any member with an account, for their own name: the path names no other member (D-3). */
    @PatchMapping("/{ledgerId}/members/me")
    FamilyMemberView renameSelf(CurrentUser user, @PathVariable long ledgerId, @Valid @RequestBody MemberName member) {
        return families.renameSelf(access.member(user.id(), ledgerId), member.displayName().strip());
    }

    /** Owners only, for a member without an account. */
    @PatchMapping("/{ledgerId}/members/{memberId}")
    FamilyMemberView renameMember(CurrentUser user, @PathVariable long ledgerId, @PathVariable long memberId,
            @Valid @RequestBody MemberName member) {
        return families.renameMember(access.owner(user.id(), ledgerId), memberId, member.displayName().strip());
    }

    /**
     * Any member with an account leaves (D-19, F6a): LEFT, detached, reading nothing of the family budget afterwards.
     * The last owner while another member with an account remains: 409 with the code {@code LAST_OWNER}.
     */
    @DeleteMapping("/{ledgerId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(CurrentUser user, @PathVariable long ledgerId) {
        memberships.leave(access.member(user.id(), ledgerId));
    }

    /**
     * Owners only (D-15, D-19, F6a): removes a member, with or without an account, who becomes LEFT and is detached;
     * a member without an account whom no record names, and without a custom share, is deleted.
     */
    @DeleteMapping("/{ledgerId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(CurrentUser user, @PathVariable long ledgerId, @PathVariable long memberId) {
        memberships.remove(access.owner(user.id(), ledgerId), memberId);
    }

    /** The family's categories (D-11), archived ones included, by name. */
    @GetMapping("/{ledgerId}/categories")
    List<CategoryView> categories(CurrentUser user, @PathVariable long ledgerId) {
        return categories.list(access.member(user.id(), ledgerId));
    }

    /** Any member: codes follow the rules of personal categories. */
    @PostMapping("/{ledgerId}/categories")
    @ResponseStatus(HttpStatus.CREATED)
    CategoryView createCategory(CurrentUser user, @PathVariable long ledgerId,
            @Valid @RequestBody NewCategory category) {
        return categories.create(access.member(user.id(), ledgerId), category.code(), category.name().strip(),
                category.type());
    }

    /** Owners only: rename, archive or restore; a category's type never changes (409). */
    @PatchMapping("/{ledgerId}/categories/{categoryId}")
    CategoryView updateCategory(CurrentUser user, @PathVariable long ledgerId, @PathVariable long categoryId,
            @Valid @RequestBody CategoryPatch patch) {
        return categories.update(access.owner(user.id(), ledgerId), categoryId,
                patch.name() == null ? null : patch.name().strip(), patch.archived(), patch.type());
    }

    /** Owners only, while nothing uses the category; otherwise 409, and it can be archived. */
    @DeleteMapping("/{ledgerId}/categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCategory(CurrentUser user, @PathVariable long ledgerId, @PathVariable long categoryId) {
        categories.delete(access.owner(user.id(), ledgerId), categoryId);
    }
}
