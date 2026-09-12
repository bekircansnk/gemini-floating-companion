# 🌟 Gemini Floating Companion — Master Mimari & Sistem Sözleşmesi (AI Referans Belgesi)

## 📌 1. Proje Genel Bakışı
- **Adı:** Gemini Floating Companion
- **Platform:** Android (API 26..35 - Kotlin)
- **Hedef Cihazlar:** Xiaomi HyperOS / MIUI ve tüm modern Android cihazlar.
- **Tasarım:** Cam Bento Grid (Glassmorphism), Magnetic Edge Snap, Akıllı Otomatik Gizlenme.
- **Temel Yetenekler:**
  1. Gerçek Zamanlı Canlı Ses Dikte (Gemini Live API - 16kHz PCM WebSocket)
  2. Akıllı Yüzen Kamera & OCR (Gemini 3.8 Flash ile Markdown Tablosu & Todo çıkarma)
  3. CamScanner Algoritması & Tek Tuşla A4 PDF Üretimi / WhatsApp Paylaşımı
  4. Klavye / Metin Alanı Odak Algılama (`AccessibilityService`)

---

## 🏛️ 2. Sistem Mimarisi ve Katmanlar

### 2.1 Arka Plan ve Servis Katmanı
- **`FloatingBubbleService.kt` (`AccessibilityService`):**
  - Klavye açıldığında veya bir `EditText` odaklandığında (`TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED`) otomatik olarak yüzen balonu görünür yapar.
  - Odak kaybedildiğinde veya klavye kapandığında yumuşak animasyonla gizlenir.
  - `WindowManager` üzerinde `FLAG_NOT_TOUCH_MODAL` ve `FLAG_WATCH_OUTSIDE_TOUCH` kullanır.

### 2.2 Yüzen Arayüz Katmanı (Overlay Engine)
- **`FloatingCameraOverlay.kt`:**
  - Yüzen kamera vizörünü ve cam menüyü yönetir.
  - Klavyenin üstünde kalması için alt kenar boşluğu (margin bottom) 110dp olarak ayarlanır.
  - **Sözleşme Kuralı:** `FLAG_NOT_FOCUSABLE` zorunludur; aksi takdirde sistem klavyesi (IME) kapanır.
  - Fotoğraf çekildiği anda vizör animasyonla hemen kapanır (Instant Close); OCR analizi arka planda sessizce yürütülür ve sonuç metin alanına `GLOBAL_ACTION_PASTE` veya erişilebilirlik düğümü üzerinden doğrudan yazılır.

### 2.3 Gemini Entegrasyon Katmanı
- **Canlı Ses (Live STT):** `models/gemini-3.5-transcribe-live` WebSocket tüneli. 16.000 Hz 16-bit Mono PCM ses doğrudan Google Cloud'a akar; hece hece metin kutusuna dökülür.
- **Canlı Video & Sesli Çeviri (Speech-to-Speech):** `models/gemini-3.5-live-translate-preview` WebSocket tüneli. 16kHz PCM mikrofon/dahili medya girişi -> Google Cloud -> 24kHz saf insan sesi PCM çıkışı (`AudioTrack`). `echoTargetLanguage: false` ile akustik döngü önlenir; `AudioAttributes.USAGE_MEDIA` ile Xiaomi Ses Asistanı bağımsız ses kanalları korunur.
- **Görsel OCR:** `gemini-3.8-flash` REST / SDK çağrısı. Belgedeki tabloları `| Kolon |` Markdown tablosuna, listeleri `- [ ]` formatına çevirir.

---

## 📜 3. Kritik Mühendislik Kuralları & Anti-Pattern'ler
1. **Pencere Çakışması:** Overlay pencerelerine asla klavyeyi engelleyecek odak bayrağı (`FLAG_ALT_FOCUSABLE_IM` hariç) verilmez.
2. **Sıfır OOM:** Kamera önizleme bitmap'leri doğrudan `CompressFormat.JPEG` kalitesiyle geçici stream'e aktarılır, bellekte tutulmaz.
3. **HyperOS Koruması:** `BOOT_COMPLETED` alıcısı ve pil kısıtlaması muafiyeti bildirim paneli hazır tutulmalıdır.
4. **WebSocket Kurulum Şeması:** `gemini-3.5-live-translate-preview` modelinde `inputAudioTranscription` ve `outputAudioTranscription` doğrudan `setup` kökünde, `translationConfig` ise `generationConfig` içinde olmalıdır.
5. **Ses Donanımı Dokunulmazlığı:** Global `AudioManager.setStreamVolume` çağrısı yapılmaz; arka plan sesini kısmak için yalnızca `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` talep edilir.
