package dev.graft.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved HTTPS credential for authenticating with a git remote.
 *
 * GitHub removed password auth in 2021 — [token] should be a
 * Personal Access Token generated at github.com/settings/tokens
 * with at least the "repo" scope.
 *
 * [token] is encrypted at rest before being written here — see
 * CredentialRepository, which routes it through TokenCrypto
 * (Android Keystore-backed) rather than storing plain text.
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Friendly label, e.g. "GitHub - Willykez" */
    val name: String,

    /** Git username used for HTTPS auth */
    val username: String,

    /** Encrypted Personal Access Token (see TokenCrypto) */
    val token: String,

    val addedAt: Long = System.currentTimeMillis()
)
