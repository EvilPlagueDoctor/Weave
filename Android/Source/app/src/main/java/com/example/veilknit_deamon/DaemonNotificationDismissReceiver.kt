package com.example.veilknit_deamon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives a user dismissal of the Android 13+ foreground notification.
 *
 * This receiver deliberately does not start the daemon on its own. It only asks an already
 * running daemon service to restore its notification. That distinction prevents a programmatic
 * notification removal during Stop Safely from resurrecting a logged-out service instance.
 */
class DaemonNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DaemonForegroundService.restoreNotificationIfDaemonIsRunning(context)
    }
}
