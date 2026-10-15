package app.fluide.core.xmltv

data class XmltvChannel(
    val id: String,
    val displayName: String?,
    val iconUrl: String?,
)

data class XmltvProgramme(
    val channelId: String,
    /** Epoch seconds, UTC. */
    val startEpochSec: Long,
    /** Epoch seconds, UTC. */
    val stopEpochSec: Long,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val category: String?,
)

class XmltvParseResult(
    val channels: Int,
    val programmes: Int,
    /** Programmes ignored because of their channel, their time window or invalid times. */
    val skippedProgrammes: Int,
)
