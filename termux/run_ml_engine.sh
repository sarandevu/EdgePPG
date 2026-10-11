#!/data/data/com.termux/files/usr/bin/bash
# EdgePPG Mobile ML Compute Engine Benchmark Launcher for Termux on Android
# Compiles ARM64 NEON C benchmark and runs multi-threaded neural network stress test.

set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=== EdgePPG Mobile ML Compute Engine ==="
if ! command -v clang &> /dev/null; then
    echo "clang not found. Installing clang..."
    pkg install clang -y
fi

echo "Compiling mobile_ml_engine.c with ARM64 NEON -O3..."
clang \
  -O3 \
  -ffast-math \
  -funroll-loops \
  -pthread \
  "$DIR/mobile_ml_engine.c" \
  -lm \
  -o "$DIR/mobile_ml_engine"

echo "Running benchmark (DURATION_SEC=${DURATION_SEC:-1200}, RAM_PCT=${RAM_PCT:-65})..."
DURATION_SEC="${DURATION_SEC:-1200}" RAM_PCT="${RAM_PCT:-65}" "$DIR/mobile_ml_engine"
