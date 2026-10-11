"""Unit tests for the EdgePPG Termux Local Compute layer.

Tests protocol serialization, model runner loading, endpoint responses, and error handling.
"""

import json
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import HTTPServer

from termux.protocol import (
    PredictRequest,
    PredictResponse,
    ProtocolError,
    PROTOCOL_VERSION,
    FEATURE_SCHEMA_VERSION,
)
from termux.ml.model_runner import ModelRunner
from termux.server import TermuxRequestHandler


class ProtocolValidationTests(unittest.TestCase):
    def test_valid_request(self):
        data = {
            "protocol_version": 1,
            "session_id": "sess-abc-123",
            "feature_schema_version": "edgeppg-1.0",
            "features": [0.5] * 28,
        }
        req = PredictRequest.from_dict(data)
        self.assertEqual(req.protocol_version, 1)
        self.assertEqual(req.session_id, "sess-abc-123")
        self.assertEqual(len(req.features), 28)

    def test_protocol_version_mismatch_rejected(self):
        data = {
            "protocol_version": 99,
            "session_id": "sess-abc-123",
            "feature_schema_version": "edgeppg-1.0",
            "features": [0.5] * 28,
        }
        with self.assertRaises(ProtocolError):
            PredictRequest.from_dict(data)

    def test_empty_session_id_rejected(self):
        data = {
            "protocol_version": 1,
            "session_id": "",
            "feature_schema_version": "edgeppg-1.0",
            "features": [0.5] * 28,
        }
        with self.assertRaises(ProtocolError):
            PredictRequest.from_dict(data)

    def test_invalid_feature_count_rejected(self):
        data = {
            "protocol_version": 1,
            "session_id": "sess-abc-123",
            "feature_schema_version": "edgeppg-1.0",
            "features": [0.5] * 27,  # 27 instead of 28
        }
        with self.assertRaises(ProtocolError):
            PredictRequest.from_dict(data)

    def test_non_numeric_feature_rejected(self):
        features = [0.5] * 28
        features[10] = "invalid_string"
        data = {
            "protocol_version": 1,
            "session_id": "sess-abc-123",
            "feature_schema_version": "edgeppg-1.0",
            "features": features,
        }
        with self.assertRaises(ProtocolError):
            PredictRequest.from_dict(data)


class ModelRunnerTests(unittest.TestCase):
    def test_model_loading_and_prediction(self):
        runner = ModelRunner()
        self.assertTrue(runner.is_loaded, f"Model failed to load: {runner.load_error}")
        features = [0.8] * 28
        p_live, raw_score, latency_ms = runner.predict(features)
        self.assertGreaterEqual(p_live, 0.0)
        self.assertLessEqual(p_live, 1.0)
        self.assertGreater(latency_ms, 0.0)


class ServerIntegrationTests(unittest.TestCase):
    TEST_PORT = 8991
    server_thread = None
    httpd = None

    @classmethod
    def setUpClass(cls):
        cls.httpd = HTTPServer(("127.0.0.1", cls.TEST_PORT), TermuxRequestHandler)
        cls.server_thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.server_thread.start()
        time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        if cls.httpd:
            cls.httpd.shutdown()
            cls.httpd.server_close()

    def test_status_endpoint(self):
        url = f"http://127.0.0.1:{self.TEST_PORT}/status"
        with urllib.request.urlopen(url, timeout=2) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertEqual(data["status"], "OK")
            self.assertEqual(data["protocol_version"], 1)

    def test_diagnostics_endpoint(self):
        url = f"http://127.0.0.1:{self.TEST_PORT}/diagnostics"
        with urllib.request.urlopen(url, timeout=2) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertIn("uptime_seconds", data)
            self.assertIn("memory_resident_mb", data)

    def test_predict_endpoint_success(self):
        url = f"http://127.0.0.1:{self.TEST_PORT}/predict"
        payload = json.dumps({
            "protocol_version": 1,
            "session_id": "test-session-999",
            "feature_schema_version": "edgeppg-1.0",
            "features": [0.85] * 28,
        }).encode("utf-8")
        req = urllib.request.Request(
            url, data=payload, headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            self.assertEqual(resp.status, 200)
            data = json.loads(resp.read().decode())
            self.assertEqual(data["status"], "OK")
            self.assertEqual(data["session_id"], "test-session-999")
            self.assertIn("p_live", data)
            self.assertIn("latency_ms", data)
            self.assertGreaterEqual(data["p_live"], 0.0)
            self.assertLessEqual(data["p_live"], 1.0)

    def test_predict_malformed_returns_400(self):
        url = f"http://127.0.0.1:{self.TEST_PORT}/predict"
        payload = json.dumps({
            "protocol_version": 1,
            "session_id": "test",
            "features": [0.1] * 10,  # too few features
        }).encode("utf-8")
        req = urllib.request.Request(
            url, data=payload, headers={"Content-Type": "application/json"}
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=2)
        self.assertEqual(ctx.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
