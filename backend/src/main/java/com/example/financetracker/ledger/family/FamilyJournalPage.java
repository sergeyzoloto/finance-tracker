package com.example.financetracker.ledger.family;

import java.util.List;

/** One page of a family ledger's change journal, newest first. */
public record FamilyJournalPage(List<FamilyChangeView> content, int page, int size, long totalElements,
        int totalPages) {
}
