"""Unit tests for XGBoost Challenger JSON export and PC vs Golden Vector parity."""

import json
import math
from pathlib import Path
import unittest

REPO_ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = REPO_ROOT / "models"
ASSETS_DIR = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"
DATA_DIR = REPO_ROOT / "data"


class XgbParityTests(unittest.TestCase):

    def test_xgb_json_artifacts_exist(self):
        models_json = MODELS_DIR / "edgeppg_xgb_v1.json"
        assets_json = ASSETS_DIR / "edgeppg_xgb_v1.json"
        golden_json = DATA_DIR / "golden_vectors.json"

        self.assertTrue(models_json.exists(), f"Missing {models_json}")
        self.assertTrue(assets_json.exists(), f"Missing {assets_json}")
        self.assertTrue(golden_json.exists(), f"Missing {golden_json}")

    def test_xgb_json_schema_and_trees(self):
        models_json = MODELS_DIR / "edgeppg_xgb_v1.json"
        with open(models_json, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.assertEqual(data["model_name"], "edgeppg_xgb_v1")
        self.assertEqual(data["schema_version"], "edgeppg-1.0")
        self.assertEqual(data["model_type"], "XGBClassifier")
        self.assertEqual(data["n_estimators"], 300)
        self.assertEqual(data["n_features"], 28)
        self.assertIn("scaler", data)
        self.assertIn("a", data["scaler"])
        self.assertIn("b", data["scaler"])
        self.assertEqual(len(data["trees"]), 300)

        tree0 = data["trees"][0]
        self.assertIn("split_indices", tree0)
        self.assertIn("split_conditions", tree0)
        self.assertIn("left_children", tree0)
        self.assertIn("right_children", tree0)
        self.assertIn("default_left", tree0)

    def test_xgb_golden_vector_evaluation_matches(self):
        models_json = MODELS_DIR / "edgeppg_xgb_v1.json"
        with open(models_json, "r", encoding="utf-8") as f:
            model = json.load(f)

        golden_json = DATA_DIR / "golden_vectors.json"
        with open(golden_json, "r", encoding="utf-8") as f:
            golden = json.load(f)

        trees = model["trees"]
        scaler_a = model["scaler"]["a"]
        scaler_b = model["scaler"]["b"]

        def eval_row(row):
            margin = 0.0
            for t in trees:
                curr = 0
                lefts = t["left_children"]
                rights = t["right_children"]
                splits = t["split_indices"]
                conds = t["split_conditions"]
                defls = t["default_left"]
                while lefts[curr] != -1:
                    f_idx = splits[curr]
                    v = row[f_idx]
                    if v is None or math.isnan(v):
                        curr = lefts[curr] if defls[curr] else rights[curr]
                    elif v <= conds[curr]:
                        curr = lefts[curr]
                    else:
                        curr = rights[curr]
                margin += conds[curr]

            raw_p = 1.0 / (1.0 + math.exp(-margin))
            z = scaler_a * raw_p + scaler_b
            cal_p = 1.0 / (1.0 + math.exp(-z))
            return raw_p, cal_p

        tolerance = golden.get("tolerance", 0.0001)
        for rec in golden["records"]:
            row = rec["row"]
            actual_raw, actual_cal = eval_row(row)
            expected_raw = rec["xgb_raw_p"]
            expected_cal = rec["xgb_cal_p"]

            self.assertAlmostEqual(actual_raw, expected_raw, delta=tolerance,
                                   msg=f"Record {rec['index']} raw proba mismatch")
            self.assertAlmostEqual(actual_cal, expected_cal, delta=tolerance,
                                   msg=f"Record {rec['index']} calibrated proba mismatch")


if __name__ == "__main__":
    unittest.main()
