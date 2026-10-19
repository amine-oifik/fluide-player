package app.fluide.player.data.backup

import android.content.Context
import android.net.Uri
import app.fluide.player.Settings
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.secure.UrlCipher

/**
 * Backup and restore of playlists, favorites, category rules and settings in one file the user keeps
 * (to move to another box). (Contract: implemented by the backup work.)
 */
class BackupManager(
    private val context: Context,
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val settings: Settings,
) {
    /** Writes the backup to [uri]; the number of playlists saved. */
    suspend fun export(uri: Uri): Result<Int> = Result.failure(UnsupportedOperationException("not implemented"))

    /** Restores [uri] (playlists are added, existing ones kept); the number of playlists restored. */
    suspend fun import(uri: Uri): Result<Int> = Result.failure(UnsupportedOperationException("not implemented"))
}
