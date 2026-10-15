package app.fluide.player.device

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import app.fluide.core.device.DeviceBudget
import app.fluide.core.device.DeviceClassifier

data class DeviceProfile(
    val budget: DeviceBudget,
    val isTv: Boolean,
    val totalRamMb: Long,
    /** Concurrent instances of the hardware H.264 decoder (1 on many cheap boxes). */
    val hardwareAvcInstances: Int,
    val tunnelingSupported: Boolean,
    val description: String,
)

/**
 * Measures the device once (RAM, decoders, form factor) and maps it to a resource class from the core
 * module. Takes 50-200 ms the first time the codec list is read: call it off the UI thread.
 */
object DeviceProfiler {

    fun profile(context: Context): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val decoders = avcDecoders()
        val budget = DeviceClassifier.classify(memory.totalMem, activityManager.isLowRamDevice, decoders.instances)
        val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val ramMb = memory.totalMem / (1024 * 1024)
        return DeviceProfile(
            budget = budget,
            isTv = isTv,
            totalRamMb = ramMb,
            hardwareAvcInstances = decoders.instances,
            tunnelingSupported = budget.tunnelingCandidate && decoders.tunneling,
            description = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
                "$ramMb Mo · ${budget.tier} · AVC x${decoders.instances}",
        )
    }

    private class AvcDecoders(val instances: Int, val tunneling: Boolean)

    private fun avcDecoders(): AvcDecoders {
        var instances = 0
        var tunneling = false
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull()
            ?: return AvcDecoders(1, false)
        for (info in infos) {
            if (info.isEncoder || isSoftware(info)) continue
            val type = info.supportedTypes.firstOrNull { it.equals("video/avc", ignoreCase = true) } ?: continue
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            instances = maxOf(instances, caps.maxSupportedInstances)
            if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) tunneling = true
        }
        return AvcDecoders(if (instances > 0) instances else 1, tunneling)
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw.")
    }
}
