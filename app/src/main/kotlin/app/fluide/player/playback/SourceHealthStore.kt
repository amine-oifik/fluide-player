package app.fluide.player.playback

import android.content.Context
import app.fluide.core.playback.SourceHealthTracker
import app.fluide.core.playback.StabilityTier
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists what the app learned about each server (core SourceHealthTracker), so a hostile server is
 * played with a safe profile from the first second after a restart instead of freezing again first.
 */
class SourceHealthStore(context: Context) {

    private val prefs = context.getSharedPreferences("source_health", Context.MODE_PRIVATE)

    val tracker: SourceHealthTracker by lazy { SourceHealthTracker().also(::restore) }

    fun save() {
        val array = JSONArray()
        for (snapshot in tracker.snapshot()) {
            array.put(
                JSONObject()
                    .put("k", snapshot.key)
                    .put("s", snapshot.score)
                    .put("t", snapshot.tier.name)
                    .put("u", snapshot.updatedAtMs),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun restore(target: SourceHealthTracker) {
        val json = prefs.getString(KEY, null) ?: return
        val snapshots = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SourceHealthTracker.Snapshot(
                    key = o.getString("k"),
                    score = o.getDouble("s"),
                    tier = StabilityTier.valueOf(o.getString("t")),
                    updatedAtMs = o.getLong("u"),
                )
            }
        }.getOrNull() ?: return
        target.restore(snapshots)
    }

    private companion object {
        const val KEY = "snapshots"
    }
}
