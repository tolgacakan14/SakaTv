#!/bin/bash
# Şaka TV · HRP: Mac'ten TV'ye kurulum ve bakım yardımcısı.
# Kullanım: ./tools/tv.sh yardim

set -o pipefail

PKG="com.hrp.sakatv"
MAIN="$PKG/.MainActivity"
LISTENER="$PKG/$PKG.data.NowPlayingListener"
DREAM="$PKG/$PKG.SakaDream"
GOOGLE_HOME="com.google.android.apps.tv.launcherx"
GOOGLE_SETUP="com.google.android.tungsten.setupwraith"
DEFAULT_TV="192.168.1.107:33383"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$HOME/.sakatv"
TV_FILE="$STATE_DIR/tv"
APK_SRC="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK="$STATE_DIR/sakatv.apk"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
SDK_ADB="$SDK/platform-tools/adb"
STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

mkdir -p "$STATE_DIR"

# ---------- yazdırma ----------
if [ -t 1 ]; then B=$'\033[1m'; D=$'\033[2m'; R=$'\033[31m'; G=$'\033[32m'; N=$'\033[0m'; else B=""; D=""; R=""; G=""; N=""; fi
baslik() { printf '\n%s%s%s\n' "$B" "$*" "$N"; }
ok()     { printf '  %s✓%s %s\n' "$G" "$N" "$*"; }
hata()   { printf '  %s✗%s %s\n' "$R" "$N" "$*"; }
not()    { printf '  %s%s%s\n' "$D" "$*" "$N"; }
bilgi()  { printf '  %s\n' "$*"; }

# ---------- TV adresi ----------
TV="$DEFAULT_TV"
if [ -s "$TV_FILE" ]; then TV="$(tr -d '[:space:]' < "$TV_FILE")"; fi
TV_IP="${TV%%:*}"

tv_kaydet() {
  TV="$1"; TV_IP="${TV%%:*}"
  printf '%s\n' "$TV" > "$TV_FILE"
}

# ---------- adb seçimi ----------
# Mac'te başka bir program (çoğu zaman Android Studio) kendi adb sunucusunu açıp
# bizimkini kapatabiliyor. Sürümler tutmayınca cihaz komut ortasında düşüyor.
# Çözüm: o an 5037 portunu dinleyen sunucu hangi adb ise istemci olarak da onu kullan.
adb_bul() {
  local pid yol
  pid="$(lsof -nP -iTCP:5037 -sTCP:LISTEN -t 2>/dev/null | head -n1)"
  if [ -n "$pid" ]; then
    yol="$(ps -o comm= -p "$pid" 2>/dev/null | sed 's/[[:space:]]*$//')"
    if [ "${yol#/}" != "$yol" ] && [ -x "$yol" ]; then printf '%s\n' "$yol"; return; fi
    yol="$(lsof -nP -p "$pid" -a -d txt -Fn 2>/dev/null | sed -n 's/^n//p' | head -n1)"
    if [ -n "$yol" ] && [ -x "$yol" ]; then printf '%s\n' "$yol"; return; fi
    yol="$(ps -o command= -p "$pid" 2>/dev/null | awk '{print $1}')"
    if [ "${yol#/}" != "$yol" ] && [ -x "$yol" ]; then printf '%s\n' "$yol"; return; fi
  fi
  if [ -x "$SDK_ADB" ]; then printf '%s\n' "$SDK_ADB"; return; fi
  command -v adb 2>/dev/null && return
  printf '%s\n' ""
}

ADB=""
YABANCI_UYARILDI=""
adb_hazirla() {
  ADB="$(adb_bul)"
  # Başka bir programın adb'si (örn. Splashtop XDisplay, Android Studio) sunucuyu tutuyorsa
  # onu kapatıp SDK'daki adb ile devam et. Yoksa sürümler sürekli birbirini düşürüyor.
  if [ -n "$ADB" ] && [ -x "$SDK_ADB" ] && [ "$ADB" != "$SDK_ADB" ]; then
    if [ -z "$YABANCI_UYARILDI" ]; then
      not "Başka bir programın adb'si çalışıyor: $ADB"
      case "$ADB" in
        *Splashtop*) not "Bu Splashtop XDisplay. Kurulum sırasında Splashtop'u menü çubuğundan tamamen kapat (Quit).";;
        *"Android Studio"*) not "Bu Android Studio. Kurulum sırasında Android Studio'yu Cmd + Q ile kapat.";;
      esac
      YABANCI_UYARILDI=1
    fi
    "$ADB" kill-server >/dev/null 2>&1
    sleep 1
    ADB="$SDK_ADB"
    "$ADB" start-server >/dev/null 2>&1
  fi
  if [ -z "$ADB" ]; then
    hata "adb bulunamadı."
    bilgi "Android Studio'yu kur ve bir kez aç (SDK'yı indirsin). adb şurada olmalı:"
    bilgi "$SDK_ADB"
    exit 1
  fi
}

# Sürüm uyarısı gibi gürültü satırlarını ayıklar.
temizle() {
  grep -vE '^\* daemon (not running|started)|^adb server (version|is out of date)|^\* failed to start daemon' || true
}

baglanti_hatasi_mi() { # $1 çıktı, $2 dönüş kodu
  local o="$1" rc="$2"
  if printf '%s' "$o" | grep -qiE "device '.*' not found|device not found|protocol fault|device offline|no devices/emulators|doesn't match this client|error: closed|connection reset|cannot connect|failed to connect"; then return 0; fi
  if printf '%s' "$o" | grep -qi "failed to install" && ! printf '%s' "$o" | grep -qE "INSTALL_|Failure"; then return 0; fi
  if [ "$rc" -ne 0 ] && [ -z "$o" ]; then return 0; fi
  return 1
}

yeniden_bagla() {
  adb_hazirla
  "$ADB" connect "$TV" >/dev/null 2>&1
  sleep 2
}

# adb komutunu TV'ye karşı çalıştırır, bağlantı koparsa 3 kez dener.
# Sonuç OUT değişkenine yazılır, dönüş kodu adb'nin kodudur.
OUT=""
tvde() {
  local i rc=1 ham
  for i in 1 2 3; do
    adb_hazirla
    ham="$("$ADB" -s "$TV" "$@" 2>&1)"; rc=$?
    OUT="$(printf '%s\n' "$ham" | temizle)"
    OUT="${OUT%$'\n'}"
    if ! baglanti_hatasi_mi "$OUT" "$rc"; then return "$rc"; fi
    if [ "$i" -lt 3 ]; then
      not "Bağlantı koptu, yeniden deniyorum ($i/3)..."
      yeniden_bagla
    fi
  done
  return "$rc"
}

# Kablosuz hata ayıklama portu değiştiyse ağdan bulmaya çalışır (mDNS).
port_ara() {
  local satir
  satir="$("$ADB" mdns services 2>/dev/null | grep '_adb-tls-connect' | grep -oE "${TV_IP//./\\.}:[0-9]+" | head -n1)"
  [ -n "$satir" ] && printf '%s\n' "$satir"
}

port_anlat() {
  bilgi "TV'ye ulaşamadım ($TV). Kablosuz hata ayıklama portu büyük ihtimalle değişti."
  bilgi "TV'de: Ayarlar → Sistem → Geliştirici seçenekleri → Kablosuz hata ayıklama"
  bilgi "Orada \"IP adresi ve Bağlantı noktası\" satırını oku, sonra:"
  bilgi "  ./tools/tv.sh baglan ${TV_IP}:YENIPORT"
  bilgi "TV ile Mac aynı Wi-Fi'da olmalı, Kablosuz hata ayıklama da açık kalmalı."
}

esle_anlat() {
  bilgi "TV bu Mac'i tanımıyor, bir kez eşlemek gerekiyor."
  bilgi "TV'de Kablosuz hata ayıklama ekranında \"Cihazı eşleme koduyla eşle\"yi seç."
  bilgi "Açılan pencerede bir IP:port ve 6 haneli kod çıkar. Sonra:"
  bilgi "  ./tools/tv.sh esle ${TV_IP}:ESLEMEPORT"
  bilgi "Eşleme portu, bağlantı portundan farklıdır. Eşledikten sonra baglan'ı tekrar çalıştır."
}

# TV'ye bağlanır. $1=sessiz ise sadece sorun olursa konuşur.
baglan_tv() {
  local sessiz="${1:-}" o durum i yeni
  adb_hazirla
  [ "$sessiz" = "sessiz" ] || not "adb: $ADB"
  o="$("$ADB" connect "$TV" 2>&1 | temizle)"
  durum=""
  for i in 1 2 3 4 5; do
    durum="$("$ADB" -s "$TV" get-state 2>&1 | temizle)"
    [ "$durum" = "device" ] && break
    printf '%s %s' "$o" "$durum" | grep -qiE 'unauthorized|authenticate|pair' && break
    sleep 1
    adb_hazirla
  done
  if [ "$durum" != "device" ]; then
    # "already connected" ama cihaz görünmüyorsa: bağlantıyı bırakıp tazele.
    "$ADB" disconnect "$TV" >/dev/null 2>&1
    sleep 1
    o="$("$ADB" connect "$TV" 2>&1 | temizle)"
    sleep 2
    durum="$("$ADB" -s "$TV" get-state 2>&1 | temizle)"
  fi
  if [ "$durum" = "device" ]; then
    [ "$sessiz" = "sessiz" ] || ok "TV bağlı: $TV"
    return 0
  fi
  if printf '%s %s' "$o" "$durum" | grep -qiE 'unauthorized|authenticate|pair'; then
    hata "Bağlanamadım: $(printf '%s' "$o" | head -n1)"
    esle_anlat
    return 1
  fi
  yeni="$(port_ara)"
  if [ -n "$yeni" ] && [ "$yeni" != "$TV" ]; then
    not "TV yeni bir portta görünüyor: $yeni. Onu deniyorum."
    tv_kaydet "$yeni"
    "$ADB" connect "$TV" >/dev/null 2>&1
    sleep 1
    if [ "$("$ADB" -s "$TV" get-state 2>/dev/null)" = "device" ]; then
      ok "TV bağlı: $TV (yeni port kaydedildi)"
      return 0
    fi
  fi
  hata "Bağlanamadım: $(printf '%s' "$o" | head -n1)"
  port_anlat
  return 1
}

tv_gerekli() { baglan_tv sessiz || exit 1; }

kurulu_mu() { tvde shell pm list packages "$PKG" && printf '%s\n' "$OUT" | grep -q "^package:$PKG\$"; }

# ---------- Java ve derleme ----------
java_surumu() { # $1 = JAVA_HOME, ana sürüm numarasını yazar
  local h="$1" v=""
  if [ -f "$h/release" ]; then
    v="$(sed -n 's/^JAVA_VERSION="\{0,1\}\([0-9][0-9]*\).*/\1/p' "$h/release" | head -n1)"
  fi
  if [ -z "$v" ] && [ -x "$h/bin/java" ]; then
    v="$("$h/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1)"
  fi
  printf '%s\n' "$v"
}

uygun_java() { # Gradle 8.9 Java 17 ile 22 arasında çalışır; 21 ve 17 tercih
  local adaylar=() h v
  if [ -x /usr/libexec/java_home ]; then
    for v in 21 17; do
      h="$(/usr/libexec/java_home -v "$v" 2>/dev/null)" && adaylar+=("$h")
    done
  fi
  for h in "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home /Library/Java/JavaVirtualMachines/*/Contents/Home \
           /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 "$STUDIO_JBR"; do
    [ -d "$h" ] && adaylar+=("$h")
  done
  for want in 21 17 22 20 19 18; do
    for h in "${adaylar[@]}"; do
      [ -x "$h/bin/java" ] || continue
      v="$(java_surumu "$h")"
      if [ "$v" = "$want" ]; then printf '%s\n' "$h"; return 0; fi
    done
  done
  return 1
}

studio_ile_derle_anlat() {
  bilgi "Bu Mac'te terminalden derlemeye uygun Java (17 ya da 21) bulamadım."
  bilgi "Sorun değil, Android Studio ile derle:"
  bilgi "  1. Android Studio'da SakaTV projesini aç."
  bilgi "  2. Menüden Build → Generate App Bundles or APKs → Generate APKs."
  bilgi "  3. Sağ altta \"APK(s) generated successfully\" yazınca bitti."
  bilgi "Sonra tekrar çalıştır:  ./tools/tv.sh kur"
}

kaynak_apkdan_yeni_mi() {
  [ -f "$APK_SRC" ] || return 0
  [ -n "$(find "$ROOT/app/src" "$ROOT/app/build.gradle.kts" "$ROOT/local.properties" -newer "$APK_SRC" -type f 2>/dev/null | head -n1)" ]
}

derle() {
  baslik "1. Derleme"
  if ! grep -q '^tmdb.key=.' "$ROOT/local.properties" 2>/dev/null; then
    not "Not: local.properties içinde tmdb.key yok. Uygulama çalışır ama afişler ve yeni filmler gelmez."
  fi
  local jh
  if jh="$(uygun_java)"; then
    not "Java $(java_surumu "$jh"): $jh"
    bilgi "Derliyorum, ilk seferde birkaç dakika sürebilir..."
    [ -d "$SDK" ] && export ANDROID_HOME="$SDK"
    if (cd "$ROOT" && JAVA_HOME="$jh" PATH="$jh/bin:$PATH" ./gradlew --console=plain assembleDebug > "$STATE_DIR/derleme.log" 2>&1); then
      ok "Derleme tamam."
      return 0
    fi
    hata "Derleme başarısız. Son satırlar:"
    tail -n 25 "$STATE_DIR/derleme.log" | sed 's/^/    /'
    bilgi "Tam kayıt: $STATE_DIR/derleme.log"
    bilgi "Android Studio ile de deneyebilirsin: Build → Generate App Bundles or APKs → Generate APKs,"
    bilgi "sonra ./tools/tv.sh kur"
    return 1
  fi
  if kaynak_apkdan_yeni_mi; then
    studio_ile_derle_anlat
    return 1
  fi
  ok "Android Studio'nun derlediği APK güncel, onu kullanıyorum."
  return 0
}

# ---------- kur ----------
abi_anlat() {
  hata "Bu APK TV'nin işlemcisine uymuyor (INSTALL_FAILED_NO_MATCHING_ABIS)."
  bilgi "Android Studio'da üstte emülatör seçiliyken Run'a basınca APK sadece emülatör için derleniyor."
  bilgi "Şöyle yap: Build → Generate App Bundles or APKs → Generate APKs, sonra ./tools/tv.sh kur"
}

kur_sonucu() { # $1 çıktı. 0 = başarılı, 1 = başarısız, 2 = ABI hatası (bitir)
  if printf '%s' "$1" | grep -q 'INSTALL_FAILED_NO_MATCHING_ABIS'; then return 2; fi
  if printf '%s' "$1" | grep -q 'Success'; then return 0; fi
  return 1
}

cmd_kur() {
  derle || exit 1

  baslik "2. APK hazırlığı"
  if [ ! -f "$APK_SRC" ]; then
    hata "APK yok: $APK_SRC"
    studio_ile_derle_anlat
    exit 1
  fi
  # Masaüstü iCloud'da; dosya buluttaysa adb yarıda kalabiliyor. Yerel bir kopyadan kuruyoruz.
  if ! cp -f "$APK_SRC" "$APK" 2>/dev/null; then
    hata "APK kopyalanamadı. Finder'da dosyaya bir kez tıkla (iCloud indirsin), sonra tekrar dene."
    exit 1
  fi
  ok "APK hazır ($(du -h "$APK" | awk '{print $1}')): $APK"

  baslik "3. TV'ye kurulum"
  tv_gerekli
  local r
  tvde install -r -t --no-streaming "$APK"
  kur_sonucu "$OUT"; r=$?
  if [ "$r" -eq 1 ]; then
    not "Doğrudan kurulum olmadı ($(printf '%s' "$OUT" | tail -n1)). Dosyayı TV'ye kopyalayıp oradan kuruyorum..."
    if tvde push "$APK" /data/local/tmp/s.apk; then
      tvde shell pm install -r -t /data/local/tmp/s.apk
      kur_sonucu "$OUT"; r=$?
      tvde shell rm -f /data/local/tmp/s.apk >/dev/null 2>&1
    fi
  fi
  if [ "$r" -eq 2 ]; then abi_anlat; exit 1; fi
  if [ "$r" -ne 0 ]; then
    hata "Kurulum başarısız:"
    printf '%s\n' "$OUT" | tail -n 5 | sed 's/^/    /'
    if printf '%s' "$OUT" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
      bilgi "TV'deki sürüm başka bir anahtarla imzalanmış. Bu durumda eskisini kaldırmak gerekir"
      bilgi "(İzledim ve Listem sıfırlanır):  adb uninstall $PKG  sonra ./tools/tv.sh kur"
    fi
    exit 1
  fi
  ok "Kuruldu."

  baslik "4. Hızlandırma"
  bilgi "Uygulamayı TV için önceden derliyorum, 1-2 dakika sürebilir..."
  if tvde shell cmd package compile -m speed -f "$PKG" && printf '%s' "$OUT" | grep -q 'Success'; then
    ok "Tamam, açılış ve kaydırma daha akıcı olur."
  else
    not "Bu adım olmadı ($(printf '%s' "$OUT" | tail -n1)). Önemli değil, TV bunu zamanla kendisi yapar."
  fi

  baslik "5. Açılış"
  if tvde shell am start -n "$MAIN" && ! printf '%s' "$OUT" | grep -q 'Error'; then
    ok "Şaka TV açıldı."
  else
    not "Uygulamayı açamadım, kumandadan Ana Sayfa'ya bas."
  fi
  if ! tvde shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN \
     || ! printf '%s' "$OUT" | grep -q "$PKG/"; then
    bilgi ""
    bilgi "Ana Sayfa tuşu henüz Şaka TV'yi açmıyorsa bir kez şunu çalıştır:  ./tools/tv.sh ana-ekran"
  fi
}

# ---------- ana ekran ----------
adim() { # $1 açıklama, kalanlar adb shell komutu
  local ac="$1"; shift
  if tvde shell "$@" && ! printf '%s' "$OUT" | grep -qiE 'exception|error|unknown|not found|bad'; then
    ok "$ac"
  else
    hata "$ac: $(printf '%s' "$OUT" | tail -n1)"
  fi
}

google_kapat() {
  adim "Ana Sayfa tuşu artık Şaka TV'yi açar" cmd package set-home-activity --user 0 "$MAIN"
  adim "Google TV ana ekranı kapatıldı" pm disable-user --user 0 "$GOOGLE_HOME"
  adim "Google TV kurulum yardımcısı kapatıldı" pm disable-user --user 0 "$GOOGLE_SETUP"
}

cmd_ana_ekran() {
  tv_gerekli
  if ! kurulu_mu; then hata "Şaka TV bu TV'de kurulu değil. Önce: ./tools/tv.sh kur"; exit 1; fi
  baslik "Ana ekran"
  google_kapat
  baslik "Çalan şarkı satırı"
  adim "Bildirim izni verildi (Spotify vb. çalarken şarkı görünür)" cmd notification allow_listener "$LISTENER"
  baslik "Ekran koruyucu"
  adim "Ekran koruyucu açık" settings put secure screensaver_enabled 1
  adim "Ekran koruyucu: Şaka" settings put secure screensaver_components "$DREAM"
  adim "Uyku öncesi otomatik açılma kapalı" settings put secure screensaver_activate_on_sleep 0
  adim "30 dakika dokunulmazsa ekran koruyucu başlar" settings put system screen_off_timeout 1800000
  adim "TV 4 saat sonra kendini uyutur" settings put secure sleep_timeout 14400000
  not "Uyku süresini istersen TV'de Ayarlar → Sistem → Enerji bölümünden değiştirebilirsin."
  bilgi ""
  bilgi "Kumandada Ana Sayfa'ya bas, Şaka TV açılmalı."
  bilgi "Google TV'ye geri dönmek istersen:  ./tools/tv.sh google-ac"
}

cmd_google_ac() {
  tv_gerekli
  baslik "Google TV geri açılıyor"
  adim "Google TV ana ekranı açıldı" pm enable --user 0 "$GOOGLE_HOME"
  adim "Google TV kurulum yardımcısı açıldı" pm enable --user 0 "$GOOGLE_SETUP"
  local g=""
  if tvde shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME; then
    g="$(printf '%s' "$OUT" | grep -oE "${GOOGLE_HOME//./\\.}/[A-Za-z0-9_.\$]+" | head -n1)"
  fi
  if [ -n "$g" ]; then
    adim "Ana Sayfa tuşu artık Google TV'yi açar" cmd package set-home-activity --user 0 "$g"
  else
    not "Ana Sayfa tuşunu Google TV'ye yönlendiremedim. Şaka TV açılırsa oradan Google TV'yi seçebilirsin."
  fi
  bilgi ""
  bilgi "Ana Sayfa artık Google TV'ye gider. Şaka TV'ye dönmek için:  ./tools/tv.sh ana-ekran"
}

cmd_google_kapat() {
  tv_gerekli
  baslik "Google TV kapatılıyor"
  google_kapat
}

# ---------- durum ----------
cmd_durum() {
  baslik "Bağlantı"
  baglan_tv || exit 1
  tvde shell getprop ro.product.model && bilgi "Cihaz: $OUT"

  baslik "Şaka TV"
  if kurulu_mu; then
    tvde shell dumpsys package "$PKG"
    bilgi "Sürüm: $(printf '%s' "$OUT" | grep -m1 versionName | sed 's/.*versionName=//')"
  else
    hata "Kurulu değil. ./tools/tv.sh kur"
  fi

  baslik "Google TV ana ekranı"
  tvde shell pm list packages -d "$GOOGLE_HOME"
  if printf '%s' "$OUT" | grep -q "package:$GOOGLE_HOME\$"; then bilgi "Kapalı"
  else
    tvde shell pm list packages -e "$GOOGLE_HOME"
    if printf '%s' "$OUT" | grep -q "package:$GOOGLE_HOME\$"; then bilgi "Açık"; else bilgi "Bulunamadı"; fi
  fi

  baslik "Ana Sayfa tuşu şunu açıyor"
  tvde shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
  bilgi "$(printf '%s' "$OUT" | tail -n1)"

  baslik "Bellek (Şaka TV)"
  tvde shell dumpsys meminfo "$PKG"
  if printf '%s' "$OUT" | grep -q 'No process found'; then bilgi "Şu an çalışmıyor."
  else printf '%s\n' "$OUT" | head -n 20 | sed 's/^/  /'; fi
}

# ---------- log ----------
cmd_log() {
  tv_gerekli
  local dosya
  dosya="$STATE_DIR/log-$(date +%Y%m%d-%H%M%S).txt"
  {
    echo "=== Çökme kayıtları ==="
    tvde logcat -b crash -d -v time; printf '%s\n' "$OUT"
    echo
    echo "=== Son 200 satır (Şaka TV) ==="
    tvde logcat -d -v time; printf '%s\n' "$OUT" | grep -E "$PKG|AndroidRuntime" | tail -n 200
  } > "$dosya"
  cat "$dosya"
  tvde logcat -b crash -c
  bilgi ""
  ok "Kayıt dosyaya yazıldı: $dosya"
  not "Çökme kayıtları temizlendi, bir dahaki seferde sadece yeni sorunlar görünür."
  bilgi "Bir sorun bildirirken bu dosyayı gönder."
}

# ---------- arşiv ----------
cmd_arsiv() {
  local key
  key="$(sed -n 's/^tmdb\.key=//p' "$ROOT/local.properties" 2>/dev/null | head -n1 | tr -d '[:space:]')"
  if [ -z "$key" ]; then
    hata "local.properties içinde tmdb.key bulamadım."
    bilgi "Dosyanın en altına şu satırı ekle:  tmdb.key=ANAHTARIN"
    exit 1
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    hata "python3 yok. Terminalde  xcode-select --install  çalıştır, sonra tekrar dene."
    exit 1
  fi
  baslik "İnternet bağlantısı (SSL) kontrolü"
  if ! python3 -c "import urllib.request; urllib.request.urlopen('https://api.themoviedb.org/3/configuration?api_key=$key', timeout=20)" >/dev/null 2>&1; then
    not "Python sertifikaları eksik görünüyor, certifi ile düzeltiyorum..."
    if ! python3 -m certifi >/dev/null 2>&1; then
      pip3 install --user certifi >/dev/null 2>&1 \
        || python3 -m pip install --user --break-system-packages certifi >/dev/null 2>&1
    fi
    if python3 -m certifi >/dev/null 2>&1; then
      SSL_CERT_FILE="$(python3 -m certifi)"
      export SSL_CERT_FILE
      ok "Sertifikalar ayarlandı."
    else
      hata "certifi kurulamadı. Python'u python.org'dan kurduysan Uygulamalar → Python 3.x → Install Certificates.command dosyasını çalıştır."
      exit 1
    fi
  else
    ok "Bağlantı tamam."
  fi
  baslik "Film arşivi hazırlanıyor (10-20 dakika sürebilir)"
  (cd "$ROOT" && python3 tools/build_top500.py --key "$key") || { hata "Betik hata verdi."; exit 1; }
  bilgi ""
  bilgi "Yeni arşivin TV'ye gitmesi için:  ./tools/tv.sh kur"
}

# ---------- eşle / bağlan ----------
cmd_esle() {
  local hedef="${1:-}" kod
  if ! printf '%s' "$hedef" | grep -qE '^[0-9.]+:[0-9]+$'; then
    hata "Eşleme adresini yaz:  ./tools/tv.sh esle ${TV_IP}:ESLEMEPORT"
    esle_anlat
    exit 1
  fi
  read -r -p "  TV ekranındaki 6 haneli kod: " kod
  adb_hazirla
  local o
  o="$("$ADB" pair "$hedef" "$kod" 2>&1 | temizle)"
  if printf '%s' "$o" | grep -qi 'Successfully paired'; then
    ok "Eşlendi."
    bilgi "Şimdi Kablosuz hata ayıklama ekranındaki \"IP adresi ve Bağlantı noktası\" ile:"
    bilgi "  ./tools/tv.sh baglan ${hedef%%:*}:BAGLANTIPORTU"
  else
    hata "Eşleme olmadı: $(printf '%s' "$o" | tail -n1)"
    bilgi "Kod 1-2 dakikada bir yenilenir, ekrandaki güncel kodu ve portu kullan."
    exit 1
  fi
}

cmd_baglan() {
  local yeni="${1:-}"
  if [ -n "$yeni" ]; then
    if ! printf '%s' "$yeni" | grep -qE '^[0-9.]+:[0-9]+$'; then
      hata "Adres şöyle olmalı: 192.168.1.107:33383"
      exit 1
    fi
    tv_kaydet "$yeni"
    not "TV adresi kaydedildi: $TV"
  fi
  baglan_tv || exit 1
}

yardim() {
  cat <<EOF
${B}Şaka TV · HRP yardımcısı${N}

  ./tools/tv.sh baglan [IP:PORT]   TV'ye bağlan (adres hatırlanır, şu an: $TV)
  ./tools/tv.sh esle IP:PORT       TV'yi bu Mac ile bir kez eşle (kod sorar)
  ./tools/tv.sh kur                Derle, TV'ye kur, hızlandır ve aç
  ./tools/tv.sh ana-ekran          Ana Sayfa tuşunu Şaka TV yap, ekran koruyucuyu ayarla
  ./tools/tv.sh google-ac          Google TV ana ekranını geri getir
  ./tools/tv.sh google-kapat       Google TV ana ekranını yeniden kapat
  ./tools/tv.sh durum              Bağlantı, sürüm, bellek ve ana ekran durumu
  ./tools/tv.sh log                Hata kayıtlarını göster ve dosyaya kaydet
  ./tools/tv.sh arsiv              Film arşivini IMDb ilk 500 ile yenile

Ayrıntı: KURULUM.md
EOF
}

komut="${1:-yardim}"
shift 2>/dev/null || true
case "$komut" in
  baglan|bağlan)           cmd_baglan "$@" ;;
  esle|eşle)               cmd_esle "$@" ;;
  kur)                     cmd_kur ;;
  ana-ekran)               cmd_ana_ekran ;;
  google-ac|google-aç)     cmd_google_ac ;;
  google-kapat)            cmd_google_kapat ;;
  durum)                   cmd_durum ;;
  log)                     cmd_log ;;
  arsiv|arşiv)             cmd_arsiv ;;
  yardim|yardım|-h|--help|help) yardim ;;
  *) hata "Bilinmeyen komut: $komut"; yardim; exit 1 ;;
esac
