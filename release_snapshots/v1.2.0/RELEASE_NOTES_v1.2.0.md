# Gemini Floating Companion - Sürüm 1.2.0 (Stabil Canlı Ses & Canlı Video Çevirisi)

**Tarih:** 12 Eylül 2026  
**Hedef Platform:** Xiaomi 13 (Android 14 / HyperOS)  
**Paket Adı:** `com.gemini.floatingcompanion`  
**Version Code:** 3  
**Version Name:** 1.2.0  

---

## 🌟 Bu Sürümde Neler Var? (Tamamlanan & Kanıtlanan Özellikler)
1. **Gemini 3.5 Live Video Sesli Çeviri (Speech-to-Speech)**:
   - `gemini-3.5-live-translate-preview` WebSocket protokolü tam RFC standardına oturtuldu.
   - Doğal insan sesi kalitesinde 24kHz PCM doğrudan `AudioTrack` ile çalınıyor (sıfır yerel robotik TTS).
   - `echoTargetLanguage: false` ile modelin kendi ürettiği Türkçe sesi tekrar duyarak sonsuz döngüye girmesi engellendi.
   - `AudioAttributes.USAGE_MEDIA` ve `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` sayesinde Xiaomi Ses Asistanı (Çoklu ses kaynakları) bozulmadan YouTube sesi arka planda kısılarak bağımsız çalışır.
2. **Ultra Düşük Kota API Test Mimarisi**:
   - Canlı Video Çevirisi için sıfır maliyetli WebSocket setup el sıkışması (`setupComplete` pingleme).
   - Sesli Konuşma ve Metin için `gemini-3.5-flash-lite` 1-token pinglemesi.
   - Her iki alt sistem arayüzde ayrı ayrı aktiflik durumunu listeler.
3. **2D Serbest Sürüklenebilir Altyazı Baloncuğu**:
   - Video izleme deneyimini kesintiye uğratmayan yüksek saydamlık (`#380B1220`).
   - Tek tıkla canlı çeviriyi durdurma, uzun basışla altyazıyı gizleme/gösterme kontrolü.
   - Ekran üzerinde istenilen herhangi bir konuma serbestçe taşınabilme (`FloatingSubtitleOverlay`).
4. **Güvenlik ve Sıfır Sızıntı**:
   - API anahtarları `local.properties` ve `GeminiApiKeyManager` havuzunda güvendedir; git deposuna asla sızdırılmaz.
