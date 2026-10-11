package com.edgeppg.app.features

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp

/**
 * Native Android-compatible inference client for EdgePPG.
 *
 * Evaluates the trained frozen Random Forest decision trees (edgeppg_rf_v1)
 * directly in pure Kotlin bytecode on the phone's CPU, applying the calibrated
 * Platt scaler parameters to produce true P(LIVE) in [0, 1].
 */
class NativeTreeMlClient(
    private val modelName: String,
    override val loadedManifest: Map<String, String>?,
    private val scalerA: Float,
    private val scalerB: Float,
    private val trees: List<List<Node>>,
) : MlFusionClient {

    data class Node(
        val feature: Int,
        val threshold: Float,
        val left: Int,
        val right: Int,
        val pLive: Float,
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

        // Impute NaNs with 0.0 for Scikit-Learn decision tree parity
        val clean = FloatArray(row.size) { i ->
            val v = row[i]
            if (v.isNaN()) 0f else v
        }

        var sumProbs = 0f
        for (tree in trees) {
            var curr = 0
            while (curr in tree.indices) {
                val node = tree[curr]
                if (node.left == node.right || node.feature < 0) {
                    sumProbs += node.pLive
                    break
                }
                val next = if (clean[node.feature] <= node.threshold) node.left else node.right
                if (next == curr || next !in tree.indices) {
                    sumProbs += node.pLive
                    break
                }
                curr = next
            }
        }
        val rawScore = sumProbs / trees.size.toFloat()

        // Calibrated Platt sigmoid: z = a * raw + b, p = 1 / (1 + exp(-z))
        val z = scalerA * rawScore + scalerB
        return (1.0f / (1.0f + exp(-z))).coerceIn(0.0001f, 0.9999f)
    }

    companion object {
        fun fromJson(jsonStr: String): NativeTreeMlClient {
            val root = JSONObject(jsonStr)
            val name = root.optString("model_name", "edgeppg_rf_v1")
            val schema = root.optString("schema_version", "edgeppg-1.0")
            val scalerObj = root.optJSONObject("scaler")
            val a = scalerObj?.optDouble("a", 17.702309536925988)?.toFloat() ?: 17.702309536925988f
            val b = scalerObj?.optDouble("b", -8.653535480193897)?.toFloat() ?: -8.653535480193897f

            val treesArr = root.optJSONArray("trees") ?: return NativeTreeMlClient(name, null, a, b, emptyList())
            val parsedTrees = mutableListOf<List<Node>>()
            for (t in 0 until treesArr.length()) {
                val nodesArr = treesArr.getJSONArray(t)
                val treeNodes = mutableListOf<Node>()
                for (n in 0 until nodesArr.length()) {
                    val nObj = nodesArr.getJSONObject(n)
                    treeNodes.add(
                        Node(
                            feature = nObj.getInt("f"),
                            threshold = nObj.getDouble("th").toFloat(),
                            left = nObj.getInt("l"),
                            right = nObj.getInt("r"),
                            pLive = nObj.getDouble("p").toFloat(),
                        )
                    )
                }
                parsedTrees.add(treeNodes)
            }

            val manifest = mapOf(
                "model_name" to name,
                "schema_version" to schema,
                "model_type" to "RandomForestClassifier",
                "n_estimators" to parsedTrees.size.toString(),
            )
            return NativeTreeMlClient(name, manifest, a, b, parsedTrees)
        }

        fun fromAssets(context: Context, assetName: String = "edgeppg_rf_v1.json"): NativeTreeMlClient? {
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
