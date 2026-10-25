package dev.cluvex.strata

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import dev.cluvex.strata.core.media.ApkIconFetcher
import dev.cluvex.strata.core.media.ApkIconKeyer
import dev.cluvex.strata.core.media.FolderBadgeFetcher
import dev.cluvex.strata.core.media.FolderBadgeKeyer
import dev.cluvex.strata.core.remote.SafFileSystem
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.shizuku.ShizukuManager
import dev.cluvex.strata.core.sound.SoundPoolDevice
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.ui.i18n.AppLocales
import dev.cluvex.strata.ui.security.AppLock

class StrataApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        AppLocales.install(this)
        Formatters.locale = resources.configuration.locales[0]
        Vault.init(filesDir, cacheDir)
        AppLock.track(this)
        RootManager.init(this)
        SafFileSystem.appContext = this
        dev.cluvex.strata.core.media.FolderIconStore.init(this)
        runCatching { ShizukuManager.init(this) }
        runCatching { UiSounds.device = SoundPoolDevice(this).also { it.trackActivities(this) } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
                add(ApkIconFetcher.Factory())
                add(ApkIconKeyer())
                add(FolderBadgeFetcher.Factory())
                add(FolderBadgeKeyer())
            }
            .crossfade(true)
            .build()
}
