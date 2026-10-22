// The OS keychain (16.10.26ad D4): service `lingara-cli`, account the
// profile name, value the profile record as one JSON document. java-keyring
// speaks to macOS Keychain, Windows Credential Manager and the Secret
// Service (ADR 16.10.26af D2). It is loaded only when a run reaches it, so
// `LINGARA_KEYRING=file` never starts D-Bus or the Security framework.
package com.getlingara.cli.kotlin.store

import com.getlingara.cli.kotlin.core.Json
import com.github.javakeyring.BackendNotSupportedException
import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException

class Keychain : CredentialStore {
    private val keyring: Keyring by lazy {
        try {
            Keyring.create()
        } catch (e: BackendNotSupportedException) {
            throw StoreUnavailable(e.message ?: "no keychain backend")
        } catch (e: RuntimeException) {
            throw StoreUnavailable(e.message ?: "no keychain backend")
        }
    }

    override fun load(profile: String): ProfileRecord? {
        val text = try {
            keyring.getPassword(SERVICE, profile)
        } catch (_: PasswordAccessException) {
            // java-keyring answers a missing entry and a refused read alike; both are "nothing stored here".
            return null
        } catch (e: RuntimeException) {
            throw StoreUnavailable(e.message ?: "the keychain could not be read")
        }
        return text?.let { Json.parse(it) }?.let(ProfileRecord::read)
    }

    override fun save(profile: String, record: ProfileRecord) {
        val text = Json.write(record.toJson())
        try {
            keyring.setPassword(SERVICE, profile, text)
        } catch (e: PasswordAccessException) {
            throw StoreUnavailable(e.message ?: "the keychain refused the write")
        } catch (e: RuntimeException) {
            throw StoreUnavailable(e.message ?: "the keychain refused the write")
        }
    }

    override fun delete(profile: String) {
        try {
            keyring.deletePassword(SERVICE, profile)
        } catch (_: PasswordAccessException) {
            // No entry: nothing to forget.
        } catch (e: RuntimeException) {
            throw StoreUnavailable(e.message ?: "the keychain could not be reached")
        }
    }

    companion object {
        const val SERVICE = "lingara-cli"
    }
}
