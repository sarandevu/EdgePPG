package com.edgeppg.app.features

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp

/**
 * Native Android-compatible inference client for EdgePPG XGBoost Challenger.
 *
 * Evaluates the trained frozen XGBoost gradient-boosted decision trees (edgeppg_xgb_v1)
 * directly in pure Kotlin bytecode on the phone's CPU, respecting missing-value branches
 * (default_left) and applying the calibrated Platt scaler parameters to produce true
 * P(LIVE) in [0, 1].
 */
class NativeXgbMlClient(
    private val modelName: String = "edgeppg_xgb_v1",
    override val loadedManifest: Map<String, String>?,
    private val scalerA: Float,
    private val scalerB: Float,
    private val baseScore: Float,
    private val trees: List<XgbTree>,
) : MlFusionClient {

    data class XgbTree(
        val splitIndices: IntArray,
        val splitConditions: FloatArray,
        val leftChildren: IntArray,
        val rightChildren: IntArray,
        val defaultLeft: IntArray,
    )

    override val isModelLoaded: Boolean = trees.isNotEmpty()

    override fun predictLiveProbability(
        row: FloatArray,
        schemaVersion: String,
    ): Float {
        if (!isModelLoaded || row.size != 28) {
            return Float.NaN
        }
        if (schemaVersion != FeatureSchema.SCHEMA_VERSION) {
            return Float.NaN
        }

        var margin = 0.0f
        for (tree in trees) {
            var curr = 0
            val lefts = tree.leftChildren
            val rights = tree.rightChildren
            val splits = tree.splitIndices
            val conds = tree.splitConditions
            val defls = tree.defaultLeft

            while (curr in lefts.indices && lefts[curr] != -1) {
                val fIdx = splits[curr]
                val v = if (fIdx in row.indices) row[fIdx] else Float.NaN
                curr = if (v.isNaN()) {
                    if (defls[curr] == 1) lefts[curr] else rights[curr]
                } else {
                    if (v <= conds[curr]) lefts[curr] else rights[curr]
                }
            }
            if (curr in conds.indices) {
                margin += conds[curr]
            }
        }

        // Logistic sigmoid of margin
        val rawScore = 1.0f / (1.0f + exp(-margin))

        // Calibrated Platt sigmoid: z = a * raw + b, p = 1 / (1 + exp(-z))
        val z = scalerA * rawScore + scalerB
        return (1.0f / (1.0f + exp(-z))).coerceIn(0.0001f, 0.9999f)
    }

    companion object {
        fun fromJson(jsonStr: String): NativeXgbMlClient {
            val root = JSONObject(jsonStr)
            val name = root.optString("model_name", "edgeppg_xgb_v1")
            val schema = root.optString("schema_version", "edgeppg-1.0")
            val scalerObj = root.optJSONObject("scaler")
            val a = scalerObj?.optDouble("a", 18.05623959257522)?.toFloat() ?: 18.05623959257522f
            val b = scalerObj?.optDouble("b", -8.851211530064269)?.toFloat() ?: -8.851211530064269f
            val baseScore = root.optDouble("base_score", 0.5).toFloat()

            val treesArr = root.optJSONArray("trees") ?: return NativeXgbMlClient(name, null, a, b, baseScore, emptyList())
            val parsedTrees = mutableListOf<XgbTree>()

            for (t in 0 until treesArr.length()) {
                val tObj = treesArr.getJSONObject(t)
                val splits = tObj.getJSONArray("split_indices")
                val conds = tObj.getJSONArray("split_conditions")
                val lefts = tObj.getJSONArray("left_children")
                val rights = tObj.getJSONArray("right_children")
                val defls = tObj.getJSONArray("default_left")

                val nNodes = splits.length()
                val splitArr = IntArray(nNodes) { splits.getInt(it) }
                val condArr = FloatArray(nNodes) { conds.getDouble(it).toFloat() }
                val leftArr = IntArray(nNodes) { lefts.getInt(it) }
                val rightArr = IntArray(nNodes) { rights.getInt(it) }
                val deflArr = IntArray(nNodes) { defls.getInt(it) }

                parsedTrees.add(XgbTree(splitArr, condArr, leftArr, rightArr, deflArr))
            }

            val manifest = mapOf(
                "model_name" to name,
                "schema_version" to schema,
                "model_type" to "XGBClassifier",
                "n_estimators" to parsedTrees.size.toString(),
            )
            return NativeXgbMlClient(name, manifest, a, b, baseScore, parsedTrees)
        }

        fun fromAssets(context: Context, assetName: String = "edgeppg_xgb_v1.json"): NativeXgbMlClient? {
            return try {
                context.assets.open(assetName).bufferedReader().use {
                    fromJson(it.readText())
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}
