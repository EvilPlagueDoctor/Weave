package com.example.veilknit_deamon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.weave.MainActivity
import app.weave.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class DaemonForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startedNative = AtomicBoolean(false)
    private val gracefulStopRequested = AtomicBoolean(false)
    private var pollJob: Job? = null
    private var stopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifiedAppRequests = ConcurrentHashMap.newKeySet<Long>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DaemonStateStore.markServiceRunning()
        acquireWakeLock()
        pollJob = serviceScope.launch { pollNativeBridge() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gracefulStopRequested.set(false)
                setNotificationPersistenceEnabled(true)
                promoteToForeground("Starting…")
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val signup = intent.getBooleanExtra(EXTRA_SIGNUP, false)
                DaemonApiRuntime.setUsername(username)
                startNativeDaemon(username, password, signup)
            }

            ACTION_COMMAND -> {
                val commands = intent.getStringArrayListExtra(EXTRA_COMMANDS).orEmpty()
                commands.forEach { command ->
                    if (!NativeDaemonBridge.sendCommand(command)) {
                        DaemonStateStore.setError("The native daemon is not accepting commands.")
                    }
                }
            }

            ACTION_APPROVE_APP -> handleAppAuthorization(intent, approve = true)
            ACTION_REJECT_APP -> handleAppAuthorization(intent, approve = false)

            ACTION_RESTORE_NOTIFICATION -> restorePersistentNotification()

            ACTION_STOP -> requestGracefulStop(
                source = intent.getStringExtra(EXTRA_STOP_SOURCE) ?: STOP_SOURCE_UNKNOWN,
            )
            else -> {
                val state = DaemonStateStore.state.value
                if (notificationPersistenceEnabled() && state.serviceRunning) {
                    promoteToForeground(state.status)
                } else {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The daemon remains user-visible through its foreground notification.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // onDestroy() can be called by Android for reasons other than our own Stop action.
        // If Rust is still alive, always send the same out-of-band stop request before the
        // service releases its wake lock.  nativeRequestStop() is deliberately idempotent.
        if (NativeDaemonBridge.isRunning()) {
            NativeDaemonBridge.requestStop()
        }
        setNotificationPersistenceEnabled(false)
        stopJob?.cancel()
        pollJob?.cancel()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notifiedAppRequests.forEach { requestId ->
            notificationManager.cancel(appRequestNotificationId(requestId))
        }
        notifiedAppRequests.clear()
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        serviceScope.cancel()
        DaemonApiRuntime.clearUsername()
        DaemonStateStore.markStopped()
        super.onDestroy()
    }

    private fun startNativeDaemon(username: String, password: String, signup: Boolean) {
        if (!startedNative.compareAndSet(false, true)) return

        if (username.isBlank() || password.isEmpty()) {
            DaemonStateStore.setError("Username and password are required.")
            updateNotification("Could not start: missing credentials")
            setNotificationPersistenceEnabled(false)
            stopSelf()
            return
        }

        serviceScope.launch {
            DaemonStateStore.setStatus(if (signup) "Creating account…" else "Logging in…")
            updateNotification(DaemonStateStore.state.value.status)

	    val started = NativeDaemonBridge.start(
	        context = applicationContext,
	        dataDirectory = filesDir.absolutePath,
	        signup = signup,
	        username = username,
	        password = password,
	    )
            if (!started) {
                val detail = NativeDaemonBridge.loadError
                    ?: "The Rust daemon refused to start. Check the native build output."
                DaemonStateStore.setError(detail)
                updateNotification("Native daemon failed to start")
                delay(2_000)
                setNotificationPersistenceEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun pollNativeBridge() {
        var lastNotificationText = ""
        var observedRunning = false
        var stoppedAfterStartTicks = 0
        var appRequestPollTicks = 0

        while (serviceScope.isActive) {
            val lines = NativeDaemonBridge.drainLogs()
            if (lines.isNotEmpty()) {
                DaemonStateStore.appendLogs(lines)
            }

            val running = NativeDaemonBridge.isRunning()
            if (running) {
                observedRunning = true
                stoppedAfterStartTicks = 0
            } else if (startedNative.get()) {
                stoppedAfterStartTicks += 1
            }
            DaemonStateStore.markNativeRunning(running)

            val state = DaemonStateStore.state.value
            if (!gracefulStopRequested.get() && state.ready) {
                appRequestPollTicks += 1
                if (appRequestPollTicks >= 12) { // roughly every 3 seconds at the 250 ms bridge poll rate
                    NativeDaemonBridge.sendCommand("app-pending")
                    appRequestPollTicks = 0
                }
            } else {
                appRequestPollTicks = 0
            }
            if (!gracefulStopRequested.get()) {
                syncApplicationRequestNotifications(state.pendingAppRequests)
                val notificationText = when {
                    state.ready && state.mainDhtKey.isNotBlank() ->
                        "Running • ${state.mainDhtKey.take(18)}…"
                    else -> state.status
                }
                if (notificationText != lastNotificationText && state.serviceRunning) {
                    updateNotification(notificationText)
                    lastNotificationText = notificationText
                }
            }

            if (
                !gracefulStopRequested.get() &&
                (observedRunning || stoppedAfterStartTicks >= 4) &&
                !running &&
                state.serviceRunning
            ) {
                // Unexpected/native-initiated exit.  A requested graceful stop owns its own
                // final drain + service teardown below so these two paths cannot race.
                setNotificationPersistenceEnabled(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                break
            }
            delay(250)
        }
    }

    private fun handleAppAuthorization(intent: Intent, approve: Boolean) {
        val requestId = intent.getLongExtra(EXTRA_APP_REQUEST_ID, -1L)
        if (requestId <= 0L) return
        val command = if (approve) {
            "app-approve $requestId"
        } else {
            "app-reject $requestId rejected by the local user"
        }
        if (NativeDaemonBridge.sendCommand(command)) {
            NativeDaemonBridge.sendCommand("app-pending")
            getSystemService(NotificationManager::class.java)
                .cancel(appRequestNotificationId(requestId))
            notifiedAppRequests.remove(requestId)
        } else {
            DaemonStateStore.setError("The native daemon is not accepting application approval commands.")
        }
    }

    private fun syncApplicationRequestNotifications(requests: List<PendingAppRequestUi>) {
        if (!DaemonStateStore.state.value.ready) return
        val manager = getSystemService(NotificationManager::class.java)
        // Weave is compiled into this same APK and self-authorizes through the embedded bridge.
        // Only genuinely external application requests should ever interrupt the user.
        val visibleRequests = requests.filterNot { it.appId == "weave.v1" }
        val activeIds = visibleRequests.map { it.requestId }.toSet()

        val stale = notifiedAppRequests.filter { it !in activeIds }
        stale.forEach { requestId ->
            manager.cancel(appRequestNotificationId(requestId))
            notifiedAppRequests.remove(requestId)
        }

        visibleRequests.forEach { request ->
            if (!notifiedAppRequests.add(request.requestId)) return@forEach
            manager.notify(
                appRequestNotificationId(request.requestId),
                buildAppRequestNotification(request),
            )
        }
    }

    private fun buildAppRequestNotification(request: PendingAppRequestUi): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            10 + appRequestNotificationId(request.requestId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val approveIntent = Intent(this, DaemonForegroundService::class.java).apply {
            action = ACTION_APPROVE_APP
            putExtra(EXTRA_APP_REQUEST_ID, request.requestId)
        }
        val rejectIntent = Intent(this, DaemonForegroundService::class.java).apply {
            action = ACTION_REJECT_APP
            putExtra(EXTRA_APP_REQUEST_ID, request.requestId)
        }
        val approvePendingIntent = PendingIntent.getService(
            this,
            20 + appRequestNotificationId(request.requestId),
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rejectPendingIntent = PendingIntent.getService(
            this,
            30 + appRequestNotificationId(request.requestId),
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val appName = request.displayName.ifBlank { request.appId }
        return NotificationCompat.Builder(this, REQUEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VeilKnit application request")
            .setContentText("$appName wants to connect to your VeilKnit account.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$appName (${request.appId}) wants permission to use the local VeilKnit daemon.",
                ),
            )
            .setContentIntent(openPendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_notification, "Allow", approvePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Refuse", rejectPendingIntent)
            .build()
    }

    private fun appRequestNotificationId(requestId: Long): Int =
        APP_REQUEST_NOTIFICATION_BASE + (requestId % 10_000L).toInt()

    /**
     * Android 13+ lets users swipe away ordinary foreground-service notifications even when
     * they are marked ongoing.  The VeilKnit daemon deliberately keeps a visible Stop Safely
     * control for as long as the daemon is alive, so restore the notification if Android reports
     * that the user dismissed it.  A genuine graceful stop sets gracefulStopRequested first,
     * therefore its notification removal is never resurrected here.
     */
    private fun restorePersistentNotification() {
        if (gracefulStopRequested.get() || !notificationPersistenceEnabled()) return

        serviceScope.launch {
            // Let SystemUI finish the dismissal before posting the same foreground notification
            // again. The receiver only routes here while this daemon run is still active.
            delay(150)
            val state = DaemonStateStore.state.value
            if (
                !gracefulStopRequested.get() &&
                notificationPersistenceEnabled() &&
                state.serviceRunning
            ) {
                promoteToForeground(state.status)
            }
        }
    }

    private fun notificationPersistenceEnabled(): Boolean =
        getSharedPreferences(PERSISTENCE_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_NOTIFICATION, false)

    private fun setNotificationPersistenceEnabled(enabled: Boolean) {
        // Commit synchronously: this bit is the barrier that prevents a notification-removal
        // callback from racing the Stop Safely path and reviving the service.
        getSharedPreferences(PERSISTENCE_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_NOTIFICATION, enabled)
            .commit()
    }

    private fun requestGracefulStop(source: String) {
        if (!gracefulStopRequested.compareAndSet(false, true)) {
            return
        }

        // Disable notification restoration before touching the posted notification. On some
        // Android/OEM builds, removing an ongoing notification invokes its delete intent.
        // This persisted barrier makes that callback a no-op instead of a service restart.
        setNotificationPersistenceEnabled(false)

        DaemonStateStore.setStatus("Stopping safely…")
        DaemonStateStore.appendLogs(
            listOf("[android-host] Graceful stop requested from $source."),
        )
        updateNotification("Saving state and stopping…")

        val notificationManager = getSystemService(NotificationManager::class.java)
        notifiedAppRequests.forEach { requestId ->
            notificationManager.cancel(appRequestNotificationId(requestId))
        }
        notifiedAppRequests.clear()

        var accepted = NativeDaemonBridge.requestStop()
        if (!accepted && NativeDaemonBridge.isRunning()) {
            DaemonStateStore.appendLogs(
                listOf("[android-host] Native stop request was not accepted immediately; retrying."),
            )
        }

        stopJob = serviceScope.launch {
            // Keep the foreground service + wake lock alive until Rust has completely returned
            // from Lifecycle.  If the command channel was momentarily unavailable, retry rather
            // than tearing down the Android host underneath a still-running native daemon.
            while (NativeDaemonBridge.isRunning()) {
                if (!accepted) {
                    accepted = NativeDaemonBridge.requestStop()
                }
                delay(250)
            }

            // RUNNING becomes false only after the native thread has published its final line.
            // Drain once more before removing the notification so a clean "Daemon stopped" or
            // any real error is reflected in the UI state instead of being lost with the service.
            repeat(3) {
                val finalLines = NativeDaemonBridge.drainLogs()
                if (finalLines.isNotEmpty()) {
                    DaemonStateStore.appendLogs(finalLines)
                }
                delay(50)
            }

            DaemonStateStore.markStopped()

            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopSelf()
        }
    }

    private fun promoteToForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, DaemonForegroundService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_STOP_SOURCE, STOP_SOURCE_NOTIFICATION)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissedIntent = Intent(this, DaemonNotificationDismissReceiver::class.java)
        val dismissedPendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            dismissedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VeilKnit networking")
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(dismissedPendingIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_notification, "Open", openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop safely", stopPendingIntent)
            .build()

        // setOngoing(true) already supplies FLAG_ONGOING_EVENT. FLAG_NO_CLEAR additionally keeps
        // older Android versions and "Clear all" from removing the daemon's safety control.
        // Android 13+ may still allow an individual swipe. The delete broadcast only routes
        // back to this already-running service while restoration is explicitly enabled.
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VeilKnit networking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent VeilKnit networking for Weave"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val requestChannel = NotificationChannel(
            REQUEST_CHANNEL_ID,
            "VeilKnit app requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Permission requests from apps that want to use your VeilKnit account"
            setShowBadge(true)
        }
        manager.createNotificationChannel(requestChannel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:veilknit-daemon",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    companion object {
        private const val CHANNEL_ID = "veilknit_daemon"
        private const val REQUEST_CHANNEL_ID = "veilknit_app_requests_v2"
        private const val NOTIFICATION_ID = 2207
        private const val ACTION_START = "com.example.veilknit_deamon.START"
        private const val ACTION_COMMAND = "com.example.veilknit_deamon.COMMAND"
        private const val ACTION_STOP = "com.example.veilknit_deamon.STOP"
        private const val ACTION_RESTORE_NOTIFICATION =
            "com.example.veilknit_deamon.RESTORE_NOTIFICATION"
        private const val ACTION_APPROVE_APP = "com.example.veilknit_deamon.APPROVE_APP"
        private const val ACTION_REJECT_APP = "com.example.veilknit_deamon.REJECT_APP"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_SIGNUP = "signup"
        private const val EXTRA_COMMANDS = "commands"
        private const val EXTRA_STOP_SOURCE = "stop_source"
        private const val EXTRA_APP_REQUEST_ID = "app_request_id"
        private const val STOP_SOURCE_NOTIFICATION = "notification"
        private const val STOP_SOURCE_APP_UI = "app-ui"
        private const val STOP_SOURCE_UNKNOWN = "unknown"
        private const val APP_REQUEST_NOTIFICATION_BASE = 30_000
        private const val PERSISTENCE_PREFS = "daemon_notification_persistence"
        private const val KEY_RESTORE_NOTIFICATION = "restore_while_running"

        /** Called by the explicit dismissal receiver. Never starts a stopped daemon. */
        fun restoreNotificationIfDaemonIsRunning(context: Context) {
            val shouldRestore = context
                .getSharedPreferences(PERSISTENCE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_RESTORE_NOTIFICATION, false)
            if (!shouldRestore || !DaemonStateStore.state.value.serviceRunning) return

            val intent = Intent(context, DaemonForegroundService::class.java).apply {
                action = ACTION_RESTORE_NOTIFICATION
            }
            // The foreground service already exists here; this is only an in-process nudge to
            // repost its user-visible safety control after an Android 13+ swipe.
            runCatching { context.startService(intent) }
        }

        fun start(context: Context, username: String, password: String, signup: Boolean) {
            val intent = Intent(context, DaemonForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
                putExtra(EXTRA_SIGNUP, signup)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendCommands(context: Context, commands: List<String>) {
            if (commands.isEmpty()) return
            val intent = Intent(context, DaemonForegroundService::class.java).apply {
                action = ACTION_COMMAND
                putStringArrayListExtra(EXTRA_COMMANDS, ArrayList(commands))
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DaemonForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_SOURCE, STOP_SOURCE_APP_UI)
            }
            context.startService(intent)
        }
    }
}
