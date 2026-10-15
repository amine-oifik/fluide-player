package app.fluide.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class FluideApp : Application(), ImageLoaderFactory {

    /** Built on first use. [onCreate] warms it on a background thread so the UI thread rarely waits. */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        Thread({ graph.warmUp() }, "fluide-warmup").start()
    }

    /** Channel logos: bounded memory cache sized for the device, disk cache, no crossfade (cheaper on TV boxes). */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.http.imageClient }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(graph.device.budget.imageMemoryCacheBytes.toInt())
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("logos"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        // Logos almost never change and IPTV servers often send "no-cache": keep them anyway.
        .respectCacheHeaders(false)
        .build()
}
