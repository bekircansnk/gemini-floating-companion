# Gemini 3.5 Live Video Sesli Çeviri & WebSocket Stabilizasyon Rehberi

## 1. Genel Bakış ve Problem Tanımı
Gemini Live API'nin `gemini-3.5-live-translate-preview` modeli, düşük gecikmeli, gerçek zamanlı konuşmadan konuşmaya (speech-to-speech) çeviri için tasarlanmıştır.

### Yaşanan Temel Sorunlar:
1. **"Canlı Çeviri Başlatılıyor..." Aşamasında Kilitlenme**:
   - Google sunucuları WebSocket açılış çerçevesinde `1007 (Invalid JSON payload)` hatası dönerek bağlantıyı kesiyordu.
2. **Akustik Yankı & Sonsuz Tekrar Döngüsü**:
   - Modelin ürettiği Türkçe ses, telefon hoparlöründen çıkıp tekrar mikrofona giriyor ve papağan gibi aynı cümleyi tekrar ediyordu.
3. **Xiaomi Sound Assistant & Ses Seviyesi Ezilmesi**:
   - `STREAM_MUSIC` üzerinde global `setStreamVolume` çağrısı, telefonun genel donanım sesini kısıyor ve Xiaomi'nin bağımsız ses kaydırıcılarını bozuyordu.
4. **API Testlerinde Aşırı Limit Tüketimi**:
   - Basit bir bağlantı testi için ağır modeller çağrılarak kota gereksiz tüketiliyordu.

---

## 2. Çözüm Mimarisi & Standartlar

### 2.1 Google Gemini Live Translate WebSocket Setup Protokolü (RFC Düzeyi Kural)
Google'ın `gemini-3.5-live-translate-preview` modeli standart chat Live API'sinden farklı bir JSON şeması bekler:
- `inputAudioTranscription` ve `outputAudioTranscription` doğrudan `setup` nesnesi altında yer almalıdır.
- `translationConfig` ise mutlaka `generationConfig` nesnesinin içinde olmalıdır.
- `echoTargetLanguage: false` kuralı hedef dildeki (Türkçe) konuşmaları modelin tekrar etmesini önler.

```json
{
  "setup": {
    "model": "models/gemini-3.5-live-translate-preview",
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "translationConfig": {
        "targetLanguageCode": "tr",
        "echoTargetLanguage": false
      }
    }
  }
}
```

### 2.2 Ses Yönlendirme & Xiaomi Ses Ayrımı
- `AudioTrack` başlatılırken `AudioAttributes.USAGE_MEDIA` ve `AudioAttributes.CONTENT_TYPE_SPEECH` seçilir.
- Asla global `AudioManager.setStreamVolume` kullanılmaz.
- Video sesini arka planda kısmak için sadece `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` talep edilir.
- Böylece Xiaomi Ses Asistanı (Çoklu ses kaynakları) YouTube için ayrı, Gemini Companion için ayrı ses kaydırıcıları sunar.

### 2.3 Sıfır-Kota API Test Mimarisi
1. **Canlı Video Çevirisi Testi**:
   - `gemini-3.5-live-translate-preview` WebSocket tüneline bağlanılır.
   - Yalnızca `setup` mesajı iletilir.
   - `setupComplete` alındığı anda soket kapatılır (0 jeton tüketimi).
2. **Sesli Konuşma & Metin Testi**:
   - Ultra hafif `gemini-3.5-flash-lite` (yedek `gemini-2.5-flash`) modeline tek jetonluk ping atılır.
3. İki servis arayüzde ayrı ayrı raporlanır.

---

## 3. UI / UX Standartları
- **Altyazı Baloncuğu**:
  - 2D serbest sürüklenebilir (`FLAG_LAYOUT_NO_LIMITS` + dokunma takip).
  - Ekrandaki videoyu kapatmayacak ultra saydam cam tasarımı (`#380B1220`).
  - Çeviri sırasında baloncuğa **tek tık**: Çeviriyi anında durdurur.
  - Baloncuğa **uzun basma**: Altyazı penceresini gizler/gösterir.
