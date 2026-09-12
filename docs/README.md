# Gemini Floating Companion - Mimari ve Sistem Dokümantasyonu

## 1. Proje Özeti
Gemini Floating Companion, Xiaomi 13 (Android 14 / HyperOS) başta olmak üzere modern Android cihazlarda çalışan; klavye/metin odağını otomatik algılayıp ekranda kayan cam baloncuk (Glassmorphism Pill) oluşturan, sesli dikte, akıllı OCR ve YouTube/video canlı ses çevirisi sağlayan yerel Kotlin Android asistanıdır.

---

## 2. Temel Modüller & Görev Dağılımı

| Modül / Sınıf | Konum | Görev & Sorumluluk |
| :--- | :--- | :--- |
| `GeminiAccessibilityService` | `service/GeminiAccessibilityService.kt` | `TYPE_VIEW_FOCUSED`, `TYPE_WINDOWS_CHANGED`, klavye/Gboard açılış-kapanış tespiti, `insertText()` ile metin yapıştırma. |
| `FloatingBubbleService` | `service/FloatingBubbleService.kt` | Arka planda kesintisiz çalışan Foreground Service (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_MICROPHONE`). |
| `FloatingBubbleManager` | `overlay/FloatingBubbleManager.kt` | Baloncuk yaşam döngüsü, radyal menü, kamera penceresi ve canlı dikte/altyazı durum yönetimi. |
| `FloatingBubbleView` | `overlay/FloatingBubbleView.kt` | Minimalist dairesel baloncuk UI'ı. Tek tık (dikte/çeviri), uzun basış (radyal menü), kenara yapışma (snap-to-edge). |
| `RadialMenuView` | `overlay/RadialMenuView.kt` | 4 çekirdek aksiyonlu cam bento menü: Canlı Dikte, Akıllı OCR, PDF Belge, Video Çeviri. |
| `FloatingCameraOverlay` | `overlay/FloatingCameraOverlay.kt` | CameraX tabanlı metin/belge yakalama arayüzü. Klavye üstünde kalacak şekilde optimize edilmiş cam kart. |
| `GeminiVisionAnalyzer` | `vision/GeminiVisionAnalyzer.kt` | Çok modelleri failover zinciri (`gemini-3.8-flash` -> `gemini-2.5-flash` -> `gemini-3.5-flash-lite`). Markdown tablo & liste korumalı OCR. |
| `DocumentScannerProcessor` | `vision/DocumentScannerProcessor.kt` | Belge köşe tespiti (kontur analizi), renk korumalı CamScanner Magic Color filtresi, yüksek çözünürlüklü A4 PDF oluşturma ve paylaşma. |
| `GeminiApiKeyManager` | `data/GeminiApiKeyManager.kt` | 5+ API anahtarı arasında otomatik yük dengeleme ve 429/503 hata durumunda kesintisiz failover havuzu. |
| `LiveVideoTranslator` | `live/LiveVideoTranslator.kt` | Video veya sistem sesinden Türkçe altyazı ve dublaj üretimi. |

---

## 3. Akıllı Kamera & OCR İş Akışı
1. Kullanıcı baloncuk üzerine uzun basar -> Radyal menü açılır.
2. `Akıllı OCR` seçilir -> `FloatingCameraOverlay` açılır.
3. `Metni Tara ve Yapıştır` butonuna basılır:
   - Pencere derhal kapanır (kullanıcı arka plandaki mesaj alanına döner).
   - Çekilen kare arka planda `GeminiVisionAnalyzer` ile analiz edilir.
   - Analiz edilen metin panoya kopyalanır ve `GeminiAccessibilityService.instance?.insertText()` ile doğrudan aktif metin kutusuna yazılır.

---

## 4. HyperOS / Xiaomi Stabilite Kuralları
- **Pil Tasarrufu**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` izni verilmiş olmalıdır.
- **Otomatik Başlatma (Autostart)**: Güvenlik uygulamasından izin verilir.
- **Pencere Tipi**: `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`.
- **Erişilebilirlik Hizmeti**: `android:canPerformGestures="true"`, `android:canRetrieveWindowContent="true"`.
