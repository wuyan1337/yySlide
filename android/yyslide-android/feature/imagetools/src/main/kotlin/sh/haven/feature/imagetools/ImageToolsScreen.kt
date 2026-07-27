package sh.haven.feature.imagetools

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class Tool(val labelRes: Int, val icon: ImageVector) {
    VIEW(R.string.imagetools_view, Icons.Filled.ZoomIn),
    CROP(R.string.imagetools_crop, Icons.Filled.Crop),
    ROTATE(R.string.imagetools_rotate, Icons.Filled.RotateRight),
    PERSPECTIVE(R.string.imagetools_perspective, Icons.Filled.GridOn),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolsScreen(
    state: ImageToolState,
    saving: Boolean = false,
    onApplyPerspective: (corners: List<Offset>, width: Int, height: Int) -> Unit,
    onApplyCrop: (left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int) -> Unit,
    onApplyRotate: (degrees: Float, width: Int, height: Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var tool by rememberSaveable { mutableStateOf(Tool.VIEW) }

    var perspCorners by remember {
        mutableStateOf(
            listOf(
                Offset(0.05f, 0.05f), Offset(0.95f, 0.05f),
                Offset(0.05f, 0.95f), Offset(0.95f, 0.95f),
            )
        )
    }
    var cropCorners by remember {
        mutableStateOf(
            listOf(
                Offset(0.1f, 0.1f), Offset(0.9f, 0.1f),
                Offset(0.1f, 0.9f), Offset(0.9f, 0.9f),
            )
        )
    }
    var rotateDegrees by remember { mutableFloatStateOf(0f) }
    var showOriginal by remember { mutableStateOf(false) }

    fun resetCurrentTool() {
        when (tool) {
            Tool.PERSPECTIVE -> perspCorners = listOf(
                Offset(0.05f, 0.05f), Offset(0.95f, 0.05f),
                Offset(0.05f, 0.95f), Offset(0.95f, 0.95f),
            )
            Tool.CROP -> cropCorners = listOf(
                Offset(0.1f, 0.1f), Offset(0.9f, 0.1f),
                Offset(0.1f, 0.9f), Offset(0.9f, 0.9f),
            )
            Tool.ROTATE -> rotateDegrees = 0f
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (state) {
                        is ImageToolState.Loaded -> state.fileName
                        is ImageToolState.Preview -> state.fileName
                        is ImageToolState.Loading -> stringResource(R.string.imagetools_loading)
                        is ImageToolState.Processing -> state.label
                        else -> stringResource(R.string.imagetools_title)
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    when (state) {
                        is ImageToolState.Loaded -> if (tool != Tool.VIEW) {
                            IconButton(onClick = { resetCurrentTool() }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.imagetools_reset))
                            }
                            IconButton(onClick = {
                                when (tool) {
                                    Tool.PERSPECTIVE -> onApplyPerspective(perspCorners, state.width, state.height)
                                    Tool.CROP -> {
                                        val xs = cropCorners.map { it.x }
                                        val ys = cropCorners.map { it.y }
                                        onApplyCrop(xs.min(), ys.min(), xs.max(), ys.max(), state.width, state.height)
                                    }
                                    Tool.ROTATE -> onApplyRotate(rotateDegrees, state.width, state.height)
                                    else -> {}
                                }
                            }) {
                                Icon(Icons.Filled.Check, stringResource(R.string.imagetools_apply))
                            }
                        }
                        is ImageToolState.Preview -> {
                            IconButton(onClick = { onReset(); tool = Tool.VIEW }) {
                                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.imagetools_reset))
                            }
                            IconButton(onClick = onSave, enabled = !saving) {
                                Icon(Icons.Filled.Save, stringResource(R.string.imagetools_save))
                            }
                        }
                        else -> {}
                    }
                },
            )
        },
        bottomBar = {
            if (state is ImageToolState.Loaded || state is ImageToolState.Preview) {
                NavigationBar {
                    Tool.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tool == t,
                            onClick = {
                                if (state is ImageToolState.Preview && t != tool) {
                                    onReset()
                                }
                                tool = t
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (val current = state) {
                is ImageToolState.Idle -> {}
                is ImageToolState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ImageToolState.Processing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(current.label, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }
                is ImageToolState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.imagetools_failed, current.message),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                is ImageToolState.Loaded -> {
                    when (tool) {
                        Tool.VIEW -> {
                            ZoomableImage(bitmap = current.bitmap)
                        }
                        Tool.PERSPECTIVE -> {
                            PerspectiveOverlay(
                                bitmap = current.bitmap,
                                corners = perspCorners,
                                onCornerMoved = { i, pos ->
                                    perspCorners = perspCorners.toMutableList().also { it[i] = pos }
                                },
                            )
                        }
                        Tool.CROP -> {
                            CropOverlay(
                                bitmap = current.bitmap,
                                corners = cropCorners,
                                onCornerMoved = { i, pos ->
                                    // Couple corners to maintain a rectangle:
                                    // 0=TL  1=TR  2=BL  3=BR
                                    val c = cropCorners.toMutableList()
                                    c[i] = pos
                                    when (i) {
                                        0 -> { c[1] = c[1].copy(y = pos.y); c[2] = c[2].copy(x = pos.x) }
                                        1 -> { c[0] = c[0].copy(y = pos.y); c[3] = c[3].copy(x = pos.x) }
                                        2 -> { c[0] = c[0].copy(x = pos.x); c[3] = c[3].copy(y = pos.y) }
                                        3 -> { c[1] = c[1].copy(x = pos.x); c[2] = c[2].copy(y = pos.y) }
                                    }
                                    cropCorners = c
                                },
                            )
                        }
                        Tool.ROTATE -> {
                            RotateOverlay(
                                bitmap = current.bitmap,
                                degrees = rotateDegrees,
                                onDegreesChanged = { rotateDegrees = it },
                            )
                        }
                    }
                }
                is ImageToolState.Preview -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            FilterChip(
                                selected = showOriginal,
                                onClick = { showOriginal = true },
                                label = { Text(stringResource(R.string.imagetools_original)) },
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = !showOriginal,
                                onClick = { showOriginal = false },
                                label = { Text(stringResource(R.string.imagetools_result)) },
                            )
                        }
                        val bmp = if (showOriginal) current.original else current.result
                        ZoomableImage(bitmap = bmp, modifier = Modifier.fillMaxSize().padding(8.dp))
                    }
                }
            }
        }
    }
}
