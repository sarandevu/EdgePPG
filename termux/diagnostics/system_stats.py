"""EdgePPG Termux System Diagnostics.

Monitors CPU usage, memory footprint, thermal state, and process performance
for local edge analytics on the iQOO phone.
"""

from __future__ import annotations

import os
import platform
import time
from typing import Any, Dict


class SystemDiagnostics:
    """Collects real system health and resource consumption metrics."""

    def __init__(self):
        self.start_time = time.time()
        self.inference_count = 0
        self.total_latency_ms = 0.0

    def record_inference(self, latency_ms: float) -> None:
        self.inference_count += 1
        self.total_latency_ms += latency_ms

    def get_stats(self) -> Dict[str, Any]:
        uptime_sec = time.time() - self.start_time
        avg_latency = (
            (self.total_latency_ms / self.inference_count)
            if self.inference_count > 0
            else 0.0
        )

        mem_mb = 0.0
        try:
            import resource
            usage = resource.getrusage(resource.RUSAGE_SELF)
            # Linux reports in KB, macOS in bytes
            mem_mb = usage.ru_maxrss / 1024.0 if usage.ru_maxrss > 10000 else usage.ru_maxrss / (1024.0 * 1024.0)
        except Exception:
            try:
                import psutil
                process = psutil.Process(os.getpid())
                mem_mb = process.memory_info().rss / (1024.0 * 1024.0)
            except Exception:
                pass

        cpu_load = [0.0, 0.0, 0.0]
        try:
            cpu_load = list(os.getloadavg())
        except Exception:
            pass

        return {
            "uptime_seconds": round(uptime_sec, 1),
            "total_inferences": self.inference_count,
            "avg_latency_ms": round(avg_latency, 2),
            "memory_resident_mb": round(mem_mb, 2),
            "cpu_load_1m": round(cpu_load[0], 2),
            "platform": platform.platform(),
            "python_version": platform.python_version(),
            "pid": os.getpid(),
        }
