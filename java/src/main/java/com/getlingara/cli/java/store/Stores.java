package com.getlingara.cli.java.store;

import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.store.CredentialStore.StoreException;

/**
 * The store a run uses, and the one rule that picks it (16.10.26ad D4): {@code
 * LINGARA_KEYRING=file} uses the file and says nothing; {@code =keychain} uses the keychain and
 * makes an unavailable one {@code auth_required}; unset tries the keychain and falls back to the
 * file, warning once for each profile it first writes there. Secrets never go in {@code
 * config.toml}.
 */
public final class Stores {
    /** Which store {@code LINGARA_KEYRING} asks for. */
    public enum Mode {
        AUTO,
        FILE,
        KEYCHAIN;

        public static Mode fromEnv(String value) {
            if ("file".equals(value)) {
                return FILE;
            }
            return "keychain".equals(value) ? KEYCHAIN : AUTO;
        }
    }

    private final Mode mode;
    private final CredentialStore keychain;
    private final FileStore file;

    public Stores(Mode mode, CredentialStore keychain, FileStore file) {
        this.mode = mode;
        this.keychain = keychain;
        this.file = file;
    }

    public FileStore file() {
        return file;
    }

    private static Fail broken(StoreException e) {
        if (e.isUnavailable()) {
            return Fail.auth("the keychain is unavailable: " + e.getMessage());
        }
        return Fail.failure("the credential store could not be read: " + e.getMessage());
    }

    public ProfileRecord load(String profile) {
        try {
            return switch (mode) {
                case FILE -> file.load(profile);
                case KEYCHAIN -> keychain.load(profile);
                case AUTO -> autoLoad(profile);
            };
        } catch (StoreException e) {
            throw broken(e);
        }
    }

    private ProfileRecord autoLoad(String profile) throws StoreException {
        try {
            ProfileRecord found = keychain.load(profile);
            if (found != null) {
                return found;
            }
        } catch (StoreException e) {
            if (!e.isUnavailable()) {
                throw e;
            }
        }
        return file.load(profile);
    }

    /**
     * Saves {@code record}; the warning to print, if this write fell back to the file silently for
     * the first time, else {@code null}.
     */
    public String save(String profile, ProfileRecord record) {
        try {
            if (mode == Mode.FILE) {
                file.save(profile, record);
                return null;
            }
            try {
                keychain.save(profile, record);
                return null;
            } catch (StoreException e) {
                if (mode == Mode.KEYCHAIN || !e.isUnavailable()) {
                    throw e;
                }
                return fallBack(profile, record);
            }
        } catch (StoreException e) {
            throw broken(e);
        }
    }

    private String fallBack(String profile, ProfileRecord record) throws StoreException {
        boolean first = !file.has(profile);
        file.save(profile, record);
        if (!first) {
            return null;
        }
        return "storing credentials in " + file.path() + "; set LINGARA_KEYRING=keychain to require the keychain";
    }

    /** Forgets {@code profile} everywhere it might be. */
    public void delete(String profile) {
        if (mode != Mode.FILE) {
            try {
                keychain.delete(profile);
            } catch (StoreException e) {
                if (mode == Mode.KEYCHAIN && !e.isUnavailable()) {
                    throw broken(e);
                }
            }
        }
        if (mode == Mode.KEYCHAIN) {
            return;
        }
        try {
            file.delete(profile);
        } catch (StoreException e) {
            throw broken(e);
        }
    }
}
