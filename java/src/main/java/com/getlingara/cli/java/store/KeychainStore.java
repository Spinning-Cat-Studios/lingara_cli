package com.getlingara.cli.java.store;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import com.getlingara.cli.java.core.Json;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The OS keychain (16.10.26ad D4; ADR 16.10.26af D2): service {@code lingara-cli}, account the
 * profile name, value the profile record as one JSON document. java-keyring speaks to macOS
 * Keychain, Windows Credential Manager and the Secret Service.
 *
 * <p>The keyring is opened on first use, so a run under {@code LINGARA_KEYRING=file} never loads
 * a native backend. A backend this machine lacks is "unavailable", which the auto mode answers
 * by falling back to the file. java-keyring reports a missing entry as an access failure, so a
 * read that fails is "no record", and a write that fails is "unavailable": a locked or refused
 * keychain then falls back too, rather than losing the sign-in.
 */
public final class KeychainStore implements CredentialStore {
    static final String SERVICE = "lingara-cli";

    private Keyring keyring;

    private Keyring open() throws StoreException {
        if (keyring == null) {
            try {
                keyring = Keyring.create();
            } catch (BackendNotSupportedException | RuntimeException | LinkageError e) {
                throw StoreException.unavailable(String.valueOf(e.getMessage()));
            }
        }
        return keyring;
    }

    @Override
    public ProfileRecord load(String profile) throws StoreException {
        String text;
        try {
            text = open().getPassword(SERVICE, profile);
        } catch (PasswordAccessException e) {
            return null;
        }
        JsonNode record = text == null ? null : Json.parse(text);
        if (text != null && record == null) {
            throw StoreException.broken("the keychain entry for " + profile + " is not a profile record");
        }
        return record == null ? null : ProfileRecord.read(record);
    }

    @Override
    public void save(String profile, ProfileRecord record) throws StoreException {
        try {
            open().setPassword(SERVICE, profile, Json.write(record.toJson()));
        } catch (PasswordAccessException e) {
            throw StoreException.unavailable(e.getMessage());
        }
    }

    @Override
    public void delete(String profile) throws StoreException {
        try {
            open().deletePassword(SERVICE, profile);
        } catch (PasswordAccessException e) {
            // No entry to delete is the same as deleted.
        }
    }
}
