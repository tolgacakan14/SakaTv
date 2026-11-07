package com.hrp.sakatv.ui

import android.view.KeyEvent as NativeKey
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hrp.sakatv.data.Film
import com.hrp.sakatv.data.TvApp

val GRAY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Kumandayla seçilebilen her şeyin temeli. OK: tıklama. OK'a uzun basmak: onLong.
 * Odak durumu içeriğe verilir, görünüm tamamen içerikte çizilir.
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
    var longFired by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    var m = modifier
    if (requester != null) m = m.focusRequester(requester)
    Box(
        m
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                val ok = k.keyCode == NativeKey.KEYCODE_DPAD_CENTER || k.keyCode == NativeKey.KEYCODE_ENTER
                if (onLong == null) return@onPreviewKeyEvent false
                if (k.keyCode == NativeKey.KEYCODE_MENU && e.type == KeyEventType.KeyUp) { onLong(); return@onPreviewKeyEvent true }
                if (!ok) return@onPreviewKeyEvent false
                when {
                    e.type == KeyEventType.KeyDown && k.repeatCount > 0 -> {
                        if (!longFired) { longFired = true; onLong() }
                        true
                    }
                    e.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
    ) { content(focused) }
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
) {
    Focusable(onClick, modifier, onFocus = onFocus) { f ->
        val c by animateColorAsState(if (f || selected) C.fg else C.muted, tween(DUR), label = "lc")
        val u by animateColorAsState(if (f) C.fg else if (selected) C.fg.copy(alpha = .6f) else Color.Transparent, tween(DUR), label = "lu")
        BasicText(
            text,
            style = inter(fontSize, if (f || selected) FontWeight.SemiBold else FontWeight.Normal, c),
            modifier = Modifier
                .padding(vertical = 2.dp)
                .drawBehind { drawLine(u, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx()) },
        )
    }
}

/** Yuvarlak buton. solid: Fragman gibi ana eylem. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, onFocus: () -> Unit = {}, requester: FocusRequester? = null) {
    Focusable(onClick, modifier, onFocus = onFocus, requester = requester) { f ->
        val bg by animateColorAsState(if (f) C.fg else Color.Transparent, tween(DUR), label = "pb")
        val fg by animateColorAsState(if (f) C.focusFg else C.fg, tween(DUR), label = "pf")
        BasicText(
            text,
            style = inter(17, if (solid) FontWeight.SemiBold else FontWeight.Medium, fg),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .border(1.dp, if (solid || f) C.fg else C.line, RoundedCornerShape(999.dp))
                .padding(horizontal = 24.dp, vertical = 11.dp),
        )
    }
}

/** Film afişi. Afiş yoksa tonlu zemin ve başlık. Seçili değilse gri ve soluk. */
@Composable
fun PosterImage(film: Film, w: Int, h: Int, color: Boolean, modifier: Modifier = Modifier, large: Boolean = false) {
    val ctx = LocalContext.current
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
                model = remember(url) {
                    ImageRequest.Builder(ctx).data(url).size(if (large) 500 else 342, if (large) 750 else 513).crossfade(200).build()
                },
                contentDescription = film.title,
                contentScale = ContentScale.Crop,
                colorFilter = if (color) null else GRAY,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Satırlardaki afiş kartı: odaklanınca hafif büyür, altında not çıkar. */
@Composable
fun PosterCard(
    film: Film,
    caption: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onLong: (() -> Unit)? = null,
    onFocus: () -> Unit = {},
) {
    Focusable(onClick, modifier, onLong = onLong, onFocus = onFocus) { f ->
        val sc by animateFloatAsState(if (f) 1.06f else 1f, tween(DUR, easing = EASE), label = "s")
        val al by animateFloatAsState(if (f) 1f else 0.42f, tween(DUR, easing = EASE), label = "a")
        Column(Modifier.width(120.dp)) {
            Box(
                Modifier.graphicsLayer {
                    scaleX = sc; scaleY = sc; alpha = al
                    translationY = if (f) -6.dp.toPx() else 0f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            ) {
                PosterImage(film, 120, 180, color = f, modifier = if (f) Modifier.ring() else Modifier)
                if (badge != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp).clip(CircleShape).background(C.fg),
                        contentAlignment = Alignment.Center,
                    ) { BasicText(badge, style = inter(13, FontWeight.SemiBold, C.focusFg)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(caption, style = inter(16, color = C.muted.copy(alpha = if (f) 1f else 0f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tasarımdaki çift halka: 3dp siyah boşluk, 2dp beyaz çizgi. */
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

/** Uygulama simgesi. Gerçek logo sistemden gelir (banner ya da ikon). */
@Composable
fun AppGlyph(app: TvApp?, glyph: String, focused: Boolean, size: Int = 56, outlined: Boolean = false) {
    val bg by animateColorAsState(if (focused) C.fg else if (outlined) Color.Transparent else C.idle, tween(DUR), label = "ab")
    val sc by animateFloatAsState(if (focused) 1.12f else 1f, tween(DUR, easing = EASE), label = "as")
    val shape = RoundedCornerShape((size * 0.28f).dp)
    Box(
        Modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .then(if (focused) Modifier.ring((size * 0.28f).toInt()) else Modifier)
            .size(size.dp)
            .clip(shape)
            .background(bg)
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

/** Dock ve uygulama ızgarasındaki öğe: simge, odakta adı. */
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
            val a by animateFloatAsState(if (f || alwaysLabel) 1f else 0f, tween(DUR), label = "la")
            BasicText(
                label,
                style = inter(16, if (f) FontWeight.Medium else FontWeight.Normal, if (f) C.fg else C.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = a },
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
