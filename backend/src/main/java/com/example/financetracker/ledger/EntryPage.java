package com.example.financetracker.ledger;

import java.util.List;

/**
 * One page of the entries {@link EntryService#search} found.
 *
 * @param page the page's number, from 0
 * @param size the most entries a page holds
 * @param totalElements how many entries match, on all pages
 */
public record EntryPage(List<EntryView> content, int page, int size, long totalElements, int totalPages) {
}
