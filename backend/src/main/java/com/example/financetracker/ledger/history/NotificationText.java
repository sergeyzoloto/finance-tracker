package com.example.financetracker.ledger.history;

/**
 * The text of a notification, built when it is read (D-1087, D-1107): from its kind, its author's name now and its
 * count of changes only. Pure Java, no SQL.
 */
public final class NotificationText {

    private NotificationText() {
    }

    /**
     * @param author the author's name now, null for a notification without an author
     * @param count how many changes a FAMILY_CHANGES notification covers; ignored for the other kinds
     */
    public static String of(String kind, String author, Integer count) {
        String who = author == null ? "Someone" : author;
        return switch (kind) {
            case "FAMILY_CHANGES" -> count == null || count <= 1 ? who + " made a change in the family budget"
                    : who + " made " + count + " changes in the family budget";
            case "FAMILY_IMPORTED" -> who + " imported records into the family budget";
            case "MEMBER_JOINED" -> who + " joined the family budget";
            case "MEMBER_LEFT" -> who + " left the family budget";
            case "SEAT_CLAIMED" -> who + " took their place in the family budget";
            default -> throw new IllegalArgumentException("Unknown notification kind " + kind);
        };
    }
}
