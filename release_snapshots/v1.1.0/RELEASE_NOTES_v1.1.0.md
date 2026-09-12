# Gemini Floating Companion - Sürüm 1.1.0 (Stabil Milestone)

**Tarih:** 12 Eylül 2026  
**Hedef Platform:** Xiaomi 13 (Android 14 / HyperOS)  
**Paket Adı:** `com.gemini.floatingcompanion`  
**Version Code:** 2  
**Version Name:** 1.1.0  

---

## 🌟 Bu Sürümde Neler Var? (Tamamlanan Özellikler)
1. **Minimalist Cam Bento Baloncuk (Floating Bubble)**:
   - Giriş kutusu (Gboard/klavye) açıldığında otomatik belirir, klavye kapandığında gizlenir.
   - Sürükle-bırak ve ekran kenarına yumuşak yapışma (Snap-to-Edge).
   - Tek dokunuşla Canlı Dikte, uzun basışla 4 seçenekli Radyal Menü.
2. **Akıllı Kamera & Canlı OCR**:
   - CameraX canlı vizör entegrasyonu.
   - Klavyenin üzerinde kalacak şekilde dikey marjin hesaplaması (110dp).
   - Çekim yapıldığı anda arayüz anında kapanır (`closeCamera()`), arka planda Gemini Vision analizi çalışır.
   - Analiz edilen tablo, metin ve listeler doğrudan aktif WhatsApp/mesaj kutusuna yazılır (`GeminiAccessibilityService.instance?.insertText()`) ve panoya kopyalanır.
3. **Kesintisiz Çoklu API Failover**:
   - 5 farklı Gemini API anahtarı arasında rotasyon ve failover havuzu (`GeminiApiKeyManager`).
   - `gemini-3.8-flash` yoğunluk anında (HTTP 503 Spike) veya kota aşımında otomatik olarak ultra hızlı `gemini-3.5-flash-lite` modeline geçerek işlemi kesintisiz tamamlar.
4. **Sıfır Sızıntı & Gizlilik Mimarisi**:
   - API anahtarları kaynak koddan temizlendi; `local.properties` ve `BuildConfig` mimarisine taşındı.
   - GitHub deposuna hiçbir özel anahtar veya token gönderilmez.
