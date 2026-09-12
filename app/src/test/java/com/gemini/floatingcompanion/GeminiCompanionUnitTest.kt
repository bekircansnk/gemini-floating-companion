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
        val model = "models/gemini-3.5-transcribe-live"
        val languageCode = "tr"

        val setupJson = JSONObject().apply {
            val setupObj = JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply { put("TEXT") })
                })
                put("inputAudioTranscription", JSONObject().apply {
                    put("languageCodes", JSONArray().apply { put(languageCode) })
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
    fun testGeminiLiveFinishFrameJson() {
        val finishJson = JSONObject().apply {
            val clientContent = JSONObject().apply {
                val turns = JSONArray().apply {
                    val turn = JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Konuşmayı Türkçe metin olarak yazıya dök.")
                            })
                        })
                    }
                    put(turn)
                }
                put("turns", turns)
                put("turnComplete", true)
            }
            put("clientContent", clientContent)
        }

        val parsed = JSONObject(finishJson.toString())
        assertTrue(parsed.has("clientContent"))
        val cc = parsed.getJSONObject("clientContent")
        assertTrue(cc.getBoolean("turnComplete"))
        val turns = cc.getJSONArray("turns")
        assertEquals(1, turns.length())
        assertEquals("user", turns.getJSONObject(0).getString("role"))
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
    fun testServerTranscriptionMessageParsing() {
        val rawMessage = """
            {
                "serverContent": {
                    "interimInputTranscription": {
                        "text": "Bugün hava çok"
                    }
                }
            }
        """.trimIndent()

        val root = JSONObject(rawMessage)
        val serverContent = root.getJSONObject("serverContent")
        val interim = serverContent.getJSONObject("interimInputTranscription").getString("text")
        assertEquals("Bugün hava çok", interim)
    }
}
