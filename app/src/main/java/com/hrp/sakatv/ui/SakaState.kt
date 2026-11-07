package com.hrp.sakatv.ui

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hrp.sakatv.data.AppRepo
import com.hrp.sakatv.data.DiskCache
import com.hrp.sakatv.data.Film
import com.hrp.sakatv.data.FilmRepo
import com.hrp.sakatv.data.ListsSnapshot
import com.hrp.sakatv.data.NowPlaying
import com.hrp.sakatv.data.NowPlayingReader
import com.hrp.sakatv.data.StateMirror
import com.hrp.sakatv.data.TvApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data object AllApps : Screen
    data class Detail(val key: String) : Screen
    data object Saver : Screen
}

enum class FilmTab(val label: String) { KLASIK("Klasikler"), YENI("Bu ay yeni"), POPULER("Popüler"), DIZI("Diziler") }

/**
 * Uygulamanın tek durum kaynağı. Activity ömrü boyunca yaşar.
 * Kural: disk ve ağ hep arka planda, durum yazmaları hep ana iş parçacığında ([scope] Main).
 */
class SakaState(private val ctx: Context, private val scope: CoroutineScope) {
    val apps = AppRepo(ctx)
    val films = FilmRepo(ctx)
    private val disk = DiskCache(ctx)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var allApps by mutableStateOf<List<TvApp>>(emptyList())
    var dock by mutableStateOf<List<String>>(emptyList())

    var archive by mutableStateOf<List<Film>>(emptyList())
    var fresh by mutableStateOf<List<Film>>(emptyList())
    var popular by mutableStateOf<List<Film>>(emptyList())
    /** Yeni ve yayında olan diziler. */
    var tvFresh by mutableStateOf<List<Film>>(emptyList())
    /** Bu hafta popüler diziler. */
    var tvPopular by mutableStateOf<List<Film>>(emptyList())
    var watched by mutableStateOf<Set<String>>(emptySet())
    var myList by mutableStateOf<Set<String>>(emptySet())
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    var tod by mutableStateOf(currentTod())
    var sakaPokes by mutableStateOf(0)

    var tab by mutableStateOf(FilmTab.KLASIK)
    var page by mutableStateOf(0)
    var compare by mutableStateOf(false)

    // ---- iç durum ----
    /** UI'nin okuduğu zenginleştirilmiş kayıtlar (sadece görünenler ve yakın çevresi). */
    private val rich = mutableStateMapOf<String, Film>()
    /** Bellekteki arama önbelleği, en fazla [MEM_CAP] kayıt (LRU). Diskte ayrıca ~600 kayıt durur. */
    private val mem = object : LinkedHashMap<String, Film>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Film>?): Boolean = size > MEM_CAP
    }
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    /** İzledim/Listem'deki filmlerin son bilinen hali: listeden düşse de kaybolmaz. */
    private val pinned = HashMap<String, Film>()
    private var pinnedTick by mutableIntStateOf(0)
    private var usageTick by mutableIntStateOf(0)
    private var gtv by mutableStateOf<Boolean?>(null)

    private var started = false
    private var stateLoaded = false
    private var listsLoaded = false
    private var listsTs = 0L
    private var lastAttempt = 0L
    private var refreshJob: Job? = null
    private var appsJob: Job? = null
    private var appsAgain = false
    private var mirrorJob: Job? = null
    private var pollJob: Job? = null

    // ================= yaşam döngüsü =================

    fun start() {
        if (started) return
        started = true
        apps.onUsed = { scope.launch { usageTick++; scheduleMirror() } }

        // 1) Kullanıcı verisi (küçük) ve önbellekteki listeler: ağdan önce, hemen.
        scope.launch {
            val st = withContext(Dispatchers.IO) { restoreState() }
            watched = st.watched
            myList = st.myList
            pinned.putAll(st.pinned)
            pinnedTick++
            stateLoaded = true

            val snap = withContext(Dispatchers.IO) { disk.readLists() }
            if (snap != null) {
                fresh = snap.fresh; popular = snap.popular; tvFresh = snap.tvFresh; tvPopular = snap.tvPopular
                listsTs = snap.ts
            }
            listsLoaded = true
            if (films.hasTmdb && isStale()) refreshOnline()
        }
        // 2) Uygulamalar.
        appsJob = scope.launch {
            do {
                appsAgain = false
                loadAppsNow()
            } while (appsAgain)
        }
        // 3) 500 filmlik arşiv: bir kez, IO'da.
        scope.launch {
            val a = withContext(Dispatchers.IO) { films.loadArchive() }
            archive = a
            withContext(Dispatchers.IO) { disk.pruneFilms(DISK_CAP) }
            delay(20_000)
            backfillPosters()
        }
        startPolling()
    }

    /** MainActivity.onResume: saat dilimi, uygulama değişiklikleri ve 6 saatten eski TMDB listeleri. */
    fun onResume() {
        tod = currentTod()
        if (!started) return
        startPolling()
        if (appsJob?.isActive != true) scope.launch {
            val changed = withContext(Dispatchers.IO) { apps.hasChanges() }
            if (changed) reloadApps()
            else {
                val g = withContext(Dispatchers.IO) { apps.googleTvAvailable() }
                gtv = g
            }
        }
        val now = System.currentTimeMillis()
        if (listsLoaded && films.hasTmdb && isStale() && now - lastAttempt > RETRY_MS) scope.launch { refreshOnline() }
    }

    /** İsteğe bağlı: MainActivity.onPause'dan çağrılırsa arka planda yoklama durur. */
    fun onPause() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Sistem bellek isterse: zenginleştirilmiş kayıtların görünmeyen kısmı bırakılır (disk kopyası durur). */
    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val keep = protectedKeys()
        mem.keys.retainAll(keep)
        trimRich(keep)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            failedAt.clear()
            forYouMemo = null
            availMemo.clear()
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        // Saat dilimi ve şu an çalan: 20 saniyede bir, hafif. İzin yoksa okuyucu 10 dakika hiç denemez.
        pollJob = scope.launch {
            while (isActive) {
                tod = currentTod()
                val np = withContext(Dispatchers.IO) { NowPlayingReader.read(ctx) }
                if (np != nowPlaying) nowPlaying = np
                delay(20_000)
            }
        }
    }

    // ================= kalıcılık =================

    private class Loaded(val watched: Set<String>, val myList: Set<String>, val pinned: Map<String, Film>)

    /** IO'da: prefs boşsa ama yansı varsa yansıdan geri yükler. */
    private fun restoreState(): Loaded {
        val m: StateMirror? = disk.readState()
        var w = films.watched()
        var l = films.myList()
        if (m != null) {
            if (w.isEmpty() && m.watched.isNotEmpty()) { films.setWatched(m.watched); w = m.watched }
            if (l.isEmpty() && m.myList.isNotEmpty()) { films.setMyList(m.myList); l = m.myList }
            if (apps.dockRaw() == null && m.dock != null) apps.setDockRaw(m.dock)
            apps.restoreUsage(m.used)
        }
        val keys = w + l
        return Loaded(w, l, m?.pinned?.filterKeys { it in keys } ?: emptyMap())
    }

    /** Değişiklikten 1,5 sn sonra yansıyı diske yazar (art arda değişiklikler tek yazmaya iner). */
    private fun scheduleMirror() {
        if (!stateLoaded) return
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            delay(1_500)
            val m = StateMirror(
                watched = watched,
                myList = myList,
                dock = apps.dockRaw(),
                used = apps.usage(),
                pinned = HashMap(pinned),
            )
            withContext(NonCancellable + Dispatchers.IO) { disk.writeState(m) }
        }
    }

    private fun updatePin(f: Film) {
        val k = f.key
        if (k in watched || k in myList) pinned[k] = film(f) else pinned.remove(k)
        pinnedTick++
    }

    // ================= TMDB listeleri =================

    private fun isStale() = System.currentTimeMillis() - listsTs > STALE_MS

    /** Listeleri yeniler. Aynı anda tek yenileme: ikinci çağrı ilkini bekler. Hata olursa eldeki listeler kalır. */
    suspend fun refreshOnline() {
        if (!films.hasTmdb) return
        val running = refreshJob
        if (running != null && running.isActive) { running.join(); return }
        val job = scope.launch { doRefresh() }
        refreshJob = job
        job.join()
    }

    private suspend fun doRefresh() {
        lastAttempt = System.currentTimeMillis()
        val res = coroutineScope {
            val a = async { runCatching { films.freshMovies() }.getOrNull() }
            val b = async { runCatching { films.popularMovies() }.getOrNull() }
            val c = async { runCatching { films.freshSeries() }.getOrNull() }
            val d = async { runCatching { films.popularSeries() }.getOrNull() }
            listOf(a.await(), b.await(), c.await(), d.await())
        }
        val ok = res.count { it != null }
        if (ok == 0) return
        // Tek seferde ata: tek yeniden çizim.
        res[0]?.let { fresh = it }
        res[1]?.let { popular = it }
        res[2]?.let { tvFresh = it }
        res[3]?.let { tvPopular = it }
        val now = System.currentTimeMillis()
        // Bir kısmı düştüyse bir saat sonra yeniden dene.
        listsTs = if (ok == res.size) now else now - STALE_MS + RETRY_PARTIAL_MS
        val snap = ListsSnapshot(listsTs, fresh, popular, tvFresh, tvPopular)
        withContext(NonCancellable + Dispatchers.IO) { disk.writeLists(snap) }
    }

    // ================= uygulamalar =================

    /** Kısa sürede gelen paket olaylarını tek yüklemede toplar. Sadece değişen paketler yeniden çizilir. */
    fun reloadApps() {
        if (appsJob?.isActive == true) { appsAgain = true; return }
        appsJob = scope.launch {
            delay(400)
            do {
                appsAgain = false
                loadAppsNow()
            } while (appsAgain)
        }
    }

    private suspend fun loadAppsNow() {
        val (list, g) = withContext(Dispatchers.IO) { apps.loadApps() to apps.googleTvAvailable() }
        allApps = list
        dock = apps.dock(list)
        gtv = g
    }

    private var appIndexSrc: List<TvApp>? = null
    private var appIndex: Map<String, TvApp> = emptyMap()

    fun app(pkg: String): TvApp? {
        val l = allApps
        if (appIndexSrc !== l) { appIndex = l.associateBy { it.pkg }; appIndexSrc = l }
        return appIndex[pkg]
    }

    fun isFeatured(pkg: String): Boolean = AppRepo.isFeatured(pkg, app(pkg)?.label)

    private var carouselKey: Triple<List<TvApp>, List<String>, Int>? = null
    private var carouselMemo: List<TvApp> = emptyList()

    /** Galeri sırası: XCIPTV, dock sabitleri, son kullanılanlar, kalanlar alfabetik. Ayarlar ve biz hariç. */
    fun carouselApps(): List<TvApp> {
        val l = allApps
        val d = dock
        val t = usageTick
        val ck = carouselKey
        if (ck != null && ck.first === l && ck.second === d && ck.third == t) return carouselMemo
        val own = ctx.packageName
        val ok = l.filter { it.pkg != own && it.pkg !in AppRepo.SETTINGS }
        val out = LinkedHashMap<String, TvApp>()
        ok.filter { AppRepo.isFeatured(it.pkg, it.label) }.forEach { out[it.pkg] = it }
        val idx = ok.associateBy { it.pkg }
        d.forEach { p -> idx[p]?.let { if (!out.containsKey(p)) out[p] = it } }
        ok.filter { !out.containsKey(it.pkg) && apps.lastUsed(it.pkg) > 0L }
            .sortedByDescending { apps.lastUsed(it.pkg) }
            .forEach { out[it.pkg] = it }
        ok.forEach { if (!out.containsKey(it.pkg)) out[it.pkg] = it } // allApps zaten alfabetik
        carouselMemo = out.values.toList()
        carouselKey = Triple(l, d, t)
        return carouselMemo
    }

    /** Uygulamayı açar ve kullanım zamanını kaydeder. */
    fun launch(pkg: String) { apps.launch(pkg) }

    /** Google TV başlatıcısı kurulu ve etkin mi (sonuç saklanır, onResume'da tazelenir). */
    fun googleTvAvailable(): Boolean = gtv ?: apps.googleTvAvailable()

    /** Etkinse Google TV'yi açar ve true döner; devre dışıysa false. */
    fun openGoogleTv(): Boolean {
        val ok = apps.openGoogleTv()
        if (!ok) gtv = apps.googleTvAvailable()
        return ok
    }

    fun toggleDock(pkg: String) {
        dock = apps.toggleDock(allApps, pkg)
        scheduleMirror()
    }

    fun moveDock(pkg: String, dir: Int) {
        val cur = dock.toMutableList()
        val i = cur.indexOf(pkg)
        val j = i + dir
        if (i < 0 || j !in cur.indices) return
        cur[i] = cur[j]; cur[j] = pkg
        apps.setDock(cur); dock = cur
        scheduleMirror()
    }

    // ================= filmler =================

    fun film(f: Film): Film = rich[f.key] ?: f

    private var keySrc: List<List<Film>> = emptyList()
    private var keyIndex: HashMap<String, Film> = HashMap()

    private fun index(): Map<String, Film> {
        val src = listOf(archive, fresh, popular, tvFresh, tvPopular)
        val same = src.size == keySrc.size && src.indices.all { src[it] === keySrc[it] }
        if (!same) {
            val m = HashMap<String, Film>(archive.size + 160)
            for (l in src) for (f in l) m[f.key] = f
            keyIndex = m
            keySrc = src
        }
        return keyIndex
    }

    private fun known(k: String): Film? {
        @Suppress("UNUSED_VARIABLE") val t = pinnedTick // pinned değişince yeniden okunsun
        return index()[k] ?: pinned[k] ?: mem[k]
    }

    fun byKey(k: String): Film? = rich[k] ?: known(k)

    /** Listem: listeden düşen filmler dahil, hiçbiri kaybolmaz. */
    fun myListFilms(): List<Film> = myList.mapNotNull { k -> byKey(k) }.sortedBy { it.title }

    /** İzlediklerin: listeden düşen filmler dahil. */
    fun watchedFilms(): List<Film> = watched.mapNotNull { k -> byKey(k) }.sortedBy { it.title }

    /** Afiş, özet ve fragman eksikse önce bellekte, sonra diskte, en son TMDB'de arar. */
    fun ensure(f: Film, full: Boolean = false) {
        val k = f.key
        if (rich.containsKey(k)) return
        val complete = f.poster != null && f.overview.isNotBlank() && (!full || (f.director.isNotBlank() && f.runtime > 0))
        if (complete) return
        // Kompozisyon içinden çağrılabilir: durum yazmasını ertele.
        val m = mem[k]
        if (m != null) { scope.launch { if (!rich.containsKey(k)) rich[k] = m }; return }
        if (!pending.add(k)) return
        scope.launch {
            try { resolve(f, publish = true) } finally { pending.remove(k) }
        }
    }

    /** Bellek, disk, ağ sırasıyla. Ağa sadece kayıt yoksa ya da bayatsa çıkar. Ana iş parçacığında çağrılır. */
    private suspend fun resolve(f: Film, publish: Boolean): Film? {
        val k = f.key
        mem[k]?.let { if (publish) put(it); return it }
        val cached = withContext(Dispatchers.IO) { disk.readFilm(k) }
        val now = System.currentTimeMillis()
        if (cached != null) {
            mem[k] = cached.first
            if (publish) put(cached.first)
            if (now - cached.second < RICH_TTL_MS || !films.hasTmdb) return cached.first
        }
        if (!films.hasTmdb) return null
        val fail = failedAt[k]
        if (fail != null && now - fail < FAIL_RETRY_MS) return cached?.first
        val e = runCatching { films.enrich(cached?.first ?: f) }.getOrNull()
        if (e == null) { failedAt[k] = now; return cached?.first }
        failedAt.remove(k)
        mem[k] = e
        if (publish) put(e)
        if (pinned.containsKey(k)) pinned[k] = e
        persist(e)
        return e
    }

    private fun put(e: Film) {
        if (rich[e.key] != e) rich[e.key] = e
        if (rich.size > RICH_SOFT_CAP) trimRich(protectedKeys())
    }

    private fun persist(e: Film) {
        scope.launch(Dispatchers.IO) { disk.writeFilm(e) }
    }

    private fun trimRich(keep: Set<String>) {
        val drop = rich.keys.filter { it !in keep }
        drop.forEach { rich.remove(it) }
    }

    /** Ekranda olabilecek her şey: bunlar bellekten atılmaz. */
    private fun protectedKeys(): Set<String> {
        val s = HashSet<String>()
        (screen as? Screen.Detail)?.let { s += it.key }
        runCatching { five() }.getOrNull()?.forEach { s += it.key }
        for (l in listOf(fresh, popular, tvFresh, tvPopular)) l.forEach { s += it.key }
        var n = 0
        for (f in archive) { if (f.key !in watched) { s += f.key; if (++n >= 40) break } }
        forYouMemo?.forEach { s += it.key }
        s += myList
        s += watched
        return s
    }

    /**
     * Arşivde afişi olmayan ilk filmler için afişleri sessizce tamamlar (bir kez; sonra diskte ve prefs'te kalır).
     * Teker teker, aralıklı; UI'yi 8'li gruplar halinde günceller.
     */
    private suspend fun backfillPosters() {
        if (!films.hasTmdb) return
        val w = watched
        val targets = (archive.asSequence().filter { it.key !in w }.take(40) + forYou().asSequence())
            .filter { it.poster == null && !rich.containsKey(it.key) }
            .distinctBy { it.key }
            .toList()
        val batch = ArrayList<Film>(8)
        for (f in targets) {
            if (rich.containsKey(f.key) || !pending.add(f.key)) continue
            val wasCached = mem.containsKey(f.key)
            val e = try { resolve(f, publish = false) } finally { pending.remove(f.key) }
            if (e != null) batch += e
            if (batch.size >= 8) { batch.forEach { put(it) }; batch.clear() }
            if (!wasCached) delay(1_200)
        }
        batch.forEach { put(it) }
    }

    /** Fragman anahtarı yoksa önce onu bulur, sonra açar. Dizilerde de çalışır. */
    fun playTrailer(f: Film) {
        val cur = film(f)
        // rich'teki kayıt zaten TMDB'den tamamlanmış: fragman yoksa yoktur, aramaya düş.
        if (cur.trailer != null || !films.hasTmdb || rich.containsKey(f.key)) {
            films.openTrailer(cur, apps); return
        }
        scope.launch {
            val k = f.key
            val e = if (pending.add(k)) {
                try { resolve(cur, publish = true) } finally { pending.remove(k) }
            } else {
                // ensure zaten çalışıyor: kısa bir süre onu bekle.
                var n = 0
                while (k in pending && n++ < 40) delay(150)
                null
            }
            films.openTrailer(e ?: film(f), apps)
        }
    }

    /** Türkiye'deki izleme seçenekleri; film ve dizi için doğru uç nokta. */
    suspend fun providers(f: Film): List<Pair<String, String>> =
        runCatching { films.providers(film(f)) }.getOrDefault(emptyList())

    fun toggleWatched(f: Film) {
        watched = films.toggleWatched(f.key)
        updatePin(f)
        scheduleMirror()
    }

    fun toggleList(f: Film) {
        myList = films.toggleList(f.key)
        updatePin(f)
        scheduleMirror()
    }

    // ---- beşli ----
    private class Avail(val src: List<Film>, val src2: List<Film>?, val w: Set<String>, val list: List<Film>)
    private val availMemo = HashMap<FilmTab, Avail>()

    private fun available(t: FilmTab): List<Film> {
        val w = watched
        val src = when (t) { FilmTab.KLASIK -> archive; FilmTab.YENI -> fresh; FilmTab.POPULER -> popular; FilmTab.DIZI -> tvFresh }
        val src2 = if (t == FilmTab.DIZI) tvPopular else null
        val m = availMemo[t]
        if (m != null && m.src === src && m.src2 === src2 && m.w === w) return m.list
        val list = if (src2 == null) src.filter { it.key !in w } else {
            // Yeni ve popüler diziler sırayla karışık.
            val out = LinkedHashMap<String, Film>()
            val n = maxOf(src.size, src2.size)
            for (i in 0 until n) {
                src.getOrNull(i)?.let { if (it.key !in w) out.putIfAbsent(it.key, it) }
                src2.getOrNull(i)?.let { if (it.key !in w) out.putIfAbsent(it.key, it) }
            }
            out.values.toList()
        }
        availMemo[t] = Avail(src, src2, w, list)
        return list
    }

    /** Ana ekrandaki beşli: izlenenler hiç görünmez. Klasikler her gün başka bir yerden başlar. */
    fun five(): List<Film> {
        val t = tab
        val p = page
        val av = available(t)
        if (av.isEmpty()) return emptyList()
        val off = if (t == FilmTab.KLASIK) (dayOfYear() * 5) % av.size else 0
        return (0 until minOf(5, av.size)).map { av[(off + p * 5 + it) % av.size] }
    }

    // ---- sana özel ----
    private var forYouMemo: List<Film>? = null
    private var forYouKey: Triple<List<Film>, Set<String>, Int>? = null

    /** İzlediklerinin türlerine göre arşivden izlemediğin en fazla 20 film. Gün içinde sabit, her gün biraz değişir. */
    fun forYou(): List<Film> {
        val a = archive
        val w = watched
        val day = dayOfYear()
        val key = forYouKey
        val memo = forYouMemo
        if (memo != null && key != null && key.first === a && key.second === w && key.third == day) return memo
        if (a.isEmpty()) return emptyList()

        val counts = HashMap<String, Int>()
        var total = 0
        for (k in w) {
            val f = known(k) ?: continue
            for (g in genreTokens(f.genres)) { counts[g] = (counts[g] ?: 0) + 1; total++ }
        }
        val denom = maxOf(1, total).toDouble()
        val out = a.asSequence()
            .filter { it.key !in w }
            .map { f ->
                val overlap = genreTokens(f.genres).sumOf { counts[it] ?: 0 } / denom
                val rating = (f.rating - 7.0).coerceIn(0.0, 3.0) * 0.15
                f to (overlap + rating + jitter(f.key, day))
            }
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }
            .toList()
        forYouMemo = out
        forYouKey = Triple(a, w, day)
        return out
    }

    private fun genreTokens(g: String): List<String> {
        if (g.isBlank()) return emptyList()
        return g.split(',', '/', '&').map { it.trim().lowercase(TR) }.filter { it.isNotEmpty() }.distinct()
    }

    /** Güne bağlı küçük, kararlı bir sapma (0..0.25): eşitlikleri her gün başka türlü bozar. */
    private fun jitter(key: String, day: Int): Double {
        var h = key.hashCode() * 31 + day * 0x9E3779B1.toInt()
        h = h xor (h ushr 16)
        h *= 0x45d9f3b
        h = h xor (h ushr 16)
        return (h and 0xffff) / 65535.0 * 0.25
    }

    private var dayCached = -1
    private var dayUntil = 0L

    private fun dayOfYear(): Int {
        val now = System.currentTimeMillis()
        if (now >= dayUntil || dayCached < 0) {
            val c = Calendar.getInstance()
            dayCached = c.get(Calendar.DAY_OF_YEAR)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.DAY_OF_YEAR, 1)
            dayUntil = c.timeInMillis
        }
        return dayCached
    }

    companion object {
        private val TR = Locale("tr")
        private const val STALE_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_MS = 10 * 60 * 1000L
        private const val RETRY_PARTIAL_MS = 60 * 60 * 1000L
        private const val RICH_TTL_MS = 21L * 24 * 60 * 60 * 1000L
        private const val FAIL_RETRY_MS = 2 * 60 * 1000L
        private const val MEM_CAP = 150
        private const val RICH_SOFT_CAP = 300
        private const val DISK_CAP = 600
    }
}
