"""EdgePPG Termux Local Model Runner.

Loads frozen .joblib models (edgeppg_rf_v1.joblib / edgeppg_xgb_v1.joblib)
and performs local inference on the iQOO phone with calibrated Platt scaling.
"""

from __future__ import annotations

import os
import time
from pathlib import Path
from typing import Optional, Tuple, Any

import joblib
import numpy as np


class ModelRunner:
    """Manages frozen model inference locally within Termux."""

    def __init__(self, model_path: Optional[str] = None):
        self.model_path = model_path or self._default_model_path()
        self.model_name = Path(self.model_path).stem if self.model_path else "unknown"
        self.clf: Any = None
        self.scaler: Any = None
        self.feature_names: list[str] = []
        self.schema_version: str = "edgeppg-1.0"
        self.is_loaded: bool = False
        self.load_error: Optional[str] = None

        self._load_model()

    def _default_model_path(self) -> str:
        candidates = [
            Path(__file__).resolve().parent.parent.parent / "models" / "edgeppg_rf_v1.joblib",
            Path(__file__).resolve().parent / "edgeppg_rf_v1.joblib",
            Path("/data/data/com.termux/files/home/EdgePPG/models/edgeppg_rf_v1.joblib"),
            Path("models/edgeppg_rf_v1.joblib"),
        ]
        for p in candidates:
            if p.exists():
                return str(p)
        return str(candidates[0])

    def _load_model(self) -> None:
        try:
            if not os.path.exists(self.model_path):
                self.load_error = f"Model artifact not found: {self.model_path}"
                self.is_loaded = False
                return

            artifact = joblib.load(self.model_path)
            if isinstance(artifact, dict):
                self.clf = artifact.get("clf")
                self.scaler = artifact.get("scaler")
                self.feature_names = artifact.get("feature_names", [])
                self.schema_version = artifact.get("schema_version", "edgeppg-1.0")
            else:
                self.clf = artifact
                self.scaler = None

            self.is_loaded = True
            self.load_error = None
        except Exception as ex:
            self.is_loaded = False
            self.load_error = f"Failed to load model: {str(ex)}"

    def predict(self, features: list[float]) -> Tuple[float, float, float]:
        """Predict P(LIVE) from a 28-feature list.

        Returns:
            (p_live, raw_score, latency_ms)
        """
        if not self.is_loaded or self.clf is None:
            raise RuntimeError(f"Model not loaded: {self.load_error}")

        if len(features) != 28:
            raise ValueError(f"Expected 28 features, got {len(features)}")

        # Handle NaNs: impute with 0.0 or baseline medians if present
        arr = np.array(features, dtype=np.float32).reshape(1, -1)
        if np.isnan(arr).any():
            # Replace NaNs with 0.0 for classifier execution
            arr = np.nan_to_num(arr, nan=0.0)

        t0 = time.perf_counter()
        if hasattr(self.clf, "predict_proba"):
            probs = self.clf.predict_proba(arr)[0]
            raw_score = float(probs[1]) if len(probs) > 1 else float(probs[0])
        elif hasattr(self.clf, "decision_function"):
            raw_score = float(self.clf.decision_function(arr)[0])
        else:
            raw_score = float(self.clf.predict(arr)[0])

        # Apply calibrated Platt scaling if available
        if self.scaler is not None and hasattr(self.scaler, "transform"):
            p_live = float(self.scaler.transform(raw_score))
        elif hasattr(self.scaler, "predict_proba"):
            p_live = float(self.scaler.predict_proba([[raw_score]])[0, 1])
        else:
            # Standard sigmoid if no scaler
            p_live = float(1.0 / (1.0 + np.exp(-raw_score)))

        latency_ms = (time.perf_counter() - t0) * 1000.0
        return p_live, raw_score, latency_ms
