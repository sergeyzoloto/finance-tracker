package com.example.financetracker.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Collects what is wrong with a command, so that one {@link InvalidEntryException} names all of it. */
final class Problems {

    private final List<String> problems = new ArrayList<>();

    /** Every command needs a date. */
    static Problems ofEntry(Object entryDate) {
        return new Problems().present(entryDate, "entry date");
    }

    Problems check(boolean ok, String problem) {
        if (!ok) {
            problems.add(problem);
        }
        return this;
    }

    Problems present(Object value, String name) {
        return check(value != null, "the " + name + " is missing");
    }

    Problems nonZero(BigDecimal amount, String name) {
        return amount == null ? present(null, name) : check(amount.signum() != 0, "the " + name + " must not be zero");
    }

    Problems positive(BigDecimal amount, String name) {
        return amount == null ? present(null, name) : check(amount.signum() > 0, "the " + name + " must be positive");
    }

    void throwIfAny() {
        if (!problems.isEmpty()) {
            throw new InvalidEntryException(problems);
        }
    }
}
