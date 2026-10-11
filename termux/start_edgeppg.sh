#!/data/data/com.termux/files/usr/bin/bash
# Start the EdgePPG local background compute daemon in Termux.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR="$(cd "$DIR/.." && pwd)"
PID_FILE="$DIR/edgeppg.pid"
LOG_FILE="$DIR/edgeppg.log"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "EdgePPG Termux service is already running (PID: $PID)."
        exit 0
    else
        rm -f "$PID_FILE"
    fi
fi

echo "Starting EdgePPG local compute service on 127.0.0.1:8081..."
export PYTHONPATH="$PARENT_DIR:$DIR:$PYTHONPATH"
nohup python "$DIR/server.py" > "$LOG_FILE" 2>&1 &
PID=$!
echo "$PID" > "$PID_FILE"

sleep 1
if kill -0 "$PID" 2>/dev/null; then
    echo "EdgePPG Termux service successfully started (PID: $PID)."
    echo "Logs: $LOG_FILE"
else
    echo "Failed to start EdgePPG service. Check logs at $LOG_FILE"
    exit 1
fi
