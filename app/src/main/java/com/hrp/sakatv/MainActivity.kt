package com.hrp.sakatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hrp.sakatv.ui.AllAppsScreen
import com.hrp.sakatv.ui.C
import com.hrp.sakatv.ui.FilmDetail
import com.hrp.sakatv.ui.HomeScreen
import com.hrp.sakatv.ui.SakaState
import com.hrp.sakatv.ui.Screen
import com.hrp.sakatv.ui.Screensaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var st: SakaState
    private var lastInput = SystemClock.elapsedRealtime()
    private var homeSignal by mutableIntStateOf(0)
    private var wakeSignal by mutableIntStateOf(0)

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { st.reloadApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        st = SakaState(applicationContext, lifecycleScope)
        st.start()

        ContextCompat.registerReceiver(this, pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_EXPORTED)

        // 30 dakika tuşa basılmazsa ekran koruyucu.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(30_000)
                    val idle = SystemClock.elapsedRealtime() - lastInput
                    if (idle >= IDLE_MS && st.screen != Screen.Saver) {
                        wakeSignal = 0
                        st.screen = Screen.Saver
                    }
                }
            }
        }

        setContent {
            val w = resources.displayMetrics.widthPixels
            // Tasarım 1280 birim genişliğinde: her ekranda birebir aynı oran.
            val d = Density(density = w / 1280f, fontScale = 1f)
            CompositionLocalProvider(LocalDensity provides d) {
                Box(Modifier.fillMaxSize().background(C.bg)) {
                    AnimatedContent(
                        targetState = st.screen,
                        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Home -> HomeScreen(st, homeSignal)
                            Screen.AllApps -> AllAppsScreen(st)
                            is Screen.Detail -> FilmDetail(st, s.key)
                            Screen.Saver -> Screensaver(st, wakeSignal) { st.screen = Screen.Home; homeSignal++ }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (st.screen == Screen.Saver) {
            // Ekran koruyucuda tuşlar başka bir şey tetiklemez, sadece uyandırır.
            if (event.action == KeyEvent.ACTION_UP) wakeSignal++
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Kumandadaki Ana Sayfa tuşu: her zaman temiz ana ekrana dön.
        lastInput = SystemClock.elapsedRealtime()
        st.screen = Screen.Home
        homeSignal++
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.elapsedRealtime()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pkgReceiver) }
        super.onDestroy()
    }

    companion object { const val IDLE_MS = 30 * 60 * 1000L }
}
