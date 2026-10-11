"""Export XGBoost challenger model to Android-compatible JSON format.

Loads models/edgeppg_xgb_v1.joblib, extracts trees and Platt scaler parameters,
and writes models/edgeppg_xgb_v1.json and android/app/src/main/assets/edgeppg_xgb_v1.json.
Also generates data/golden_vectors.json for PC vs Android parity validation.
"""

from __future__ import annotations

import json
import math
from pathlib import Path
import joblib
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
MODELS_DIR = REPO_ROOT / "models"
ASSETS_DIR = REPO_ROOT / "android" / "app" / "src" / "main" / "assets"
DATA_DIR = REPO_ROOT / "data"

from ml.src.feature_schema import FEATURE_ORDER, SCHEMA_VERSION


def export_xgb_model():
    xgb_path = MODELS_DIR / "edgeppg_xgb_v1.joblib"
    if not xgb_path.exists():
        raise FileNotFoundError(f"Model file not found: {xgb_path}")

    bundle = joblib.load(xgb_path)
    clf = bundle["clf"]
    scaler = bundle["scaler"]
    booster = clf.get_booster()

    # Save to temp XGBoost JSON to parse official tree representation
    tmp_json_path = MODELS_DIR / "_tmp_xgb.json"
    booster.save_model(str(tmp_json_path))
    with open(tmp_json_path, "r", encoding="utf-8") as f:
        official_data = json.load(f)
    if tmp_json_path.exists():
        tmp_json_path.unlink()

    gb_model = official_data["learner"]["gradient_booster"]["model"]
    trees_raw = gb_model["trees"]
    base_score_val = float(official_data["learner"]["learner_model_param"].get("base_score", "0.5").replace("[", "").replace("]", ""))

    parsed_trees = []
    for t in trees_raw:
        parsed_trees.append({
            "split_indices": [int(x) for x in t["split_indices"]],
            "split_conditions": [float(x) for x in t["split_conditions"]],
            "left_children": [int(x) for x in t["left_children"]],
            "right_children": [int(x) for x in t["right_children"]],
            "default_left": [int(x) for x in t["default_left"]],
        })

    scaler_dict = scaler.to_dict() if hasattr(scaler, "to_dict") else {"a": float(scaler.a), "b": float(scaler.b)}

    export_payload = {
        "model_name": "edgeppg_xgb_v1",
        "schema_version": SCHEMA_VERSION,
        "model_type": "XGBClassifier",
        "n_estimators": len(parsed_trees),
        "n_features": len(FEATURE_ORDER),
        "base_score": base_score_val,
        "scaler": {
            "a": float(scaler_dict["a"]),
            "b": float(scaler_dict["b"]),
        },
        "trees": parsed_trees,
    }

    # Write to models/edgeppg_xgb_v1.json
    out_models = MODELS_DIR / "edgeppg_xgb_v1.json"
    out_models.write_text(json.dumps(export_payload, separators=(",", ":")), encoding="utf-8")
    print(f"Exported XGBoost model JSON to: {out_models} ({out_models.stat().st_size} bytes)")

    # Copy to android assets
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    out_assets = ASSETS_DIR / "edgeppg_xgb_v1.json"
    out_assets.write_text(json.dumps(export_payload, separators=(",", ":")), encoding="utf-8")
    print(f"Copied XGBoost model JSON to Android assets: {out_assets} ({out_assets.stat().st_size} bytes)")

    return export_payload, clf, scaler


def generate_golden_vectors(xgb_payload, xgb_clf, xgb_scaler):
    """Generate golden vectors with expected RF and XGBoost predictions."""
    rf_path = MODELS_DIR / "edgeppg_rf_v1.joblib"
    rf_bundle = joblib.load(rf_path)
    rf_clf = rf_bundle["clf"]
    rf_scaler = rf_bundle["scaler"]

    np.random.seed(42)
    # 5 random synthetic vectors + 5 vectors from test split
    import pandas as pd
    test_df = pd.read_csv(DATA_DIR / "splits" / "test.csv")
    test_rows = test_df[list(FEATURE_ORDER)].head(5).values.tolist()

    synthetic_rows = np.random.uniform(0.1, 0.9, size=(5, 28)).tolist()
    # Add a row with some NaNs to test missing feature branch
    synthetic_rows[0][2] = float("nan")
    synthetic_rows[0][10] = float("nan")

    all_vectors = synthetic_rows + test_rows

    golden_records = []
    for i, row in enumerate(all_vectors):
        # PC RF inference
        rf_clean = [(0.0 if math.isnan(x) else x) for x in row]
        rf_raw = float(rf_clf.predict_proba([rf_clean])[0, 1])
        rf_cal = float(rf_scaler.transform(rf_raw))

        # PC XGBoost inference
        xgb_raw = float(xgb_clf.predict_proba([row])[0, 1])
        xgb_cal = float(xgb_scaler.transform(xgb_raw))

        golden_records.append({
            "index": i,
            "row": [(None if math.isnan(x) else round(float(x), 6)) for x in row],
            "rf_raw_p": round(rf_raw, 6),
            "rf_cal_p": round(rf_cal, 6),
            "xgb_raw_p": round(xgb_raw, 6),
            "xgb_cal_p": round(xgb_cal, 6),
        })

    golden_payload = {
        "schema_version": SCHEMA_VERSION,
        "tolerance": 1e-4,
        "n_features": 28,
        "records": golden_records,
    }

    golden_file = DATA_DIR / "golden_vectors.json"
    golden_file.write_text(json.dumps(golden_payload, indent=2), encoding="utf-8")
    print(f"Generated {len(golden_records)} golden vectors in: {golden_file}")


if __name__ == "__main__":
    payload, clf, scaler = export_xgb_model()
    generate_golden_vectors(payload, clf, scaler)
