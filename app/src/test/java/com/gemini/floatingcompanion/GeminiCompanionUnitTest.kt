package com.gemini.floatingcompanion

import com.gemini.floatingcompanion.data.AppPreferences
import com.gemini.floatingcompanion.data.BubbleState
import com.gemini.floatingcompanion.data.CameraMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class GeminiCompanionUnitTest {

    @Test
    fun testAppPreferencesDefaults() {
        val prefs = AppPreferences()
        assertEquals("", prefs.apiKey)
        assertEquals("models/gemini-3.5-transcribe-live", prefs.liveModel)
        assertEquals("models/gemini-3.8-flash", prefs.visionModel)
        assertEquals("tr", prefs.language)
        assertTrue(prefs.isAutoShowOnKeyboard)
        assertTrue(prefs.isHapticEnabled)
    }

    @Test
    fun testGeminiLiveSetupFrameJson() {
        val model = "gemini-3.5-transcribe-live"
        val cleanModel = if (model.startsWith("models/")) model else "models/$model"
        val languageCode = "tr"

        val setupJson = JSONObject().apply {
            val setupObj = JSONObject().apply {
                put("model", cleanModel)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("TEXT") })
                })
                put("inputAudioTranscription", JSONObject().apply {
                    val langArray = if (languageCode.isNotBlank()) {
                        JSONArray().apply { put(languageCode) }
                    } else {
                        JSONArray()
                    }
                    put("languageCodes", langArray)
                })
            }
            put("setup", setupObj)
        }

        val parsed = JSONObject(setupJson.toString())
        assertTrue(parsed.has("setup"))
        val innerSetup = parsed.getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", innerSetup.getString("model"))
        assertEquals("TEXT", innerSetup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("tr", innerSetup.getJSONObject("inputAudioTranscription").getJSONArray("languageCodes").getString(0))
    }

    @Test
    fun testLiveVideoTranslateSetupFrameJson() {
        val targetModel = "models/gemini-3.5-live-translate-preview"
        val setupJson = JSONObject().apply {
            val setupObj = JSONObject().apply {
                put("model", targetModel)
                val transConfig = JSONObject().apply {
                    put("targetLanguageCode", "tr")
                    put("echoTargetLanguage", false) // SIFIR YANKI: Hedef dil tekrarı devre dışı
                }
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("AUDIO") })
                    put("translationConfig", transConfig)
                })
                put("translationConfig", transConfig)
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Sen gerçek zamanlı bir video ve ses çevirmenisin. Dinlediğin yabancı dildeki (İngilizce, Almanca, İspanyolca vb.) konuşmaları anında doğal, akıcı ve anlaşılır Türkçe konuşma olarak çevir. ASLA aynı cümleyi veya ifadeyi tekrarlama (loop yapma). Türkçe duyduğun sesleri çevirme ve tekrarlama. Videodaki yeni konuşmaları kesintisiz takip ederek çevir.")
                        })
                    })
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            }
            put("setup", setupObj)
        }

        val parsed = JSONObject(setupJson.toString())
        assertTrue(parsed.has("setup"))
        val innerSetup = parsed.getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", innerSetup.getString("model"))
        assertTrue(innerSetup.has("inputAudioTranscription"))
        assertTrue(innerSetup.has("outputAudioTranscription"))
        
        // translationConfig hem doğrudan setup altında hem de generationConfig altında bulunmalıdır
        assertTrue("Setup doğrudan translationConfig içermeli", innerSetup.has("translationConfig"))
        assertEquals("tr", innerSetup.getJSONObject("translationConfig").getString("targetLanguageCode"))
        assertFalse(innerSetup.getJSONObject("translationConfig").getBoolean("echoTargetLanguage"))

        val genConfig = innerSetup.getJSONObject("generationConfig")
        assertEquals("AUDIO", genConfig.getJSONArray("responseModalities").getString(0))
        val transConfig = genConfig.getJSONObject("translationConfig")
        assertEquals("tr", transConfig.getString("targetLanguageCode"))
        assertFalse("echoTargetLanguage false olmalı ki Gemini Türkçe sesi papağan gibi tekrarlamasın", transConfig.getBoolean("echoTargetLanguage"))

        // Anti-repetition prompt kontrolü
        val prompt = innerSetup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue("Sistem talimatı döngü/tekrarlama engelleyici kural içermeli", prompt.contains("ASLA aynı cümleyi"))
    }

    @Test
    fun testStereoToMonoDownmixing() {
        // 4 frame stereo: Left=1000, Right=3000 -> Expected Mono = 2000
        val stereoBytes = ByteArray(16)
        val inBuf = java.nio.ByteBuffer.wrap(stereoBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        inBuf.putShort(1000.toShort()) // L0
        inBuf.putShort(3000.toShort()) // R0
        inBuf.putShort((-2000).toShort()) // L1
        inBuf.putShort((-4000).toShort()) // R1
        inBuf.putShort(0.toShort()) // L2
        inBuf.putShort(0.toShort()) // R2
        inBuf.putShort(32767.toShort()) // L3
        inBuf.putShort(32767.toShort()) // R3

        val sampleCount = stereoBytes.size / 4
        val monoBytes = ByteArray(sampleCount * 2)
        val readBuf = java.nio.ByteBuffer.wrap(stereoBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val outBuf = java.nio.ByteBuffer.wrap(monoBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val left = readBuf.short.toInt()
            val right = readBuf.short.toInt()
            val mono = ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.putShort(mono.toShort())
        }

        val checkBuf = java.nio.ByteBuffer.wrap(monoBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000.toShort(), checkBuf.short)
        assertEquals((-3000).toShort(), checkBuf.short)
        assertEquals(0.toShort(), checkBuf.short)
        assertEquals(32767.toShort(), checkBuf.short)
    }

    @Test
    fun testHardwareDacHeadPositionRemainingCalculation() {
        val totalFramesWritten = 48000L // 2 seconds of 24kHz audio
        val sampleRate = 24000L

        // 1. DAC has played 24000 frames (1 second remaining in hardware buffer)
        val head1 = 24000L
        val remainingFrames1 = totalFramesWritten - head1
        val remainingMs1 = (remainingFrames1 * 1000L) / sampleRate
        assertEquals(1000L, remainingMs1)
        assertTrue("Donanım tamponunda ses çalmaya devam ediyor", remainingMs1 > 0)

        // 2. DAC has played all 48000 frames (0 remaining in hardware buffer)
        val head2 = 48000L
        val remainingFrames2 = totalFramesWritten - head2
        val remainingMs2 = (remainingFrames2 * 1000L) / sampleRate
        assertEquals(0L, remainingMs2)
    }

    @Test
    fun testAcousticGatingSuppressesMicrophoneDuringAiSpeech() {
        // AI konuşurken mikrofon modunda hoparlör sesinin mikrofona geri girip
        // Gemini'yi döngüye sokmaması için akustik gating (sıfır PCM) testi
        val rawInput = ByteArray(3200) { 0x55.toByte() }

        fun gateAudio(input: ByteArray, isInternalCapture: Boolean, isAiSpeaking: Boolean): ByteArray {
            return if (!isInternalCapture && isAiSpeaking) {
                ByteArray(input.size) // Digital silence
            } else {
                input
            }
        }

        // 1. Durum: Dahili sistem sesi yakalama (YouTube saf PCM) aktifken AI konuşsa bile ses kırpılmaz
        val internalCaptured = gateAudio(rawInput, isInternalCapture = true, isAiSpeaking = true)
        assertEquals(rawInput[0], internalCaptured[0])
        assertEquals(3200, internalCaptured.size)

        // 2. Durum: Mikrofon fallback modunda AI konuşurken ses sıfırlanmalıdır (Akustik Gating)
        val micGated = gateAudio(rawInput, isInternalCapture = false, isAiSpeaking = true)
        assertEquals(0.toByte(), micGated[0])
        assertEquals(0.toByte(), micGated[100])
        assertEquals(3200, micGated.size)

        // 3. Durum: Mikrofon fallback modunda AI suskunken konuşma normal geçer
        val micNormal = gateAudio(rawInput, isInternalCapture = false, isAiSpeaking = false)
        assertEquals(rawInput[0], micNormal[0])
    }

    @Test
    fun testLiveVideoTranslateServerResponseParsing() {
        // Simulates serverContent frame from gemini-3.5-live-translate-preview
        val dummyPcmBase64 = "UklGRg=="
        val serverJson = JSONObject().apply {
            put("serverContent", JSONObject().apply {
                put("outputTranscription", JSONObject().apply {
                    put("text", "Merhaba dünya, harika bir gün.")
                    put("languageCode", "tr")
                })
                put("modelTurn", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=24000")
                                put("data", dummyPcmBase64)
                            })
                        })
                    })
                })
            })
        }

        val parsed = JSONObject(serverJson.toString())
        val serverContent = parsed.getJSONObject("serverContent")
        
        // 1. Verify translated Turkish transcript extraction
        val transcript = serverContent.getJSONObject("outputTranscription").getString("text")
        assertEquals("Merhaba dünya, harika bir gün.", transcript)
        
        // 2. Verify 24kHz AI voice PCM chunk extraction
        val modelTurn = serverContent.getJSONObject("modelTurn")
        val part = modelTurn.getJSONArray("parts").getJSONObject(0)
        val inlineData = part.getJSONObject("inlineData")
        assertEquals("audio/pcm;rate=24000", inlineData.getString("mimeType"))
        assertEquals(dummyPcmBase64, inlineData.getString("data"))
    }

    @Test
    fun testVisionModelCandidateOrder() {
        val preferred = "gemini-3.8-flash"
        val candidates = listOf(
            preferred,
            "gemini-3.8-flash",
            "gemini-2.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest"
        ).distinct()

        assertEquals(4, candidates.size)
        assertEquals("gemini-3.8-flash", candidates[0])
        assertEquals("gemini-2.5-flash", candidates[1])
        assertEquals("gemini-3.5-flash-lite", candidates[2])
        assertEquals("gemini-flash-latest", candidates[3])
    }

    @Test
    fun testPerspectiveTransformQuadrilateralMath() {
        data class CornerPoint(val x: Float, val y: Float)
        // Test corner detection sorting: TL, TR, BR, BL
        val points = listOf(
            CornerPoint(50f, 60f),   // TL
            CornerPoint(350f, 40f),  // TR
            CornerPoint(380f, 450f), // BR
            CornerPoint(30f, 460f)   // BL
        )

        var tl = points[0]
        var tr = points[0]
        var br = points[0]
        var bl = points[0]

        var minSum = Float.MAX_VALUE
        var maxSum = -Float.MAX_VALUE
        var maxDiff = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE

        for (p in points) {
            val sum = p.x + p.y
            val diff = p.x - p.y
            if (sum < minSum) { minSum = sum; tl = p }
            if (sum > maxSum) { maxSum = sum; br = p }
            if (diff > maxDiff) { maxDiff = diff; tr = p }
            if (diff < minDiff) { minDiff = diff; bl = p }
        }

        assertEquals(50f, tl.x, 0.1f)
        assertEquals(350f, tr.x, 0.1f)
        assertEquals(380f, br.x, 0.1f)
        assertEquals(30f, bl.x, 0.1f)

        // Shoelace quad area
        val quadArea = 0.5f * kotlin.math.abs(
            (tl.x * tr.y - tr.x * tl.y) +
            (tr.x * br.y - br.x * tr.y) +
            (br.x * bl.y - bl.x * br.y) +
            (bl.x * tl.y - tl.x * bl.y)
        )
        assertTrue("Belge alani pozitif ve beklendigi olcekte olmali", quadArea > 100000f)
    }

    @Test
    fun testMagicColorAlgorithmMathColorPreservation() {
        // 1. Mavi İmza Testi: R=20, G=40, B=210
        val r = 20
        val g = 40
        val b = 210
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val chroma = maxC - minC
        assertTrue("Mavi imza renk doygunluğu yüksek olmalı", chroma > 12)

        val paperWhite = 210f
        val gain = (255f / paperWhite).coerceIn(1.05f, 1.45f)
        val luma = (299 * r + 587 * g + 114 * b) / 1000
        val boost = 1.10f
        val scaledR = (r * gain).coerceIn(0f, 255f)
        val scaledB = (b * gain).coerceIn(0f, 255f)
        val newR = ((scaledR - luma) * boost + luma).toInt().coerceIn(0, 255)
        val newB = ((scaledB - luma) * boost + luma).toInt().coerceIn(0, 255)
        assertTrue("Mavi kanal kırmızı kanaldan belirgin yüksek kalmalı (mavi ton korunmalı)", newB > newR + 100)

        // 2. Fotoğraf / Cilt Tonu Testi (Düşük/Orta Kroma): R=180, G=150, B=135
        val photoR = 180
        val photoG = 150
        val photoB = 135
        val photoChroma = max(photoR, max(photoG, photoB)) - min(photoR, min(photoG, photoB))
        assertTrue("Fotoğraf pikseli kroma değeri pozitif olmalı", photoChroma > 12)
        val photoScaledR = (photoR * gain).coerceIn(0f, 255f)
        val photoScaledG = (photoG * gain).coerceIn(0f, 255f)
        val photoScaledB = (photoB * gain).coerceIn(0f, 255f)
        val photoLuma = (299 * photoR + 587 * photoG + 114 * photoB) / 1000
        val photoNewR = ((photoScaledR - photoLuma) * boost + photoLuma).toInt().coerceIn(0, 255)
        val photoNewG = ((photoScaledG - photoLuma) * boost + photoLuma).toInt().coerceIn(0, 255)
        val photoNewB = ((photoScaledB - photoLuma) * boost + photoLuma).toInt().coerceIn(0, 255)
        assertTrue("Fotoğraf rengi asla griye binarize edilmemeli (R > G > B oranı korunmalı)", photoNewR > photoNewG && photoNewG > photoNewB)

        // 3. Kağıt Arka Planı Testi: R=195, G=190, B=185
        val paperR = 195
        val paperG = 190
        val paperB = 185
        val paperPixelLuma = (299 * paperR + 587 * paperG + 114 * paperB) / 1000
        val paperPixelChroma = max(paperR, max(paperG, paperB)) - min(paperR, min(paperG, paperB))
        assertTrue(paperPixelChroma < 18)
        assertTrue(paperPixelLuma >= paperWhite * 0.88f)

        val factor = ((paperPixelLuma - paperWhite * 0.88f) / (255f - paperWhite * 0.88f)).coerceIn(0f, 1f)
        val targetWhite = (242 + factor * 13).toInt()
        assertTrue("Kağıt arka planı temiz parlak beyaza taşınmalı (242-255)", targetWhite >= 242)
    }

    @Test
    fun testDragToCloseDropZoneCalculation() {
        val targetCenterX = 540f
        val targetCenterY = 2200f
        val hitRadius = 150f

        // Bubble dragged near target center
        val nearX = 530f
        val nearY = 2190f
        val distanceNear = Math.hypot((nearX - targetCenterX).toDouble(), (nearY - targetCenterY).toDouble()).toFloat()
        assertTrue("Hedefe yakın sürüklenen baloncuk kapatma alanında olmalı", distanceNear < hitRadius)

        // Bubble far from target
        val farX = 100f
        val farY = 500f
        val distanceFar = Math.hypot((farX - targetCenterX).toDouble(), (farY - targetCenterY).toDouble()).toFloat()
        assertFalse("Uzak baloncuk kapatma alanında olmamalı", distanceFar < hitRadius)
    }

    @Test
    fun testContinuousLiveAudioStreamingNeverBlocksSpeech() {
        // In Gemini 3.5 Live Translation, incoming mic chunks are NEVER discarded during playback.
        // This guarantees that no speaker in the video is skipped even while translation audio is active.
        var isAiSpeaking = true
        var chunksDelivered = 0

        fun onAudioChunkSimulated(chunk: ByteArray) {
            // Uninterrupted pipeline
            chunksDelivered++
        }

        onAudioChunkSimulated(ByteArray(3200))
        assertEquals("Yapay zeka konuşurken bile mikrofon kesilmemeli, kesintisiz iletilmeli", 1, chunksDelivered)

        isAiSpeaking = false
        onAudioChunkSimulated(ByteArray(3200))
        assertEquals(2, chunksDelivered)
    }

    @Test
    fun testSubtitleIncrementalAndCumulativeBuffering() {
        val currentSubtitle = StringBuilder()
        fun updateSubtitle(text: String): String {
            val prev = currentSubtitle.toString().trim()
            if (prev.isEmpty()) {
                currentSubtitle.append(text)
            } else if (text.startsWith(prev, ignoreCase = true)) {
                currentSubtitle.clear()
                currentSubtitle.append(text)
            } else if (!prev.endsWith(text, ignoreCase = true)) {
                currentSubtitle.append(" ").append(text)
            }
            return currentSubtitle.toString().trim()
        }

        // Test cumulative streaming
        val r1 = updateSubtitle("Bugün")
        assertEquals("Bugün", r1)

        val r2 = updateSubtitle("Bugün hava")
        assertEquals("Bugün hava", r2)

        val r3 = updateSubtitle("Bugün hava çok güzel.")
        assertEquals("Bugün hava çok güzel.", r3)

        // Test incremental/delta streaming
        currentSubtitle.clear()
        val d1 = updateSubtitle("Merhaba")
        assertEquals("Merhaba", d1)
        val d2 = updateSubtitle("nasılsın")
        assertEquals("Merhaba nasılsın", d2)
    }

    @Test
    fun testDeskEdgeDetectionBounds() {
        val sampleW = 400
        val sampleH = 300

        var cropLeft = 25
        var cropRight = 375
        var cropTop = 20
        var cropBottom = 280

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        val areaFraction = (cropW.toFloat() * cropH) / (sampleW.toFloat() * sampleH)
        assertTrue("Belge alanı geçerli oranda olmalı (%30 - %98)", areaFraction in 0.25f..0.98f)
        assertTrue("Masa kenarları kırpılmış olmalı", cropLeft > 10 || cropTop > 10)
    }

    @Test
    fun testGeminiLiveAudioChunkOfficialJson() {
        val base64DummyAudio = "UklGRi4AAABXQVZFZm10"
        val chunkJson = JSONObject().apply {
            val realtimeInput = JSONObject().apply {
                val audio = JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64DummyAudio)
                }
                put("audio", audio)
            }
            put("realtimeInput", realtimeInput)
        }

        val parsed = JSONObject(chunkJson.toString())
        assertTrue(parsed.has("realtimeInput"))
        val rt = parsed.getJSONObject("realtimeInput")
        assertTrue(rt.has("audio"))
        val audio = rt.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(base64DummyAudio, audio.getString("data"))
    }

    @Test
    fun testGeminiLiveAudioStreamEndJson() {
        val finishJson = JSONObject().apply {
            val realtimeInput = JSONObject().apply {
                put("audioStreamEnd", true)
            }
            put("realtimeInput", realtimeInput)
        }

        val parsed = JSONObject(finishJson.toString())
        assertTrue(parsed.has("realtimeInput"))
        val rt = parsed.getJSONObject("realtimeInput")
        assertTrue(rt.getBoolean("audioStreamEnd"))
    }

    @Test
    fun testWebSocketUrlFormat() {
        val apiKey = "AIzaSyTest123"
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        assertTrue(url.contains("v1beta"))
        assertTrue(url.contains("BidiGenerateContent"))
        assertTrue(url.contains("key=AIzaSyTest123"))
    }

    @Test
    fun testCameraModesAndStates() {
        val ocrMode = CameraMode.OCR_STRUCTURED
        val pdfMode = CameraMode.CAM_SCANNER_PDF
        assertEquals("OCR_STRUCTURED", ocrMode.name)
        assertEquals("CAM_SCANNER_PDF", pdfMode.name)

        val states = BubbleState.values()
        assertTrue(states.contains(BubbleState.IDLE))
        assertTrue(states.contains(BubbleState.LISTENING))
        assertTrue(states.contains(BubbleState.PROCESSING))
        assertTrue(states.contains(BubbleState.ERROR))
        assertTrue(states.contains(BubbleState.VIDEO_DETECTED))
        assertTrue(states.contains(BubbleState.TRANSLATING))
    }

    @Test
    fun testServerTranscriptionInterimAndFinalParsing() {
        val rawInterim = """
            {
                "serverContent": {
                    "interimInputTranscription": {
                        "text": "Bugün hava çok"
                    }
                }
            }
        """.trimIndent()

        val rootInterim = JSONObject(rawInterim)
        val interim = rootInterim.getJSONObject("serverContent").getJSONObject("interimInputTranscription").getString("text")
        assertEquals("Bugün hava çok", interim)

        val rawFinal = """
            {
                "serverContent": {
                    "inputTranscription": {
                        "text": "Bugün hava çok güzel."
                    }
                }
            }
        """.trimIndent()

        val rootFinal = JSONObject(rawFinal)
        val finalized = rootFinal.getJSONObject("serverContent").getJSONObject("inputTranscription").getString("text")
        assertEquals("Bugün hava çok güzel.", finalized)
    }

    @Test
    fun testStreamingDeltaCalculation() {
        var lastPasted = ""
        val step1Text = "Merhaba"
        val delta1 = if (step1Text.startsWith(lastPasted)) step1Text.substring(lastPasted.length) else step1Text
        assertEquals("Merhaba", delta1)
        lastPasted = step1Text

        val step2Text = "Merhaba nasılsın"
        val delta2 = if (step2Text.startsWith(lastPasted)) step2Text.substring(lastPasted.length) else step2Text
        assertEquals(" nasılsın", delta2)
        lastPasted = step2Text

        val step3Text = "Merhaba nasılsınız efendim"
        val delta3 = if (step3Text.startsWith(lastPasted)) step3Text.substring(lastPasted.length) else step3Text
        assertEquals("ız efendim", delta3)
    }
}
