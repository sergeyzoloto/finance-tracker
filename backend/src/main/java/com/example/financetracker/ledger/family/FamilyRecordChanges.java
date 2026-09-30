package com.example.financetracker.ledger.family;

/**
 * A change of a family record's family fields (D-14): the category, the comment and the split. Null leaves a field as
 * it is; the comment is changed, also to none, when {@code changesComment} is set.
 */
public record FamilyRecordChanges(Long categoryId, boolean changesComment, String comment, RecordSplit split) {
}
