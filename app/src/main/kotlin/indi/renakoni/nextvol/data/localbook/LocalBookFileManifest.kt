package indi.renakoni.nextvol.data.localbook

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import indi.renakoni.nextvol.data.book.SourceBookId
import kotlinx.serialization.Serializable
import java.nio.charset.Charset
import java.security.MessageDigest

/** Portable evidence of the original and its parsed chapter mapping; never contains the original. */
@Serializable
@Entity(tableName = "local_book_file_manifest")
data class LocalBookFileManifest(
    @PrimaryKey val bookId: String,
    val format: String,
    val originalName: String,
    val originalDigest: String,
    val mappingDigest: String,
    val encoding: String? = null,
    val rule: String? = null,
) {
    fun validate() {
        require(LocalBookStore.isLocal(SourceBookId.fromStorageKey(bookId)))
        require(format in LocalBookFormat.entries.map { it.name })
        require(originalDigest.matches(Regex("[0-9a-f]{64}")) && mappingDigest.matches(Regex("[0-9a-f]{64}")))
        require(originalName.length <= 1024 && (rule == null || rule.length <= 1024))
        require(encoding == null || Charset.isSupported(encoding))
    }
}

@Dao
interface LocalBookFileManifestDao {
    @Query("SELECT * FROM local_book_file_manifest WHERE bookId = :bookId")
    suspend fun get(bookId: String): LocalBookFileManifest?
    @Query("SELECT * FROM local_book_file_manifest")
    suspend fun all(): List<LocalBookFileManifest>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(manifest: LocalBookFileManifest)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restore(manifest: LocalBookFileManifest)
    @Query("DELETE FROM local_book_file_manifest WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
    @Query("DELETE FROM local_book_file_manifest WHERE bookId NOT IN (SELECT bookId FROM imported_book)")
    suspend fun clearUnowned()
}

internal fun MessageDigest.finishDigest(): String = digest().joinToString("") { "%02x".format(it) }
