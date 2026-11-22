package com.example.financetracker.ledger.api;

import java.util.Map;

import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.Notifications;
import com.example.financetracker.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The family notifications (ADR 0006, section 13, D-1087, D-1088); only with the family switch on. */
@RestController
@ConditionalOnProperty(name = FamilySwitch.PROPERTY, havingValue = "true")
class NotificationController {

    private final Notifications notifications;

    NotificationController(Notifications notifications) {
        this.notifications = notifications;
    }

    /** How many notifications the caller hasn't read, over all their family budgets. */
    @GetMapping("/api/notifications/unread-count")
    Map<String, Long> unreadCount(CurrentUser user) {
        return Map.of("count", notifications.unreadCount(user.id()));
    }
}
