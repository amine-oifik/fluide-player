package app.fluide.core.net

/** How the player must react to an HTTP failure. */
enum class HttpFailure {
    /** 401/403: credentials or subscription. Never retry blindly (risk of account ban). */
    ACCESS_DENIED,

    /** 404/410: the resource is gone; for a live segment, reload the playlist. */
    NOT_FOUND,

    /** 429: respect Retry-After and stop prefetching from this source. */
    RATE_LIMITED,

    /** 5xx: transient on the server side; backoff then fail over. */
    SERVER_ERROR,

    /** Other 4xx: request is wrong; do not retry. */
    CLIENT_ERROR,

    /** Timeouts, resets, DNS: transient network trouble. */
    NETWORK;

    val retryable: Boolean
        get() = this == SERVER_ERROR || this == NETWORK || this == RATE_LIMITED || this == NOT_FOUND

    companion object {
        fun fromStatus(code: Int): HttpFailure = when (code) {
            401, 403, 407, 451 -> ACCESS_DENIED
            404, 410 -> NOT_FOUND
            429 -> RATE_LIMITED
            in 500..599 -> SERVER_ERROR
            in 400..499 -> CLIENT_ERROR
            else -> NETWORK
        }
    }
}
