package com.example.financetracker.ledger.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** D-1107: a notification's text, from its kind, its author's name and its count only; no decision number. */
class NotificationTextTests {

    @Test
    void eachKindReadsShortWithTheAuthorsName() {
        assertThat(NotificationText.of("FAMILY_CHANGES", "Mum", 1)).isEqualTo("Mum made a change in the family budget");
        assertThat(NotificationText.of("FAMILY_CHANGES", "Mum", 4)).isEqualTo("Mum made 4 changes in the family budget");
        assertThat(NotificationText.of("FAMILY_IMPORTED", "Dad", null))
                .isEqualTo("Dad imported records into the family budget");
        assertThat(NotificationText.of("MEMBER_JOINED", "Gran", null)).isEqualTo("Gran joined the family budget");
        assertThat(NotificationText.of("MEMBER_LEFT", "Former member", null))
                .isEqualTo("Former member left the family budget");
        assertThat(NotificationText.of("SEAT_CLAIMED", "Sam", null))
                .isEqualTo("Sam took their place in the family budget");
        assertThat(NotificationText.of("MEMBER_JOINED", null, null)).isEqualTo("Someone joined the family budget");
        for (Notifications.Kind kind : Notifications.Kind.values()) {
            assertThat(NotificationText.of(kind.name(), "A", 2)).doesNotContainPattern("D-\\d");
        }
        assertThatThrownBy(() -> NotificationText.of("OTHER", "A", 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
