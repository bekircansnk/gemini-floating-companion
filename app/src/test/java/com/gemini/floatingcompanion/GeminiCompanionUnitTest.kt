package com.gemini.floatingcompanion

import com.gemini.floatingcompanion.data.AppPreferences
import com.gemini.floatingcompanion.data.BubbleState
import com.gemini.floatingcompanion.data.CameraMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("ız efendim", delta3) // notice incremental suffix
    }
}
