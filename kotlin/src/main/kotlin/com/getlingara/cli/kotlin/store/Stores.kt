// Token storage (ADR 16.10.26ad D4): one [CredentialStore] interface with a
// keychain adapter, a file adapter and an in-memory fake, and the one rule
// that picks between them.
//
// `LINGARA_KEYRING=file` uses the file and says nothing; `=keychain` uses the
// keychain and makes an unavailable one `auth_required`; unset tries the
// keychain and falls back to the file, warning once for each profile it
// first writes there. Secrets never go in `config.toml`.
package com.getlingara.cli.kotlin.store

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json

/** One profile's credentials: D4's record, stored whole. */
data class ProfileRecord(
    val developerId: String = "",
    val email: String = "",
    /** The token response's space-separated `scope`, rewritten at each refresh. */
    val scopes: String = "",
    val refreshToken: String = "",
    val accessToken: String = "",
    /** RFC 3339, UTC. */
    val accessExpiresAt: String = "",
) {
    /** D4's members, in the reference kit's order: the keychain's value and the file's record. */
    fun toJson(): ObjectNode = Json.obj(
        "developer_id" to developerId, "email" to email, "scopes" to scopes,
        "refresh_token" to refreshToken, "access_token" to accessToken, "access_expires_at" to accessExpiresAt,
    )

    companion object {
        /** A stored record, read leniently: a missing member is empty. */
        fun read(v: JsonNode): ProfileRecord {
            val text = { key: String -> v.path(key).asText("") }
            return ProfileRecord(text("developer_id"), text("email"), text("scopes"), text("refresh_token"), text("access_token"), text("access_expires_at"))
        }
    }
}

/** No keychain on this machine (a headless Linux box, say). */
class StoreUnavailable(why: String) : Exception(why)

/** A store that is there but could not be read or written. */
class StoreBroken(why: String) : Exception(why)

interface CredentialStore {
    fun load(profile: String): ProfileRecord?

    fun save(profile: String, record: ProfileRecord)

    fun delete(profile: String)
}

enum class Mode {
    AUTO,
    FILE,
    KEYCHAIN,
    ;

    companion object {
        fun fromEnv(value: String?): Mode = when (value) {
            "file" -> FILE
            "keychain" -> KEYCHAIN
            else -> AUTO
        }
    }
}

/** The store a run uses, chosen by [mode]. */
class Stores(val mode: Mode, val keychain: CredentialStore, val file: FileStore) {
    private fun <T> guarded(body: () -> T): T = try {
        body()
    } catch (e: StoreUnavailable) {
        throw Fail.auth("the keychain is unavailable: ${e.message}")
    } catch (e: StoreBroken) {
        throw Fail.failure("the credential store could not be read: ${e.message}")
    }

    fun load(profile: String): ProfileRecord? = guarded {
        when (mode) {
            Mode.FILE -> file.load(profile)
            Mode.KEYCHAIN -> keychain.load(profile)
            Mode.AUTO -> try {
                keychain.load(profile) ?: file.load(profile)
            } catch (_: StoreUnavailable) {
                file.load(profile)
            }
        }
    }

    /** Saves [record]; the warning to print, if this write fell back to the file silently for the first time. */
    fun save(profile: String, record: ProfileRecord): String? = guarded {
        when (mode) {
            Mode.FILE -> null.also { file.save(profile, record) }
            Mode.KEYCHAIN -> null.also { keychain.save(profile, record) }
            Mode.AUTO -> try {
                null.also { keychain.save(profile, record) }
            } catch (_: StoreUnavailable) {
                fallBack(profile, record)
            }
        }
    }

    private fun fallBack(profile: String, record: ProfileRecord): String? {
        val first = !file.has(profile)
        file.save(profile, record)
        return if (first) "storing credentials in ${file.path}; set LINGARA_KEYRING=keychain to require the keychain" else null
    }

    /** Forgets [profile] everywhere it might be. */
    fun delete(profile: String) = guarded {
        if (mode != Mode.FILE) {
            try {
                keychain.delete(profile)
            } catch (_: StoreUnavailable) {
                // No keychain: nothing stored there to forget.
            } catch (e: StoreBroken) {
                if (mode == Mode.KEYCHAIN) throw e
            }
        }
        if (mode != Mode.KEYCHAIN) file.delete(profile)
    }
}
