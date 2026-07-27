package sh.haven.feature.imagetools

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val HANDLE_HIT_RADIUS_DP = 32f
private const val HANDLE_VISUAL_RADIUS_DP = 10f

@Composable
fun PerspectiveOverlay(
    bitmap: Bitmap,
    corners: List<Offset>,
    onCornerMoved: (index: Int, newPosition: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var activeCorner by remember { mutableIntStateOf(-1) }

    val currentCorners by rememberUpdatedState(corners)
    val currentOnCornerMoved by rememberUpdatedState(onCornerMoved)

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    fun fitParams(): FloatArray {
        if (viewSize == IntSize.Zero) return floatArrayOf(0f, 0f, 0f, 0f)
        val fitScale = minOf(
            viewSize.width.toFloat() / bitmap.width,
            viewSize.height.toFloat() / bitmap.height,
        )
        val imgW = bitmap.width * fitScale
        val imgH = bitmap.height * fitScale
        val imgLeft = (viewSize.width - imgW) / 2f
        val imgTop = (viewSize.height - imgH) / 2f
        return floatArrayOf(imgLeft, imgTop, imgW, imgH)
    }

    fun imageToScreen(normalized: Offset): Offset {
        val (imgLeft, imgTop, imgW, imgH) = fitParams()
        val x = (imgLeft + normalized.x * imgW) * zoom + panOffset.x
        val y = (imgTop + normalized.y * imgH) * zoom + panOffset.y
        return Offset(x, y)
    }

    fun screenDeltaToNormalized(dx: Float, dy: Float): Offset {
        val (_, _, imgW, imgH) = fitParams()
        return Offset(dx / (imgW * zoom), dy / (imgH * zoom))
    }

    fun nearestCorner(screenPos: Offset, hitRadiusPx: Float): Int {
        var best = -1
        var bestDist = hitRadiusPx * hitRadiusPx
        currentCorners.forEachIndexed { i, corner ->
            val sp = imageToScreen(corner)
            val dist = (sp.x - screenPos.x) * (sp.x - screenPos.x) +
                (sp.y - screenPos.y) * (sp.y - screenPos.y)
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    val accentColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { viewSize = it.size }
            .pointerInput(Unit) {
                val hitRadiusPx = HANDLE_HIT_RADIUS_DP * density
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Main)
                    val hit = nearestCorner(down.position, hitRadiusPx)
                    if (hit >= 0) {
                        // Corner drag
                        down.consume()
                        activeCorner = hit
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) break
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                change.consume()
                                val nd = screenDeltaToNormalized(delta.x, delta.y)
                                val cur = currentCorners[activeCorner]
                                currentOnCornerMoved(
                                    activeCorner,
                                    Offset(
                                        (cur.x + nd.x).coerceIn(0f, 1f),
                                        (cur.y + nd.y).coerceIn(0f, 1f),
                                    ),
                                )
                            }
                        }
                        activeCorner = -1
                    } else {
                        // Pan / pinch-to-zoom
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val (a, b) = pressed
                                val oldDist = (a.previousPosition - b.previousPosition).getDistance()
                                val newDist = (a.position - b.position).getDistance()
                                if (oldDist > 0f) {
                                    zoom = (zoom * (newDist / oldDist)).coerceIn(0.5f, 10f)
                                }
                                val centroidDelta = pressed.map { it.positionChange() }
                                    .reduce { acc, o -> acc + o } / pressed.size.toFloat()
                                panOffset += centroidDelta
                                pressed.forEach { it.consume() }
                            } else {
                                val change = pressed.first()
                                val delta = change.positionChange()
                                if (delta != Offset.Zero) {
                                    panOffset += delta
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            },
    ) {
        if (viewSize == IntSize.Zero) return@Box

        val (imgLeft, imgTop, imgW, imgH) = fitParams()

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panOffset.x
                    translationY = panOffset.y
                },
        ) {
            drawImage(
                imageBitmap,
                dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                dstOffset = IntOffset(imgLeft.roundToInt(), imgTop.roundToInt()),
            )
        }

        val handleRadiusPx = HANDLE_VISUAL_RADIUS_DP * density

        Canvas(modifier = Modifier.fillMaxSize()) {
            val screenCorners = currentCorners.map { imageToScreen(it) }
            val path = Path().apply {
                moveTo(screenCorners[0].x, screenCorners[0].y)
                lineTo(screenCorners[1].x, screenCorners[1].y)
                lineTo(screenCorners[3].x, screenCorners[3].y)
                lineTo(screenCorners[2].x, screenCorners[2].y)
                close()
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

            screenCorners.forEachIndexed { i, pos ->
                drawCircle(Color.Black.copy(alpha = 0.4f), handleRadiusPx + 2.dp.toPx(), pos)
                drawCircle(
                    if (i == activeCorner) Color.White else accentColor,
                    handleRadiusPx,
                    pos,
                )
                drawCircle(
                    if (i == activeCorner) accentColor else Color.White,
                    handleRadiusPx * 0.3f,
                    pos,
                )
            }
        }
    }
}

private operator fun FloatArray.component6(): Float = this[5]
