package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getEncryptedSharedPreferences


class BootBroadcastReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {

            // the intents we are looking for
            val bootActions = arrayOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.LOCKED_BOOT_COMPLETED"
            )
            Log.d(tag, "Intent action is ${intent.action}.")

            // if this is a boot intent
            if (intent.action in bootActions) {

                Log.d(tag, "Boot completed!  Intent action is ${intent.action}. ")

                // verify that the app is enabled
                val prefs = getEncryptedSharedPreferences(context)
                val enabled = prefs.getBoolean("enabled", false)

                if (enabled) {

                    // todo if phone reboots between the 'are you there' check and the final alarm
                    //  check, we may miss it?

                    // since we can't assume that the user initiated the reboot, set the alarm
                    //  based on the last detected activity
                    // assume a periodic alarm here since we don't know what the last alarm stage was
                    doAlertCheck(context, "periodic")

                } else {
                    Log.d(tag, "App is disabled?!")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception in BootBroadcastReceiver", e)
        }
    }
}