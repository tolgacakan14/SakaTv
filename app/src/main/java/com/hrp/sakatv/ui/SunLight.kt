package com.hrp.sakatv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

// Günün ışığı: sabah mercan, öğle beyaz, akşam kehribar, gece soluk ay mavisi.
private val DAWN = Color(0xFFFF8A6B)
private val DAY = Color(0xFFFFFFFF)
private val DUSK = Color(0xFFFFB54A)
private val MOON = Color(0xFF9DB7FF)

private class LightKey(val h: Float, val c: Color, val a: Float)

/** Saat çapaları: aralarda dakika dakika geçiş yapılır (04-05, 10-11, 16-17, 20-21). */
private val KEYS = listOf(
    LightKey(0f, MOON, .11f), LightKey(4f, MOON, .11f),
    LightKey(5f, DAWN, .15f), LightKey(10f, DAWN, .14f),
    LightKey(11f, DAY, .12f), LightKey(16f, DAY, .12f),
    LightKey(17f, DUSK, .15f), LightKey(20f, DUSK, .15f),
    LightKey(21f, MOON, .11f), LightKey(24f, MOON, .11f),
)

class SunTint(val color: Color, val peak: Float)

private fun hourOf(now: Calendar): Float = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f

fun sunTint(now: Calendar): SunTint {
    val h = hourOf(now)
    for (i in 0 until KEYS.size - 1) {
        val a = KEYS[i]
        val b = KEYS[i + 1]
        if (h >= a.h && h <= b.h) {
            val t = if (b.h > a.h) (h - a.h) / (b.h - a.h) else 0f
            return SunTint(lerp(a.c, b.c, t), a.a + (b.a - a.a) * t)
        }
    }
    return SunTint(MOON, .11f)
}

fun isNight(now: Calendar): Boolean {
    val h = now.get(Calendar.HOUR_OF_DAY)
    return h >= 21 || h < 5
}

/** Gündüz güneş 06:00'da doğudan (180°) 20:00'de batıya (0°) akar; gece ay tepede (90°) durur. */
fun sunAngle(now: Calendar): Double =
    if (isNight(now)) 90.0 else 180.0 - ((hourOf(now) - 6f).coerceIn(0f, 14f) / 14f) * 180.0

/** Yay kutusu (genişlik 2r, yükseklik r) içinde gök cisminin yeri, kutu boyutuna oranla. */
fun orbFraction(now: Calendar): Offset {
    val a = Math.toRadians(sunAngle(now))
    return Offset(0.5f + 0.5f * cos(a).toFloat(), 1f - sin(a).toFloat())
}

/** Saatin yanındaki küçük güneş/ay yayı. Yuvarlak, ışıkla aynı renge boyanır. */
@Composable
fun SunArc(now: Calendar, modifier: Modifier = Modifier, onPlaced: ((LayoutCoordinates) -> Unit)? = null) {
    val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val tint = remember(minute) { sunTint(now).color }
    val frac = remember(minute) { orbFraction(now) }
    val night = remember(minute) { isNight(now) }
    Box(
        modifier
            .size(120.dp, 60.dp)
            .then(if (onPlaced != null) Modifier.onGloballyPositioned(onPlaced) else Modifier)
            .drawBehind {
                drawArc(C.line, 180f, 180f, false, topLeft = Offset.Zero, size = Size(size.width, size.width), style = Stroke(1.dp.toPx()))
                val c = Offset(frac.x * size.width, frac.y * size.height)
                if (night) {
                    drawCircle(tint.copy(alpha = 0.16f), 9.dp.toPx(), c)
                    drawCircle(Color.Black, 4.dp.toPx(), c)
                    drawCircle(tint, 4.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                } else {
                    drawCircle(tint.copy(alpha = 0.35f), 8.dp.toPx(), c)
                    drawCircle(lerp(C.fg, tint, 0.6f), 4.dp.toPx(), c)
                }
            },
    )
}

// Yay ölçülmediğinde (diğer ekranlar) varsayılan yer: sağ üst, tasarım birimiyle.
private const val FALLBACK_X = 1060f
private const val FALLBACK_Y = 30f

// Işığın aktığı yön: yörüngeden sol alta doğru (ekran koordinatında saat yönünde 149°).
private const val DIAG = 149f

/**
 * Günün saatine göre yumuşak ışık. Yörüngenin ekrandaki gerçek yerinden doğar, çapraz sol alta yayılır.
 * flowIn: ekran açılınca ~2,5 sn'de süzülerek gelir, sonra tamamen durağandır.
 * Renk ve yer sadece dakika değişince güncellenir. Tek bir drawBehind, kendi katmanında önbelleklenir.
 * arcPx: yay kutusunun bu kutuya göre piksel dikdörtgeni (boşsa varsayılan yer).
 */
@Composable
fun SunLight(now: Calendar, modifier: Modifier = Modifier, flowIn: Boolean = false, arcPx: (() -> Rect)? = null) {
    val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val tint = remember(minute) { sunTint(now) }
    val frac = remember(minute) { orbFraction(now) }
    val grow = remember { Animatable(if (flowIn) 0f else 1f) }
    if (flowIn) {
        LaunchedEffect(Unit) { grow.animateTo(1f, tween(2500, easing = FastOutSlowInEasing)) }
    }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                val g = grow.value
                if (g <= 0.001f) return@drawBehind
                val r0 = arcPx?.invoke()
                val arc = if (r0 != null && r0.width > 0f) r0 else Rect(
                    FALLBACK_X.dp.toPx(), FALLBACK_Y.dp.toPx(), (FALLBACK_X + 120f).dp.toPx(), (FALLBACK_Y + 60f).dp.toPx(),
                )
                val c = Offset(arc.left + frac.x * arc.width, arc.top + frac.y * arc.height)
                val w = size.width
                val a = tint.peak * g
                val col = tint.color
                // Uzun ve çok soluk kuyruk: sayfanın üstüne çapraz yayılır.
                val r2 = w * (0.30f + 0.42f * g)
                rotate(DIAG, c) {
                    scale(1.55f, 0.85f, c) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to col.copy(alpha = a * 0.38f), 0.5f to col.copy(alpha = a * 0.12f), 1f to Color.Transparent,
                                center = c, radius = r2,
                            ),
                            r2, c,
                        )
                    }
                }
                // Yörüngeye yakın yumuşak çekirdek.
                val r1 = w * (0.10f + 0.22f * g)
                rotate(DIAG, c) {
                    scale(1.35f, 0.9f, c) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to col.copy(alpha = a), 0.35f to col.copy(alpha = a * 0.45f), 1f to Color.Transparent,
                                center = c, radius = r1,
                            ),
                            r1, c,
                        )
                    }
                }
            },
    )
}
