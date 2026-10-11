package com.edgeppg.app.features

import com.edgeppg.app.Log

/**
 * Composite Local ML Client for the iQOO phone.
 *
 * Implements Kotlin-first resolution order:
 *  1. Native Android embedded tree inference (pure Kotlin bytecode evaluating frozen 300 decision trees).
 *  2. Termux local Python ML inference (127.0.0.1:8081) if available as fallback.
 *  3. NaNFallbackMlClient (never crashes, routes safely to UNCERTAIN).
 */
class CompositeLocalMlClient(
    var nativeClient: NativeTreeMlClient? = null,
    var nativeXgbClient: NativeXgbMlClient? = null,
    val termuxClient: TermuxMlClient = TermuxMlClient(),
) : MlFusionClient {

    var activeModel: String = "RF" // "RF" (Random Forest Baseline) or "XGB" (XGBoost Challenger)

    @Volatile var lastInferenceSource: String = "NONE"
        private set

    override val isModelLoaded: Boolean
        get() = (nativeClient?.isModelLoaded == true) || (nativeXgbClient?.isModelLoaded == true) || termuxClient.isModelLoaded

    override val loadedManifest: Map<String, String>?
        get() = if (activeModel == "XGB" && nativeXgbClient?.isModelLoaded == true) {
            nativeXgbClient?.loadedManifest
        } else {
            nativeClient?.loadedManifest ?: if (termuxClient.isModelLoaded) {
                termuxClient.loadedManifest
            } else null
        }

    override fun predictLiveProbability(
        row: FloatArray,
        schemaVersion: String,
    ): Float {
        // 1. Try Native XGBoost Challenger if active
        if (activeModel == "XGB") {
            val xgb = nativeXgbClient
            if (xgb != null && xgb.isModelLoaded) {
                val xgbP = xgb.predictLiveProbability(row, schemaVersion)
                if (!xgbP.isNaN()) {
                    lastInferenceSource = "NATIVE_XGBOOST"
                    Log.stage("ml", "ML Prediction from Native Kotlin XGBoost: P(LIVE)=$xgbP")
                    return xgbP
                }
            }
        }

        // 2. Try Native Android embedded RF model (100% Kotlin on CPU)
        val native = nativeClient
        if (native != null && native.isModelLoaded) {
            val nativeP = native.predictLiveProbability(row, schemaVersion)
            if (!nativeP.isNaN()) {
                lastInferenceSource = "NATIVE_RANDOM_FOREST"
                Log.stage("ml", "ML Prediction from Native Kotlin RF: P(LIVE)=$nativeP")
                return nativeP
            }
        }

        // 3. Try Termux local service if active
        val termuxP = termuxClient.predictLiveProbability(row, schemaVersion)
        if (!termuxP.isNaN()) {
            lastInferenceSource = "TERMUX"
            Log.stage("ml", "ML Prediction from Termux Local Python: P(LIVE)=$termuxP")
            return termuxP
        }

        // 4. Fallback
        lastInferenceSource = "FALLBACK_NAN"
        return Float.NaN
    }
}
