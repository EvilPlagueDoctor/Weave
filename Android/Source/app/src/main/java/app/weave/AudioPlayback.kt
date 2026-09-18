package app.weave

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun WeaveAudioPlayer(
    title: String,
    contentHash: String,
    recordKey: String,
    media: LocalMediaStore,
    controller: SocialNetworkController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(contentHash, recordKey) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(contentHash, recordKey) { mutableStateOf(false) }
    var loading by remember(contentHash, recordKey) { mutableStateOf(false) }
    var error by remember(contentHash, recordKey) { mutableStateOf<String?>(null) }
    var tempFile by remember(contentHash, recordKey) { mutableStateOf<File?>(null) }

    DisposableEffect(contentHash, recordKey) {
        onDispose {
            runCatching { player?.release() }
            runCatching { tempFile?.delete() }
        }
    }

    fun stopPlayback() {
        runCatching { player?.pause() }
        playing = false
    }

    fun startPlayback() {
        if (loading) return
        val existing = player
        if (existing != null) {
            if (playing) stopPlayback()
            else {
                runCatching { existing.start() }
                .onSuccess { playing = true }
                .onFailure { error = "Couldn't play this audio." }
            }
            return
        }

        loading = true
        error = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                media.audioBytesFor(contentHash) ?: if (recordKey.isNotBlank() && contentHash.isNotBlank()) {
                    controller.downloadMedia(
                        rootRecordKey = recordKey,
                        expectedSha256Hex = contentHash,
                        maxBytes = MAX_SANITIZED_AUDIO_BYTES,
                    )?.also { media.storeVerifiedAudioBytes(contentHash, it) }
                } else null
            }
            if (bytes == null) {
                loading = false
                error = "Couldn't load this audio."
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                File.createTempFile("weave-play-", ".m4a", context.cacheDir).apply { writeBytes(bytes) }
            }
            tempFile = file
            val mp = MediaPlayer()
            player = mp
            mp.setOnPreparedListener {
                loading = false
                runCatching { it.start() }
                    .onSuccess { playing = true }
                    .onFailure { error = "Couldn't play this audio." }
            }
            mp.setOnCompletionListener {
                playing = false
                runCatching { it.seekTo(0) }
            }
            mp.setOnErrorListener { _, _, _ ->
                loading = false
                playing = false
                error = "Couldn't play this audio."
                true
            }
            runCatching {
                mp.setDataSource(file.absolutePath)
                mp.prepareAsync()
            }.onFailure {
                loading = false
                error = "Couldn't play this audio."
            }
        }
    }

    ElevatedCard(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "Audio" }, style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        loading -> "Loading audio…"
                        error != null -> error!!
                        recordKey.isBlank() -> "Stored on this device"
                        else -> "Loads only when you press Play"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            val audioRef = WeaveObjectRef(
                objectId = contentHash,
                recordKey = recordKey,
                type = WeaveObjectType.Audio,
            )
            encodeMediaLink(audioRef)?.let { link ->
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Weave audio", link))
                }) { Text("🔗") }
            }
            Button(onClick = ::startPlayback, enabled = !loading) {
                Text(if (playing) "Pause" else "Play")
            }
        }
    }
}
