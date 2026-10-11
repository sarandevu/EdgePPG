package com.edgeppg.app.features

import com.edgeppg.app.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Termux Local ML Client.
 *
 * Connects to the local Python compute service running in Termux on localhost (127.0.0.1:8081).
 * Executes genuine frozen models with full Python scikit-learn stack on the iQOO device.
 * Never connects to any cloud or external network.
 */
class TermuxMlClient(
    val endpointUrl: String = "http://127.0.0.1:8081/predict",
    private val timeoutMs: Int = 400,
) : MlFusionClient {

    override val isModelLoaded: Boolean
        get() = checkHealth()

    override val loadedManifest: Map<String, String>?
        get() = mapOf(
            "runtime" to "Termux-Python-Local",
            "endpoint" to endpointUrl,
            "device" to "127.0.0.1 (local iQOO)",
        )

    fun checkHealth(): Boolean {
        return try {
            val statusUrl = URL(endpointUrl.replace("/predict", "/status"))
            val conn = statusUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 250
            conn.readTimeout = 250
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    override fun predictLiveProbability(
        row: FloatArray,
        schemaVersion: String,
    ): Float {
        if (row.size != 28) return Float.NaN

        return try {
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val featArr = JSONArray()
            for (f in row) {
                if (f.isNaN()) featArr.put(JSONObject.NULL) else featArr.put(f.toDouble())
            }

            val body = JSONObject().apply {
                put("protocol_version", 1)
                put("session_id", "sess-local-" + System.currentTimeMillis())
                put("feature_schema_version", schemaVersion)
                put("features", featArr)
            }.toString()

            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                val respText = conn.inputStream.bufferedReader().use { it.readText() }
                val respJson = JSONObject(respText)
                val pLive = respJson.optDouble("p_live", Double.NaN).toFloat()
                Log.stage("termux", "Termux inference success: P(LIVE)=$pLive")
                conn.disconnect()
                pLive
            } else {
                Log.error("termux", "Termux responded with code $code")
                conn.disconnect()
                Float.NaN
            }
        } catch (_: Exception) {
            // Local Termux daemon not running or timed out — return NaN for seamless fallback
            Float.NaN
        }
    }
}
