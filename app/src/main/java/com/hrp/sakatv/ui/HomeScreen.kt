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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hrp.sakatv.data.Film
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(st: SakaState, homeSignal: Int) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dockFocus = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }

    // Plazma koruması: 2 dakikada bir 3dp kayan içerik.
    val shift by produceState(Offset.Zero) {
        val steps = listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3, -3 to 3, -3 to 0, -3 to -3, 0 to -3)
        var i = 0
        while (true) { value = Offset(steps[i].first.toFloat(), steps[i].second.toFloat()); i = (i + 1) % steps.size; delay(120_000) }
    }

    fun toTop() { scope.launch { list.animateScrollToItem(0); runCatching { dockFocus.requestFocus() } } }

    LaunchedEffect(homeSignal, st.dock.isNotEmpty()) {
        if (st.dock.isNotEmpty() || st.allApps.isNotEmpty()) {
            list.scrollToItem(0)
            delay(50)
            runCatching { dockFocus.requestFocus() }
        }
    }
    BackHandler(enabled = menuFor == null && !about) { toTop() }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        TodLight(st.tod)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().offset(shift.x.dp, shift.y.dp),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
            item(key = "hero") { Hero(st, dockFocus, onHero = { if (list.firstVisibleItemIndex != 0 || list.firstVisibleItemScrollOffset != 0) scope.launch { list.animateScrollToItem(0) } }, onMenu = { menuFor = it }) }
            item(key = "rows") { Rows(st) }
            item(key = "apps") { AppsGrid(st, onMenu = { menuFor = it }) }
            item(key = "foot") { Footer(st, onAbout = { about = true }) }
        }
        menuFor?.let { pkg -> AppMenu(st, pkg, onClose = { menuFor = null }) }
        if (about) AboutOverlay(onClose = { about = false })
    }
}

// ---------------- arka plan ----------------

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
private fun Hero(st: SakaState, dockFocus: FocusRequester, onHero: () -> Unit, onMenu: (String) -> Unit) {
    val now = rememberClock()
    Box(Modifier.fillMaxWidth().height(720.dp)) {
        Row(Modifier.offset(72.dp, 50.dp), verticalAlignment = Alignment.Bottom) {
            BasicText("Şaka TV", style = serif(22, FontWeight.Medium))
            Spacer(Modifier.width(12.dp))
            BasicText("HRP", style = inter(13, FontWeight.Medium, C.muted, ls = 3f), modifier = Modifier.padding(bottom = 3.dp))
        }

        Row(Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 72.dp)) {
            SunArc(st.tod, now, Modifier.padding(top = 6.dp))
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
        Focusable(onClick = { st.sakaPokes++ }, modifier = Modifier.offset(72.dp, 150.dp), onFocus = onHero) { f ->
            Saka(
                size = 200.dp, tod = st.tod, mood = mood,
                line = sakaLine(mood, st.tod, st.sakaPokes),
                lineColor = if (f) C.fg else Color(0xFFBDBDBD),
                look = if (f) 4f else 0f,
            )
        }

        Dock(st, dockFocus, onHero, onMenu, Modifier.offset(310.dp, 214.dp).padding(end = 72.dp))

        Film5(st, onHero, Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 44.dp))
    }
}

@Composable
private fun SunArc(tod: Tod, now: Calendar, modifier: Modifier) {
    // Gündüz güneş doğudan batıya akar, gece ay tepede durur.
    val h = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f
    val night = tod == Tod.GECE
    val ang = if (night) 90.0 else (180.0 - ((h - 6f).coerceIn(0f, 14f) / 14f) * 180.0)
    Box(
        modifier.size(120.dp, 60.dp).drawBehind {
            val r = size.width / 2f
            drawArc(C.line, 180f, 180f, false, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, size.width),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            val a = Math.toRadians(ang)
            val c = Offset(r + r * cos(a).toFloat(), r - r * sin(a).toFloat())
            if (night) {
                drawCircle(Color.Black, 4.dp.toPx(), c)
                drawCircle(C.fg, 4.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            } else {
                drawCircle(Color.White.copy(alpha = 0.35f), 8.dp.toPx(), c)
                drawCircle(C.fg, 4.dp.toPx(), c)
            }
        },
    )
}

@Composable
private fun Dock(st: SakaState, first: FocusRequester, onHero: () -> Unit, onMenu: (String) -> Unit, modifier: Modifier) {
    Row(modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val pins = st.dock
        pins.forEachIndexed { i, pkg ->
            val a = st.app(pkg)
            AppItem(
                app = a, label = a?.label ?: pkg, glyph = glyphFor(a?.label ?: "?"),
                onClick = { st.apps.launch(pkg) }, onLong = { onMenu(pkg) }, onFocus = onHero,
                requester = if (i == 0) first else null,
            )
        }
        AppItem(app = null, label = "Mağaza", glyph = "+", onClick = { st.apps.openStore() }, onFocus = onHero, outlined = true,
            requester = if (pins.isEmpty()) first else null)
        AppItem(app = null, label = "Tümü", glyph = "⋯", onClick = { st.screen = Screen.AllApps }, onFocus = onHero, outlined = true)
    }
}

// ---------------- beşli film seçici ----------------

@Composable
private fun Film5(st: SakaState, onHero: () -> Unit, modifier: Modifier) {
    val five = st.five().map { st.film(it) }
    var sel by remember { mutableIntStateOf(0) }
    val s = sel.coerceIn(0, maxOf(0, five.size - 1))
    LaunchedEffect(five.getOrNull(s)?.key) { five.getOrNull(s)?.let { st.ensure(it) } }
    val cur = five.getOrNull(s)
    val top = five.maxOfOrNull { it.rating } ?: 0.0

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FilmTab.entries.forEach { t ->
                    LinkText(t.label, { st.tab = t; st.page = 0; sel = 0 }, selected = st.tab == t, fontSize = 18, onFocus = onHero)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LinkText(if (st.compare) "Karşılaştırmayı kapat" else "Karşılaştır", { st.compare = !st.compare }, selected = st.compare, onFocus = onHero)
                LinkText("Yenile ↻", { st.page++; sel = 0 }, onFocus = onHero)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.width(420.dp)) {
                if (cur != null) {
                    BasicText(cur.title, style = serif(36), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    BasicText(
                        "★ " + "%.1f".format(cur.rating) + "   " + listOf(cur.year, cur.genres, cur.runtimeText).filter { it.isNotBlank() }.joinToString(" · "),
                        style = inter(17, color = C.muted), maxLines = 1,
                    )
                    Spacer(Modifier.height(10.dp))
                    BasicText(cur.overview, style = inter(18, color = C.fg.copy(alpha = .82f)).copy(lineHeight = 26.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Pill("▶  Fragman", { st.playTrailer(cur) }, solid = true, onFocus = onHero)
                        Pill("İzledim", { st.toggleWatched(cur); sel = 0 }, onFocus = onHero)
                        Pill("Detay", { st.screen = Screen.Detail(cur.key) }, onFocus = onHero)
                    }
                } else {
                    val (t, b) = when {
                        st.tab == FilmTab.KLASIK && st.archive.isNotEmpty() -> "Hepsini izledin" to "Liste bitti. Yeni filmler eklendikçe burada görünecek."
                        st.tab == FilmTab.KLASIK -> "Yükleniyor" to ""
                        !st.films.hasTmdb -> (if (st.tab == FilmTab.YENI) "Bu ay vizyonda" else "Bu hafta popüler") to
                            "TMDB anahtarı eklenince her gün güncellenir: afiş, Türkçe özet, puan ve resmi fragman."
                        else -> "Bağlantı bekleniyor" to "İnternet gelince liste kendini yeniler."
                    }
                    BasicText(t, style = serif(36))
                    Spacer(Modifier.height(10.dp))
                    BasicText(b, style = inter(18, color = C.fg.copy(alpha = .82f)), maxLines = 3)
                }
            }
            Spacer(Modifier.width(36.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.End), verticalAlignment = Alignment.Bottom) {
                five.forEachIndexed { n, f ->
                    val isSel = n == s
                    Focusable(
                        onClick = { st.screen = Screen.Detail(f.key) },
                        onFocus = { sel = n; onHero() },
                        onLong = { st.toggleWatched(f) },
                    ) { foc ->
                        val sc by animateFloatAsState(if (foc) 1.06f else 1f, tween(DUR, easing = EASE), label = "fs")
                        val al by animateFloatAsState(if (isSel) 1f else 0.42f, tween(DUR, easing = EASE), label = "fa")
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PosterImage(
                                f, 118, 177, color = isSel,
                                modifier = Modifier
                                    .graphicsLayer {
                                        scaleX = sc; scaleY = sc; alpha = al
                                        translationY = if (foc) -6.dp.toPx() else 0f
                                        transformOrigin = TransformOrigin(0.5f, 1f)
                                    }
                                    .then(if (foc) Modifier.ring() else Modifier),
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.size(24.dp, 2.dp).clip(CircleShape).background(C.fg.copy(alpha = if (isSel && !foc) .4f else 0f)))
                            if (st.compare) {
                                BasicText(
                                    "★ " + "%.1f".format(f.rating) + (if (f.runtimeText.isNotBlank()) " · " + f.runtimeText else ""),
                                    style = inter(16, FontWeight.Medium, if (f.rating == top) C.fg else C.muted),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- aşağıdaki satırlar ----------------

@Composable
private fun Rows(st: SakaState) {
    val known = remember(st.archive, st.fresh, st.popular) { (st.archive + st.fresh + st.popular).distinctBy { it.key } }
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(44.dp)) {
        val classics = st.archive.filter { it.key !in st.watched }.take(30)
        FilmRow(st, "Klasikler", "IMDb ilk 500 · izlemediklerin", classics) { "★ " + "%.1f".format(it.rating) + " · " + it.year }
        val fresh = st.fresh.filter { it.key !in st.watched }
        val month = MONTHS[Calendar.getInstance().get(Calendar.MONTH)] + " " + Calendar.getInstance().get(Calendar.YEAR)
        FilmRow(st, "Bu ay yeni", "$month · vizyon ve dijital", fresh, empty = emptyText(st)) { "TMDB · " + it.year }
        FilmRow(st, "Bu hafta popüler", "Türkiye'de en çok izlenenler", st.popular.filter { it.key !in st.watched }, empty = emptyText(st)) {
            "★ " + "%.1f".format(it.rating)
        }
        val mine = known.filter { it.key in st.myList && it.key !in st.watched }
        if (mine.isNotEmpty()) FilmRow(st, "Listem", "sonra izlemek için ayırdıkların", mine) { "★ " + "%.1f".format(it.rating) }
        val seen = known.filter { it.key in st.watched }
        if (seen.isNotEmpty()) {
            FilmRow(st, "İzlediklerin", "${seen.size} film · bir daha önerilmez", seen, badge = "✓", clickUnwatch = true) { "Geri almak için OK" }
        }
    }
}

private fun emptyText(st: SakaState) =
    if (!st.films.hasTmdb) "TMDB anahtarı eklenince burası dolacak. Kurulum notuna bak." else "Bağlantı bekleniyor."

@Composable
private fun FilmRow(
    st: SakaState, title: String, sub: String, films: List<Film>,
    empty: String? = null, badge: String? = null, clickUnwatch: Boolean = false,
    caption: (Film) -> String,
) {
    Column {
        SectionTitle(title, sub)
        Spacer(Modifier.height(16.dp))
        if (films.isEmpty()) {
            BasicText(empty ?: "", style = inter(17, color = C.muted), modifier = Modifier.height(60.dp))
        } else {
            LazyRow(
                Modifier.height(236.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 10.dp),
            ) {
                items(films.distinctBy { it.key }, key = { it.key }) { raw ->
                    val f = st.film(raw)
                    PosterCard(
                        f, caption(f),
                        onClick = {
                            if (clickUnwatch) st.toggleWatched(f) else { st.screen = Screen.Detail(f.key) }
                        },
                        badge = badge,
                        onLong = { st.toggleList(f) },
                    )
                }
            }
        }
    }
}

// ---------------- uygulamalar ----------------

@Composable
private fun AppsGrid(st: SakaState, onMenu: (String) -> Unit) {
    val recent = remember(st.allApps) { st.allApps.sortedByDescending { st.apps.lastUsed(it.pkg) }.take(16) }
    var hint by remember { mutableStateOf("Uzun bas: dock'a ekle, sırala, kaldır") }
    Column(Modifier.padding(start = 72.dp, end = 72.dp, top = 52.dp)) {
        SectionTitle("Uygulamalar", "Google TV'deki tüm uygulamaların burada", right = hint)
        Spacer(Modifier.height(18.dp))
        // Hücre sırası: 0 = mağaza, 1..recent.size = son kullanılanlar, son = tümünü gör.
        val total = recent.size + 2
        (0 until total).chunked(9).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                row.forEach { i ->
                    when (i) {
                        0 -> AppItem(null, "Mağazada ara", "⌕", { st.apps.openStore() }, alwaysLabel = true, outlined = true, onFocus = { hint = "OK: Google Play" })
                        total - 1 -> AppItem(null, "Tümünü gör", "→", { st.screen = Screen.AllApps }, alwaysLabel = true, outlined = true, onFocus = { hint = "OK: tüm uygulamalar ekranı" })
                        else -> {
                            val a = recent[i - 1]
                            AppItem(a, a.label, glyphFor(a.label), { st.apps.launch(a.pkg) }, onLong = { onMenu(a.pkg) }, alwaysLabel = true,
                                onFocus = { hint = "OK: aç · Uzun bas: dock'a ekle, sırala, kaldır" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(st: SakaState, onAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 72.dp, top = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                LinkText("Ayarlar", { st.apps.openSettings() })
                LinkText("Ekran koruyucu", { st.screen = Screen.Saver })
                LinkText("Yenile", { st.reloadApps(); scope.launch { st.refreshOnline() } })
                LinkText("Hakkında", onAbout)
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

// ---------------- katmanlar ----------------

@Composable
fun AppMenu(st: SakaState, pkg: String, onClose: () -> Unit) {
    val a = st.app(pkg)
    val inDock = pkg in st.dock
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Overlay(onClose) {
        BasicText(a?.label ?: pkg, style = serif(30))
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Aç", { onClose(); st.apps.launch(pkg) }, requester = first)
            Pill(if (inDock) "Dock'tan kaldır" else "Dock'a ekle", { st.toggleDock(pkg); onClose() })
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
fun Overlay(onClose: () -> Unit, content: @Composable () -> Unit) {
    // Ayrı pencere: odak içeride kalır, Geri tuşu kapatır.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(Color(0xFF0D0D0D))
                .border(1.dp, C.line, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .padding(36.dp),
        ) { content() }
    }
}
