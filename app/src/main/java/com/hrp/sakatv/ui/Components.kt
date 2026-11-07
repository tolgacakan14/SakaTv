package com.hrp.sakatv.ui

import android.view.KeyEvent as NativeKey
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hrp.sakatv.data.Film
import com.hrp.sakatv.data.TvApp

val GRAY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private val OK_KEYS = intArrayOf(
    NativeKey.KEYCODE_DPAD_CENTER, NativeKey.KEYCODE_ENTER, NativeKey.KEYCODE_NUMPAD_ENTER, NativeKey.KEYCODE_BUTTON_A,
)

/** OK tuşunun durumu. Durum nesnesi değil: yazmak yeniden çizim tetiklemez. */
private class KeyLatch {
    var down = false
    var long = false
}

/**
 * Kumandayla seçilebilen her şeyin temeli. OK: tıklama. OK'a uzun basmak (ya da Menü tuşu): onLong.
 * OK tuşunu tamamen kendisi işler: tıklama sadece bu öğede başlayan bir basışın bırakılmasıyla olur.
 * Böylece uzun basıştan sonra açılan pencerede ya da yeni ekranda "hayalet" tıklama olmaz.
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLong: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    requester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val latch = remember { KeyLatch() }
    val src = remember { MutableInteractionSource() }
    var m = modifier
    if (requester != null) m = m.focusRequester(requester)
    Box(
        m
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode == NativeKey.KEYCODE_MENU) {
                    if (onLong == null) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyUp) onLong()
                    return@onPreviewKeyEvent true
                }
                if (k.keyCode !in OK_KEYS) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (k.repeatCount == 0) {
                            latch.down = true; latch.long = false
                        } else if (onLong != null && latch.down && !latch.long) {
                            latch.long = true; onLong()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val click = latch.down && !latch.long
                        latch.down = false; latch.long = false
                        if (click) onClick()
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged { s ->
                if (focused != s.isFocused) {
                    focused = s.isFocused
                    if (s.isFocused) onFocus() else { latch.down = false; latch.long = false }
                }
            }
            .focusable(interactionSource = src),
    ) { content(focused) }
}

/**
 * Yön tuşlarını öğe bazında yönlendirir. İşleyici true dönerse tuş tüketilir ve odak araması yapılmaz.
 * Focusable'a modifier olarak verilir; yapı her zaman aynı kalsın diye işleyiciler null olabilir.
 */
fun Modifier.dpad(
    up: (() -> Boolean)? = null,
    down: (() -> Boolean)? = null,
    left: (() -> Boolean)? = null,
    right: (() -> Boolean)? = null,
): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val h = when (e.nativeKeyEvent.keyCode) {
        NativeKey.KEYCODE_DPAD_UP -> up
        NativeKey.KEYCODE_DPAD_DOWN -> down
        NativeKey.KEYCODE_DPAD_LEFT -> left
        NativeKey.KEYCODE_DPAD_RIGHT -> right
        else -> null
    }
    h?.invoke() ?: false
}

/**
 * Odağı bir hedefe taşır; hedef henüz kompoze edilmemişse (tembel liste kayarken) birkaç kare dener.
 * landed: odağın gerçekten oraya vardığını söyler (onFocus geri çağrılarıyla tutulan bilgi).
 */
suspend fun focusSoon(req: () -> FocusRequester?, tries: Int = 10, landed: () -> Boolean): Boolean {
    repeat(tries) {
        withFrameNanos { }
        req()?.let { r -> runCatching { r.requestFocus() } }
        withFrameNanos { }
        if (landed()) return true
    }
    return false
}

/** Alt çizgili metin bağlantısı: Karşılaştır, Yenile, sekmeler, alt menü. */
@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    fontSize: Int = 17,
    onFocus: () -> Unit = {},
    requester: FocusRequester? = null,
) {
    Focusable(onClick, modifier, onFocus = onFocus, requester = requester) { f ->
        val c by animateColorAsState(if (f || selected) C.fg else C.muted, tween(DUR), label = "lc")
        val u = animateColorAsState(if (f) C.fg else if (selected) C.fg.copy(alpha = .6f) else Color.Transparent, tween(DUR), label = "lu")
        BasicText(
            text,
            style = inter(fontSize, if (f || selected) FontWeight.SemiBold else FontWeight.Normal, c),
            modifier = Modifier
                .padding(vertical = 2.dp)
                .drawBehind { drawLine(u.value, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx()) },
        )
    }
}

/** Yuvarlak buton. solid: Fragman gibi ana eylem. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, onFocus: () -> Unit = {}, requester: FocusRequester? = null) {
    Focusable(onClick, modifier, onFocus = onFocus, requester = requester) { f ->
        val bg = animateColorAsState(if (f) C.fg else Color.Transparent, tween(DUR), label = "pb")
        val fg by animateColorAsState(if (f) C.focusFg else C.fg, tween(DUR), label = "pf")
        BasicText(
            text,
            style = inter(17, if (solid) FontWeight.SemiBold else FontWeight.Medium, fg),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .drawBehind { drawRect(bg.value) }
                .border(1.dp, if (solid || f) C.fg else C.line, RoundedCornerShape(999.dp))
                .padding(horizontal = 24.dp, vertical = 11.dp),
        )
    }
}

/** Afişin altındaki küçük not: yıl · puan. */
fun filmCaption(f: Film): String = listOfNotNull(
    f.year.ifBlank { null },
    if (f.rating > 0) "★ " + "%.1f".format(f.rating) else null,
).joinToString(" · ")

/** Film afişi. Afiş yoksa tonlu zemin ve başlık. Seçili değilse gri. Görsel tam ekran boyutunda çözülür. */
@Composable
fun PosterImage(film: Film, w: Int, h: Int, color: Boolean, modifier: Modifier = Modifier, large: Boolean = false) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val pw = with(d) { w.dp.roundToPx() }
    val ph = with(d) { h.dp.roundToPx() }
    Box(
        modifier
            .size(w.dp, h.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(film.tone)),
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText(
            film.title,
            style = serif(if (w > 200) 26 else 17, color = Color.White),
            modifier = Modifier.padding(12.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val url = if (large) film.posterUrlLarge else film.posterUrl
        if (url != null) {
            AsyncImage(
                model = remember(url, pw, ph) {
                    ImageRequest.Builder(ctx).data(url).size(pw, ph).crossfade(160).build()
                },
                contentDescription = film.title,
                contentScale = ContentScale.Crop,
                colorFilter = if (color) null else GRAY,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Satırlardaki afiş kartı: altında her zaman adı ve yıl · puan. Odakta büyür, ad beyazlaşır. */
@Composable
fun PosterCard(
    film: Film,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String = filmCaption(film),
    badge: String? = null,
    onLong: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    requester: FocusRequester? = null,
) {
    Focusable(onClick, modifier, onLong = onLong, onFocus = onFocus, requester = requester) { f ->
        val k = animateFloatAsState(if (f) 1f else 0f, tween(DUR, easing = EASE), label = "pc")
        Column(Modifier.width(120.dp)) {
            Box(
                Modifier.graphicsLayer {
                    val v = k.value
                    val s = 1f + 0.06f * v
                    scaleX = s; scaleY = s
                    alpha = 0.5f + 0.5f * v
                    translationY = -6.dp.toPx() * v
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            ) {
                PosterImage(film, 120, 180, color = f, modifier = if (f) Modifier.ring() else Modifier)
                if (badge != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(C.fg),
                        contentAlignment = Alignment.Center,
                    ) { BasicText(badge, style = inter(13, FontWeight.SemiBold, C.focusFg)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            BasicText(
                film.title,
                style = inter(15, if (f) FontWeight.Medium else FontWeight.Normal, if (f) C.fg else C.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                BasicText(caption, style = inter(13, color = if (f) C.muted else C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Tasarımdaki çift halka: 4dp boşluk, 2dp beyaz çizgi. */
fun Modifier.ring(radius: Int = 8): Modifier = this
    .drawBehind {
        val r = radius.dp.toPx()
        val o = 4.dp.toPx()
        drawRoundRect(
            color = C.fg,
            topLeft = Offset(-o, -o),
            size = androidx.compose.ui.geometry.Size(size.width + 2 * o, size.height + 2 * o),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + o),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
        )
    }

/** Uygulama simgesi. Gerçek logo sistemden gelir. Animasyon sadece çizim katmanında okunur. */
@Composable
fun AppGlyph(app: TvApp?, glyph: String, focused: Boolean, size: Int = 56, outlined: Boolean = false) {
    val k = animateFloatAsState(if (focused) 1f else 0f, tween(DUR, easing = EASE), label = "ag")
    val shape = RoundedCornerShape((size * 0.28f).dp)
    val idle = if (outlined) Color.Transparent else C.idle
    Box(
        Modifier
            .graphicsLayer { val s = 1f + 0.12f * k.value; scaleX = s; scaleY = s }
            .then(if (focused) Modifier.ring((size * 0.28f).toInt()) else Modifier)
            .size(size.dp)
            .clip(shape)
            .drawBehind { drawRect(lerp(idle, C.fg, k.value)) }
            .then(if (outlined && !focused) Modifier.border(1.dp, C.line, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val img = app?.icon
        if (img != null) {
            Image(img, app?.label, Modifier.size((size * 0.72f).dp).clip(RoundedCornerShape((size * 0.18f).dp)), contentScale = ContentScale.Fit)
        } else {
            BasicText(glyph, style = inter((size * 0.32f).toInt(), FontWeight.SemiBold, if (focused) C.focusFg else C.fg))
        }
    }
}

/** Uygulama ızgarasındaki öğe: simge, altında adı. */
@Composable
fun AppItem(
    app: TvApp?,
    label: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLong: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
    alwaysLabel: Boolean = false,
    outlined: Boolean = false,
    requester: FocusRequester? = null,
) {
    Focusable(onClick, modifier, onLong = onLong, onFocus = onFocus, requester = requester) { f ->
        Column(Modifier.width(82.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppGlyph(app, glyph, f, outlined = outlined)
            Spacer(Modifier.height(if (alwaysLabel) 10.dp else 4.dp))
            val a = animateFloatAsState(if (f || alwaysLabel) 1f else 0f, tween(DUR), label = "la")
            BasicText(
                label,
                style = inter(16, if (f) FontWeight.Medium else FontWeight.Normal, if (f) C.fg else C.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = a.value },
            )
        }
    }
}

@Composable
fun SectionTitle(title: String, sub: String, modifier: Modifier = Modifier, right: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(title, style = serif(26))
            Spacer(Modifier.width(16.dp))
            BasicText(sub, style = inter(16, color = C.muted), modifier = Modifier.padding(bottom = 3.dp))
        }
        if (right != null) BasicText(right, style = inter(16, color = C.muted))
    }
}

fun glyphFor(label: String): String = label.trim().take(1).uppercase()
