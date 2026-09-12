# Kamera OCR, Klavye Entegrasyonu ve Xiaomi HyperOS Stabilizasyon Rehberi

## 1. Problem ve Kök Neden Analizi
1. **Pencere Çakışması & Klavye Kapanması**: CameraX overlay `FLAG_NOT_FOCUSABLE` bayrağı olmadan açıldığında sistem klavyesini (Gboard) zorla kapatıyordu. Çözüm: `FLAG_NOT_FOCUSABLE` ve `FLAG_LAYOUT_IN_SCREEN` bayrakları uygulandı.
2. **Klavye Altında Kalan Butonlar**: Ekranın alt yarısını klavye kapladığında `cameraCard` butonları ekran dışına itiliyordu. Çözüm: `cameraCard` alt marjini `110dp` olarak ayarlanarak klavyenin tam üzerine oturtuldu.
3. **Mükerrer Yazma & Baloncuk Metin Kalabalığı**: Dikte tamamlandığında metin tekrar yapıştırılıyor ve baloncuk üzerinde konuşulanlar beliriyordu. Çözüm: Baloncuk üzerinde yazı gösterimi tamamen kaldırıldı; dikte bittiğinde sadece temizleme ve anlık yapıştırma uygulandı.
4. **Google AI Studio 503 / 429 Aşımı**: `gemini-3.8-flash` yoğunluk anında 503 döndürdüğünde işlem iptal oluyordu. Çözüm: `GeminiVisionAnalyzer` içine `gemini-3.5-flash-lite` içeren otomatik failover zinciri eklendi.

---

## 2. Çözüm Mimarisi & Kod Kalıpları
- **Cerrahi Yapıştırma**:
```kotlin
val inserted = GeminiAccessibilityService.instance?.insertText(structuredText, isStreaming = false) ?: false
```
- **Hızlı Kapanma & Arka Plan Analizi**:
```kotlin
closeCamera() // Kamera penceresi beklemeden kapanır
scope.launch(Dispatchers.IO) {
    val result = analyzer.analyzeImageForStructuredText(file)
    withContext(Dispatchers.Main) {
        // Panoya kopyala ve aktif metin alanına yaz
    }
}
```

---

## 3. Doğrulama ve Canlı Test Kanıtı
- Xiaomi 13 (`62c0ba60`) üzerinde WhatsApp açıkken OCR çekimi yapılmış, Python kodu anında okunup mesaj kutusuna yazılmıştır.
