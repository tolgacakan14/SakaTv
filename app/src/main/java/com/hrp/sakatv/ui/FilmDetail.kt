package com.hrp.sakatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun FilmDetail(st: SakaState, key: String) {
    val raw = st.byKey(key)
    BackHandler { st.screen = Screen.Home }
    if (raw == null) { LaunchedEffect(Unit) { st.screen = Screen.Home }; return }
    LaunchedEffect(key) { st.ensure(raw, full = true) }
    val f = st.film(raw)
    var where by remember(key) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var showWhere by remember(key) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(key) { delay(60); runCatching { first.requestFocus() } }
    val watched = f.key in st.watched
    val listed = f.key in st.myList

    val now = rememberClock()
    val genre = f.genres.split(",").firstOrNull()?.trim().orEmpty()
    val pool = if (f.isTv) st.tvPopular + st.tvFresh else st.archive
    val sims = remember(f.key, genre, pool, st.watched) {
        val open = pool.filter { it.key != f.key && it.key !in st.watched }.distinctBy { it.key }
        open.filter { genre.isNotBlank() && it.genres.contains(genre) }.ifEmpty { open }.take(6)
    }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        SunLight(now)
        Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 72.dp, top = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            LinkText("← Ana ekran", { st.screen = Screen.Home })
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText("Şaka TV", style = serif(22, FontWeight.Medium))
                Spacer(Modifier.width(10.dp))
                BasicText("HRP", style = inter(13, FontWeight.Medium, C.muted, ls = 3f))
            }
        }

        PosterImage(f, 260, 390, color = true, large = true, modifier = Modifier.offset(72.dp, 100.dp))

        Column(Modifier.offset(380.dp, 100.dp).width(828.dp)) {
            BasicText(f.title, style = serif(48), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.original.isNotBlank() && f.original != f.title) {
                Spacer(Modifier.height(8.dp)); BasicText(f.original, style = inter(17, color = C.muted))
            }
            Spacer(Modifier.height(12.dp))
            BasicText(
                (if (f.rating > 0) "★ " + "%.1f".format(f.rating) + "   " else "") + listOf(if (f.isTv) "Dizi" else "", f.year, f.genres, f.runtimeText).filter { it.isNotBlank() }.joinToString(" · "),
                style = inter(17, color = C.muted),
            )
            Spacer(Modifier.height(16.dp))
            Column(Modifier.height(168.dp)) {
                if (!showWhere) {
                    BasicText(f.overview, style = inter(18, color = C.fg.copy(alpha = .86f)).copy(lineHeight = 25.sp), maxLines = 4,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.width(760.dp))
                    Spacer(Modifier.height(12.dp))
                    if (f.director.isNotBlank()) BasicText("Yönetmen   " + f.director, style = inter(17))
                    if (f.cast.isNotBlank()) BasicText("Oyuncular   " + f.cast, style = inter(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    BasicText("Türkiye'de nerede izlenir", style = inter(17, FontWeight.Medium, C.muted))
                    val w = where
                    when {
                        w == null -> BasicText("Bakılıyor…", style = inter(18), modifier = Modifier.padding(top = 4.dp))
                        w.isEmpty() -> BasicText(
                            if (st.films.hasTmdb) "Şu an Türkiye'de dijital bir seçenek görünmüyor." else "Bu bilgi için TMDB anahtarı gerekli.",
                            style = inter(18), modifier = Modifier.padding(top = 4.dp),
                        )
                        else -> w.take(4).forEach { (p, k) -> BasicText("$p · $k", style = inter(18), modifier = Modifier.padding(top = 4.dp)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    BasicText("Bilgi TMDB'den canlı gelir (Türkiye)", style = inter(16, color = C.muted))
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Pill("▶  Fragmanı izle", { st.playTrailer(f) }, solid = true, requester = first)
                Pill(if (showWhere) "Özete dön" else "Nerede izlenir?", { showWhere = !showWhere })
                Pill(if (watched) "İzledim ✓" else "İzledim", { st.toggleWatched(f) })
                Pill(if (listed) "Listemde ✓" else "Listeme ekle", { st.toggleList(f) })
            }
            if (watched) {
                Spacer(Modifier.height(12.dp)); BasicText("Bir daha önerilmeyecek", style = inter(16, color = C.muted))
            }
        }
        LaunchedEffect(showWhere) { if (showWhere && where == null) where = st.providers(f) }

        if (sims.isNotEmpty()) {
            Column(Modifier.offset(380.dp, 500.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText(if (f.isTv) "Benzer diziler" else "Benzer filmler", style = serif(24))
                    Spacer(Modifier.width(16.dp))
                    BasicText(if (genre.isNotBlank()) genre else "arşivden", style = inter(17, color = C.muted))
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    sims.forEach { s ->
                        Focusable({ st.screen = Screen.Detail(s.key) }) { foc ->
                            PosterImage(st.film(s), 92, 138, color = foc, modifier = if (foc) Modifier.ring() else Modifier)
                        }
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 30.dp), verticalAlignment = Alignment.Bottom) {
            Saka(90.dp, st.tod, Mood.MERAK, look = 6f)
            Spacer(Modifier.width(10.dp))
            BasicText("This product uses the TMDB API but is not endorsed or certified by TMDB.", style = inter(13, color = C.dim),
                modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
