#!/bin/sh
export QTWEBENGINEPROCESS_PATH=/app/lib/libexec/QtWebEngineProcess
exec /app/lib/cove/cove_shell \
    --backend /app/lib/cove/cove \
    --webroot /app/share/cove/web \
    "$@"
