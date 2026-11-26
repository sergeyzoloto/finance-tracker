package com.example.financetracker.ledger.api;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.RestorePlanner;
import com.example.financetracker.ledger.history.RestoreService;
import com.example.financetracker.ledger.history.SnapshotService;
import com.example.financetracker.ledger.history.SnapshotService.SnapshotPage;
import com.example.financetracker.ledger.history.SnapshotService.SnapshotView;
import com.example.financetracker.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Snapshots of the caller's personal ledger (ADR 0006, HIST-4 part 1), for its owner only. A family budget's snapshots
 * are refused with the code {@code FAMILY_SNAPSHOTS_UNSUPPORTED} until HIST-5.
 */
@RestController
@RequestMapping("/api")
class SnapshotController {

    /** @param name optional; trimmed, 1 to 80 characters; absent or blank is the date and time */
    record NewSnapshot(String name) {
    }

    /** @param atRevision the ledger's revision the preview was made at (D-1222) */
    record RestoreRequest(@NotNull Long atRevision) {
    }

    private final SnapshotService snapshots;
    private final LedgerAccess access;
    private final FamilySwitch family;
    private final RestorePlanner restores;
    private final RestoreService restoring;

    SnapshotController(SnapshotService snapshots, LedgerAccess access, FamilySwitch family,
            RestorePlanner restores, RestoreService restoring) {
        this.restores = restores;
        this.restoring = restoring;
        this.snapshots = snapshots;
        this.access = access;
        this.family = family;
    }

    @PostMapping("/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    SnapshotView create(LedgerScope personal, @RequestBody(required = false) NewSnapshot body) {
        return snapshots.create(personal, body == null ? null : body.name());
    }

    /** Newest first, offset paging. */
    @GetMapping("/snapshots")
    SnapshotPage list(LedgerScope personal, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return snapshots.list(personal, page, size);
    }

    @GetMapping("/snapshots/{id}")
    SnapshotView get(LedgerScope personal, @PathVariable long id) {
        return snapshots.get(personal, id);
    }

    /** A family budget's snapshots: refused for its members (409 with a code), unknown for anyone else. */
    @PostMapping("/family-ledgers/{ledgerId}/snapshots")
    SnapshotView createFamily(CurrentUser user, HttpServletRequest request, @PathVariable long ledgerId)
            throws NoResourceFoundException {
        return snapshots.create(familyScope(user, request, ledgerId), null);
    }

    /** No paging: every family request is refused until HIST-5, which must add paging here as the personal list has. */
    @GetMapping("/family-ledgers/{ledgerId}/snapshots")
    SnapshotPage listFamily(CurrentUser user, HttpServletRequest request, @PathVariable long ledgerId)
            throws NoResourceFoundException {
        return snapshots.list(familyScope(user, request, ledgerId), 0, 50);
    }

    /** The read-only plan of restoring this snapshot (HIST-4 part 2a): nothing is written, blockers say what would stop it. */
    @PostMapping("/snapshots/{id}/restore-preview")
    RestorePlanner.Preview restorePreview(LedgerScope personal, @PathVariable long id) {
        return restores.ofSnapshot(personal, id).preview();
    }

    /** A family budget's snapshot: refused for its members (409 with a code), unknown for anyone else. */
    @PostMapping("/family-ledgers/{ledgerId}/snapshots/{id}/restore-preview")
    RestorePlanner.Preview familyRestorePreview(CurrentUser user, HttpServletRequest request,
            @PathVariable long ledgerId, @PathVariable long id) throws NoResourceFoundException {
        return restores.ofSnapshot(familyScope(user, request, ledgerId), id).preview();
    }

    /**
     * Restores this snapshot, all or nothing (HIST-4 part 2b): 409 {@code RESTORE_STALE} if the ledger moved past
     * {@code atRevision}, 422 with the preview's blockers if the plan is refused.
     */
    @PostMapping("/snapshots/{id}/restore")
    RestoreService.Result restore(LedgerScope personal, @PathVariable long id,
            @Valid @RequestBody RestoreRequest body) {
        return restoring.restore(personal, id, body.atRevision());
    }

    /** A family budget's snapshot: refused for its members (409 with a code), unknown for anyone else. */
    @PostMapping("/family-ledgers/{ledgerId}/snapshots/{id}/restore")
    RestoreService.Result familyRestore(CurrentUser user, HttpServletRequest request, @PathVariable long ledgerId,
            @PathVariable long id, @RequestBody(required = false) RestoreRequest body) throws NoResourceFoundException {
        return restoring.restore(familyScope(user, request, ledgerId), id,
                body == null || body.atRevision() == null ? -1 : body.atRevision());
    }

    /** A refused plan: the rules' problem with the preview's blockers, nothing written. */
    @ExceptionHandler(RestoreService.BlockedException.class)
    org.springframework.http.ResponseEntity<ProblemDetail> blocked(RestoreService.BlockedException e) {
        java.util.List<String> violations = e.preview().blockers().stream().map(RestorePlanner.Blocker::message)
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,
                "The restore is blocked: " + String.join("; ", violations) + ".");
        problem.setTitle("The ledger's rules are broken");
        problem.setProperty("violations", violations);
        problem.setProperty("restorable", false);
        problem.setProperty("blockers", e.preview().blockers());
        problem.setProperty("counts", e.preview().counts());
        return org.springframework.http.ResponseEntity.unprocessableEntity().body(problem);
    }

    private LedgerScope familyScope(CurrentUser user, HttpServletRequest request, long ledgerId)
            throws NoResourceFoundException {
        if (!family.enabled()) {
            throw new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()),
                    request.getRequestURI().substring(1));
        }
        return access.member(user.id(), ledgerId);
    }
}
