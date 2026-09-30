package com.example.financetracker.ledger.family;

import java.util.List;

/**
 * One page of a family ledger's records, newest first.
 *
 * @param page the page's number, from 0
 * @param totalElements how many records there are, on all pages
 */
public record FamilyRecordPage(List<FamilyRecordView> content, int page, int size, long totalElements,
        int totalPages) {
}
