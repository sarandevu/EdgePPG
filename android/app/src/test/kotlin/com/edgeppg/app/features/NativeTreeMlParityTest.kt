package com.edgeppg.app.features

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * End-to-end PC-versus-Android ML parity test.
 *
 * Evaluates both the Random Forest baseline (edgeppg_rf_v1.json) and the
 * XGBoost challenger (edgeppg_xgb_v1.json) against the PC-generated
 * golden test vectors (data/golden_vectors.json), verifying that predictions
 * match within numerical tolerance (1e-4).
 */
class NativeTreeMlParityTest {

    private fun findFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("android/app/$relativePath"),
            File("app/$relativePath"),
            File("../$relativePath"),
            File("../../$relativePath"),
            File("src/main/assets/${File(relativePath).name}"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not find file: $relativePath. Checked: ${candidates.map { it.absolutePath }}")
    }

    @Test
    fun test_random_forest_and_xgboost_golden_vector_parity() {
        val rfJsonFile = findFile("android/app/src/main/assets/edgeppg_rf_v1.json")
        val xgbJsonFile = findFile("android/app/src/main/assets/edgeppg_xgb_v1.json")
        val goldenFile = findFile("data/golden_vectors.json")

        val rfClient = NativeTreeMlClient.fromJson(rfJsonFile.readText())
        val xgbClient = NativeXgbMlClient.fromJson(xgbJsonFile.readText())

        assertTrue("RF client must be loaded", rfClient.isModelLoaded)
        assertTrue("XGB client must be loaded", xgbClient.isModelLoaded)

        val goldenRoot = JSONObject(goldenFile.readText())
        val records = goldenRoot.getJSONArray("records")
        val tolerance = goldenRoot.optDouble("tolerance", 0.0001).toFloat()

        println("Running Golden Vector Parity on ${records.length()} records (tolerance: $tolerance)…")

        for (i in 0 until records.length()) {
            val rec = records.getJSONObject(i)
            val rowArr = rec.getJSONArray("row")
            val floatRow = FloatArray(28) { idx ->
                if (rowArr.isNull(idx)) Float.NaN else rowArr.getDouble(idx).toFloat()
            }

            val expectedRfCalP = rec.getDouble("rf_cal_p").toFloat()
            val expectedXgbCalP = rec.getDouble("xgb_cal_p").toFloat()

            // Run Android Kotlin evaluation
            val actualRfCalP = rfClient.predictLiveProbability(floatRow)
            val actualXgbCalP = xgbClient.predictLiveProbability(floatRow)

            val rfDiff = abs(actualRfCalP - expectedRfCalP)
            val xgbDiff = abs(actualXgbCalP - expectedXgbCalP)

            println("Record $i: RF expected=$expectedRfCalP actual=$actualRfCalP diff=$rfDiff | XGB expected=$expectedXgbCalP actual=$actualXgbCalP diff=$xgbDiff")

            assertTrue(
                "RF parity failed at record $i: actual=$actualRfCalP, expected=$expectedRfCalP, diff=$rfDiff > tolerance=$tolerance",
                rfDiff <= tolerance
            )
            assertTrue(
                "XGB parity failed at record $i: actual=$actualXgbCalP, expected=$expectedXgbCalP, diff=$xgbDiff > tolerance=$tolerance",
                xgbDiff <= tolerance
            )
        }
    }
}
