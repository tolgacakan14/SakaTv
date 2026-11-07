package com.hrp.sakatv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hrp.sakatv.data.TvApp
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val STORE = "_store"
private const val ALL = "_all"

/**
 * Galerinin dışarıdan yönetimi: kaydırma durumu ve her döşemenin odak isteyicisi.
 * Düz alanlar durum nesnesi değil; yazmak yeniden kompozisyon tetiklemez.
 */
@Stable
class CarouselCtl(val state: LazyListState) {
    private val reqs = HashMap<String, FocusRequester>()
    var keys: List<String> = emptyList()
        internal set
    var focusedKey: String? = null
        internal set

    fun req(key: String): FocusRequester = reqs.getOrPut(key) { FocusRequester() }
    fun firstReq(): FocusRequester? = keys.firstOrNull()?.let { req(it) }
    fun currentReq(): FocusRequester? = (focusedKey?.takeIf { it in keys } ?: keys.firstOrNull())?.let { req(it) }
    fun isFirstFocused(): Boolean = focusedKey != null && focusedKey == keys.firstOrNull()
}

@Composable
fun rememberCarouselCtl(): CarouselCtl {
    val s = rememberLazyListState()
    return remember(s) { CarouselCtl(s) }
}

/**
 * Dönen galeri: tüm uygulamalar tek sırada. Odaktaki döşeme sabit bir çapa noktasına kayar,
 * sondan sağa basınca başa, baştan sola basınca sona döner. Öne çıkan uygulama (XCIPTV) ilk ve geniş.
 * Son iki döşeme: Mağaza ve Tüm uygulamalar.
 */
@Composable
fun AppCarousel(
    st: SakaState,
    ctl: CarouselCtl,
    onFocus: () -> Unit,
    onMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val appsState = remember { derivedStateOf { st.carouselApps() } }
    val apps = appsState.value
    val keys = remember(apps) { apps.map { it.pkg } + STORE + ALL }
    SideEffect { ctl.keys = keys }
    // Çapa: içerik başlangıcından 104dp (ekranda galerinin solundan 120dp). Bir önceki döşeme yarım görünür.
    val anchorPx = with(LocalDensity.current) { 104.dp.roundToPx() }

    fun snap(i: Int) {
        scope.launch {
            val s = ctl.state
            val info = s.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
            if (info == null) {
                s.animateScrollToItem(i)
                return@launch
            }
            val d = (info.offset - anchorPx).toFloat()
            if (abs(d) > 2f) s.animateScrollBy(d, tween(320, easing = EASE))
        }
    }

    fun wrap(toLast: Boolean) {
        scope.launch {
            val ks = ctl.keys
            if (ks.isEmpty()) return@launch
            val target = if (toLast) ks.last() else ks.first()
            ctl.state.scrollToItem(if (toLast) (ks.size - 4).coerceAtLeast(0) else 0)
            focusSoon({ ctl.req(target) }) { ctl.focusedKey == target }
        }
    }

    val n = keys.size
    LazyRow(
        state = ctl.state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 780.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(keys, key = { _, k -> k }) { i, k ->
            val edge = Modifier.dpad(
                left = if (i == 0) ({ wrap(true); true }) else null,
                right = if (i == n - 1) ({ wrap(false); true }) else null,
            )
            val focus = { ctl.focusedKey = k; snap(i); onFocus() }
            when (k) {
                STORE -> Tile(
                    app = null, label = "Mağaza", glyph = "+", featured = false, outlined = true,
                    req = ctl.req(k), modifier = edge, onClick = { st.apps.openStore() }, onLong = null, onFocus = focus,
                )
                ALL -> Tile(
                    app = null, label = "Tüm uygulamalar", glyph = "⋯", featured = false, outlined = true,
                    req = ctl.req(k), modifier = edge, onClick = { st.screen = Screen.AllApps }, onLong = null, onFocus = focus,
                )
                else -> {
                    val a = apps.getOrNull(i)
                    if (a != null) {
                        Tile(
                            app = a, label = a.label, glyph = glyphFor(a.label), featured = st.isFeatured(a.pkg), outlined = false,
                            req = ctl.req(k), modifier = edge, onClick = { st.launch(a.pkg) }, onLong = { onMenu(a.pkg) }, onFocus = focus,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(
    app: TvApp?,
    label: String,
    glyph: String,
    featured: Boolean,
    outlined: Boolean,
    req: FocusRequester,
    modifier: Modifier,
    onClick: () -> Unit,
    onLong: (() -> Unit)?,
    onFocus: () -> Unit,
) {
    val banner = app?.banner
    val icon = app?.icon
    val tw: Dp = if (featured) 200.dp else 76.dp
    val th: Dp = if (featured) 112.dp else 76.dp
    val corner = if (featured) 16 else 20
    val shape = RoundedCornerShape(corner.dp)
    val rest = if (featured) 0.96f else 0.86f
    val peak = if (featured) 1.04f else 1.1f
    val restAlpha = if (featured) 0.8f else 0.55f
    val idle = if (outlined) Color.Transparent else C.idle
    Focusable(onClick, modifier, onLong = onLong, onFocus = onFocus, requester = req) { f ->
        val k = animateFloatAsState(if (f) 1f else 0f, tween(DUR, easing = EASE), label = "tile")
        Column(Modifier.width(if (featured) 204.dp else 92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(124.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .graphicsLayer {
                            val v = k.value
                            val s = rest + (peak - rest) * v
                            scaleX = s; scaleY = s
                            alpha = restAlpha + (1f - restAlpha) * v
                        }
                        .then(if (f) Modifier.ring(corner) else Modifier)
                        .size(tw, th)
                        .clip(shape)
                        .drawBehind {
                            drawRect(lerp(idle, C.fg, k.value))
                            if (outlined && !f) {
                                drawRoundRect(C.line, cornerRadius = CornerRadius(corner.dp.toPx()), style = Stroke(1.dp.toPx()))
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        featured && banner != null -> Image(
                            banner, label,
                            Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape((corner - 4).dp)),
                            contentScale = ContentScale.Crop,
                        )
                        featured -> Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                Image(icon, label, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Fit)
                                Spacer(Modifier.width(12.dp))
                            }
                            BasicText(
                                label, style = inter(18, FontWeight.SemiBold, if (f) C.focusFg else C.fg),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        icon != null -> Image(icon, label, Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                        else -> BasicText(glyph, style = inter(26, FontWeight.SemiBold, if (f) C.focusFg else C.fg))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val text = if (featured) (if (f) "Öne çıkan · $label" else "Öne çıkan") else label
            BasicText(
                text,
                style = inter(15, if (f) FontWeight.Medium else FontWeight.Normal, if (f) C.fg else C.muted).copy(textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .requiredWidth(if (featured) 230.dp else 170.dp)
                    .graphicsLayer { alpha = if (featured) 0.75f + 0.25f * k.value else k.value },
            )
        }
    }
}
