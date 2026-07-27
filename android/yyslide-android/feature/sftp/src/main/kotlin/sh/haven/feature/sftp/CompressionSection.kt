package sh.haven.feature.sftp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Compression controls for the Convert dialog. Exposes quality (CRF),
 * encoder preset, output resolution, and audio bitrate — the four knobs
 * that matter when users come to the dialog to shrink a file.
 *
 * State is held here rather than in the dialog so it survives recomposition
 * and configuration changes. [reset] is called when the user picks the
 * `copy` encoder so the downstream CLI preview shows no compression flags.
 */
class CompressionState(
    initialCrf: Int = 23,
    initialPreset: String = "medium",
) {
    /** Encoder quality, 0 = off (let encoder default apply). Range depends on codec. */
    var crf by mutableIntStateOf(initialCrf)

    /** libx264/libx265 speed preset. Slower = smaller output at the same quality. */
    var preset by mutableStateOf(initialPreset)

    /** Target height in pixels, or null to keep the source resolution. */
    var scaleHeight by mutableStateOf<Int?>(null)

    /** Audio bitrate like "192k", or null to use the encoder default. */
    var audioBitrate by mutableStateOf<String?>(null)

    fun applyQualityPreset(crf: Int, preset: String, height: Int?) {
        this.crf = crf
        this.preset = preset
        this.scaleHeight = height
    }

    /** Reset to codec-neutral defaults — used when the user picks `copy`. */
    fun reset() {
        crf = 0
        preset = "medium"
        scaleHeight = null
        audioBitrate = null
    }

    /** Default CRF for a given video encoder, matching ffmpeg's baseline "visually lossless" points. */
    fun defaultCrfFor(videoEncoder: String): Int = when (videoEncoder) {
        "libx264" -> 23
        "libx265" -> 28
        "libvpx-vp9" -> 31
        "libvpx" -> 10
        "mpeg4" -> 0 // no CRF
        else -> 0
    }

    /** Adjust the current crf to the codec's neutral default unless the user has deliberately moved it. */
    fun rebaseForEncoder(videoEncoder: String) {
        crf = defaultCrfFor(videoEncoder)
    }

    companion object {
        val Saver: Saver<CompressionState, *> = listSaver(
            save = {
                listOf(
                    it.crf, it.preset,
                    it.scaleHeight ?: -1,
                    it.audioBitrate ?: "",
                )
            },
            restore = {
                CompressionState(
                    initialCrf = it[0] as Int,
                    initialPreset = it[1] as String,
                ).apply {
                    val h = it[2] as Int
                    if (h > 0) scaleHeight = h
                    val ab = it[3] as String
                    if (ab.isNotEmpty()) audioBitrate = ab
                }
            },
        )
    }
}

/**
 * Compression section UI. Collapsible like [FilterSection]. The caller
 * is responsible for only showing it when a real video encoder is active
 * (i.e. not `copy`); when audio-only, the scale and preset controls hide
 * automatically but audio bitrate stays visible.
 */
@Composable
fun CompressionSection(
    state: CompressionState,
    audioOnly: Boolean,
    videoEncoder: String,
    onChanged: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    // CRF range depends on encoder. x264 is 0..51; x265 0..51; VP9 0..63.
    val crfMax = when (videoEncoder) {
        "libvpx-vp9" -> 63
        "libvpx" -> 63
        else -> 51
    }
    val crfMin = when (videoEncoder) {
        "libvpx-vp9", "libvpx" -> 0
        else -> 0
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Compression",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                summary(state, audioOnly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
            )
        }

        if (expanded) {
            if (!audioOnly) {
                QuickPresetRow(
                    onPick = { crf, preset, height ->
                        state.applyQualityPreset(crf, preset, height)
                        onChanged()
                    },
                )
                Spacer(Modifier.height(8.dp))
                ResolutionDropdown(
                    value = state.scaleHeight,
                    onChange = {
                        state.scaleHeight = it
                        onChanged()
                    },
                )
                Spacer(Modifier.height(8.dp))
                CrfSlider(
                    value = state.crf,
                    min = crfMin,
                    max = crfMax,
                    onChange = {
                        state.crf = it
                        onChanged()
                    },
                )
                Spacer(Modifier.height(8.dp))
                PresetDropdown(
                    value = state.preset,
                    onChange = {
                        state.preset = it
                        onChanged()
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            AudioBitrateDropdown(
                value = state.audioBitrate,
                onChange = {
                    state.audioBitrate = it
                    onChanged()
                },
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
        }
    }
}

private fun summary(state: CompressionState, audioOnly: Boolean): String {
    val parts = buildList {
        if (!audioOnly) {
            state.scaleHeight?.let { add("${it}p") } ?: add("source")
            if (state.crf > 0) add("CRF ${state.crf}")
            add(state.preset)
        }
        state.audioBitrate?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun QuickPresetRow(
    onPick: (crf: Int, preset: String, height: Int?) -> Unit,
) {
    // Each preset nudges the three sliders at once for common shrink goals.
    // Values chosen against libx264 — they remain reasonable defaults for
    // libx265 too, and will simply apply a slightly different visual target
    // if the user switches encoder afterwards.
    val presets = listOf(
        Triple("Share 720p", Triple(26, "fast", 720), "Good-enough for messaging / upload"),
        Triple("Small 480p", Triple(28, "veryfast", 480), "Aggressive size; faster encode"),
        Triple("Archive 1080p", Triple(22, "slow", 1080), "High quality; slower encode"),
        Triple("HQ source", Triple(18, "slow", null), "Visually lossless; largest output"),
    )
    Text(stringResource(R.string.sftp_compression_quick_presets), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        presets.forEach { (label, spec, _) ->
            OutlinedButton(onClick = { onPick(spec.first, spec.second, spec.third) }) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Scale helper — pixel height presets plus an explicit "Same as source"
 * entry. Custom entry is not offered yet; the presets cover the 99% case.
 */
@Composable
private fun ResolutionDropdown(
    value: Int?,
    onChange: (Int?) -> Unit,
) {
    val options = listOf(
        null to "Same as source",
        2160 to "4K (2160p)",
        1440 to "1440p",
        1080 to "1080p",
        720 to "720p",
        480 to "480p",
        360 to "360p",
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: "Custom"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sftp_compression_resolution), style = MaterialTheme.typography.labelMedium)
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (h, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onChange(h); expanded = false },
                )
            }
        }
    }
}

/**
 * CRF slider — lower = bigger + better, higher = smaller + worse. The
 * visible label flips so users see "Smaller" on the right regardless of
 * which encoder they're using.
 */
@Composable
private fun CrfSlider(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.sftp_compression_quality, value), style = MaterialTheme.typography.labelMedium)
            Text(
                "Lower = better quality, larger file",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun PresetDropdown(
    value: String,
    onChange: (String) -> Unit,
) {
    val presets = listOf(
        "ultrafast", "superfast", "veryfast",
        "faster", "fast", "medium", "slow", "slower", "veryslow",
    )
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.sftp_compression_speed), style = MaterialTheme.typography.labelMedium)
                Text(
                    "Slower = smaller file at same quality",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p) },
                    onClick = { onChange(p); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun AudioBitrateDropdown(
    value: String?,
    onChange: (String?) -> Unit,
) {
    val options = listOf<Pair<String?, String>>(
        null to "Use encoder default",
        "320k" to "320 kbps (transparent)",
        "256k" to "256 kbps",
        "192k" to "192 kbps",
        "128k" to "128 kbps",
        "96k" to "96 kbps",
        "64k" to "64 kbps (voice)",
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value ?: "Use encoder default"
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.sftp_compression_audio_bitrate), style = MaterialTheme.typography.labelMedium)
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onChange(v); expanded = false },
                )
            }
        }
    }
}
