package app.weave

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Small persistent breadcrumb/RPC log intended for bugs that disappear when logcat is attached.
 *
 * This file deliberately records metadata only: lifecycle transitions, action names, byte counts,
 * elapsed times and exception classes/messages. It must never receive session tokens, request/
 * response payload bodies, authentication proofs, private profile text or media bytes.
 */
object WeaveDiagnostics {
    private const val FILE_NAME = "weave-breadcrumbs.log"
    private const val MAX_BYTES = 768 * 1024L
    private const val KEEP_CHARS = 520_000
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    @Volatile private var crashHandlerInstalled = false

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    private fun now(): String =
        Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).format(stamp)


    @Synchronized
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val message = throwable.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(240)
                val frames = throwable.stackTrace.take(16).joinToString(" <- ") { frame ->
                    "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                }
                event(
                    app,
                    "FATAL",
                    "thread=${thread.name} exception=${throwable::class.java.name}${if (message.isBlank()) "" else ":$message"} stack=$frames"
                )
                throwable.cause?.let { cause ->
                    val causeMessage = cause.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(180)
                    event(app, "FATAL_CAUSE", "exception=${cause::class.java.name}${if (causeMessage.isBlank()) "" else ":$causeMessage"}")
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
        crashHandlerInstalled = true
    }

    @Synchronized
    fun event(context: Context, category: String, message: String) {
        append(context, "[$category] $message")
    }

    @Synchronized
    fun rpc(
        context: Context,
        action: String,
        requestBytes: Int,
        responseBytes: Int,
        elapsedMs: Long,
        ok: Boolean,
        error: Throwable? = null,
    ) {
        // Successful tiny/fast calls are intentionally omitted so diagnostics cannot become a
        // new source of UI/storage jank. Failures are always kept; large/slow successes are kept
        // because they are useful when investigating Binder transaction pressure.
        if (ok && elapsedMs < 250L && requestBytes < 256 * 1024 && responseBytes < 256 * 1024) return
        val outcome = if (ok) "ok" else "error"
        val detail = error?.let {
            val msg = it.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(220)
            " exception=${it::class.java.simpleName}${if (msg.isBlank()) "" else ":$msg"}"
        }.orEmpty()
        append(
            context,
            "[RPC] action=$action result=$outcome request_bytes=$requestBytes response_bytes=$responseBytes elapsed_ms=$elapsedMs$detail"
        )
    }

    @Synchronized
    private fun append(context: Context, body: String) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.appendText("[${now()}] $body\n")
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(KEEP_CHARS)
                f.writeText(tail.substringAfter('\n', tail))
            }
        }
    }

    @Synchronized
    fun tail(context: Context, maxChars: Int = KEEP_CHARS): String = runCatching {
        val f = file(context)
        if (!f.exists()) "" else f.readText().takeLast(maxChars.coerceAtLeast(2_000))
    }.getOrDefault("")
}
