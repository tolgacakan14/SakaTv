# Şaka TV: R8 kuralları.
# Compose, Coil 2, OkHttp ve coroutines kendi tüketici kurallarını getirir; burada sadece
# sistemin isimle bulduğu sınıflar ve birkaç güvenlik kuralı var.

# Sistem tarafından isimle çağrılan sınıflar (manifestte de tanımlı, yine de açıkça korunur).
-keep class com.hrp.sakatv.SakaApp { <init>(); }
-keep class com.hrp.sakatv.SakaDream { <init>(); }
-keep class com.hrp.sakatv.data.NowPlayingListener { <init>(); }

# Coroutines: Main dispatcher ServiceLoader ile bulunur.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.debug.**

# Coil 2 / OkHttp / Okio: isteğe bağlı platform sınıfları.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# Hata ayıklama kayıtları sürümde boşa iş yapmasın.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Çökme raporlarında satır numaraları kalsın.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
