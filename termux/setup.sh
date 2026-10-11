#!/data/data/com.termux/files/usr/bin/bash
# EdgePPG Termux Environment Setup Script
# Run inside Termux on the iQOO phone to prepare the local Python environment.

set -e

echo "=========================================="
echo " EdgePPG Termux Local Compute Setup"
echo " Target Device: iQOO 12/15 / Android 14+"
echo "=========================================="

echo "[1/4] Updating Termux package repositories..."
pkg update -y
pkg install -y python clang libjpeg-turbo git tur-repo

echo "[2/4] Installing Python scientific toolchain..."
pkg install -y python-numpy python-scipy

echo "[3/4] Installing ML and inference dependencies..."
pip install --upgrade pip
pip install joblib scikit-learn psutil

echo "[4/4] Verifying installation..."
python -c "import numpy, scipy, sklearn, joblib; print('All core scientific dependencies verified successfully!')"

echo "=========================================="
echo " Setup complete! Ready to start EdgePPG."
echo " Run: ./start_edgeppg.sh"
echo "=========================================="
