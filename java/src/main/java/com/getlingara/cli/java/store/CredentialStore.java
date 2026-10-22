package com.getlingara.cli.java.store;

/**
 * Token storage (ADR 16.10.26ad D4): one interface with a keychain adapter, a file adapter and,
 * in the tests, an in-memory fake.
 */
public interface CredentialStore {
    /** The profile's record, or {@code null} when it has none. */
    ProfileRecord load(String profile) throws StoreException;

    void save(String profile, ProfileRecord record) throws StoreException;

    void delete(String profile) throws StoreException;

    /** Why a store could not answer: no keychain on this machine, or a broken one. */
    final class StoreException extends Exception {
        private static final long serialVersionUID = 1L;

        private final boolean unavailable;

        private StoreException(boolean unavailable, String why) {
            super(why, null, false, false);
            this.unavailable = unavailable;
        }

        /** No keychain here (a headless Linux box, say): the fallback's cue. */
        public static StoreException unavailable(String why) {
            return new StoreException(true, why);
        }

        public static StoreException broken(String why) {
            return new StoreException(false, why);
        }

        public boolean isUnavailable() {
            return unavailable;
        }
    }
}
