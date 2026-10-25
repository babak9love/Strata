package dev.cluvex.strata.feature.media

import dev.cluvex.strata.ui.isolated
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.ImageZoom
import dev.cluvex.strata.core.media.MediaList
import dev.cluvex.strata.core.media.MediaSiblings
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.util.IntentUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.DynamicZoomSpec
import me.saket.telephoto.zoomable.DynamicZoomSpecInputs
import me.saket.telephoto.zoomable.DynamicZoomSpecScope
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageViewerScreen(path: String, showHidden: Boolean, onBack: () -> Unit, onOpenWith: (FileItem) -> Unit) {
    val context = LocalContext.current
    val list by produceState<MediaList?>(null, path) {
        value = withContext(Dispatchers.IO) { MediaSiblings.of(File(path), FileKind.IMAGE, showHidden) }
    }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<File?>(null) }
    val status = remember { mutableStateMapOf<File, PictureStatus>() }
    BackHandler(onBack = onBack)
    LightSystemBarIcons()

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
        val l = list
        if (l == null) {
            LoadingIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        val pager = rememberPagerState(initialPage = l.start) { l.files.size }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val file = l.files[page]
            PicturePage(file, settled = pager.settledPage == page, onTap = { chrome = !chrome }, onStatus = { status[file] = it })
        }
        val current = l.files.getOrNull(pager.currentPage) ?: l.current
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Row {
                        Text(stringResource(R.string.media_image_position, pager.currentPage + 1, l.files.size), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        status[current]?.let { s ->
                            Text(
                                "  ·  " + stringResource(R.string.media_image_size, s.width, s.height) + s.zoom?.let { "  ·  $it" }.orEmpty(),
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("image-status"),
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { IntentUtils.share(context, listOf(FileItem.of(current))) }) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.common_share), tint = Color.White)
                }
                IconButton(onClick = { onOpenWith(FileItem.of(current)) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.media_open_with), tint = Color.White)
                }
                IconButton(onClick = { info = current }) {
                    Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.media_image_info), tint = Color.White)
                }
            }
        }
    }

    info?.let { file -> ImageInfoDialog(file, onDismiss = { info = null }) }
}

private data class PictureStatus(val width: Int, val height: Int, val zoom: String?)

private class Picture(val width: Int, val height: Int, val bitmap: ImageBitmap?) {
    companion object {
        fun load(file: File): Picture {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.path, bounds) }
            val (w, h) = bounds.outWidth to bounds.outHeight
            val header = runCatching { ImageZoom.header(file) }.getOrDefault(ByteArray(0))
            val bitmap = if (ImageZoom.drawsDirectly(header, w, h)) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
            return Picture(w, h, bitmap?.asImageBitmap())
        }
    }
}

@Composable
private fun PicturePage(file: File, settled: Boolean, onTap: () -> Unit, onStatus: (PictureStatus) -> Unit) {
    val picture by produceState<Picture?>(null, file) { value = withContext(Dispatchers.IO) { Picture.load(file) } }
    val rules = remember { ViewerZoom() }
    val zoomable = rememberZoomableState(zoomSpec = rules)
    val state = rememberZoomableImageState(zoomable)
    val p = picture
    if (p != null) {
        val scale by remember(p) { derivedStateOf { rules.shownScale(zoomable, p) } }
        val direct = remember(p) { p.bitmap?.let { DirectPicture(PixelPainter(it) { (scale ?: 0f) >= ImageZoom.CRISP_FROM }) } }
        if (direct != null) {
            ZoomableImage(
                image = direct,
                contentDescription = file.name,
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside,
                onClick = { onTap() },
                onDoubleClick = rules,
            )
        } else {
            ZoomableAsyncImage(
                model = file,
                contentDescription = file.name,
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside,
                onClick = { onTap() },
                onDoubleClick = rules,
            )
        }
        if (p.width > 0 && p.height > 0) {
            LaunchedEffect(p) {
                snapshotFlow { scale }.collect { s ->
                    val (w, h) = rules.oriented(p)
                    onStatus(PictureStatus(w, h, s?.let(ImageZoom::percent)))
                }
            }
        }
    }
    if (!settled) {
        LaunchedEffect(Unit) { zoomable.resetZoom(animationSpec = snap()) }
    }
}

private class ViewerZoom : DynamicZoomSpec, DoubleClickToZoomListener {
    private var content = Size.Zero
    private var view = Size.Zero

    override fun DynamicZoomSpecScope.compute(inputs: DynamicZoomSpecInputs): ZoomSpec {
        content = inputs.unscaledContentSize
        view = inputs.paddedViewportBounds.size
        return ZoomSpec(maxZoomFactor = ImageZoom.maxScale(content.width, content.height, view.width, view.height))
    }

    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        val now = state.contentTransformation.takeIf { it.isSpecified }?.scale?.scaleX ?: return
        if (content.isEmpty() || view.isEmpty()) return
        state.zoomTo(ImageZoom.doubleTapTarget(now, content.width, content.height, view.width, view.height), centroid)
    }

    fun shownScale(state: ZoomableState, picture: Picture): Float? {
        val t = state.contentTransformation
        if (!t.isSpecified || content.isEmpty() || picture.width <= 0) return null
        return t.scale.scaleX * content.width / oriented(picture).first
    }

    fun oriented(picture: Picture): Pair<Int, Int> = ImageZoom.oriented(picture.width, picture.height, content.width, content.height)
}

private class DirectPicture(private val painter: Painter) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult =
        remember(painter) { ZoomableImageSource.ResolveResult(ZoomableImageSource.PainterDelegate(painter)) }
}

private class PixelPainter(private val image: ImageBitmap, private val crisp: () -> Boolean) : Painter() {
    override val intrinsicSize = Size(image.width.toFloat(), image.height.toFloat())

    override fun DrawScope.onDraw() {
        drawImage(
            image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = if (crisp()) FilterQuality.None else FilterQuality.Low,
        )
    }
}

@Composable
private fun ImageInfoDialog(file: File, onDismiss: () -> Unit) {
    val size by produceState<Pair<Int, Int>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(isolated(file.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                size?.let { (w, h) -> InfoLine(stringResource(R.string.media_resolution), stringResource(R.string.media_image_size, w, h)) }
                InfoLine(stringResource(R.string.common_size), Formatters.size(file.length()))
                InfoLine(stringResource(R.string.media_modified), Formatters.date(file.lastModified()))
                InfoLine(stringResource(R.string.common_path), file.path, mono = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
internal fun InfoLine(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
