package com.getlingara.cli.java.auth;

import com.getlingara.cli.java.store.ProfileRecord;

/**
 * The signed-in session (16.10.26ad D3): either the CI pair, kept in memory only, or the stored
 * record; the access token in use; the email {@code --confirm} is checked against (the record's,
 * or under CI {@code developer.me}'s, read once); and the granted scopes.
 */
public final class Session {
    /** {@code LINGARA_PLATFORM_CLIENT_ID} / {@code _SECRET}, or {@code null} for a stored one. */
    final String[] ci;
    /** The stored record, or {@code null} under CI. */
    final ProfileRecord record;
    String access;
    String email;
    final String scopes;

    Session(String[] ci, ProfileRecord record, String access, String email, String scopes) {
        this.ci = ci;
        this.record = record;
        this.access = access;
        this.email = email;
        this.scopes = scopes;
    }

    /** A stored record's session; {@code email} may be {@code null} until {@code developer.me} says. */
    public static Session stored(ProfileRecord record, String email) {
        return new Session(null, record, record.accessToken, email, record.scopes);
    }

    public String scopes() {
        return scopes;
    }
}
