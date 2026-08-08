#!/bin/sh
exec /app/lib/cove/Cove/bin/Cove \
    --backend /app/lib/cove/cove \
    "$@"
