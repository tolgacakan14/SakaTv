package com.hrp.sakatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hrp.sakatv.data.AppCategory
import kotlinx.coroutines.delay

/** Tüm uygulamalar: filtre, sıralama, dock'a ekleme, mağaza ve dosyadan yükleme. */
@Composable
fun AllAppsScreen(st: SakaState) {
    var filter by remember { mutableStateOf<AppCategory?>(null) }
    var byRecent by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    BackHandler(enabled = menuFor == null) { st.screen = Screen.Home }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    val shown = st.allApps
        .filter { filter == null || it.category == filter }
        .let { l -> if (byRecent) l.sortedByDescending { st.apps.lastUsed(it.pkg) } else l }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        TodLight(st.tod)
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 44.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                LinkText("← Ana ekran", { st.screen = Screen.Home })
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicText("Şaka TV", style = serif(22, FontWeight.Medium))
                    Spacer(Modifier.width(10.dp))
                    BasicText("HRP", style = inter(13, FontWeight.Medium, C.muted, ls = 3f))
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText("Uygulamalar", style = serif(40))
                Spacer(Modifier.width(16.dp))
                BasicText("${st.allApps.size} uygulama · ${st.dock.size} tanesi dock'ta", style = inter(17, color = C.muted), modifier = Modifier.padding(bottom = 5.dp))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    LinkText("Tümü", { filter = null }, selected = filter == null)
                    AppCategory.entries.forEach { c -> LinkText(c.label, { filter = c }, selected = filter == c) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    LinkText("A-Z", { byRecent = false }, selected = !byRecent)
                    LinkText("Son kullanılan", { byRecent = true }, selected = byRecent)
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                item(key = "store") {
                    Box(contentAlignment = Alignment.Center) {
                        AppItem(null, "Mağazada ara", "⌕", { st.apps.openStore() }, alwaysLabel = true, outlined = true, requester = first)
                    }
                }
                items(shown, key = { it.pkg }) { a ->
                    Box(contentAlignment = Alignment.Center) {
                        AppItem(
                            a, (if (a.pkg in st.dock) "• " else "") + a.label, glyphFor(a.label),
                            { st.apps.launch(a.pkg) }, onLong = { menuFor = a.pkg }, alwaysLabel = true,
                        )
                    }
                }
            }
            Row(Modifier.padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("Google Play'de yeni uygulama bul →", { st.apps.openStore() }, solid = true)
                Pill("Dosyadan yükle", { st.apps.openDownloader() })
                Spacer(Modifier.width(10.dp))
                BasicText("Uzun bas: dock'a ekle, sırala, kaldır", style = inter(16, color = C.muted))
            }
        }
        menuFor?.let { AppMenu(st, it, onClose = { menuFor = null }) }
    }
}
