package app.weave

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun LinkedMediaScreen(
    type: WeaveObjectType,
    recordKey: String,
    sha256: String,
    media: LocalMediaStore,
    controller: SocialNetworkController,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("←") }
                Text(
                    if (type == WeaveObjectType.Audio) "Audio" else "Image",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        when (type) {
            WeaveObjectType.Audio -> {
                WeaveAudioPlayer(
                    title = "Linked audio",
                    contentHash = sha256,
                    recordKey = recordKey,
                    media = media,
                    controller = controller,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            WeaveObjectType.Image -> LinkedImageBody(recordKey, sha256, media, controller)
            else -> Text(
                "This media link type is not supported.",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun LinkedImageBody(
    recordKey: String,
    sha256: String,
    media: LocalMediaStore,
    controller: SocialNetworkController,
) {
    val scope = rememberCoroutineScope()
    var revision by remember(recordKey, sha256) { mutableIntStateOf(0) }
    var loading by remember(recordKey, sha256) { mutableStateOf(false) }
    var requested by remember(recordKey, sha256) { mutableStateOf(false) }
    var error by remember(recordKey, sha256) { mutableStateOf<String?>(null) }
    val bitmap = remember(revision, sha256) { media.bitmapFor(sha256) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (bitmap != null) {
            FilteredImage(
                contentId = "linked-image:$sha256",
                bitmap = bitmap,
                modifier = Modifier.fillMaxWidth(),
            ) { gateModifier ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Linked image",
                    modifier = gateModifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        } else if (!requested) {
            Button(onClick = {
                requested = true
                loading = true
                scope.launch {
                    val bytes = controller.downloadMedia(
                        rootRecordKey = recordKey,
                        expectedSha256Hex = sha256,
                        maxBytes = MAX_REMOTE_IMAGE_BYTES,
                    )
                    if (bytes == null || !media.storeVerifiedBytes(sha256, bytes)) {
                        error = "Couldn't load this image."
                    } else {
                        revision++
                    }
                    loading = false
                }
            }) {
                Text("Load image")
            }
            Text(
                "The full image is fetched only when you ask for it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Loading image…")
            }
        } else {
            Text(
                error ?: "Image unavailable.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
