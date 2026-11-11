package com.example.financetracker.ledger;

import java.util.List;

/**
 * One page of the entries {@link EntryMatchService#matches} found, newest first.
 *
 * @param totalElements how many entries match, on all pages
 */
public record EntryMatchPage(List<EntryMatch> content, int page, int size, long totalElements, int totalPages) {
}
