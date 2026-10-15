package app.fluide.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.fluide.core.net.Backoff
import app.fluide.core.net.HttpFailure

/**
 * Load retries tuned for IPTV:
 *
 * - First retries are fast (300 ms, 600 ms, 1.2 s... with jitter) instead of Media3's 1 s steps: most
 *   IPTV load errors are one lost segment or one reset connection, and a fast retry is invisible.
 * - 401/403 are never retried in a loop: the credentials or the connection limit will not change in a
 *   second, and hammering can get the subscriber's account blocked. The controller does one fresh
 *   restart (new tokens) and then shows the error.
 * - 429 waits longer, as the server asked.
 * - Live TS over HTTP gets more attempts: servers drop those connections routinely.
 */
@OptIn(UnstableApi::class)
class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    private val backoff = Backoff(baseMs = 300, maxMs = 3_000, jitterMs = 200)

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = responseCode(loadErrorInfo.exception)
        if (status != null) {
            when (HttpFailure.fromStatus(status)) {
                HttpFailure.ACCESS_DENIED -> return C.TIME_UNSET
                HttpFailure.RATE_LIMITED -> return 5_000 + backoff.delayFor(loadErrorInfo.errorCount)
                else -> Unit
            }
        }
        val standard = super.getRetryDelayMsFor(loadErrorInfo)
        return if (standard == C.TIME_UNSET) C.TIME_UNSET else backoff.delayFor(loadErrorInfo.errorCount)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        if (dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE) 8 else 4

    companion object {
        /** HTTP status of a load error, looking through wrapped causes. */
        fun responseCode(error: Throwable?): Int? {
            var current = error
            var depth = 0
            while (current != null && depth++ < 8) {
                if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
                current = current.cause
            }
            return null
        }
    }
}
