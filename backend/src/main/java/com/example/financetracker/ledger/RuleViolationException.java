package com.example.financetracker.ledger;

import java.util.List;

import com.example.financetracker.ledger.domain.InvalidEntryException;

/**
 * A change, other than to an entry, that breaks one of the ledger's rules, such as settings that name an account the
 * user doesn't have, or a file of rates with invalid rows. An entry that breaks them is an
 * {@link InvalidEntryException}.
 */
public class RuleViolationException extends RuntimeException {

    private final List<String> violations;
    private final List<Violation> details;

    public RuleViolationException(String message) {
        this(List.of(message));
    }

    /** @param violations at least one; each a sentence without its full stop */
    public RuleViolationException(List<String> violations) {
        this(violations, List.of());
    }

    /**
     * Violations that a client can place, such as the family budget's, which name the member each is about.
     *
     * @param details at least one
     */
    public static RuleViolationException of(List<Violation> details) {
        return new RuleViolationException(details.stream().map(Violation::message).toList(), details);
    }

    private RuleViolationException(List<String> violations, List<Violation> details) {
        super(String.join("; ", violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("A rule violation needs a message");
        }
        this.violations = List.copyOf(violations);
        this.details = List.copyOf(details);
    }

    public List<String> violations() {
        return violations;
    }

    /** The violations with a code and the member each is about, where the rule has them; empty otherwise. */
    public List<Violation> details() {
        return details;
    }

    /**
     * One violation, as a client can place it.
     *
     * @param code what is wrong, such as NO_SHARE; stable, unlike the message
     * @param memberId the family member it is about, or null
     * @param message the sentence without its full stop, as {@link #violations()} has it
     */
    public record Violation(String code, Long memberId, String message) {
    }
}
