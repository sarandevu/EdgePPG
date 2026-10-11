#!/data/data/com.termux/files/usr/bin/bash
# Stop the EdgePPG local background compute daemon in Termux.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$DIR/edgeppg.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "No EdgePPG PID file found. Service does not appear to be running."
    exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
    echo "Stopping EdgePPG Termux service (PID: $PID)..."
    kill "$PID"
    sleep 1
    if kill -0 "$PID" 2>/dev/null; then
        kill -9 "$PID"
    fi
    echo "Service stopped."
else
    echo "Process $PID was not running."
fi

rm -f "$PID_FILE"
