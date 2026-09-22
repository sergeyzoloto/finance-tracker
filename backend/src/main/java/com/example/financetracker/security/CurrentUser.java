package com.example.financetracker.security;

/** Principal of an authenticated request: the local {@code users.id} behind the JWT subject. */
public record CurrentUser(long id) {
}
