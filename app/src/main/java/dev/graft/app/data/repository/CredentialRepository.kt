package dev.graft.app.data.repository

import dev.graft.app.data.db.dao.CredentialDao
import dev.graft.app.data.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Plain-text view of a credential, for use in the UI layer and by GitEngine.
 * The token here is decrypted and should only ever live in memory.
 */
data class DecryptedCredential(
    val id: Long,
    val name: String,
    val username: String,
    val token: String
)

/**
 * Wraps CredentialDao so the rest of the app never has to think about
 * encryption: tokens are encrypted right before being written to Room,
 * and decrypted right after being read back out.
 */
class CredentialRepository(private val dao: CredentialDao) {

    val allCredentials: Flow<List<DecryptedCredential>> =
        dao.getAllFlow().map { list -> list.map { it.toDecrypted() } }

    suspend fun getAll(): List<DecryptedCredential> =
        dao.getAll().map { it.toDecrypted() }

    suspend fun getById(id: Long): DecryptedCredential? =
        dao.getById(id)?.toDecrypted()

    /** Returns the new credential's row ID. */
    suspend fun addCredential(name: String, username: String, plainToken: String): Long {
        val entity = CredentialEntity(
            name = name,
            username = username,
            token = TokenCrypto.encrypt(plainToken)
        )
        return dao.insert(entity)
    }

    suspend fun updateCredential(id: Long, name: String, username: String, plainToken: String) {
        dao.update(
            CredentialEntity(
                id = id,
                name = name,
                username = username,
                token = TokenCrypto.encrypt(plainToken)
            )
        )
    }

    suspend fun deleteCredential(credential: DecryptedCredential) {
        dao.delete(
            CredentialEntity(
                id = credential.id,
                name = credential.name,
                username = credential.username,
                token = "" // not needed to delete by primary key, Room @Delete matches on id
            )
        )
    }

    private fun CredentialEntity.toDecrypted(): DecryptedCredential =
        DecryptedCredential(
            id = id,
            name = name,
            username = username,
            token = try {
                TokenCrypto.decrypt(token)
            } catch (e: Exception) {
                "" // decryption failure (e.g. keystore key lost) -> treat as empty, forces re-entry
            }
        )
}
