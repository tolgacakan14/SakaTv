package com.hrp.sakatv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale

enum class AppCategory(val label: String) { VIDEO("Video"), MUSIC("Müzik"), GAME("Oyun"), TOOLS("Araçlar") }

data class TvApp(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    val banner: ImageBitmap?,
    val category: AppCategory,
    val installedAt: Long,
)

/** Kurulu uygulamalar, dock sabitleri ve uygulama açma işleri. */
class AppRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("saka_apps", Context.MODE_PRIVATE)
    private val tr = Locale("tr")

    /** Yavaş bir işlem: arka planda çağır. */
    fun loadApps(): List<TvApp> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, TvApp>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        for (q in queries) {
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                val banner = runCatching { ai.loadBanner(pm) ?: pm.getApplicationBanner(pkg) }.getOrNull()
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                val installed = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrDefault(0L)
                seen[pkg] = TvApp(
                    pkg = pkg,
                    label = label,
                    icon = icon?.let { runCatching { it.toBitmap(128, 128).asImageBitmap() }.getOrNull() },
                    banner = banner?.let { runCatching { it.toBitmap(320, 180).asImageBitmap() }.getOrNull() },
                    category = categorize(pkg, ai.applicationInfo),
                    installedAt = installed,
                )
            }
        }
        return seen.values.sortedBy { it.label.lowercase(tr) }
    }

    private fun categorize(pkg: String, info: ApplicationInfo?): AppCategory {
        val p = pkg.lowercase()
        if (info != null) {
            when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAME
                ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
                ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
            }
        }
        return when {
            listOf("spotify", "music", "radio", "tunein", "fizy", "deezer").any { p.contains(it) } -> AppCategory.MUSIC
            listOf("game", "asphalt", "crossy", "buggy").any { p.contains(it) } -> AppCategory.GAME
            listOf("netflix", "youtube", "smarttube", "video", "disney", "plex", "vlc", "kodi", "twitch", "blutv",
                "exxen", "tod", "trt", "bein", "mubi", "apple", "hbo", "max").any { p.contains(it) } -> AppCategory.VIDEO
            else -> AppCategory.TOOLS
        }
    }

    // ---- Dock ----
    fun dock(all: List<TvApp>): List<String> {
        val saved = prefs.getString("dock", null)
        if (saved != null) return saved.split(",").filter { s -> s.isNotBlank() && all.any { it.pkg == s } }
        val installed = all.map { it.pkg }.toSet()
        val defaults = DEFAULT_DOCK.mapNotNull { group -> group.firstOrNull { it in installed } }
        return if (defaults.isNotEmpty()) defaults else all.take(7).map { it.pkg }
    }

    fun setDock(pkgs: List<String>) { prefs.edit().putString("dock", pkgs.joinToString(",")).apply() }

    fun toggleDock(all: List<TvApp>, pkg: String): List<String> {
        val cur = dock(all).toMutableList()
        if (pkg in cur) cur.remove(pkg) else cur.add(pkg)
        setDock(cur)
        return cur
    }

    fun lastUsed(pkg: String): Long = prefs.getLong("used_$pkg", 0L)

    // ---- Açma ----
    fun launch(pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        prefs.edit().putLong("used_$pkg", System.currentTimeMillis()).apply()
        return start(intent)
    }

    fun openStore(): Boolean {
        val pm = ctx.packageManager
        val store = pm.getLeanbackLaunchIntentForPackage(PLAY) ?: pm.getLaunchIntentForPackage(PLAY)
        if (store != null && start(store)) return true
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps")))
    }

    fun storeSearch(query: String): Boolean {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))).setPackage(PLAY)
        return start(i) || openStore()
    }

    fun openSettings(): Boolean = start(Intent(Settings.ACTION_SETTINGS))

    fun appInfo(pkg: String): Boolean =
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    fun openDownloader(): Boolean {
        for (p in listOf("com.esaba.downloader", "com.mobisystems.fileman", "com.android.documentsui")) if (launch(p)) return true
        return storeSearch("Downloader AFTVnews")
    }

    fun start(intent: Intent): Boolean = runCatching {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    companion object {
        const val PLAY = "com.android.vending"

        /** Her satırda aynı uygulamanın olası paket adları; ilk kurulu olan dock'a girer. */
        val DEFAULT_DOCK = listOf(
            listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
            listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "org.smarttube.stable", "org.smarttube.beta"),
            listOf("com.amazon.amazonvideo.livingroom"),
            listOf("com.disney.disneyplus"),
            listOf("com.spotify.tv.android", "com.spotify.music"),
            listOf("com.apple.atve.androidtv.appletv"),
            listOf("com.android.tv.settings", "com.google.android.tv.settings"),
        )

        fun component(ctx: Context, cls: Class<*>) = ComponentName(ctx, cls)
    }
}
