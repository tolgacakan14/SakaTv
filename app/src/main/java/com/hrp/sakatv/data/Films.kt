package com.hrp.sakatv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hrp.sakatv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Film(
    val key: String,          // IMDb kimliği ya da "tmdb:<id>"
    val tmdbId: Int,
    val title: String,
    val original: String,
    val year: String,
    val rating: Double,
    val genres: String,
    val runtime: Int,         // dakika
    val overview: String,
    val poster: String?,      // TMDB poster_path, örn. /abc.jpg
    val director: String,
    val cast: String,
    val trailer: String?,     // YouTube video anahtarı
    val tone: Long,           // afiş gelene kadar kullanılan ton
) {
    val runtimeText: String get() = if (runtime <= 0) "" else "${runtime / 60}s ${runtime % 60}dk"
    val posterUrl: String? get() = poster?.let { "https://image.tmdb.org/t/p/w342$it" }
    val posterUrlLarge: String? get() = poster?.let { "https://image.tmdb.org/t/p/w500$it" }
}

private val TONES = longArrayOf(0xFF33414C, 0xFF2A2622, 0xFF1B2633, 0xFF2E2E2E, 0xFF262626, 0xFF33302A, 0xFF3A1E1E, 0xFF2B3640, 0xFF352434, 0xFF1A2E3A)

/** Film arşivi (assets/films.json), İzledim/Listem kayıtları ve TMDB istemcisi. */
class FilmRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("saka_films", Context.MODE_PRIVATE)
    private val key = BuildConfig.TMDB_KEY
    val hasTmdb: Boolean get() = key.isNotBlank()

    fun loadArchive(): List<Film> {
        val text = ctx.assets.open("films.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cachedPoster = prefs.getString("poster_${o.optInt("tmdb")}", null)
            Film(
                key = o.getString("imdb"),
                tmdbId = o.optInt("tmdb"),
                title = o.getString("title"),
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
    }

    // ---- İzledim / Listem ----
    fun watched(): Set<String> = prefs.getStringSet("watched", emptySet()) ?: emptySet()
    fun toggleWatched(k: String): Set<String> = toggle("watched", k)
    fun myList(): Set<String> = prefs.getStringSet("mylist", emptySet()) ?: emptySet()
    fun toggleList(k: String): Set<String> = toggle("mylist", k)

    private fun toggle(name: String, k: String): Set<String> {
        val cur = (prefs.getStringSet(name, emptySet()) ?: emptySet()).toMutableSet()
        if (!cur.add(k)) cur.remove(k)
        prefs.edit().putStringSet(name, cur).apply()
        return cur
    }

    // ---- TMDB ----
    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? = withContext(Dispatchers.IO) {
        if (!hasTmdb) return@withContext null
        runCatching {
            val q = (params + ("api_key" to key)).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            val conn = URL("https://api.themoviedb.org/3$path?$q").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                if (conn.responseCode != 200) null
                else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    private fun fromTmdb(o: JSONObject, i: Int): Film = Film(
        key = "tmdb:${o.optInt("id")}",
        tmdbId = o.optInt("id"),
        title = o.optString("title"),
        original = o.optString("original_title"),
        year = o.optString("release_date").take(4),
        rating = o.optDouble("vote_average", 0.0),
        genres = "",
        runtime = 0,
        overview = o.optString("overview"),
        poster = o.optString("poster_path").ifBlank { null },
        director = "",
        cast = "",
        trailer = null,
        tone = TONES[i % TONES.size],
    )

    suspend fun nowPlaying(): List<Film> = list("/movie/now_playing", mapOf("language" to "tr-TR", "region" to "TR"))
    suspend fun trending(): List<Film> = list("/trending/movie/week", mapOf("language" to "tr-TR"))

    private suspend fun list(path: String, p: Map<String, String>): List<Film> {
        val res = get(path, p)?.optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).map { fromTmdb(res.getJSONObject(it), it) }
    }

    /** Eksik afiş, Türkçe özet, yönetmen, oyuncu ve fragmanı TMDB'den tamamlar. */
    suspend fun enrich(f0: Film): Film {
        var f = f0
        if (f.tmdbId <= 0 && f.key.startsWith("tt")) {
            // Arşiv sadece IMDb verisiyle üretildiyse TMDB kimliğini burada bulur ve saklar.
            val cached = prefs.getInt("tmdbid_${f.key}", 0)
            val id = if (cached > 0) cached else get("/find/${f.key}", mapOf("external_source" to "imdb_id"))
                ?.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id") ?: 0
            if (id > 0) { prefs.edit().putInt("tmdbid_${f.key}", id).apply(); f = f.copy(tmdbId = id) }
        }
        if (f.tmdbId <= 0) return f
        val o = get("/movie/${f.tmdbId}", mapOf("language" to "tr-TR", "append_to_response" to "credits,videos")) ?: return f
        val poster = o.optString("poster_path").ifBlank { null }
        if (poster != null) prefs.edit().putString("poster_${f.tmdbId}", poster).apply()
        val crew = o.optJSONObject("credits")?.optJSONArray("crew")
        val castArr = o.optJSONObject("credits")?.optJSONArray("cast")
        val director = crew?.let { c -> (0 until c.length()).map { c.getJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }?.optString("name") }
        val cast = castArr?.let { c -> (0 until minOf(3, c.length())).joinToString(", ") { c.getJSONObject(it).optString("name") } }
        var trailer = pickTrailer(o.optJSONObject("videos"))
        if (trailer == null) trailer = pickTrailer(get("/movie/${f.tmdbId}/videos", mapOf("language" to "en-US")))
        val genres = o.optJSONArray("genres")?.let { g -> (0 until minOf(2, g.length())).joinToString(", ") { g.getJSONObject(it).optString("name") } }
        return f.copy(
            title = o.optString("title").ifBlank { f.title },
            poster = poster ?: f.poster,
            overview = o.optString("overview").ifBlank { f.overview },
            runtime = o.optInt("runtime").takeIf { it > 0 } ?: f.runtime,
            genres = if (f.genres.isBlank()) genres ?: "" else f.genres,
            director = if (f.director.isBlank()) director ?: "" else f.director,
            cast = if (f.cast.isBlank()) cast ?: "" else f.cast,
            trailer = f.trailer ?: trailer,
        )
    }

    private fun pickTrailer(v: JSONObject?): String? {
        val r = v?.optJSONArray("results") ?: return null
        val all = (0 until r.length()).map { r.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        return (all.firstOrNull { it.optString("type") == "Trailer" } ?: all.firstOrNull())?.optString("key")
    }

    /** Türkiye'de izleme seçenekleri (TMDB watch/providers). */
    suspend fun providers(tmdbId: Int): List<Pair<String, String>> {
        val tr = get("/movie/$tmdbId/watch/providers")?.optJSONObject("results")?.optJSONObject("TR") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((k, label) in listOf("flatrate" to "abonelikle", "rent" to "kiralık", "buy" to "satın alınabilir")) {
            val a = tr.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) out += a.getJSONObject(i).optString("provider_name") to label
        }
        return out.distinctBy { it.first }
    }

    /** Fragmanı SmartTube ya da YouTube'da açar. Anahtar yoksa YouTube araması yapar. */
    fun openTrailer(f: Film, apps: AppRepo) {
        val k = f.trailer
        if (k != null) {
            for (pkg in listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "com.google.android.youtube.tv")) {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$k")).setPackage(pkg)
                if (apps.start(i)) return
            }
            if (apps.start(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$k")))) return
        }
        val q = Uri.encode("${f.original.ifBlank { f.title }} ${f.year} trailer")
        apps.start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
    }
}
