package com.hrp.sakatv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Uygulama sınıfı. Tek işi Coil'in ortak görsel yükleyicisini zayıf TV'ye göre ayarlamak:
 * küçük bellek önbelleği, diskte 80 MB afiş, RGB565 (yarı bellek), varsayılan geçiş yok.
 */
class SakaApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.12)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("img"))
                .maxSizeBytes(80L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
