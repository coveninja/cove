#!/bin/sh
# libtorrent installs its own SIGSEGV handler once a torrent has been added, and
# that is the handler HotSpot needs: JIT-compiled code omits null tests and lets
# the CPU fault, then turns the fault into a NullPointerException. With libtorrent
# owning it, the next null dereference anywhere in the process kills the app — on
# whichever thread reaches one first, so the crash never lands near the torrent.
#
# libjsig interposes sigaction so both handlers live, but only when it is preloaded
# before the JVM installs its own; it cannot be loaded from inside the app. The
# bundled runtime ships it, so preload it here and leave any existing value intact.
JSIG=/app/lib/cove/Cove/lib/runtime/lib/libjsig.so
if [ -f "$JSIG" ]; then
    LD_PRELOAD="${JSIG}${LD_PRELOAD:+:$LD_PRELOAD}"
    export LD_PRELOAD
fi
exec /app/lib/cove/Cove/bin/Cove "$@"
