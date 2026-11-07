# Şaka TV · HRP kurulumu

Bu klasör, Thomson Cast 150 için yazdığım başlatıcının tamamı. Mac'te derleyip TV'ye kuracaksın. Toplam 30-40 dakika sürer, çoğu ilk indirmelerde geçer.

## 1. Mac'i hazırla

1. **Android Studio**'yu indir ve kur: https://developer.android.com/studio
2. Açılışta "Standard" kurulumu seç. SDK'yı kendisi indirir.
3. **Open** ile bu `SakaTV` klasörünü aç. İlk açılışta "Gradle sync" birkaç dakika sürer, alt çubukta ilerlemeyi görürsün.
4. Senkron hata verirse ekran görüntüsünü bana at.

## 2. TMDB anahtarı (afişler, fragmanlar, "Bu ay yeni" ve "Popüler" için)

Anahtar olmadan da çalışır: Klasikler yerel arşivden gelir, ama afişler ve yeni film listeleri boş kalır.

1. https://www.themoviedb.org adresinde ücretsiz hesap aç.
2. Ayarlar → API → "Request an API key" → Developer. Kişisel kullanım yaz.
3. Sana verilen **API Key (v3)** değerini kopyala.
4. `SakaTV/local.properties` dosyasını aç (Android Studio oluşturur) ve en alta ekle:

```
tmdb.key=BURAYA_ANAHTAR
```

## 3. TV'de geliştirici modunu aç

1. TV'de Ayarlar → Sistem → Hakkında → **Android TV OS derlemesi** satırına 7 kez bas. "Artık geliştiricisin" yazar.
2. Ayarlar → Sistem → **Geliştirici seçenekleri** → **USB hata ayıklama** ve **Kablosuz hata ayıklama**yı aç.
3. Ayarlar → Ağ ve İnternet'ten TV'nin IP adresini not et (örnek: 192.168.1.42).

## 4. Mac'i TV'ye bağla

Terminal'i aç. `adb` Android Studio ile gelir, kısa yol için:

```
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
```

**Kablosuz hata ayıklama** ekranında "Cihazı eşleme koduyla eşle" de. Ekrandaki IP:port ve 6 haneli kodu kullan:

```
adb pair 192.168.1.42:EŞLEME_PORTU
adb connect 192.168.1.42:BAĞLANTI_PORTU
adb devices
```

`adb devices` listesinde TV görünüyorsa tamam. TV ekranında "izin ver" sorarsa "Her zaman izin ver" de.

## 5. Derle ve kur

En kolay yol: Android Studio'da üstteki cihaz listesinden TV'yi seç ve yeşil **▶ Run** tuşuna bas. Uygulama TV'ye kurulur ve açılır.

Terminalden istersen:

```
cd SakaTV
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 6. Ana ekran yap (reklamlı Google TV ekranından kurtul)

Önce nazik yöntem:

```
adb shell cmd package set-home-activity --user 0 com.hrp.sakatv/.MainActivity
```

Kumandada Ana Sayfa'ya bas. Şaka TV açılıyorsa bitti. Google TV hâlâ kendi ekranını açıyorsa kendi başlatıcısını devre dışı bırak:

```
adb shell pm list packages | grep -i -E "launcher|tungsten"
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Paket adları Thomson'da farklı çıkarsa ilk komutun çıktısını bana at.

**Geri almak için** (her şeyi eski haline döndürür):

```
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

## 7. Spotify'da çalan şarkı satırı

Saatin altındaki "♪ şarkı · sanatçı" satırı için bir kerelik izin:

```
adb shell cmd notification allow_listener com.hrp.sakatv/com.hrp.sakatv.data.NowPlayingListener
```

Spotify, YouTube Music ya da başka bir müzik uygulaması çalarken satır kendiliğinden belirir.

## 8. Ekran koruyucu ve plazma ayarları

Şaka'lı ekran koruyucu 30 dakika tuşa basılmazsa kendiliğinden açılır. Google'ın kendi ekran koruyucusu araya girmesin diye:

```
adb shell settings put secure screensaver_enabled 0
```

TV'nin tamamen kapanma süresini Ayarlar → Sistem → Enerji bölümünden istediğin gibi bırak (örneğin 4 saat).

Panasonic tarafında: TV menüsünde **Pixel Orbiter** açık kalsın. Şaka TV de ana ekranı 2 dakikada bir 3 piksel kaydırıyor, ekran koruyucu 5 dakika sonra kısılıyor.

## 9. İsteğe bağlı: tüm uygulamalardaki reklamları azalt

Cihaz genelinde AdGuard DNS:

```
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.adguard-dns.com
```

Bir uygulama bozulursa geri al:

```
adb shell settings put global private_dns_mode off
```

## 10. Film arşivini 500 filme çıkar

Uygulama şu an 13 klasikle geliyor. IMDb ilk 500 listesini üretmek için (Mac'te Python 3 yüklü):

```
cd SakaTV
python3 tools/build_top500.py --key BURAYA_TMDB_ANAHTARI
```

Betik IMDb veri setlerini indirir, 25.000 oy altını eler, IMDb'nin ağırlıklı puanıyla sıralar ve her film için Türkçe özet, afiş ve fragmanı TMDB'den alır. Bitince 5. adımdaki gibi yeniden derle ve kur. İzledim işaretlerin silinmez.

## Kullanım

| Tuş | Ne yapar |
|---|---|
| Yön tuşları | Dock, Şaka, film şeridi ve aşağıdaki satırlar arasında gezinir |
| OK | Uygulamayı açar, filmin detayına gider, Şaka'nın ruh halini değiştirir |
| OK'a uzun bas | Uygulamada: dock'a ekle, sırala, kaldır. Ana film şeridinde: İzledim. Satırlarda: Listeme ekle |
| Geri | Aşağıdaysan en üste döner, diğer ekranlarda ana ekrana döner |
| Ana Sayfa | Her zaman temiz ana ekran |

## Lisanslar

Inter ve Fraunces yazı tipleri SIL Open Font License 1.1 ile dağıtılır, metinleri `licenses/` klasöründe. Film verileri TMDB API ve IMDb veri setlerinden gelir. This product uses the TMDB API but is not endorsed or certified by TMDB.
