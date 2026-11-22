package com.example.financetracker.ledger.api;

import java.util.Map;

import com.example.financetracker.ledger.family.FamilySwitch;
import com.example.financetracker.ledger.history.Notifications;
import com.example.financetracker.ledger.history.Notifications.NotificationPage;
import com.example.financetracker.security.CurrentUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The family notifications (ADR 0006, section 13, D-1087, D-1088, D-1107); only with the family switch on. */
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

    /** The caller's notifications in the family budgets they are an ACTIVE member of, newest first. */
    @GetMapping("/api/notifications")
    NotificationPage list(CurrentUser user, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return notifications.page(user.id(), page, size);
    }

    @PostMapping("/api/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(CurrentUser user, @PathVariable long id) {
        notifications.markRead(user.id(), id);
    }

    @PostMapping("/api/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(CurrentUser user) {
        notifications.markAllRead(user.id());
    }
}
