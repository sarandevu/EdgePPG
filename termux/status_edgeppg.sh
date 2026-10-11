#!/data/data/com.termux/files/usr/bin/bash
# Query status and diagnostics of EdgePPG local service in Termux.

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$DIR/edgeppg.pid"

echo "=== EdgePPG Termux Service Status ==="
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "State: RUNNING (PID $PID)"
        echo "Endpoint: http://127.0.0.1:8081"
        echo ""
        echo "=== Local Health Query ==="
        python -c "
import urllib.request, json
try:
    with urllib.request.urlopen('http://127.0.0.1:8081/status', timeout=2) as r:
        print(json.dumps(json.loads(r.read().decode()), indent=2))
except Exception as e:
    print(f'Error querying status: {e}')
"
    else
        echo "State: DEAD (stale PID file found)"
        rm -f "$PID_FILE"
    fi
else
    echo "State: STOPPED"
fi
