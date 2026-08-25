#!/bin/sh
# libtorrent replaces HotSpot's SIGSEGV handler after torrent use. Preload the bundled
# libjsig before the JVM starts so both handlers remain active.
JSIG=/app/lib/cove/Cove/lib/runtime/lib/libjsig.so
if [ -f "$JSIG" ]; then
    LD_PRELOAD="${JSIG}${LD_PRELOAD:+:$LD_PRELOAD}"
    export LD_PRELOAD
fi
exec /app/lib/cove/Cove/bin/Cove "$@"
