package com.hrp.sakatv.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Ekran koruyucu. 30 dakika dokunulmayınca açılır.
 * Plazma için her şey çok yavaş kayar, 5 dakika sonra kısılır. Herhangi bir tuş Şaka'yı uyandırır.
 * wake sayacı artınca uyanma dizisi başlar, bitince onDone çağrılır.
 */
@Composable
fun Screensaver(st: SakaState, wake: Int, onDone: () -> Unit) {
    val now = rememberClock()
    var phase by remember { mutableStateOf(0) } // 0 uyku, 1 uyanık, 2 çıkış
    var dimmed by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(8_000); hint = false; delay(292_000); dimmed = true }
    LaunchedEffect(wake) {
        if (wake == 0 || phase != 0) return@LaunchedEffect
        phase = 1; delay(700); phase = 2; delay(600); onDone()
    }

    val t = rememberInfiniteTransition(label = "ss")
    val lx by t.animateFloat(0f, 560f, infiniteRepeatable(tween(317_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lx")
    val ly by t.animateFloat(0f, 212f, infiniteRepeatable(tween(211_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ly")
    val sec by t.animateFloat(0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "sec")
    val dim by animateFloatAsState(if (dimmed) 0.55f else 1f, tween(4000), label = "dim")
    val out by animateFloatAsState(if (phase == 2) 0f else 1f, tween(500), label = "out")
    val hintA by animateFloatAsState(if (hint) 0.8f else 0f, tween(1800), label = "hint")
    val minute = now.get(Calendar.MINUTE)

    Box(Modifier.fillMaxSize().background(Color.Black).graphicsLayer { alpha = dim }) {
        Box(Modifier.offset { IntOffset((72 + lx).dp.roundToPx(), (44 + ly).dp.roundToPx()) }.size(380.dp, 440.dp).graphicsLayer { alpha = out; scaleX = 0.98f + 0.02f * out; scaleY = scaleX }) {
            Box(Modifier.offset(40.dp, 0.dp).size(300.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    for (i in 0 until 60) {
                        val len = if (i % 5 == 0) 9.dp.toPx() else 6.dp.toPx()
                        val col = when { i == minute -> Color.White.copy(alpha = .8f); i % 5 == 0 -> Color.White.copy(alpha = .22f); else -> Color.White.copy(alpha = .12f) }
                        rotate(i * 6f, c) { drawLine(col, Offset(c.x, 0f), Offset(c.x, len), 1.dp.toPx()) }
                    }
                    rotate(sec, c) {
                        drawCircle(Color.White.copy(alpha = .3f), 6.dp.toPx(), Offset(c.x, 0f))
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
                st.nowPlaying?.let {
                    Spacer(Modifier.height(6.dp))
                    BasicText("♪  " + it.title + (if (it.artist.isNotBlank()) " · " + it.artist else ""), style = inter(16, color = Color(0xFF6E6E6E)), maxLines = 1)
                }
            }
        }
        BasicText(
            "30 dakikadır sessiz. Bir tuşa bas, Şaka uyansın.",
            style = inter(16, color = Color(0xFF9A9A9A)),
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-44).dp).graphicsLayer { alpha = hintA },
        )
        if (phase == 2) {
            BasicText("Hoş geldin. Ana ekrana dönülüyor…", style = inter(22, FontWeight.Light, Color(0xFFEAEAEA)).copy(textAlign = TextAlign.Center),
                modifier = Modifier.align(Alignment.Center))
        }
    }
}
