package dev.cluvex.strata.ui.expressive

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.graphics.shapes.RoundedPolygon
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.ui.theme.LocalAppearance

const val EXPRESSIVE_DIALOG_TAG = "expressive-dialog"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ExpressiveDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    art: Art? = null,
    icon: ImageVector? = null,
    picture: ImageBitmap? = null,
    tone: ArtTone = ArtTone.PRIMARY,
    shapes: List<RoundedPolygon> = HeroShapes.calm,
    sound: UiSound? = null,
    properties: DialogProperties = DialogProperties(),
    text: (@Composable () -> Unit)? = null,
) {
    LaunchedEffect(Unit) { UiSounds.play(sound) }
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        val moving = LocalAppearance.current.motion != MotionStyle.REDUCED
        val entrance = remember { Animatable(if (moving) 0.88f else 1f) }
        val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        LaunchedEffect(Unit) { entrance.animateTo(1f, spec) }
        val hero = art != null || icon != null || picture != null
        Surface(
            modifier = Modifier
                .testTag(EXPRESSIVE_DIALOG_TAG)
                .graphicsLayer {
                    scaleX = entrance.value
                    scaleY = entrance.value
                },
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                if (hero) {
                    ExpressiveHero(
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
                        art = art,
                        icon = icon,
                        picture = picture,
                        tone = tone,
                        size = 96.dp,
                        shapes = shapes,
                    )
                }
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth(),
                    color = AlertDialogDefaults.titleContentColor,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = if (hero) TextAlign.Center else TextAlign.Start,
                )
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(
                                Modifier
                                    .weight(1f, fill = false)
                                    .padding(top = 16.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) { text() }
                        }
                    }
                }
                FlowRow(
                    Modifier.align(Alignment.End).padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
