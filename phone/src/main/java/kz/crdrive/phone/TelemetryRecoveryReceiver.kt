package kz.crdrive.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes the durable upload queue after a phone reboot or app replacement. */
class TelemetryRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> RemoteTelemetryUploader.flush(context.applicationContext)
        }
    }
}
