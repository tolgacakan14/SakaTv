package com.hrp.sakatv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hrp.sakatv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

data class Film(
    val key: String,          // IMDb kimliği, "tmdb:<id>" ya da dizi için "tv:<id>"
    val tmdbId: Int,
    val title: String,
    val original: String,
    val year: String,
    val rating: Double,
    val genres: String,
    val runtime: Int,         // dakika (dizide bölüm süresi)
    val overview: String,
    val poster: String?,      // TMDB poster_path, örn. /abc.jpg
    val director: String,     // dizide yaratıcılar
    val cast: String,
    val trailer: String?,     // YouTube video anahtarı
    val tone: Long,           // afiş gelene kadar kullanılan ton
    val isTv: Boolean = false,
) {
    val runtimeText: String get() = if (runtime <= 0) "" else "${runtime / 60}s ${runtime % 60}dk"
    val posterUrl: String? get() = poster?.let { "https://image.tmdb.org/t/p/w342$it" }
    val posterUrlLarge: String? get() = poster?.let { "https://image.tmdb.org/t/p/w500$it" }
}

private val TONES = longArrayOf(0xFF33414C, 0xFF2A2622, 0xFF1B2633, 0xFF2E2E2E, 0xFF262626, 0xFF33302A, 0xFF3A1E1E, 0xFF2B3640, 0xFF352434, 0xFF1A2E3A)

/** TMDB tür kimlikleri, Türkçe adlarıyla (listelerde ek istek atmadan tür göstermek için). */
private val GENRES = mapOf(
    28 to "Aksiyon", 12 to "Macera", 16 to "Animasyon", 35 to "Komedi", 80 to "Suç", 99 to "Belgesel",
    18 to "Dram", 10751 to "Aile", 14 to "Fantastik", 36 to "Tarih", 27 to "Korku", 10402 to "Müzik",
    9648 to "Gizem", 10749 to "Romantik", 878 to "Bilim Kurgu", 10770 to "TV Filmi", 53 to "Gerilim",
    10752 to "Savaş", 37 to "Western",
    10759 to "Aksiyon", 10762 to "Çocuk", 10763 to "Haber", 10764 to "Gerçeklik", 10765 to "Bilim Kurgu",
    10766 to "Pembe Dizi", 10767 to "Talk Show", 10768 to "Savaş",
)

/** Dizi listelerinde gösterilmeyen türler: haber ve talk show. */
private val TV_SKIP = setOf(10763, 10767)

/** Film arşivi (assets/films.json), İzledim/Listem kayıtları ve TMDB istemcisi. */
class FilmRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("saka_films", Context.MODE_PRIVATE)
    private val key = BuildConfig.TMDB_KEY
    val hasTmdb: Boolean get() = key.isNotBlank()

    /** Aynı anda en fazla 3 istek: zayıf cihazda ağ ve CPU'yu boğmasın. */
    private val gate = Semaphore(3)

    /** Bloklayıcı ve ağır: sadece bir kez, Dispatchers.IO üzerinde çağır. */
    fun loadArchive(): List<Film> = runCatching {
        val text = ctx.assets.open("films.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        val out = ArrayList<Film>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val imdb = o.optString("imdb")
            val tmdb = o.optInt("tmdb")
            if (imdb.isBlank() && tmdb <= 0) continue
            val cachedPoster = if (tmdb > 0) prefs.getString("poster_$tmdb", null) else prefs.getString("poster_$imdb", null)
            out += Film(
                key = imdb.ifBlank { "tmdb:$tmdb" },
                tmdbId = tmdb,
                title = o.optString("title"),
                original = o.optString("original"),
                year = o.optString("year"),
                rating = o.optDouble("rating", 0.0),
                genres = o.optString("genres"),
                runtime = o.optInt("runtime"),
                overview = o.optString("overview"),
                poster = o.optString("poster").ifBlank { null } ?: cachedPoster,
                director = o.optString("director"),
                cast = o.optString("cast"),
                trailer = o.optString("trailer").ifBlank { null },
                tone = TONES[i % TONES.size],
            )
        }
        out
    }.getOrDefault(emptyList())

    // ---- İzledim / Listem ----
    fun watched(): Set<String> = HashSet(prefs.getStringSet("watched", emptySet()) ?: emptySet())
    fun toggleWatched(k: String): Set<String> = toggle("watched", k)
    fun myList(): Set<String> = HashSet(prefs.getStringSet("mylist", emptySet()) ?: emptySet())
    fun toggleList(k: String): Set<String> = toggle("mylist", k)

    /** Yansıdan geri yükleme için. */
    fun setWatched(s: Set<String>) { prefs.edit().putStringSet("watched", HashSet(s)).apply() }
    fun setMyList(s: Set<String>) { prefs.edit().putStringSet("mylist", HashSet(s)).apply() }

    private fun toggle(name: String, k: String): Set<String> {
        val cur = HashSet(prefs.getStringSet(name, emptySet()) ?: emptySet())
        if (!cur.add(k)) cur.remove(k)
        prefs.edit().putStringSet(name, cur).apply()
        return cur
    }

    // ---- TMDB ----
    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        if (!hasTmdb) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val q = (params + ("api_key" to key)).entries.joinToString("&") {
                        "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
                    }
                    val conn = URL("https://api.themoviedb.org/3$path?$q").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.useCaches = false
                    conn.setRequestProperty("Accept", "application/json")
                    try {
                        if (conn.responseCode != 200) null
                        else JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    } finally { conn.disconnect() }
                }.getOrNull()
            }
        }
    }

    private class Hit(val id: Int, val pop: Double, val film: Film)

    private fun genreNames(o: JSONObject): String {
        val g = o.optJSONArray("genre_ids") ?: return ""
        val names = ArrayList<String>(2)
        for (i in 0 until g.length()) {
            val n = GENRES[g.optInt(i)] ?: continue
            if (n !in names) names += n
            if (names.size == 2) break
        }
        return names.joinToString(", ")
    }

    private fun hasSkippedGenre(o: JSONObject): Boolean {
        val g = o.optJSONArray("genre_ids") ?: return false
        for (i in 0 until g.length()) if (g.optInt(i) in TV_SKIP) return true
        return false
    }

    private fun fromTmdb(o: JSONObject, tv: Boolean): Film {
        val id = o.optInt("id")
        return Film(
            key = if (tv) "tv:$id" else "tmdb:$id",
            tmdbId = id,
            title = (if (tv) o.optString("name") else o.optString("title")).ifBlank {
                if (tv) o.optString("original_name") else o.optString("original_title")
            },
            original = if (tv) o.optString("original_name") else o.optString("original_title"),
            year = (if (tv) o.optString("first_air_date") else o.optString("release_date")).take(4),
            rating = o.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
            genres = genreNames(o),
            runtime = 0,
            overview = o.optString("overview"),
            poster = o.optString("poster_path").ifBlank { null }?.takeIf { it != "null" },
            director = "",
            cast = "",
            trailer = null,
            tone = TONES[(id and 0x7fffffff) % TONES.size],
            isTv = tv,
        )
    }

    /** Bir yanıtın sonuçlarını süzer: afişsiz, yetişkin, az oylu ve (dizide) haber/talk show kayıtları çıkar. */
    private fun hits(o: JSONObject, tv: Boolean, minVotes: Int): List<Hit> {
        val r = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Hit>(r.length())
        for (i in 0 until r.length()) {
            val x = r.optJSONObject(i) ?: continue
            if (x.optBoolean("adult")) continue
            if (x.optInt("vote_count") < minVotes) continue
            if (x.optString("poster_path").let { it.isBlank() || it == "null" }) continue
            if (tv && hasSkippedGenre(x)) continue
            // trending karışık dönebilir; yanlış türü at.
            val mt = x.optString("media_type")
            if (mt.isNotBlank() && mt != (if (tv) "tv" else "movie")) continue
            out += Hit(x.optInt("id"), x.optDouble("popularity", 0.0), fromTmdb(x, tv))
        }
        return out
    }

    /** İsteklerden biri bile düşerse null: çağıran önbellekteki listeyi korur. */
    private suspend fun merged(
        reqs: List<Pair<String, Map<String, String>>>, tv: Boolean, minVotes: Int, limit: Int = 30,
    ): List<Film>? = coroutineScope {
        val res = reqs.map { (p, q) -> async { get(p, q) } }.map { it.await() }
        if (res.any { it == null }) return@coroutineScope null
        val seen = HashSet<Int>()
        res.flatMap { hits(it!!, tv, minVotes) }
            .filter { seen.add(it.id) }
            .sortedByDescending { it.pop }
            .take(limit)
            .map { it.film }
    }

    /** "Bu ay yeni": vizyondakiler + son 45 günde çıkan popüler filmler (sinema ve dijital). */
    suspend fun freshMovies(): List<Film>? {
        val today = LocalDate.now()
        return merged(
            listOf(
                "/movie/now_playing" to mapOf("language" to "tr-TR", "region" to "TR"),
                "/discover/movie" to mapOf(
                    "language" to "tr-TR", "region" to "TR", "sort_by" to "popularity.desc",
                    "primary_release_date.gte" to today.minusDays(45).toString(),
                    "primary_release_date.lte" to today.toString(),
                    "with_release_type" to "2|3|4", "vote_count.gte" to "10", "include_adult" to "false",
                ),
            ),
            tv = false, minVotes = 0,
        )
    }

    /** "Popüler": bu haftanın gözdeleri, en az 50 oy. İki sayfa ki süzgeçten sonra ~30 kalsın. */
    suspend fun popularMovies(): List<Film>? = merged(
        listOf(
            "/trending/movie/week" to mapOf("language" to "tr-TR"),
            "/trending/movie/week" to mapOf("language" to "tr-TR", "page" to "2"),
        ),
        tv = false, minVotes = 50,
    )

    /** Yayında olan ve son 60 günde başlayan diziler. */
    suspend fun freshSeries(): List<Film>? {
        val today = LocalDate.now()
        return merged(
            listOf(
                "/tv/on_the_air" to mapOf("language" to "tr-TR"),
                "/discover/tv" to mapOf(
                    "language" to "tr-TR", "sort_by" to "popularity.desc",
                    "first_air_date.gte" to today.minusDays(60).toString(),
                    "first_air_date.lte" to today.toString(),
                    "vote_count.gte" to "10", "include_adult" to "false",
                ),
            ),
            tv = true, minVotes = 0,
        )
    }

    /** Bu hafta popüler diziler. */
    suspend fun popularSeries(): List<Film>? = merged(
        listOf(
            "/trending/tv/week" to mapOf("language" to "tr-TR"),
            "/trending/tv/week" to mapOf("language" to "tr-TR", "page" to "2"),
        ),
        tv = true, minVotes = 0,
    )

    // Eski isimler (geri uyum).
    suspend fun nowPlaying(): List<Film> = freshMovies() ?: emptyList()
    suspend fun trending(): List<Film> = popularMovies() ?: emptyList()

    /** IMDb kimliğinden TMDB kimliği; bir kez bulunur, saklanır. */
    private suspend fun resolveImdb(imdb: String): Int {
        val cached = prefs.getInt("tmdbid_$imdb", 0)
        if (cached > 0) return cached
        val id = get("/find/$imdb", mapOf("external_source" to "imdb_id"))
            ?.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") ?: 0
        if (id > 0) prefs.edit().putInt("tmdbid_$imdb", id).apply()
        return id
    }

    private suspend fun withId(f: Film): Film {
        if (f.isTv || f.tmdbId > 0 || !f.key.startsWith("tt")) return f
        val id = resolveImdb(f.key)
        return if (id > 0) f.copy(tmdbId = id) else f
    }

    /**
     * Eksik afiş, Türkçe özet, yönetmen (dizide yaratıcılar), oyuncu, süre ve fragmanı TMDB'den tamamlar.
     * Ağ hatasında null döner (çağıran sonra yeniden dener).
     */
    suspend fun enrich(f0: Film): Film? {
        if (!hasTmdb) return null
        val f = withId(f0)
        if (f.tmdbId <= 0) return null
        val base = if (f.isTv) "/tv/${f.tmdbId}" else "/movie/${f.tmdbId}"
        val o = get(base, mapOf(
            "language" to "tr-TR",
            "append_to_response" to "credits,videos",
            "include_video_language" to "tr,en,null",
        )) ?: return null

        val poster = o.optString("poster_path").ifBlank { null }?.takeIf { it != "null" }
        if (poster != null && !f.isTv) {
            val e = prefs.edit().putString("poster_${f.tmdbId}", poster)
            if (f0.tmdbId <= 0) e.putString("poster_${f.key}", poster)
            e.apply()
        }
        val credits = o.optJSONObject("credits")
        val crew = credits?.optJSONArray("crew")
        val castArr = credits?.optJSONArray("cast")
        val director: String? = if (f.isTv) {
            o.optJSONArray("created_by")?.let { c ->
                (0 until minOf(2, c.length())).mapNotNull { c.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                    .joinToString(", ").ifBlank { null }
            }
        } else {
            crew?.let { c ->
                (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                    .firstOrNull { it.optString("job") == "Director" }?.optString("name")?.ifBlank { null }
            }
        }
        val cast = castArr?.let { c ->
            (0 until minOf(3, c.length())).mapNotNull { c.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                .joinToString(", ").ifBlank { null }
        }
        val runtime = if (f.isTv) {
            o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 }
                ?: o.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 }
                ?: 0
        } else o.optInt("runtime")

        var videos: JSONObject? = o.optJSONObject("videos")
        if ((videos?.optJSONArray("results")?.length() ?: 0) == 0 && f.trailer == null) {
            videos = get("$base/videos", mapOf("language" to "en-US"))
        }
        val trailer = pickTrailer(videos)

        var overview = o.optString("overview")
        if (overview.isBlank() && f.overview.isBlank()) {
            overview = get(base, mapOf("language" to "en-US"))?.optString("overview").orEmpty()
        }
        val genres = o.optJSONArray("genres")?.let { g ->
            (0 until minOf(2, g.length())).mapNotNull { g.optJSONObject(it)?.optString("name")?.ifBlank { null } }
                .joinToString(", ")
        }
        val title = if (f.isTv) o.optString("name") else o.optString("title")
        val original = if (f.isTv) o.optString("original_name") else o.optString("original_title")
        val year = (if (f.isTv) o.optString("first_air_date") else o.optString("release_date")).take(4)
        return f.copy(
            title = title.ifBlank { f.title },
            original = f.original.ifBlank { original },
            year = f.year.ifBlank { year },
            poster = poster ?: f.poster,
            overview = overview.ifBlank { f.overview },
            runtime = runtime.takeIf { it > 0 } ?: f.runtime,
            genres = f.genres.ifBlank { genres.orEmpty() },
            director = f.director.ifBlank { director.orEmpty() },
            cast = f.cast.ifBlank { cast.orEmpty() },
            trailer = f.trailer ?: trailer,
        )
    }

    /** Önce Türkçe fragman, sonra İngilizce, sonra herhangi bir fragman ya da tanıtım. */
    private fun pickTrailer(v: JSONObject?): String? {
        val r = v?.optJSONArray("results") ?: return null
        val all = (0 until r.length()).mapNotNull { r.optJSONObject(it) }
            .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        if (all.isEmpty()) return null
        fun pick(type: String, lang: String?): JSONObject? = all
            .filter { it.optString("type") == type && (lang == null || it.optString("iso_639_1") == lang) }
            .sortedByDescending { it.optBoolean("official") }
            .firstOrNull()
        return (pick("Trailer", "tr") ?: pick("Trailer", "en") ?: pick("Trailer", null)
            ?: pick("Teaser", "tr") ?: pick("Teaser", null) ?: all.first()).optString("key")
    }

    /** Türkiye'de izleme seçenekleri (TMDB watch/providers), film ve dizi için. */
    suspend fun providers(f0: Film): List<Pair<String, String>> {
        val f = withId(f0)
        if (f.tmdbId <= 0) return emptyList()
        val path = if (f.isTv) "/tv/${f.tmdbId}/watch/providers" else "/movie/${f.tmdbId}/watch/providers"
        val tr = get(path)?.optJSONObject("results")?.optJSONObject("TR") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((k, label) in listOf("flatrate" to "abonelikle", "free" to "ücretsiz", "ads" to "reklamlı", "rent" to "kiralık", "buy" to "satın alınabilir")) {
            val a = tr.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val name = a.optJSONObject(i)?.optString("provider_name").orEmpty()
                if (name.isNotBlank()) out += name to label
            }
        }
        return out.distinctBy { it.first }
    }

    /** Geri uyum: sadece film kimliğiyle. */
    suspend fun providers(tmdbId: Int): List<Pair<String, String>> =
        if (tmdbId <= 0) emptyList()
        else providers(Film("tmdb:$tmdbId", tmdbId, "", "", "", 0.0, "", 0, "", null, "", "", null, 0))

    /** Fragmanı SmartTube ya da YouTube'da açar. Anahtar yoksa YouTube araması yapar. */
    fun openTrailer(f: Film, apps: AppRepo) {
        val k = f.trailer
        if (k != null) {
            for (pkg in listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.smarttube.stable", "org.smarttube.beta", "com.google.android.youtube.tv")) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$k")).setPackage(pkg)
                if (apps.start(i)) return
            }
            if (apps.start(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$k")))) return
        }
        val kind = if (f.isTv) "dizi fragman" else "trailer"
        val q = Uri.encode("${f.original.ifBlank { f.title }} ${f.year} $kind")
        apps.start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
    }
}
