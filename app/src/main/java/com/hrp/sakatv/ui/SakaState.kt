package com.hrp.sakatv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hrp.sakatv.data.AppRepo
import com.hrp.sakatv.data.Film
import com.hrp.sakatv.data.FilmRepo
import com.hrp.sakatv.data.NowPlaying
import com.hrp.sakatv.data.NowPlayingReader
import com.hrp.sakatv.data.TvApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object AllApps : Screen
    data class Detail(val key: String) : Screen
    data object Saver : Screen
}

enum class FilmTab(val label: String) { KLASIK("Klasikler"), YENI("Bu ay yeni"), POPULER("Popüler") }

/** Uygulamanın tek durum kaynağı. Activity ömrü boyunca yaşar. */
class SakaState(private val ctx: Context, private val scope: CoroutineScope) {
    val apps = AppRepo(ctx)
    val films = FilmRepo(ctx)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var allApps by mutableStateOf<List<TvApp>>(emptyList())
    var dock by mutableStateOf<List<String>>(emptyList())

    var archive by mutableStateOf<List<Film>>(emptyList())
    var fresh by mutableStateOf<List<Film>>(emptyList())
    var popular by mutableStateOf<List<Film>>(emptyList())
    var watched by mutableStateOf<Set<String>>(emptySet())
    var myList by mutableStateOf<Set<String>>(emptySet())
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    var tod by mutableStateOf(currentTod())
    var sakaPokes by mutableStateOf(0)

    var tab by mutableStateOf(FilmTab.KLASIK)
    var page by mutableStateOf(0)
    var compare by mutableStateOf(false)

    /** TMDB'den tamamlanmış film kayıtları. */
    private val rich = mutableStateMapOf<String, Film>()
    private val pending = HashSet<String>()

    fun start() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { apps.loadApps() }
            allApps = list
            dock = apps.dock(list)
        }
        scope.launch {
            archive = withContext(Dispatchers.IO) { films.loadArchive() }
            watched = films.watched()
            myList = films.myList()
        }
        scope.launch { refreshOnline() }
        // Saat dilimi ve şu an çalan: 20 saniyede bir, hafif.
        scope.launch {
            while (isActive) {
                tod = currentTod()
                nowPlaying = withContext(Dispatchers.IO) { NowPlayingReader.read(ctx) }
                delay(20_000)
            }
        }
    }

    suspend fun refreshOnline() {
        if (!films.hasTmdb) return
        fresh = films.nowPlaying()
        popular = films.trending()
    }

    fun reloadApps() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { apps.loadApps() }
            allApps = list
            dock = apps.dock(list)
        }
    }

    fun film(f: Film): Film = rich[f.key] ?: f
    fun byKey(k: String): Film? = rich[k] ?: (archive + fresh + popular).firstOrNull { it.key == k }

    /** Afiş, özet ve fragman eksikse arka planda TMDB'den tamamlar. */
    fun ensure(f: Film) {
        if (!films.hasTmdb || rich.containsKey(f.key) || !pending.add(f.key)) return
        scope.launch {
            val e = runCatching { films.enrich(f) }.getOrDefault(f)
            rich[f.key] = e
            pending.remove(f.key)
        }
    }

    fun toggleWatched(f: Film) { watched = films.toggleWatched(f.key) }
    fun toggleList(f: Film) { myList = films.toggleList(f.key) }

    /** Ana ekrandaki beşli: izlenenler hiç görünmez. */
    fun five(): List<Film> {
        val src = when (tab) { FilmTab.KLASIK -> archive; FilmTab.YENI -> fresh; FilmTab.POPULER -> popular }
        val av = src.filter { it.key !in watched }
        if (av.isEmpty()) return emptyList()
        return (0 until minOf(5, av.size)).map { av[(page * 5 + it) % av.size] }
    }

    fun app(pkg: String): TvApp? = allApps.firstOrNull { it.pkg == pkg }

    fun toggleDock(pkg: String) { dock = apps.toggleDock(allApps, pkg) }

    fun moveDock(pkg: String, dir: Int) {
        val cur = dock.toMutableList()
        val i = cur.indexOf(pkg)
        val j = i + dir
        if (i < 0 || j !in cur.indices) return
        cur[i] = cur[j]; cur[j] = pkg
        apps.setDock(cur); dock = cur
    }
}
