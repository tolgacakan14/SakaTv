package com.hrp.sakatv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.service.dreams.DreamService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.hrp.sakatv.data.NowPlaying
import com.hrp.sakatv.data.NowPlayingReader
import com.hrp.sakatv.ui.SaverContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Sistem ekran koruyucusu. TV kendi bekleme süresi dolunca, başka bir uygulama açıkken bile
 * Şaka'yı (uyuyan kedi, saat halkası, saat) gösterir. Video oynarken sistem zaten açmaz.
 * Etkileşimsiz: herhangi bir tuş ekran koruyucuyu kapatır ve kullanıcı kaldığı yere döner.
 *
 * Compose bir servis penceresinde çalışsın diye servis kendisi LifecycleOwner,
 * SavedStateRegistryOwner ve ViewModelStoreOwner olur; yaşam döngüsü rüya olaylarıyla sürülür.
 */
class SakaDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false // plazma için daha loş

        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window?.decorView?.let { attachOwners(it) }

        val view = ComposeView(this)
        attachOwners(view)
        view.setContent { DreamContent() }
        setContentView(view)
    }

    private fun attachOwners(v: android.view.View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        move(Lifecycle.State.RESUMED)
    }

    override fun onDreamingStopped() {
        move(Lifecycle.State.CREATED)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        move(Lifecycle.State.DESTROYED)
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        move(Lifecycle.State.DESTROYED)
        store.clear()
        super.onDestroy()
    }

    private fun move(to: Lifecycle.State) {
        val cur = lifecycleRegistry.currentState
        if (cur == to || cur == Lifecycle.State.DESTROYED || cur == Lifecycle.State.INITIALIZED) return
        runCatching { lifecycleRegistry.currentState = to }
    }

    @Composable
    private fun DreamContent() {
        // MainActivity ile aynı: tasarım 1280 birim genişliğinde.
        val d = remember { Density(density = resources.displayMetrics.widthPixels / 1280f, fontScale = 1f) }
        var np by remember { mutableStateOf<NowPlaying?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                np = withContext(Dispatchers.IO) { NowPlayingReader.read(applicationContext) }
                delay(20_000)
            }
        }
        CompositionLocalProvider(LocalDensity provides d) {
            SaverContent(np, 0, { finish() }, hint = "Bir tuşa bas, Şaka uyansın.")
        }
    }
}
