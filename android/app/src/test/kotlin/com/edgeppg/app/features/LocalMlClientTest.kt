package com.edgeppg.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMlClientTest {

    private val sampleModelJson = """
        {
          "model_name": "edgeppg_rf_v1",
          "schema_version": "edgeppg-1.0",
          "scaler": {
            "a": 17.702309536925988,
            "b": -8.653535480193897
          },
          "trees": [
            [
              {"f": 2, "th": 0.35, "l": 1, "r": 2, "p": 0.0},
              {"f": -1, "th": 0.0, "l": -1, "r": -1, "p": 0.05},
              {"f": -1, "th": 0.0, "l": -1, "r": -1, "p": 0.95}
            ],
            [
              {"f": 5, "th": 0.44, "l": 1, "r": 2, "p": 0.0},
              {"f": -1, "th": 0.0, "l": -1, "r": -1, "p": 0.10},
              {"f": -1, "th": 0.0, "l": -1, "r": -1, "p": 0.90}
            ]
          ]
        }
    """.trimIndent()

    @Test
    fun native_tree_client_predicts_correctly() {
        val client = NativeTreeMlClient.fromJson(sampleModelJson)
        assertTrue(client.isModelLoaded)
        assertEquals("edgeppg_rf_v1", client.loadedManifest?.get("model_name"))

        // Sample with high physiological signals (feature 2 = 0.8, feature 5 = 0.75)
        val liveRow = FloatArray(28) { 0.8f }
        val pLive = client.predictLiveProbability(liveRow)
        assertTrue("Expected high P(LIVE), got $pLive", pLive > 0.8f)

        // Sample with low signals (feature 2 = 0.1, feature 5 = 0.1)
        val spoofRow = FloatArray(28) { 0.1f }
        val pSpoof = client.predictLiveProbability(spoofRow)
        assertTrue("Expected low P(LIVE), got $pSpoof", pSpoof < 0.2f)
    }

    @Test
    fun native_tree_handles_nan_features_safely() {
        val client = NativeTreeMlClient.fromJson(sampleModelJson)
        val nanRow = FloatArray(28) { Float.NaN }
        val p = client.predictLiveProbability(nanRow)
        assertFalse(p.isNaN())
        assertTrue(p in 0.0001f..0.9999f)
    }

    @Test
    fun native_tree_rejects_wrong_length_row() {
        val client = NativeTreeMlClient.fromJson(sampleModelJson)
        val invalidRow = FloatArray(27) { 0.5f }
        val p = client.predictLiveProbability(invalidRow)
        assertTrue(p.isNaN())
    }

    @Test
    fun termux_client_graceful_fallback_when_offline() {
        // Point to an unused port
        val client = TermuxMlClient(endpointUrl = "http://127.0.0.1:54321/predict")
        assertFalse(client.isModelLoaded)
        val row = FloatArray(28) { 0.5f }
        val p = client.predictLiveProbability(row)
        assertTrue(p.isNaN())
    }

    @Test
    fun composite_ml_client_falls_back_to_native() {
        val native = NativeTreeMlClient.fromJson(sampleModelJson)
        val offlineTermux = TermuxMlClient(endpointUrl = "http://127.0.0.1:54321/predict")
        val composite = CompositeLocalMlClient(termuxClient = offlineTermux, nativeClient = native)

        assertTrue(composite.isModelLoaded)
        val liveRow = FloatArray(28) { 0.8f }
        val pLive = composite.predictLiveProbability(liveRow)
        assertEquals("NATIVE_RANDOM_FOREST", composite.lastInferenceSource)
        assertTrue(pLive > 0.8f)
    }
}
