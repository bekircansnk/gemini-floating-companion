# 🌟 Gemini Floating Companion (Android)
### Cam Efektli Akıllı Baloncuk • Canlı Ses Dikte • Gemini 3.8 Flash OCR & CamScanner PDF

Gemini Floating Companion; Xiaomi HyperOS / MIUI ve tüm modern Android cihazlar için tasarlanmış, sistem kaynaklarını yormayan (< 5MB), sıfır OOM çöküş garantili yeni nesil bir yapay zeka asistanıdır.

---

## 🚀 Öne Çıkan Temel Yetenekler

### 1. 🪟 Akıllı Otomatik Gizleme & Cam Efekti (Glassmorphism Bento)
- **Klavye / Mesaj Alanı Odak Algılama:** `AccessibilityService` (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED`) sayesinde yalnızca siz bir mesaj kutusuna, arama çubuğuna veya not uygulamasına dokunup klavyeyi açtığınızda ekranda belirir.
- **Otomatik Gizlenme:** Mesajlaşmadan veya yazı alanından çıktığınızda baloncuk kendiliğinden pürüzsüz bir animasyonla ekrandan kaybolur, ekranı asla kirletmez.
- **Açık & Koyu Tema Uyumlu Cam Efekti:** Sistemin temasını anlık takip eden buzlu cam (frosted glass), dinamik kenarlık ışıması ve yaylı kenara yapışma (magnetic edge snap) fiziği.

### 2. 🎙️ Tek Dokunuş: Anlık Canlı Ses Dikte (Gemini Live API)
- Geleneksel STT sistemleri gibi konuşmanın bitmesini beklemez!
- 16.000 Hz 16-bit Mono PCM ses doğrudan Google Cloud WebSocket tüneline (`models/gemini-3.5-transcribe-live`) akar.
- **Siz konuştukça her hece ve kelime gerçek zamanlı olarak aktif metin kutusuna doğrudan yazılır.**

### 3. 📸 Uzun Basma: Hızlı Eylem Menüsü (Quick Action Drawer)
Baloncuğa 400ms basılı tutulduğunda cam bento eylem menüsü açılır:
1. **🎙️ Canlı Dikte:** Doğrudan gerçek zamanlı deşifreyi başlatır.
2. **📸 Akıllı Görsel OCR (Gemini 3.8 Flash):**
   - WhatsApp veya Notlar üzerindeyken ekranı kapatmayan yüzen kamera vizörü açılır.
   - Herhangi bir fiziksel tablo, el yazısı, fiş veya not fotoğraflandığında Gemini 3.8 Flash görseli analiz eder.
   - Tabloları doğrudan temiz **Markdown Tablosu** (`| Başlık |`), görevleri **Yapılacaklar Listesi** (`- [ ]`) formatına dönüştürerek aktif yazı alanına anında yapıştırır.
3. **📄 CamScanner Tarayıcı & PDF İhracı:**
   - Belgenin perspektifini ve kontrastını (CamScanner filtre algoritması) otomatik iyileştirir.
   - Android `PdfDocument` ile kristal netliğinde A4 PDF üretir.
   - Doğrudan sistem paylaşım menüsünü (`ACTION_SEND`) tetikleyerek WhatsApp, Telegram veya Mail'e hazır hale getirir.

### 4. 🛡️ Xiaomi HyperOS / MIUI Kesintisiz Çalışma Koruması
- Düşük öncelikli arka plan `ForegroundService` bildirimi.
- `BOOT_COMPLETED` ile telefon her açıldığında otomatik canlanma.
- HyperOS Otomatik Başlatma (Autostart) ve Pil Optimizasyonu muafiyet arayüzü.

---

## 🛠️ Kurulum ve Derleme Rehberi

### Gereksinimler
- Android SDK (API 26..35)
- Java 17+ (Homebrew OpenJDK 17)

### Hızlı Derleme (Terminal)
```bash
cd /Users/bekir/Uygulamalarim/GeminiFloatingCompanion
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
```

Derlenen APK konumu:
`app/build/outputs/apk/debug/app-debug.apk`

### Telefona Yükleme ve İzinleri Tek Komutla Verme
```bash
bash build_and_install.sh
```
