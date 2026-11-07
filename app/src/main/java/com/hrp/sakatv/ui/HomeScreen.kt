package com.hrp.sakatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hrp.sakatv.data.Film
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------- odak haritası ----------------

private enum class Zone { NONE, CAROUSEL, HERO, ROWS, FOOT, TOAST }

/** Odağın nerede olduğu ve ölçümler. Düz alanlar: yazmak yeniden kompozisyon tetiklemez. */
private class Nav {
    var zone = Zone.NONE
    var tag = ""
    var rowLazy = -1
    var hero: LayoutCoordinates? = null
    var arc: LayoutCoordinates? = null
}

private class RowModel(
    val id: String,
    val title: String,
    val sub: String,
    val films: List<Film>,
    val empty: String? = null,
    val badge: String? = null,
)

/** Son İzledim işlemi: Geri al bunu tersine çevirir, odak back'e döner. */
private class Undo(val film: Film, val nowWatched: Boolean, val back: FocusRequester)

/** Ekranlar arası küçük hafıza: detaydan dönünce odak kaldığı yere gelir, satırlar kaydığı yerde kalır. */
private object HomeMemory {
    var signal = -1
    var target: String? = null // "hero" ya da "row:<satır>/<film>"
    var sel = 0
    var lazy = 0
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(id: String): LazyListState = rowStates.getOrPut(id) { LazyListState() }
}

@Composable
fun HomeScreen(st: SakaState, homeSignal: Int) {
    val restoreRow = remember { HomeMemory.target?.startsWith("row:") == true && homeSignal == HomeMemory.signal }
    val list = rememberLazyListState(if (restoreRow) HomeMemory.lazy else 0)
    val scope = rememberCoroutineScope()
    val nav = remember { Nav() }
    val carousel = rememberCarouselCtl()
    val posterReqs = remember { List(5) { FocusRequester() } }
    val tabReqs = remember { List(FilmTab.entries.size) { FocusRequester() } }
    val pillReq = remember { FocusRequester() }
    val undoReq = remember { FocusRequester() }
    val footReq = remember { FocusRequester() }
    val cardReqs = remember { HashMap<String, FocusRequester>() }
    val selState = remember { mutableIntStateOf(HomeMemory.sel) }
    val undoState = remember { mutableStateOf<Undo?>(null) }
    val undoFocused = remember { mutableStateOf(false) }
    val arcRect = remember { mutableStateOf(Rect.Zero) }
    val fiveState = remember { derivedStateOf { st.five() } }
    val rowsState = remember { derivedStateOf { buildRows(st) } }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    var gtvInfo by remember { mutableStateOf(false) }
    val now = rememberClock()

    // Plazma koruması: 2 dakikada bir 3dp kayan içerik. Sadece yerleşim fazında okunur.
    val shift = produceState(Offset.Zero) {
        val steps = listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3, -3 to 3, -3 to 0, -3 to -3, 0 to -3)
        var i = 0
        while (true) { value = Offset(steps[i].first.toFloat(), steps[i].second.toFloat()); i = (i + 1) % steps.size; delay(120_000) }
    }

    fun cardReq(rowId: String, key: String): FocusRequester = cardReqs.getOrPut("$rowId/$key") { FocusRequester() }

    fun measureArc() {
        val h = nav.hero ?: return
        val a = nav.arc ?: return
        if (!h.isAttached || !a.isAttached) return
        val r = Rect(h.localPositionOf(a, Offset.Zero), a.size.toSize())
        if (r != arcRect.value) arcRect.value = r
    }

    fun firstRow(): Pair<Int, RowModel>? {
        val rs = rowsState.value
        val i = rs.indexOfFirst { it.films.isNotEmpty() }
        return if (i < 0) null else (i + 1) to rs[i]
    }

    /** Aşağı: satırları görünür yap, ilk dolu satırın ilk kartına odaklan. */
    fun goRows(): Boolean {
        scope.launch {
            val t = firstRow()
            if (t == null) {
                list.animateScrollToItem(rowsState.value.size + 1)
                focusSoon({ footReq }) { nav.zone == Zone.FOOT }
            } else {
                val (idx, r) = t
                list.animateScrollToItem(idx)
                HomeMemory.rowState(r.id).scrollToItem(0)
                focusSoon({ cardReq(r.id, r.films[0].key) }) { nav.zone == Zone.ROWS }
            }
        }
        return true
    }

    /** Yukarı: en başa dön, seçili afişe (ya da galeriye) odaklan. */
    fun goTop(toCarousel: Boolean) {
        scope.launch {
            list.animateScrollToItem(0)
            val n = fiveState.value.size
            if (!toCarousel && n > 0) {
                val s = selState.intValue.coerceIn(0, n - 1)
                focusSoon({ posterReqs[s] }) { nav.zone == Zone.HERO }
            } else {
                if (toCarousel) carousel.state.animateScrollToItem(0)
                focusSoon({ if (toCarousel) carousel.firstReq() else carousel.currentReq() }) {
                    nav.zone == Zone.CAROUSEL && (!toCarousel || carousel.isFirstFocused())
                }
            }
        }
    }

    fun toastDown(): Boolean {
        if (undoState.value == null) return false
        scope.launch { focusSoon({ undoReq }) { nav.zone == Zone.TOAST } }
        return true
    }

    fun heroDown(): Boolean = toastDown() || goRows()

    val onHero: () -> Unit = {
        nav.rowLazy = -1
        if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) scope.launch { list.animateScrollToItem(0) }
    }

    /** İzledim (afiş, buton ya da uzun basış). Seçim yeri sabit kalır: sıradaki film aynı yuvaya kayar. */
    fun heroMark(f: Film, slot: Int, fromPill: Boolean) {
        val was = f.key in st.watched
        st.toggleWatched(f)
        undoFocused.value = false
        undoState.value = Undo(f, !was, if (fromPill) pillReq else posterReqs[slot])
        scope.launch {
            withFrameNanos { }
            val n = fiveState.value.size
            if (n == 0) {
                focusSoon({ tabReqs[st.tab.ordinal] }) { nav.tag == "tab" }
                return@launch
            }
            val s = slot.coerceAtMost(n - 1)
            selState.intValue = s
            if (fromPill) focusSoon({ pillReq }) { nav.tag == "pill" }
            else focusSoon({ posterReqs[s] }) { nav.tag == "poster:$s" }
        }
    }

    /** Satırlarda uzun basış: izlendi/izlenmedi. Odak aynı yuvadaki sıradaki karta geçer. */
    fun rowLong(r: RowModel, slot: Int, f: Film) {
        val was = f.key in st.watched
        st.toggleWatched(f)
        undoFocused.value = false
        undoState.value = Undo(f, !was, cardReq(r.id, f.key))
        scope.launch {
            withFrameNanos { }
            val cur = rowsState.value.firstOrNull { it.id == r.id }
            if (cur == null || cur.films.isEmpty()) {
                goRows()
                return@launch
            }
            val k = cur.films[slot.coerceAtMost(cur.films.size - 1)].key
            focusSoon({ cardReq(r.id, k) }) { nav.tag == "${r.id}/$k" }
        }
    }

    fun undoNow() {
        val u = undoState.value ?: return
        st.toggleWatched(u.film)
        undoState.value = null
        undoFocused.value = false
        scope.launch {
            if (!focusSoon({ u.back }) { nav.zone != Zone.TOAST }) goTop(false)
        }
    }

    fun openDetail(f: Film, target: String) {
        HomeMemory.target = target
        HomeMemory.sel = selState.intValue
        HomeMemory.lazy = if (nav.rowLazy >= 0) nav.rowLazy else list.firstVisibleItemIndex
        HomeMemory.signal = homeSignal
        st.screen = Screen.Detail(f.key)
    }

    // İlk odak: Ana Sayfa tuşu ya da ilk açılış galeriye; detaydan dönüş kalınan yere.
    LaunchedEffect(homeSignal) {
        val t = HomeMemory.target
        val fresh = homeSignal != HomeMemory.signal || t == null
        HomeMemory.signal = homeSignal
        HomeMemory.target = null
        when {
            fresh -> {
                if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) list.scrollToItem(0)
                carousel.state.scrollToItem(0)
                focusSoon({ carousel.firstReq() }) { nav.zone == Zone.CAROUSEL }
            }
            t == "hero" -> {
                val n = fiveState.value.size
                if (n > 0) focusSoon({ posterReqs[selState.intValue.coerceIn(0, n - 1)] }) { nav.zone == Zone.HERO }
                else focusSoon({ carousel.firstReq() }) { nav.zone == Zone.CAROUSEL }
            }
            t != null && t.startsWith("row:") -> {
                val parts = t.removePrefix("row:").split("/", limit = 2)
                val rowId = parts[0]
                val key = parts.getOrElse(1) { "" }
                val r = rowsState.value.firstOrNull { it.id == rowId }
                val k = r?.films?.firstOrNull { it.key == key }?.key ?: r?.films?.firstOrNull()?.key
                if (r == null || k == null) goRows() else focusSoon({ cardReq(rowId, k) }) { nav.zone == Zone.ROWS }
            }
        }
    }

    // Uygulamalar ilk açılışta geç gelirse odak öne çıkan uygulamaya geçer (kullanıcı başka yere gitmediyse).
    val hasApps = st.allApps.isNotEmpty()
    val hadAppsAtStart = remember { hasApps }
    LaunchedEffect(hasApps) {
        if (hasApps && !hadAppsAtStart && (nav.zone == Zone.NONE || nav.zone == Zone.CAROUSEL)) {
            carousel.state.scrollToItem(0)
            focusSoon({ carousel.firstReq() }) { carousel.isFirstFocused() }
        }
    }

    BackHandler(enabled = menuFor == null && !about && !gtvInfo) { goTop(toCarousel = true) }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        SunLight(now, flowIn = true, arcPx = { arcRect.value })
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(shift.value.x.dp.roundToPx(), shift.value.y.dp.roundToPx()) },
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
            item(key = "hero", contentType = "hero") {
                Hero(
                    st = st, now = now, nav = nav, carousel = carousel,
                    fiveState = fiveState, selState = selState,
                    posterReqs = posterReqs, tabReqs = tabReqs, pillReq = pillReq,
                    onHero = onHero,
                    onDown = { heroDown() },
                    onMark = { f, slot, pill -> heroMark(f, slot, pill) },
                    onOpen = { f -> openDetail(f, "hero") },
                    onMenu = { menuFor = it },
                    onMeasure = { measureArc() },
                )
            }
            val rs = rowsState.value
            val first = rs.indexOfFirst { it.films.isNotEmpty() }
            itemsIndexed(rs, key = { _, r -> "row:" + r.id }, contentType = { _, _ -> "row" }) { i, r ->
                FilmRow(
                    st = st, r = r, state = HomeMemory.rowState(r.id), isFirst = i == first,
                    reqFor = { k -> cardReq(r.id, k) },
                    onCard = { f ->
                        nav.zone = Zone.ROWS
                        nav.tag = "${r.id}/${f.key}"
                        st.ensure(f)
                        val li = i + 1
                        if (nav.rowLazy != li) {
                            nav.rowLazy = li
                            scope.launch { list.animateScrollToItem(li) }
                        }
                    },
                    onOpen = { f -> openDetail(f, "row:${r.id}/${f.key}") },
                    onLong = { slot, f -> rowLong(r, slot, f) },
                    up = { goTop(false); true },
                    down = { toastDown() },
                )
            }
            item(key = "foot", contentType = "foot") {
                Footer(
                    st, footReq,
                    onFocus = { nav.zone = Zone.FOOT; nav.tag = "foot"; nav.rowLazy = -1 },
                    onAbout = { about = true },
                    onGoogleTv = { if (!(st.googleTvAvailable() && st.openGoogleTv())) gtvInfo = true },
                )
            }
        }
        UndoToast(
            state = undoState, focused = undoFocused, req = undoReq,
            onUndo = { undoNow() },
            onFocus = { nav.zone = Zone.TOAST; nav.tag = "toast" },
            onUp = { u -> scope.launch { focusSoon({ u.back }) { nav.zone != Zone.TOAST } } },
            modifier = Modifier.align(Alignment.BottomStart),
        )
        menuFor?.let { pkg -> AppMenu(st, pkg, onClose = { menuFor = null }) }
        if (about) AboutOverlay(onClose = { about = false })
        if (gtvInfo) GoogleTvOverlay(onClose = { gtvInfo = false })
    }
}

// ---------------- arka plan ----------------

/** Eski, saat dilimine göre sabit gri ışık. Ana ekranda artık SunLight kullanılıyor. */
@Composable
fun TodLight(tod: Tod) {
    val (pos, col) = when (tod) {
        Tod.SABAH -> Offset(0.2f, 0.18f) to Color(0xFF1A1A1A)
        Tod.OGLE -> Offset(0.5f, 0f) to Color(0xFF1E1E1E)
        Tod.AKSAM -> Offset(0.82f, 0.26f) to Color(0xFF151515)
        Tod.GECE -> Offset(0.5f, 1f) to Color(0xFF0B0B0B)
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    0f to col, 0.55f to Color(0xFF050505), 1f to Color.Black,
                    center = Offset(pos.x * size.width, pos.y * size.height),
                    radius = size.width * 0.75f,
                ),
            )
        },
    )
}

@Composable
fun rememberClock(): Calendar {
    val c by produceState(Calendar.getInstance()) {
        while (true) {
            value = Calendar.getInstance()
            delay(((60 - value.get(Calendar.SECOND)) * 1000L).coerceAtLeast(1000L))
        }
    }
    return c
}

// ---------------- üst ekran ----------------

@Composable
private fun Hero(
    st: SakaState,
    now: Calendar,
    nav: Nav,
    carousel: CarouselCtl,
    fiveState: State<List<Film>>,
    selState: MutableIntState,
    posterReqs: List<FocusRequester>,
    tabReqs: List<FocusRequester>,
    pillReq: FocusRequester,
    onHero: () -> Unit,
    onDown: () -> Boolean,
    onMark: (Film, Int, Boolean) -> Unit,
    onOpen: (Film) -> Unit,
    onMenu: (String) -> Unit,
    onMeasure: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(720.dp)
            .onGloballyPositioned { nav.hero = it; onMeasure() },
    ) {
        Row(Modifier.offset(72.dp, 50.dp), verticalAlignment = Alignment.Bottom) {
            BasicText("Şaka TV", style = serif(22, FontWeight.Medium))
            Spacer(Modifier.width(12.dp))
            BasicText("HRP", style = inter(13, FontWeight.Medium, C.muted, ls = 3f), modifier = Modifier.padding(bottom = 3.dp))
        }

        Row(Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 72.dp)) {
            SunArc(now, Modifier.padding(top = 6.dp), onPlaced = { nav.arc = it; onMeasure() })
            Spacer(Modifier.width(22.dp))
            Column(horizontalAlignment = Alignment.End) {
                BasicText(pad(now.get(Calendar.HOUR_OF_DAY)) + ":" + pad(now.get(Calendar.MINUTE)), style = inter(44, FontWeight.Light, ls = -1f))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + now.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[now.get(Calendar.MONTH)],
                    style = inter(18, color = C.muted),
                )
                st.nowPlaying?.let { np ->
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "♪  " + np.title + (if (np.artist.isNotBlank()) " · " + np.artist else ""),
                        style = inter(16, color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(420.dp),
                    )
                }
            }
        }

        // Şaka: sola basınca seçilir, OK ile ruh hali değişir.
        val mood = sakaMood(st.tod, st.sakaPokes)
        Focusable(
            onClick = { st.sakaPokes++ },
            modifier = Modifier.offset(72.dp, 150.dp),
            onFocus = { nav.zone = Zone.HERO; nav.tag = "saka"; onHero() },
        ) { f ->
            Saka(
                size = 200.dp, tod = st.tod, mood = mood,
                line = sakaLine(mood, st.tod, st.sakaPokes),
                lineColor = if (f) C.fg else Color(0xFFBDBDBD),
                look = if (f) 4f else 0f,
            )
        }

        // Dönen galeri: Şaka'nın sağından ekranın sağ kenarına kadar akar.
        AppCarousel(
            st, carousel,
            onFocus = { nav.zone = Zone.CAROUSEL; nav.tag = "carousel"; onHero() },
            onMenu = onMenu,
            modifier = Modifier.offset(294.dp, 196.dp).width(986.dp),
        )

        Film5(
            st, nav, fiveState, selState, posterReqs, tabReqs, pillReq, onHero, onDown, onMark, onOpen,
            Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 52.dp),
        )
    }
}

// ---------------- beşli film seçici ----------------

@Composable
private fun Film5(
    st: SakaState,
    nav: Nav,
    fiveState: State<List<Film>>,
    selState: MutableIntState,
    posterReqs: List<FocusRequester>,
    tabReqs: List<FocusRequester>,
    pillReq: FocusRequester,
    onHero: () -> Unit,
    onDown: () -> Boolean,
    onMark: (Film, Int, Boolean) -> Unit,
    onOpen: (Film) -> Unit,
    modifier: Modifier,
) {
    val five = fiveState.value.map { st.film(it) }
    val n = five.size
    val s = selState.intValue.coerceIn(0, maxOf(0, n - 1))
    val cur = five.getOrNull(s)
    LaunchedEffect(cur?.key) { cur?.let { st.ensure(it) } }
    val top = five.maxOfOrNull { it.rating } ?: 0.0
    // Afiş yoksa sekmeler en alttaki öğedir: aşağı yine satırlara gider.
    val emptyDown: () -> Boolean = { if (fiveState.value.isEmpty()) onDown() else false }
    fun heroFocus(tag: String) { nav.zone = Zone.HERO; nav.tag = tag; onHero() }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FilmTab.entries.forEachIndexed { i, t ->
                    LinkText(
                        t.label, { st.tab = t; st.page = 0; selState.intValue = 0 },
                        selected = st.tab == t, fontSize = 18,
                        onFocus = { heroFocus("tab") },
                        requester = tabReqs.getOrNull(i),
                        modifier = Modifier.dpad(down = emptyDown),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LinkText(
                    if (st.compare) "Karşılaştırmayı kapat" else "Karşılaştır", { st.compare = !st.compare },
                    selected = st.compare, onFocus = { heroFocus("compare") }, modifier = Modifier.dpad(down = emptyDown),
                )
                LinkText(
                    "Yenile ↻", { st.page++; selState.intValue = 0 },
                    onFocus = { heroFocus("refresh") }, modifier = Modifier.dpad(down = emptyDown),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(420.dp)) {
                if (cur != null) {
                    BasicText(cur.title, style = serif(36), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        (if (cur.rating > 0) "★ " + "%.1f".format(cur.rating) + "   " else "") +
                            listOf(if (cur.isTv) "Dizi" else "", cur.year, cur.genres, cur.runtimeText).filter { it.isNotBlank() }.joinToString(" · "),
                        style = inter(17, color = C.muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        cur.overview, style = inter(18, color = C.fg.copy(alpha = .82f)).copy(lineHeight = 26.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill("▶  Fragman", { st.playTrailer(cur) }, solid = true, onFocus = { heroFocus("trailer") }, modifier = Modifier.dpad(down = onDown))
                        Pill("İzledim", { onMark(cur, s, true) }, onFocus = { heroFocus("pill") }, requester = pillReq, modifier = Modifier.dpad(down = onDown))
                        Pill("Detay", { onOpen(cur) }, onFocus = { heroFocus("detail") }, modifier = Modifier.dpad(down = onDown))
                    }
                } else {
                    val src = when (st.tab) {
                        FilmTab.KLASIK -> st.archive
                        FilmTab.YENI -> st.fresh
                        FilmTab.POPULER -> st.popular
                        else -> st.tvFresh + st.tvPopular
                    }
                    val (t, b) = when {
                        src.isNotEmpty() -> "Hepsini izledin" to "Liste bitti. Yeniler eklendikçe burada görünecek."
                        st.tab == FilmTab.KLASIK -> "Yükleniyor" to ""
                        !st.films.hasTmdb -> st.tab.label to "TMDB anahtarı eklenince her gün güncellenir: afiş, Türkçe özet, puan ve resmi fragman."
                        else -> "Bağlantı bekleniyor" to "İnternet gelince liste kendini yeniler."
                    }
                    BasicText(t, style = serif(36))
                    Spacer(Modifier.height(10.dp))
                    BasicText(b, style = inter(18, color = C.fg.copy(alpha = .82f)), maxLines = 3)
                }
            }
            Spacer(Modifier.width(36.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End), verticalAlignment = Alignment.Bottom) {
                five.forEachIndexed { i, f ->
                    HeroPoster(
                        f, selected = i == s, compare = st.compare, best = top > 0 && f.rating == top,
                        req = posterReqs[i],
                        onFocus = { selState.intValue = i; heroFocus("poster:$i") },
                        onClick = { onOpen(f) },
                        onLong = { onMark(f, i, false) },
                        onDown = onDown,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPoster(
    f: Film,
    selected: Boolean,
    compare: Boolean,
    best: Boolean,
    req: FocusRequester,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLong: () -> Unit,
    onDown: () -> Boolean,
) {
    Focusable(onClick = onClick, modifier = Modifier.dpad(down = onDown), onLong = onLong, onFocus = onFocus, requester = req) { foc ->
        val k = animateFloatAsState(if (foc) 1f else 0f, tween(DUR, easing = EASE), label = "hf")
        val al = animateFloatAsState(if (selected) 1f else 0.42f, tween(DUR, easing = EASE), label = "ha")
        Column(Modifier.width(118.dp)) {
            PosterImage(
                f, 118, 177, color = selected,
                modifier = Modifier
                    .graphicsLayer {
                        val v = k.value
                        val sc = 1f + 0.06f * v
                        scaleX = sc; scaleY = sc; alpha = al.value
                        translationY = -6.dp.toPx() * v
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .then(if (foc) Modifier.ring() else Modifier),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                f.title,
                style = inter(15, if (foc) FontWeight.Medium else FontWeight.Normal, if (foc) C.fg else if (selected) C.fg.copy(alpha = .8f) else C.muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val cap = if (compare) {
                "★ " + "%.1f".format(f.rating) + (if (f.runtimeText.isNotBlank()) " · " + f.runtimeText else "")
            } else filmCaption(f)
            BasicText(
                cap,
                style = inter(13, if (compare && best) FontWeight.SemiBold else FontWeight.Normal, if (compare && best) C.fg else C.dim),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------- aşağıdaki satırlar ----------------

private fun buildRows(st: SakaState): List<RowModel> {
    val w = st.watched
    val out = ArrayList<RowModel>(8)
    val tmdbEmpty = if (!st.films.hasTmdb) "TMDB anahtarı eklenince burası dolacak. Kurulum notuna bak."
    else "Bağlantı bekleniyor. İnternet gelince liste kendini yeniler."
    fun unseen(l: List<Film>): List<Film> = l.asSequence().filter { it.key !in w }.distinctBy { it.key }.toList()
    fun tmdbRow(id: String, title: String, sub: String, src: List<Film>) {
        val l = unseen(src)
        out += RowModel(id, title, sub, l, if (l.isNotEmpty()) null else if (src.isNotEmpty()) "Bu listedekilerin hepsini izledin." else tmdbEmpty)
    }

    val mine = unseen(st.forYou())
    if (mine.isNotEmpty()) out += RowModel("foryou", "Senin için", "izlediklerine göre arşivden", mine)
    val classics = unseen(st.archive).take(40)
    if (classics.isNotEmpty()) out += RowModel("klasik", "Klasikler", "IMDb ilk 500 · izlemediklerin", classics)
    val cal = Calendar.getInstance()
    val month = MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR)
    tmdbRow("yeni", "Bu ay yeni", "$month · vizyon ve dijital", st.fresh)
    tmdbRow("yenidizi", "Yeni diziler", "Türkiye'de yeni ve yayında", st.tvFresh)
    tmdbRow("populer", "Bu hafta popüler", "Türkiye'de en çok izlenen filmler", st.popular)
    tmdbRow("popdizi", "Popüler diziler", "bu hafta en çok izlenenler", st.tvPopular)
    val listed = st.myListFilms().filter { it.key !in w }.distinctBy { it.key }
    if (listed.isNotEmpty()) out += RowModel("listem", "Listem", "sonra izlemek için ayırdıkların", listed)
    val seen = st.watchedFilms().distinctBy { it.key }
    if (seen.isNotEmpty()) out += RowModel("izlenen", "İzlediklerin", "${seen.size} kayıt · bir daha önerilmez", seen, badge = "✓")
    return out
}

@Composable
private fun FilmRow(
    st: SakaState,
    r: RowModel,
    state: LazyListState,
    isFirst: Boolean,
    reqFor: (String) -> FocusRequester,
    onCard: (Film) -> Unit,
    onOpen: (Film) -> Unit,
    onLong: (Int, Film) -> Unit,
    up: () -> Boolean,
    down: () -> Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp)) {
        SectionTitle(r.title, r.sub, Modifier.padding(horizontal = 72.dp))
        if (r.films.isEmpty()) {
            BasicText(
                r.empty ?: "", style = inter(17, color = C.muted),
                modifier = Modifier.padding(start = 72.dp, top = 16.dp).height(40.dp),
            )
        } else {
            Spacer(Modifier.height(4.dp))
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = 72.dp, end = 72.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(r.films, key = { _, f -> f.key }, contentType = { _, _ -> "card" }) { i, raw ->
                    val f = st.film(raw)
                    PosterCard(
                        f,
                        onClick = { onOpen(f) },
                        badge = r.badge,
                        onLong = { onLong(i, f) },
                        onFocus = { onCard(f) },
                        requester = reqFor(f.key),
                        modifier = Modifier.dpad(up = if (isFirst) up else null, down = down),
                    )
                }
            }
        }
    }
}

// ---------------- alt kısım ----------------

@Composable
private fun Footer(st: SakaState, footReq: FocusRequester, onFocus: () -> Unit, onAbout: () -> Unit, onGoogleTv: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(start = 72.dp, end = 72.dp, top = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LinkText("Ayarlar", { st.apps.openSettings() }, onFocus = onFocus, requester = footReq)
                LinkText("Ekran koruyucu", { st.screen = Screen.Saver }, onFocus = onFocus)
                LinkText("Yenile", { st.reloadApps(); scope.launch { st.refreshOnline() } }, onFocus = onFocus)
                LinkText("Hakkında", onAbout, onFocus = onFocus)
                LinkText("Google TV'ye dön", onGoogleTv, onFocus = onFocus)
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Film verileri TMDB ve IMDb veri setlerinden gelir. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                style = inter(13, color = C.dim), modifier = Modifier.width(640.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("Sayfanın sonu. Şaka burada uyuyor.", style = inter(16, color = C.muted), modifier = Modifier.padding(bottom = 18.dp))
            Spacer(Modifier.width(14.dp))
            Saka(150.dp, Tod.GECE, Mood.UYKU, zColor = C.muted)
        }
    }
}

/** İzledim sonrası alttaki geçici satır. ~3 sn kalır; odaktayken kaybolmaz. OK: geri al. */
@Composable
private fun UndoToast(
    state: MutableState<Undo?>,
    focused: MutableState<Boolean>,
    req: FocusRequester,
    onUndo: () -> Unit,
    onFocus: () -> Unit,
    onUp: (Undo) -> Unit,
    modifier: Modifier,
) {
    val u = state.value ?: return
    val isFocused = focused.value
    LaunchedEffect(u, isFocused) {
        if (!isFocused) {
            delay(3000)
            if (state.value === u) state.value = null
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))),
    ) {
        Row(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                (if (u.nowWatched) "✓  " else "") + u.film.title,
                style = inter(16, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            BasicText(
                if (u.nowWatched) "  izlendi olarak işaretlendi  ·  " else "  izlediklerinden çıkarıldı  ·  ",
                style = inter(16, color = C.muted),
            )
            LinkText(
                "Geri al", onUndo, fontSize = 16, requester = req, onFocus = onFocus,
                modifier = Modifier
                    .onFocusChanged { focused.value = it.hasFocus }
                    .dpad(up = { onUp(u); true }, down = { true }, left = { true }, right = { true }),
            )
        }
    }
}

// ---------------- katmanlar ----------------

@Composable
fun AppMenu(st: SakaState, pkg: String, onClose: () -> Unit) {
    val a = st.app(pkg)
    val inDock = pkg in st.dock
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Overlay(onClose) {
        BasicText(a?.label ?: pkg, style = serif(30))
        if (st.isFeatured(pkg)) {
            Spacer(Modifier.height(6.dp))
            BasicText("Öne çıkan uygulama, galeride hep ilk sırada.", style = inter(16, color = C.muted))
        }
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Aç", { onClose(); st.launch(pkg) }, requester = first)
            Pill(if (inDock) "Sıradan çıkar" else "Öne al", { st.toggleDock(pkg); onClose() })
            if (inDock) {
                Pill("← Sola taşı", { st.moveDock(pkg, -1) })
                Pill("Sağa taşı →", { st.moveDock(pkg, 1) })
            }
            Pill("Uygulama bilgisi", { onClose(); st.apps.appInfo(pkg) })
            Pill("Kapat", onClose)
        }
    }
}

@Composable
private fun AboutOverlay(onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Overlay(onClose) {
        BasicText("Şaka TV · HRP", style = serif(30))
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Reklamsız, sade bir başlatıcı. Film verileri TMDB API ve IMDb veri setlerinden gelir; " +
                "This product uses the TMDB API but is not endorsed or certified by TMDB.\n\n" +
                "Yazı tipleri: Inter ve Fraunces, SIL Open Font License 1.1.",
            style = inter(17, color = C.muted).copy(lineHeight = 26.sp),
            modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(20.dp))
        Pill("Kapat", onClose, requester = first)
    }
}

@Composable
private fun GoogleTvOverlay(onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Overlay(onClose) {
        BasicText("Google TV kapalı", style = serif(30))
        Spacer(Modifier.height(14.dp))
        BasicText(
            "Google TV tamamen kapatıldı, bu yüzden arka planda ne RAM ne de işlemci harcıyor. " +
                "Geri açmak için Mac'te şu komutu çalıştırman yeterli:",
            style = inter(17, color = C.muted).copy(lineHeight = 26.sp),
            modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(12.dp))
        BasicText("./tools/tv.sh google-ac", style = inter(18, FontWeight.Medium))
        Spacer(Modifier.height(12.dp))
        BasicText("Tekrar kapatmak istersen:", style = inter(17, color = C.muted))
        Spacer(Modifier.height(6.dp))
        BasicText("./tools/tv.sh google-kapat", style = inter(18, FontWeight.Medium))
        Spacer(Modifier.height(22.dp))
        Pill("Tamam", onClose, requester = first)
    }
}

@Composable
fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Ayrı pencere: odak içeride kalır, Geri tuşu kapatır.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0D0D0D))
                .border(1.dp, C.line, RoundedCornerShape(20.dp))
                .padding(36.dp),
        ) { content() }
    }
}
