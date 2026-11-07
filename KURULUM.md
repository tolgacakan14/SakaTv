# Şaka TV · HRP kurulumu

Bu klasör, Thomson Cast 150 için yazdığım başlatıcının tamamı. Bir kez hazırlık yapıyorsun, sonra her şeyi tek bir yardımcı komutla hallediyorsun: `./tools/tv.sh`.

İlk kurulum 30-40 dakika sürer, çoğu Android Studio'nun ilk indirmelerinde geçer. Sonraki güncellemeler birkaç dakika.

## Bir kerelik hazırlık

### 1. Android Studio

1. Android Studio'yu indir ve kur: https://developer.android.com/studio
2. Açılışta "Standard" kurulumu seç. SDK'yı ve adb'yi kendisi indirir.
3. **Open** ile bu `SakaTV` klasörünü aç. İlk "Gradle sync" birkaç dakika sürer, alt çubukta ilerlemeyi görürsün.
4. Senkron hata verirse ekran görüntüsünü bana at.

### 2. TMDB anahtarı

Afişler, fragmanlar, "Bu ay yeni", "Popüler" ve diziler için gerekli. Anahtar olmadan da çalışır ama bu bölümler boş kalır.

1. https://www.themoviedb.org adresinde ücretsiz hesap aç.
2. Ayarlar → API → "Request an API key" → Developer. Kişisel kullanım yaz.
3. **API Key (v3)** değerini kopyala.
4. `SakaTV/local.properties` dosyasını aç (Android Studio oluşturur) ve en alta ekle:

```
tmdb.key=BURAYA_ANAHTAR
```

### 3. TV'de geliştirici modu

1. TV'de Ayarlar → Sistem → Hakkında → **Android TV OS derlemesi** satırına 7 kez bas. "Artık geliştiricisin" yazar.
2. Ayarlar → Sistem → **Geliştirici seçenekleri** → **Kablosuz hata ayıklama**yı aç.
3. Aynı ekranda **IP adresi ve Bağlantı noktası** satırını oku. Örnek: `192.168.1.107:33383`.

### 4. Mac'i TV ile eşle

Terminal'i aç ve proje klasörüne geç:

```
cd ~/Desktop/SakaTV
```

TV'de Kablosuz hata ayıklama ekranında **Cihazı eşleme koduyla eşle**yi seç. Çıkan pencerede bir IP:port ve 6 haneli kod görürsün. Bu port, 3. adımdakinden farklıdır.

```
./tools/tv.sh esle 192.168.1.107:ESLEMEPORT
```

Kodu sorar, yaz. Sonra 3. adımdaki adresle bağlan:

```
./tools/tv.sh baglan 192.168.1.107:33383
```

"TV bağlı" yazıyorsa tamam. Adres `~/.sakatv/tv` dosyasında saklanır, bir daha yazman gerekmez. TV'de "izin ver" sorarsa "Her zaman izin ver" de.

Eşleme bir kere yapılır. TV yeniden başlayınca sadece bağlantı portu değişebilir, o zaman yeni portla `baglan`'ı tekrar çalıştırman yeter.

## Kur ve ana ekran yap

```
./tools/tv.sh kur
```

Bu komut sırayla şunları yapar: uygulamayı derler, APK'yı `~/.sakatv/` içine kopyalar, TV'ye kurar, TV için önceden derleyip hızlandırır (1-2 dakika) ve uygulamayı açar.

Terminalden derleme için Mac'te Java 17 ya da 21 gerekiyor. Bulamazsa betik sana Android Studio'dan derlemeni söyler: **Build → Generate App Bundles or APKs → Generate APKs**. Bitince `./tools/tv.sh kur`'u tekrar çalıştır, Android Studio'nun derlediği APK'yı kurar.

Sonra bir kez:

```
./tools/tv.sh ana-ekran
```

Bu da şunları ayarlar:

- Ana Sayfa tuşu Şaka TV'yi açar, reklamlı Google TV ekranı kapanır.
- Saatin altındaki çalan şarkı satırı için bildirim izni verilir (Spotify, YouTube Music vb.).
- Şaka'lı ekran koruyucu seçilir, 30 dakika tuşa basılmazsa açılır.
- TV 4 saat hareketsiz kalınca kendini uyutur. İstersen Ayarlar → Sistem → Enerji'den değiştir.

Kumandada Ana Sayfa'ya bas. Şaka TV açılıyorsa bitti.

## Güncelleme geldiğinde

Yeni dosyaları `SakaTV` klasörüne koy (eskilerin üstüne yaz, `local.properties` kalsın), sonra:

```
cd ~/Desktop/SakaTV
./tools/tv.sh kur
```

İzledim, Listem ve dock sıran silinmez. `ana-ekran`'ı tekrar çalıştırmana gerek yok. TV yeniden başladıysa ve bağlanamazsa önce `baglan` (aşağıya bak).

Ne durumda olduğunu görmek için:

```
./tools/tv.sh durum
```

Bağlantıyı, kurulu sürümü, Google TV ekranının açık mı kapalı mı olduğunu, Ana Sayfa tuşunun neyi açtığını ve uygulamanın ne kadar bellek kullandığını gösterir.

## Sorun çıkarsa

| Gördüğün | Ne yapmalı |
|---|---|
| "TV'ye ulaşamadım", `failed to connect`, `Connection refused` | Port değişmiş. TV'de Kablosuz hata ayıklama ekranındaki "IP adresi ve Bağlantı noktası"nı oku, `./tools/tv.sh baglan 192.168.1.107:YENIPORT`. Betik yeni portu çoğu zaman kendisi de bulur. |
| "TV bu Mac'i tanımıyor", `unauthorized`, `failed to authenticate` | Eşleme gitmiş. `./tools/tv.sh esle 192.168.1.107:ESLEMEPORT`, sonra `baglan`. |
| `adb server version (40) doesn't match this client (41)`, `device not found`, `protocol fault` | Android Studio kendi adb'sini çalıştırıyor. Betik artık hangi adb çalışıyorsa onu kullanıyor ve 3 kez yeniden deniyor. Yine de olursa Android Studio'yu kapatıp tekrar dene. |
| Terminalde derleme `What went wrong: 25.0.4` | Mac'teki varsayılan Java 25, Gradle onunla çalışmıyor. Betik Java 17/21 arar. Bulamazsa Android Studio'da Build → Generate App Bundles or APKs → Generate APKs, sonra `./tools/tv.sh kur`. |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | APK emülatör için derlenmiş (Android Studio'da üstte emülatör seçiliyken Run'a basınca olur). Generate APKs ile derle, sonra `./tools/tv.sh kur`. |
| `failed to install` ama sebep yok | Betik önce doğrudan kurar, olmazsa dosyayı TV'ye kopyalayıp oradan kurar. İkisi de olmazsa `durum` ile bağlantıya bak. |
| "APK kopyalanamadı" | Masaüstü iCloud'da, dosya henüz inmemiş. Finder'da `app/build/outputs/apk/debug` klasörünü bir kez aç, sonra tekrar dene. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | TV'deki sürüm başka bir anahtarla imzalanmış. Eskisini kaldırmak gerekir, İzledim ve Listem sıfırlanır: `~/Library/Android/sdk/platform-tools/adb uninstall com.hrp.sakatv`, sonra `kur`. |
| "Hızlandırma" adımı olmadı | Önemli değil, TV bunu zamanla kendisi yapar. |
| Uygulama kapanıyor ya da donuyor | `./tools/tv.sh log` çalıştır. Kayıtları gösterir ve `~/.sakatv/` içine bir dosyaya yazar. O dosyayı bana gönder. |

Komutu yazınca `zsh: permission denied` dersen bir kez `chmod +x tools/tv.sh` çalıştır.

## Google TV'ye dönmek

```
./tools/tv.sh google-ac
```

Google TV ana ekranını ve kurulum yardımcısını geri açar, Ana Sayfa tuşu Google TV'ye gider. Şaka TV kurulu kalır, uygulamalar arasından açabilirsin.

Tekrar Şaka TV'ye dönmek için:

```
./tools/tv.sh ana-ekran
```

Sadece Google TV ekranını yeniden kapatmak istersen (ekran koruyucu ayarlarına dokunmadan): `./tools/tv.sh google-kapat`.

## Film arşivini 500 filme çıkar

Uygulama küçük bir klasik arşiviyle gelir. IMDb ilk 500 listesini üretmek için:

```
./tools/tv.sh arsiv
```

TMDB anahtarını `local.properties`'ten alır, Python'un sertifika sorunu varsa kendisi düzeltir. IMDb veri setlerini indirir, 25.000 oy altını eler, IMDb'nin ağırlıklı puanıyla sıralar ve her film için Türkçe özet, afiş ve fragmanı TMDB'den alır. 10-20 dakika sürebilir. Bitince `./tools/tv.sh kur`. İzledim işaretlerin silinmez.

## İsteğe bağlı

**Tüm uygulamalarda reklamları azaltmak** (cihaz genelinde AdGuard DNS). adb'yi kısa yoldan kullanmak için önce:

```
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
adb shell settings put global private_dns_mode hostname
adb shell settings put global private_dns_specifier dns.adguard-dns.com
```

Bir uygulama bozulursa geri al: `adb shell settings put global private_dns_mode off`

**Plazma için:** Panasonic menüsünde **Pixel Orbiter** açık kalsın. Şaka TV de ana ekranı arada birkaç piksel kaydırıyor, ekran koruyucu bir süre sonra kısılıyor.

## Kullanım

| Tuş | Ne yapar |
|---|---|
| Yön tuşları | Uygulamalar, Şaka, film şeridi ve aşağıdaki satırlar arasında gezinir |
| OK | Uygulamayı açar, filmin detayına gider, Şaka'nın ruh halini değiştirir |
| OK'a uzun bas | Uygulamada: dock'a ekle, sırala, kaldır. Ana film şeridinde: İzledim. Satırlarda: Listeme ekle |
| Geri | Aşağıdaysan en üste döner, diğer ekranlarda ana ekrana döner |
| Ana Sayfa | Her zaman temiz ana ekran |

## Lisanslar

Inter ve Fraunces yazı tipleri SIL Open Font License 1.1 ile dağıtılır, metinleri `licenses/` klasöründe. Film verileri TMDB API ve IMDb veri setlerinden gelir. This product uses the TMDB API but is not endorsed or certified by TMDB.
