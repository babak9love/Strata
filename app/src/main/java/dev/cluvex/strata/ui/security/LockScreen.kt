package dev.cluvex.strata.ui.security

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveHero
import dev.cluvex.strata.ui.expressive.HeroShapes
import kotlinx.coroutines.flow.first

const val APP_LOCK_TAG = "app-lock"

@Composable
fun AppLockGate(onTurnOff: () -> Unit, content: @Composable () -> Unit) {
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (locked) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
        AnimatedVisibility(
            visible = locked,
            enter = EnterTransition.None,
            exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()) + scaleOut(MaterialTheme.motionScheme.slowSpatialSpec(), targetScale = 1.12f),
        ) {
            LockScreen(onTurnOff)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockScreen(onTurnOff: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val availability = remember { DeviceAuth.current.lockAvailability(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun unlock() {
        val a = activity as? FragmentActivity ?: return
        if (busy) return
        busy = true
        error = null
        AppLock.authStarted()
        DeviceAuth.current.authenticate(a, AuthRequest(title = tr(R.string.lock_prompt_title), subtitle = tr(R.string.lock_prompt_subtitle), deviceCredential = true)) { result ->
            busy = false
            when (result) {
                is AuthResult.Success -> {
                    AppLock.authFinished(true)
                    UiSounds.play(UiSound.UNLOCK)
                }
                is AuthResult.Failed -> {
                    AppLock.authFinished(false)
                    error = result.message.ifBlank { null }
                }
                else -> AppLock.authFinished(false)
            }
        }
    }

    LaunchedEffect(availability) {
        if (availability == AuthAvailability.READY) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            unlock()
        }
    }
    BackHandler { activity?.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize().testTag(APP_LOCK_TAG), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ExpressiveHero(icon = Icons.Rounded.Lock, tone = ArtTone.PRIMARY, size = 148.dp, shapes = HeroShapes.calm)
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.lock_title),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (availability == AuthAvailability.READY) R.string.lock_subtitle else R.string.lock_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
            if (availability == AuthAvailability.READY) {
                Button(
                    onClick = ::unlock,
                    enabled = !busy,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MediumContainerHeight)))
                    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MediumContainerHeight)))
                    Text(stringResource(R.string.lock_unlock), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            } else {
                OutlinedButton(onClick = onTurnOff, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.lock_turn_off)) }
            }
        }
    }
}
