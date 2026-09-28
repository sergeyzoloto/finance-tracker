package com.example.financetracker.ledger.api;

import com.example.financetracker.ledger.UserDataService;
import com.example.financetracker.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The user's data in this app as a whole. */
@RestController
class UserDataController {

    private final UserDataService userData;

    UserDataController(UserDataService userData) {
        this.userData = userData;
    }

    /**
     * Deletes everything the user has in this app, in one transaction. The next request starts them again with the
     * starter ledger, as a new user. The login account on auth.finance-nl.com stays.
     */
    @DeleteMapping("/api/me/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAll(CurrentUser user) {
        userData.deleteAll(user.id());
    }
}
