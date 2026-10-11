"""EdgePPG Termux Local Compute Server.

Runs as a background daemon on the iQOO phone in Termux, listening exclusively on
127.0.0.1:8081 for zero-network-latency local IPC from Android native CameraX.

Routes:
  GET  /status       - Health check, model status, and uptime
  POST /predict      - Genuine 28-feature ML inference (returns P(LIVE))
  GET  /diagnostics  - Resource telemetry, memory usage, latency statistics
  POST /research/dsp - Research rPPG signal analysis
"""

from __future__ import annotations

import json
import logging
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Any

from termux.protocol import (
    PredictRequest,
    PredictResponse,
    ProtocolError,
    PROTOCOL_VERSION,
)
from termux.ml.model_runner import ModelRunner
from termux.diagnostics.system_stats import SystemDiagnostics

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] [EdgePPG-Termux] %(levelname)s: %(message)s",
)
logger = logging.getLogger("termux_server")

HOST: str = "127.0.0.1"
PORT: int = 8081

runner = ModelRunner()
diagnostics = SystemDiagnostics()


class TermuxRequestHandler(BaseHTTPRequestHandler):
    """Handles IPC requests from Android CameraX pipeline on localhost."""

    def _send_json(self, status_code: int, data: dict[str, Any]) -> None:
        payload = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self) -> None:
        if self.path in ("/status", "/health", "/"):
            self._send_json(200, {
                "status": "OK",
                "service": "EdgePPG-Termux-Local",
                "protocol_version": PROTOCOL_VERSION,
                "model_loaded": runner.is_loaded,
                "model_name": runner.model_name,
                "schema_version": runner.schema_version,
                "load_error": runner.load_error,
            })
        elif self.path == "/diagnostics":
            stats = diagnostics.get_stats()
            stats["model_loaded"] = runner.is_loaded
            stats["model_name"] = runner.model_name
            self._send_json(200, stats)
        else:
            self._send_json(404, {"error": "Endpoint not found"})

    def do_POST(self) -> None:
        if self.path == "/predict":
            self._handle_predict()
        elif self.path == "/research/dsp":
            self._handle_research_dsp()
        else:
            self._send_json(404, {"error": "Endpoint not found"})

    def _handle_predict(self) -> None:
        content_length = int(self.headers.get("Content-Length", 0))
        if content_length <= 0:
            self._send_json(400, {"error": "Empty request body"})
            return

        body = self.rfile.read(content_length).decode("utf-8")
        try:
            req_data = json.loads(body)
            request = PredictRequest.from_dict(req_data)
        except ProtocolError as pe:
            logger.warning("Protocol violation: %s", pe)
            self._send_json(400, {"error": str(pe), "status": "PROTOCOL_ERROR"})
            return
        except Exception as ex:
            logger.error("JSON decode error: %s", ex)
            self._send_json(400, {"error": f"Invalid JSON payload: {ex}"})
            return

        if not runner.is_loaded:
            logger.error("Predict called while model is not loaded: %s", runner.load_error)
            self._send_json(503, {
                "error": f"Model unavailable: {runner.load_error}",
                "status": "MODEL_UNAVAILABLE",
            })
            return

        try:
            p_live, raw_score, latency_ms = runner.predict(request.features)
            diagnostics.record_inference(latency_ms)

            response = PredictResponse(
                protocol_version=PROTOCOL_VERSION,
                session_id=request.session_id,
                model_version=runner.model_name,
                p_live=round(p_live, 4),
                raw_score=round(raw_score, 4),
                status="OK",
                latency_ms=round(latency_ms, 2),
            )
            self._send_json(200, response.to_dict())
        except Exception as ex:
            logger.exception("Inference failed for session %s: %s", request.session_id, ex)
            self._send_json(500, {
                "protocol_version": PROTOCOL_VERSION,
                "session_id": request.session_id,
                "error": str(ex),
                "status": "INFERENCE_ERROR",
            })

    def _handle_research_dsp(self) -> None:
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8")
        try:
            from termux.rppg.research_dsp import extract_pos_pulse, bandpass_filter, compute_spectral_snr
            import numpy as np

            req = json.loads(body)
            rgb = np.array(req.get("rgb", []), dtype=np.float32)
            fps = float(req.get("fps", 30.0))

            pulse = extract_pos_pulse(rgb, fps=fps)
            filtered = bandpass_filter(pulse, fps=fps)
            hr, snr = compute_spectral_snr(filtered, fps=fps)

            self._send_json(200, {
                "status": "OK",
                "hr_bpm": round(hr, 1),
                "snr": round(snr, 3),
                "samples_processed": len(rgb),
            })
        except Exception as ex:
            self._send_json(500, {"error": str(ex)})

    def log_message(self, format: str, *args: Any) -> None:
        # Suppress noisy standard request logging in production
        pass


def run_server(port: int = PORT) -> None:
    logger.info("Initializing EdgePPG Termux Local Compute Service...")
    logger.info("Model status: loaded=%s, model=%s", runner.is_loaded, runner.model_name)
    server = HTTPServer((HOST, port), TermuxRequestHandler)
    logger.info("Listening on http://%s:%d (localhost only)", HOST, port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down Termux server...")
    finally:
        server.server_close()


if __name__ == "__main__":
    p = PORT
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        p = int(sys.argv[1])
    run_server(port=p)
