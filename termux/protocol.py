"""EdgePPG Termux Local Compute Protocol.

Defines the message format, validation, and serialization contracts between
the Android native runtime and the local Termux Python compute layer on the iQOO phone.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from typing import Any, List, Optional

PROTOCOL_VERSION: int = 1
FEATURE_SCHEMA_VERSION: str = "edgeppg-1.0"
FEATURE_COUNT: int = 28


class ProtocolError(Exception):
    """Raised when a request violates the EdgePPG Termux protocol contract."""
    pass


@dataclass
class PredictRequest:
    protocol_version: int
    session_id: str
    feature_schema_version: str
    features: List[float]

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PredictRequest:
        if not isinstance(data, dict):
            raise ProtocolError("Request payload must be a JSON object")

        version = data.get("protocol_version")
        if version != PROTOCOL_VERSION:
            raise ProtocolError(
                f"Protocol version mismatch: expected {PROTOCOL_VERSION}, got {version}"
            )

        session_id = data.get("session_id")
        if not isinstance(session_id, str) or not session_id.strip():
            raise ProtocolError("session_id must be a non-empty string")

        schema_version = data.get("feature_schema_version")
        if schema_version != FEATURE_SCHEMA_VERSION:
            raise ProtocolError(
                f"feature_schema_version mismatch: expected {FEATURE_SCHEMA_VERSION}, got {schema_version}"
            )

        features = data.get("features")
        if not isinstance(features, list):
            raise ProtocolError("features must be a list of 28 floats")
        if len(features) != FEATURE_COUNT:
            raise ProtocolError(
                f"Expected exactly {FEATURE_COUNT} features, got {len(features)}"
            )

        cleaned_features: list[float] = []
        for i, val in enumerate(features):
            if val is None:
                cleaned_features.append(float("nan"))
            elif isinstance(val, (int, float)):
                cleaned_features.append(float(val))
            else:
                raise ProtocolError(f"Feature at index {i} is not a valid number: {val}")

        return cls(
            protocol_version=version,
            session_id=session_id.strip(),
            feature_schema_version=schema_version,
            features=cleaned_features,
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class PredictResponse:
    protocol_version: int
    session_id: str
    model_version: str
    p_live: float
    raw_score: float
    status: str
    latency_ms: float
    error_message: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        if self.error_message is None:
            d.pop("error_message")
        return d

    def to_json(self) -> str:
        return json.dumps(self.to_dict())
