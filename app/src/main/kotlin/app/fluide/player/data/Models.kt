package app.fluide.player.data

import app.fluide.core.m3u.StreamKind

enum class SourceType { URL, FILE }

data class SourceInfo(
    val id: Long,
    val name: String,
    val type: SourceType,
    val channelCount: Int,
    val programmeCount: Int,
    val updatedAt: Long,
    val epgUpdatedAt: Long,
    val maxConnections: Int,
    val userAgent: String?,
    val lastError: String?,
)

/** One line of the channel list. Built page by page, never for a whole list. */
data class ChannelRow(
    val id: Long,
    val number: Int?,
    val name: String,
    val group: String?,
    val logo: String?,
    val epgKey: String?,
    val nowTitle: String?,
    val nowStartSec: Long,
    val nowStopSec: Long,
    val favorite: Boolean,
)

/** Everything the player needs for one channel, URLs decrypted. Kept in memory only while playing. */
data class PlayableChannel(
    val id: Long,
    val sourceId: Long,
    val number: Int?,
    val name: String,
    val nameNorm: String,
    val group: String?,
    val logo: String?,
    val epgKey: String?,
    val url: String,
    val kind: StreamKind,
    val userAgent: String?,
    val referrer: String?,
    val drmScheme: String?,
    val drmLicenseUrl: String?,
    /** How many simultaneous connections the provider allows (preloading needs more than one). */
    val maxConnections: Int,
)

data class Programme(
    val startSec: Long,
    val stopSec: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/** `name` null: channels without group-title. */
data class GroupInfo(val name: String?, val count: Int)

sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favorites : ChannelFilter
    data class Group(val name: String?) : ChannelFilter
    data class Search(val query: String) : ChannelFilter
}
