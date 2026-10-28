package dev.cluvex.strata.feature.media

import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import kotlin.math.max
import kotlin.math.roundToInt

/** How the video is laid out on the screen before pinch zoom is applied. */
enum class VideoScaleMode { FIT, FILL, STRETCH, RATIO_16_9, RATIO_4_3, RATIO_21_9, ORIGINAL }

/** Current display mode, pinch zoom factor and pan offset of the video. */
class VideoZoomState {
    var mode by mutableStateOf(VideoScaleMode.FIT)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    val zoomed: Boolean get() = zoom > 1.01f

    fun resetZoom() {
        zoom = 1f
        pan = Offset.Zero
    }

    fun select(newMode: VideoScaleMode) {
        mode = newMode
        resetZoom()
    }
}

private const val MAX_ZOOM = 10f

private fun fitInside(aspect: Float, w: Float, h: Float): Size =
    if (w / h > aspect) Size(h * aspect, h) else Size(w, w / aspect)

private fun cover(aspect: Float, w: Float, h: Float): Size =
    if (w / h > aspect) Size(w, w / aspect) else Size(h * aspect, h)

private fun baseSize(mode: VideoScaleMode, w: Float, h: Float, videoW: Float, videoH: Float): Size {
    val aspect = if (videoW > 0f && videoH > 0f) videoW / videoH else 0f
    if (aspect <= 0f) return Size(w, h)
    return when (mode) {
        VideoScaleMode.FIT -> fitInside(aspect, w, h)
        VideoScaleMode.FILL -> cover(aspect, w, h)
        VideoScaleMode.STRETCH -> Size(w, h)
        VideoScaleMode.RATIO_16_9 -> fitInside(16f / 9f, w, h)
        VideoScaleMode.RATIO_4_3 -> fitInside(4f / 3f, w, h)
        VideoScaleMode.RATIO_21_9 -> fitInside(21f / 9f, w, h)
        VideoScaleMode.ORIGINAL -> Size(videoW, videoH)
    }
}

/**
 * Video surface plus the player controls. It replaces the stock Media3 Player composable so the
 * picture itself can be resized, zoomed with two fingers and panned, while the controls stay put.
 * One finger pans only while the picture is zoomed in; otherwise it is left to the caller's
 * brightness and volume swipe.
 */
@androidx.annotation.OptIn(ExperimentalApi::class, UnstableApi::class)
@Composable
fun ZoomablePlayer(
    player: Player?,
    state: VideoZoomState,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    topControls: @Composable (Player?, Boolean) -> Unit = { _, _ -> },
    bottomControls: @Composable (Player?, Boolean) -> Unit = { _, _ -> },
) {
    var videoSize by remember(player) { mutableStateOf(player?.videoSize ?: VideoSize.UNKNOWN) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player?.addListener(listener)
        videoSize = player?.videoSize ?: VideoSize.UNKNOWN
        onDispose { player?.removeListener(listener) }
    }

    // The screen stays on only while the video is actually playing (or buffering to play), so a paused video,
    // a finished one or the sleep timer running out lets the screen turn off by itself.
    val view = LocalView.current
    var keepOn by remember(player) { mutableStateOf(player.wantsScreenOn()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                keepOn = p.wantsScreenOn()
            }
        }
        keepOn = player.wantsScreenOn()
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val originalKeepOn = remember(view) { view.keepScreenOn }
    DisposableEffect(view) { onDispose { view.keepScreenOn = originalKeepOn } }
    SideEffect { view.keepScreenOn = keepOn }

    BoxWithConstraints(modifier.clipToBounds().background(Color.Black)) {
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val videoW = videoSize.width * videoSize.pixelWidthHeightRatio
        val videoH = videoSize.height.toFloat()
        val base = baseSize(state.mode, cw, ch, videoW, videoH)

        fun clampPan(pan: Offset, zoom: Float): Offset {
            val limitX = max(0f, (base.width * zoom - cw) / 2f)
            val limitY = max(0f, (base.height * zoom - ch) / 2f)
            return Offset(pan.x.coerceIn(-limitX, limitX), pan.y.coerceIn(-limitY, limitY))
        }

        // Zooms around the fingers (centroid is measured from the middle of the screen) and pans.
        val applyTransform = rememberUpdatedState<(Float, Offset, Offset) -> Unit> { zoomChange, panChange, centroid ->
            val oldZoom = state.zoom
            val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_ZOOM)
            val about = centroid - Offset(cw / 2f, ch / 2f)
            val moved = (state.pan - about) * (newZoom / oldZoom) + about + panChange
            state.zoom = newZoom
            state.pan = if (newZoom <= 1.01f) Offset.Zero else clampPan(moved, newZoom)
        }
        val zoomedNow = rememberUpdatedState(state.zoomed)

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var slopPassed = false
                        var travelled = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (event.changes.none { it.isConsumed }) {
                                if (pressed >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        applyTransform.value(zoomChange, panChange, centroid)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                    slopPassed = true
                                } else if (pressed == 1 && zoomedNow.value) {
                                    val panChange = event.calculatePan()
                                    travelled += panChange
                                    if (!slopPassed && travelled.getDistance() > slop) slopPassed = true
                                    if (slopPassed && panChange != Offset.Zero) {
                                        applyTransform.value(1f, panChange, Offset.Zero)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            // The video is always shown on a SurfaceView, which keeps playback cheap and keeps HDR/DRM intact.
            // The surface keeps one fixed size (baseW x baseH); zoom and pan are applied as scale and
            // translation on the SurfaceView itself, so the system compositor moves the picture and
            // nothing is redrawn by the app. The wrapper does not clip, so a picture larger than the
            // screen (FILL, ORIGINAL, zoomed) is cut only by the screen edge.
            val baseW = base.width.roundToInt().coerceAtLeast(1)
            val baseH = base.height.roundToInt().coerceAtLeast(1)
            var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
            DisposableEffect(player, surfaceView) {
                val sv = surfaceView
                if (player != null && sv != null) {
                    player.setVideoSurfaceView(sv)
                    onDispose { player.clearVideoSurfaceView(sv) }
                } else {
                    onDispose { }
                }
            }
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        clipChildren = false
                        clipToPadding = false
                        val sv = SurfaceView(ctx)
                        addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                        surfaceView = sv
                    }
                },
                modifier = Modifier.layout { measurable, c ->
                    val placeable = measurable.measure(Constraints.fixed(baseW, baseH))
                    layout(c.maxWidth, c.maxHeight) {
                        placeable.place((c.maxWidth - baseW) / 2, (c.maxHeight - baseH) / 2)
                    }
                },
                update = { frame ->
                    val sv = frame.getChildAt(0)
                    val zoom = state.zoom
                    val pan = clampPan(state.pan, zoom)
                    sv.scaleX = zoom
                    sv.scaleY = zoom
                    sv.translationX = pan.x
                    sv.translationY = pan.y
                },
            )

            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
                )
            }
            Box(Modifier.align(Alignment.TopCenter)) { topControls(player, showControls) }
            Box(Modifier.align(Alignment.BottomCenter)) { bottomControls(player, showControls) }
        }
    }
}

private fun Player?.wantsScreenOn(): Boolean =
    this != null && playWhenReady && playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE
