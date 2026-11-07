package com.hrp.sakatv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    /** pkg -> (lastUpdateTime, hazır TvApp). Yeniden yüklemede sadece değişen paketler yeniden çizilir. */
    private val cache = HashMap<String, Pair<Long, TvApp>>()
    private var seq = 0

    /** Kullanım zamanları bellekte; prefs sadece yazmak için. */
    private val used: ConcurrentHashMap<String, Long> by lazy {
        val m = ConcurrentHashMap<String, Long>()
        for ((k, v) in prefs.all) if (k.startsWith("used_") && v is Long) m[k.removePrefix("used_")] = v
        m
    }

    /** Bir uygulama açıldığında haber verir (SakaState yansıyı ve sıralamayı günceller). */
    @Volatile var onUsed: ((String) -> Unit)? = null

    /** Yavaş bir işlem: arka planda (Dispatchers.IO) çağır. */
    @Synchronized
    fun loadApps(): List<TvApp> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, TvApp>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        for (q in queries) {
            val found = runCatching { pm.queryIntentActivities(q, 0) }.getOrDefault(emptyList())
            for (ri in found) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                val updated = info?.lastUpdateTime ?: 0L
                val hit = cache[pkg]
                if (hit != null && hit.first == updated && updated != 0L) { seen[pkg] = hit.second; continue }

                val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()?.ifBlank { null } ?: pkg
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                    ?.let { d -> runCatching { d.toBitmap(ICON, ICON).asImageBitmap() }.getOrNull() }
                // Afiş sadece gerçekten afiş tanımlayan uygulamalar ve öne çıkan uygulama için.
                val declares = ai.banner != 0 || (ai.applicationInfo?.banner ?: 0) != 0
                val banner = if (declares || isFeatured(pkg, label)) {
                    runCatching { ai.loadBanner(pm) ?: pm.getApplicationBanner(pkg) }.getOrNull()
                        ?.let { d -> runCatching { d.toBitmap(BANNER_W, BANNER_H).asImageBitmap() }.getOrNull() }
                } else null
                val app = TvApp(
                    pkg = pkg,
                    label = label,
                    icon = icon,
                    banner = banner,
                    category = categorize(pkg, ai.applicationInfo),
                    installedAt = info?.firstInstallTime ?: 0L,
                )
                cache[pkg] = updated to app
                seen[pkg] = app
            }
        }
        cache.keys.retainAll(seen.keys)
        runCatching { pm.getChangedPackages(seq)?.let { seq = it.sequenceNumber } }
        return seen.values.sortedBy { it.label.lowercase(tr) }
    }

    /** Son yüklemeden beri bir paket kurulup kaldırıldı ya da güncellendi mi? Ucuz; arka planda çağır. */
    @Synchronized
    fun hasChanges(): Boolean {
        if (cache.isEmpty()) return true
        return runCatching {
            val ch = ctx.packageManager.getChangedPackages(seq) ?: return@runCatching false
            seq = ch.sequenceNumber
            ch.packageNames.isNotEmpty()
        }.getOrDefault(true)
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
                "exxen", "tod", "trt", "bein", "mubi", "apple", "hbo", "max", "iptv").any { p.contains(it) } -> AppCategory.VIDEO
            else -> AppCategory.TOOLS
        }
    }

    // ---- Dock ----
    fun dock(all: List<TvApp>): List<String> {
        val saved = prefs.getString("dock", null)
        val installed = all.mapTo(HashSet()) { it.pkg }
        if (saved != null) return saved.split(",").filter { s -> s.isNotBlank() && s in installed }
        val defaults = DEFAULT_DOCK.mapNotNull { group -> group.firstOrNull { it in installed } }
        return if (defaults.isNotEmpty()) defaults else all.take(7).map { it.pkg }
    }

    /** Kayıtlı ham dock dizgesi (kurulu olmayanlar dahil). Yansı için. */
    fun dockRaw(): String? = prefs.getString("dock", null)

    fun setDock(pkgs: List<String>) { prefs.edit().putString("dock", pkgs.joinToString(",")).apply() }
    fun setDockRaw(raw: String) { prefs.edit().putString("dock", raw).apply() }

    fun toggleDock(all: List<TvApp>, pkg: String): List<String> {
        val cur = dock(all).toMutableList()
        if (pkg in cur) cur.remove(pkg) else cur.add(pkg)
        setDock(cur)
        return cur
    }

    // ---- Kullanım zamanları ----
    fun lastUsed(pkg: String): Long = used[pkg] ?: 0L
    fun usage(): Map<String, Long> = HashMap(used)

    /** Yansıdan geri yükleme: sadece eksik olanları yazar. */
    fun restoreUsage(m: Map<String, Long>) {
        if (m.isEmpty()) return
        val e = prefs.edit()
        var any = false
        for ((k, v) in m) if (!used.containsKey(k) && v > 0) { used[k] = v; e.putLong("used_$k", v); any = true }
        if (any) e.apply()
    }

    private fun markUsed(pkg: String) {
        val now = System.currentTimeMillis()
        used[pkg] = now
        prefs.edit().putLong("used_$pkg", now).apply()
        onUsed?.invoke(pkg)
    }

    // ---- Açma ----
    fun launch(pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            ?: return false
        val ok = start(intent)
        if (ok) markUsed(pkg)
        return ok
    }

    // ---- Google TV ----
    /** Google TV başlatıcısı kurulu ve etkin mi. Bir binder çağrısı: sık çağırma, sonucu sakla. */
    fun googleTvAvailable(): Boolean = runCatching {
        val pm = ctx.packageManager
        when (pm.getApplicationEnabledSetting(GOOGLE_TV)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> pm.getApplicationInfo(GOOGLE_TV, 0).enabled
        }
    }.getOrDefault(false)

    /** Google TV ana ekranını açar. Devre dışıysa ya da bulunamazsa false. */
    fun openGoogleTv(): Boolean {
        if (!googleTvAvailable()) return false
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(GOOGLE_TV)
        if (runCatching { pm.resolveActivity(home, 0) }.getOrNull() != null && start(home)) return true
        val li = runCatching { pm.getLeanbackLaunchIntentForPackage(GOOGLE_TV) ?: pm.getLaunchIntentForPackage(GOOGLE_TV) }.getOrNull()
            ?: return false
        return start(li)
    }

    fun openStore(): Boolean {
        val pm = ctx.packageManager
        val store = runCatching { pm.getLeanbackLaunchIntentForPackage(PLAY) ?: pm.getLaunchIntentForPackage(PLAY) }.getOrNull()
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
        const val GOOGLE_TV = "com.google.android.apps.tv.launcherx"
        const val XCIPTV = "com.nathnetwork.xciptv"
        private const val ICON = 96
        private const val BANNER_W = 240
        private const val BANNER_H = 135

        /** Galeride gösterilmeyen sistem ayarları (alt satırdaki "Ayarlar" bağlantısı onları açar). */
        val SETTINGS = setOf("com.android.tv.settings", "com.google.android.tv.settings", "com.android.settings")

        /** XCIPTV: paket adında ya da etikette "xciptv" geçer. */
        fun isFeatured(pkg: String, label: String?): Boolean =
            pkg == XCIPTV || pkg.contains("xciptv", ignoreCase = true) || (label?.contains("xciptv", ignoreCase = true) == true)

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
