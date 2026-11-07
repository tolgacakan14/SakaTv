package com.hrp.sakatv.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Film <-> JSON. Diskteki önbellekler ve durum yansısı bunu kullanır. */
object FilmJson {
    fun toJson(f: Film): JSONObject = JSONObject().apply {
        put("k", f.key)
        put("id", f.tmdbId)
        put("t", f.title)
        put("o", f.original)
        put("y", f.year)
        put("r", if (f.rating.isNaN() || f.rating.isInfinite()) 0.0 else f.rating)
        put("g", f.genres)
        put("rt", f.runtime)
        put("ov", f.overview)
        if (f.poster != null) put("p", f.poster)
        put("d", f.director)
        put("c", f.cast)
        if (f.trailer != null) put("tr", f.trailer)
        put("tone", f.tone)
        if (f.isTv) put("tv", true)
    }

    fun fromJson(o: JSONObject): Film? {
        val key = o.optString("k")
        if (key.isBlank()) return null
        return Film(
            key = key,
            tmdbId = o.optInt("id"),
            title = o.optString("t"),
            original = o.optString("o"),
            year = o.optString("y"),
            rating = o.optDouble("r", 0.0).let { if (it.isNaN()) 0.0 else it },
            genres = o.optString("g"),
            runtime = o.optInt("rt"),
            overview = o.optString("ov"),
            poster = o.optString("p").ifBlank { null },
            director = o.optString("d"),
            cast = o.optString("c"),
            trailer = o.optString("tr").ifBlank { null },
            tone = o.optLong("tone", 0xFF262626),
            isTv = o.optBoolean("tv", false),
        )
    }

    fun listToJson(l: List<Film>): JSONArray = JSONArray().also { a -> l.forEach { a.put(toJson(it)) } }

    fun listFromJson(a: JSONArray?): List<Film> {
        if (a == null) return emptyList()
        val out = ArrayList<Film>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { fromJson(it) }?.let { out += it }
        return out
    }
}

/** TMDB listelerinin diskteki kopyası. */
class ListsSnapshot(
    val ts: Long,
    val fresh: List<Film>,
    val popular: List<Film>,
    val tvFresh: List<Film>,
    val tvPopular: List<Film>,
)

/** Kullanıcı verisinin yansısı (SharedPreferences silinse bile geri gelir). */
class StateMirror(
    val watched: Set<String>,
    val myList: Set<String>,
    val dock: String?,
    val used: Map<String, Long>,
    /** İzledim/Listem'deki arşiv dışı filmlerin son bilinen hali (listeden düşseler de görünsünler diye). */
    val pinned: Map<String, Film>,
)

/**
 * filesDir altındaki JSON dosyaları. Bütün fonksiyonlar bloklayıcıdır: Dispatchers.IO üzerinden çağır.
 * Yazmalar AtomicFile ile yapılır, yarım kalan yazma eski dosyayı bozmaz.
 */
class DiskCache(ctx: Context) {
    private val dir = ctx.filesDir
    private val filmDir = File(dir, "films").apply { mkdirs() }
    private val listsFile = AtomicFile(File(dir, "lists.json"))
    private val stateFile = AtomicFile(File(dir, "state.json"))
    private val lock = Any()

    // ---- ortak ----
    private fun read(f: AtomicFile): JSONObject? = runCatching {
        if (!f.baseFile.exists()) return@runCatching null
        JSONObject(String(f.readFully(), Charsets.UTF_8))
    }.getOrNull()

    private fun write(f: AtomicFile, o: JSONObject) = synchronized(lock) {
        val out = runCatching { f.startWrite() }.getOrNull() ?: return@synchronized
        try {
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    // ---- TMDB listeleri ----
    fun readLists(): ListsSnapshot? {
        val o = read(listsFile) ?: return null
        return runCatching {
            ListsSnapshot(
                ts = o.optLong("ts"),
                fresh = FilmJson.listFromJson(o.optJSONArray("fresh")),
                popular = FilmJson.listFromJson(o.optJSONArray("popular")),
                tvFresh = FilmJson.listFromJson(o.optJSONArray("tvFresh")),
                tvPopular = FilmJson.listFromJson(o.optJSONArray("tvPopular")),
            )
        }.getOrNull()
    }

    fun writeLists(s: ListsSnapshot) = write(listsFile, JSONObject().apply {
        put("ts", s.ts)
        put("fresh", FilmJson.listToJson(s.fresh))
        put("popular", FilmJson.listToJson(s.popular))
        put("tvFresh", FilmJson.listToJson(s.tvFresh))
        put("tvPopular", FilmJson.listToJson(s.tvPopular))
    })

    // ---- zenginleştirilmiş filmler: film başına küçük bir dosya ----
    private fun filmFile(key: String) = File(filmDir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Film ve kaydedildiği zaman. Okunan dosyanın tarihi yenilenir (LRU benzeri budama için). */
    fun readFilm(key: String): Pair<Film, Long>? = runCatching {
        val f = filmFile(key)
        if (!f.exists()) return@runCatching null
        val o = JSONObject(f.readText(Charsets.UTF_8))
        val film = FilmJson.fromJson(o) ?: return@runCatching null
        val ts = o.optLong("ts")
        f.setLastModified(System.currentTimeMillis())
        film to ts
    }.getOrNull()

    fun writeFilm(film: Film) {
        runCatching {
            val f = filmFile(film.key)
            val tmp = File(filmDir, f.name + ".tmp")
            tmp.writeText(FilmJson.toJson(film).put("ts", System.currentTimeMillis()).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Diskte en fazla [max] film kalır; en uzun süredir dokunulmayanlar silinir. */
    fun pruneFilms(max: Int = 600) {
        runCatching {
            val files = filmDir.listFiles() ?: return
            files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            val json = files.filter { it.name.endsWith(".json") }
            if (json.size <= max) return
            json.sortedBy { it.lastModified() }.take(json.size - max).forEach { it.delete() }
        }
    }

    // ---- kullanıcı durumu yansısı ----
    fun readState(): StateMirror? {
        val o = read(stateFile) ?: return null
        return runCatching {
            fun set(name: String): Set<String> {
                val a = o.optJSONArray(name) ?: return emptySet()
                return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }.toSet()
            }
            val used = HashMap<String, Long>()
            o.optJSONObject("used")?.let { u -> u.keys().forEach { k -> used[k] = u.optLong(k) } }
            val pinned = HashMap<String, Film>()
            FilmJson.listFromJson(o.optJSONArray("pinned")).forEach { pinned[it.key] = it }
            StateMirror(
                watched = set("watched"),
                myList = set("mylist"),
                dock = if (o.has("dock")) o.optString("dock") else null,
                used = used,
                pinned = pinned,
            )
        }.getOrNull()
    }

    fun writeState(s: StateMirror) = write(stateFile, JSONObject().apply {
        put("v", 1)
        put("ts", System.currentTimeMillis())
        put("watched", JSONArray(s.watched.toList()))
        put("mylist", JSONArray(s.myList.toList()))
        if (s.dock != null) put("dock", s.dock)
        put("used", JSONObject().also { u -> s.used.forEach { (k, v) -> u.put(k, v) } })
        put("pinned", FilmJson.listToJson(s.pinned.values.toList()))
    })
}
