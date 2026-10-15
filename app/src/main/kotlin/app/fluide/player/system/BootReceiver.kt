package app.fluide.player.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fluide.player.MainActivity

/**
 * "Lancer au démarrage de l'appareil": opens the app when the box has booted. Android 10+ only allows
 * it on devices that let apps start from the background (most TV boxes); elsewhere it does nothing.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("start_on_boot", false)
        if (!enabled) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
