package com.hrp.sakatv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hrp.sakatv.data.NowPlaying
import kotlinx.coroutines.delay
import java.util.Calendar

const val SAVER_HINT = "30 dakikadır sessiz. Bir tuşa bas, Şaka uyansın."

/** Uygulama içi ekran koruyucu (MainActivity çağırır). Görsel kısım SaverContent'te. */
@Composable
fun Screensaver(st: SakaState, wake: Int, onDone: () -> Unit) {
    SaverContent(st.nowPlaying, wake, onDone)
}

/**
 * Ekran koruyucunun görünen kısmı. Hem uygulama içinde hem sistem ekran koruyucusunda (SakaDream) kullanılır.
 * Plazma için her şey çok yavaş kayar, 5 dakika sonra kısılır. wake sayacı artınca uyanma dizisi başlar,
 * bitince onDone çağrılır. Kayma ve saniye noktası ~12 kare/sn ile güncellenir, sadece yerleşim ve
 * çizim fazında okunur (yeniden kompozisyon yok).
 */
@Composable
fun SaverContent(nowPlaying: NowPlaying?, wake: Int, onDone: () -> Unit, hint: String = SAVER_HINT) {
    val now = rememberMinuteClock()
    var phase by remember { mutableIntStateOf(0) } // 0 uyku, 1 uyanık, 2 çıkış
    var dimmed by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(8_000); showHint = false; delay(292_000); dimmed = true }
    LaunchedEffect(wake) {
        if (wake == 0 || phase != 0) return@LaunchedEffect
        phase = 1; delay(700); phase = 2; delay(600); onDone()
    }

    // Kaba saat: ~12 kare/sn. Hem kayma hem saniye noktası bundan okunur.
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            delay(70)
            withFrameMillis { tick.longValue = it - start }
        }
    }

    val dim by animateFloatAsState(if (dimmed) 0.55f else 1f, tween(4000), label = "dim")
    val out by animateFloatAsState(if (phase == 2) 0f else 1f, tween(500), label = "out")
    val hintA by animateFloatAsState(if (showHint) 0.8f else 0f, tween(1800), label = "hint")
    val minute = now.get(Calendar.MINUTE)

    // Zemin pencerenin siyahı; burada tam ekran arka plan ya da tam ekran alfa katmanı yok.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset {
                    val t = tick.longValue
                    val lx = 560f * drift(t, 317_000)
                    val ly = 212f * drift(t, 211_000)
                    IntOffset((72 + lx).dp.roundToPx(), (44 + ly).dp.roundToPx())
                }
                .size(380.dp, 440.dp)
                .graphicsLayer {
                    alpha = dim * out
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    scaleX = 0.98f + 0.02f * out
                    scaleY = scaleX
                },
        ) {
            Box(Modifier.offset(40.dp, 0.dp).size(300.dp)) {
                // Dakika çizgileri: dakikada bir yeniden çizilir, kendi katmanında.
                Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val w = 1.dp.toPx()
                    val big = 9.dp.toPx()
                    val small = 6.dp.toPx()
                    for (i in 0 until 60) {
                        val len = if (i % 5 == 0) big else small
                        val col = when { i == minute -> TICK_NOW; i % 5 == 0 -> TICK_FIVE; else -> TICK }
                        rotate(i * 6f, c) { drawLine(col, Offset(c.x, 0f), Offset(c.x, len), w) }
                    }
                }
                // Saniye noktası: gerçek saniyeye bağlı, kendi katmanında, ~12 kare/sn.
                Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                    // okuma: her tıkta sadece bu katman yeniden çizilir
                    if (tick.longValue < 0L) return@Canvas
                    val ms = System.currentTimeMillis() % 60_000L
                    val c = Offset(size.width / 2, size.height / 2)
                    rotate(ms / 60_000f * 360f, c) {
                        drawCircle(DOT_HALO, 6.dp.toPx(), Offset(c.x, 0f))
                        drawCircle(Color.White, 3.dp.toPx(), Offset(c.x, 0f))
                    }
                }
                Box(Modifier.offset(50.dp, 62.dp)) {
                    Saka(200.dp, Tod.GECE, if (phase == 0) Mood.UYKU else Mood.MERAK, mono = true, zColor = Color(0xFFEAEAEA))
                }
            }
            Column(Modifier.offset(0.dp, 320.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(pad(now.get(Calendar.HOUR_OF_DAY)) + ":" + pad(minute), style = inter(56, FontWeight.ExtraLight, Color(0xFFEAEAEA), ls = -1f))
                Spacer(Modifier.height(6.dp))
                BasicText(DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + " · " + now.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[now.get(Calendar.MONTH)],
                    style = inter(18, color = Color(0xFF8C8C8C)))
                nowPlaying?.let {
                    Spacer(Modifier.height(6.dp))
                    BasicText("♪  " + it.title + (if (it.artist.isNotBlank()) " · " + it.artist else ""), style = inter(16, color = Color(0xFF6E6E6E)), maxLines = 1)
                }
            }
        }
        BasicText(
            hint,
            style = inter(16, color = Color(0xFF9A9A9A)),
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-44).dp).graphicsLayer { alpha = hintA * dim },
        )
        if (phase == 2) {
            BasicText("Hoş geldin. Ana ekrana dönülüyor…", style = inter(22, FontWeight.Light, Color(0xFFEAEAEA)).copy(textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.Center))
        }
    }
}

private val TICK_NOW = Color.White.copy(alpha = .8f)
private val TICK_FIVE = Color.White.copy(alpha = .22f)
private val TICK = Color.White.copy(alpha = .12f)
private val DOT_HALO = Color.White.copy(alpha = .3f)

/** Eski tween(half, FastOutSlowIn) + Reverse kaymasının birebir karşılığı: 0→1→0. */
private fun drift(t: Long, half: Int): Float {
    val p = (t % (half * 2L)).toFloat() / half
    return FastOutSlowInEasing.transform(if (p <= 1f) p else 2f - p)
}

/** Dakika başında güncellenen saat (ekran koruyucuya özel, başka dosyaya bağımlı değil). */
@Composable
private fun rememberMinuteClock(): Calendar {
    val c by produceState(Calendar.getInstance()) {
        while (true) {
            value = Calendar.getInstance()
            delay(((60 - value.get(Calendar.SECOND)) * 1000L).coerceAtLeast(1000L))
        }
    }
    return c
}
