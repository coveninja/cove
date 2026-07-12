import QtQuick
import QtQuick.Window
import QtWebEngine
import QtWebChannel
import mpv

// Single Quick scene: mpv FBO at the back, transparent WebEngineView on top.
// They share one scene graph and the web layer background is transparent, so
// the web UI composites over the video (what Electron cannot do).
Window {
    id: win
    visible: true
    width: 1366
    height: 850
    title: "Cove"
    color: "black"

    // Consecutive crash-reload counter. When the Chromium renderer terminates
    // abnormally (OOM, GPU fault) the web overlay goes permanently dead while
    // mpv keeps rendering — the user sees video but the entire UI is frozen.
    // We destroy and recreate the WebEngineView to restore the overlay. A
    // simple reload() reuses the same renderer process and has been observed to
    // stack the dead page's document graph inside that process (Documents metric
    // 2→9→17 across a crash loop, heap baseline 40→209→244 MB). Destroying the
    // view via Loader.active = false discards the renderer entirely; re-enabling
    // it spawns a genuinely fresh one. This counter prevents an infinite
    // crash–recreate spin when the same fault is reproduced immediately on every
    // renderer boot; once it exceeds rendererCrashLimit we stop.
    property int rendererCrashCount: 0
    readonly property int rendererCrashLimit: 3

    // Deferred renderer recreation. Qt recommends not mutating the view
    // synchronously from renderProcessTerminated (the engine is mid-cleanup at
    // that point), so both the destroy and the recreate happen here, in a
    // single-shot timer fired from that handler: active = false destroys the
    // crashed view and its renderer process, then (a beat later, so teardown
    // settles) active = true creates a genuinely fresh view.
    Timer {
        id: rendererReloadTimer
        interval: 1000
        repeat: false
        onTriggered: {
            webLoader.active = false
            Qt.callLater(function() {
                webLoader.active = true
                console.log("[shell] web renderer: fresh view created")
            })
        }
    }

    MpvObject {
        id: mpv
        anchors.fill: parent

        // Identifier the JS side uses: channel.objects.mpv
        WebChannel.id: "mpv"

        Component.onCompleted: {
            if (typeof mpvTestFile !== "undefined" && mpvTestFile.length > 0)
                mpv.play(mpvTestFile)
        }
    }

    // Bridge: exposes mpv's slots/signals to the web layer. QtWebEngine injects
    // qt.webChannelTransport into the page once this channel is set on the view.
    WebChannel {
        id: coveChannel
        registeredObjects: [mpv]
    }

    Connections {
        target: mpv
        function onFullscreenRequested(fs) {
            if (fs) {
                win.showFullScreen();
            } else if (win.visibility === Window.FullScreen) {
                win.showNormal();
            }
        }
    }

    // The WebEngineView is wrapped in a Loader so it can be fully destroyed and
    // recreated on renderer crash. Setting active = false tears down the view and
    // its Chromium renderer process; setting it back to true spawns a fresh one.
    // The Loader fills the window in the same position the bare view occupied,
    // preserving the mpv-behind / web-on-top layering.
    Loader {
        id: webLoader
        anchors.fill: parent
        active: true
        sourceComponent: webViewComponent
    }

    // Template for the WebEngineView. Instantiated (and re-instantiated after a
    // crash) by the Loader above. All signal handlers that were on the bare view
    // live here so they are wired to the correct object instance each time.
    //
    // Properties that reference objects declared at Window level (coveChannel,
    // mpv, launchUrl, rendererCrashCount/Limit, rendererReloadTimer, webLoader)
    // resolve correctly because QML closes over the parent scope.
    Component {
        id: webViewComponent

        WebEngineView {
            anchors.fill: parent
            backgroundColor: "transparent"
            webChannel: coveChannel
            url: launchUrl

            // Open window.open() links (e.g. JustWatch provider pages) in the
            // system browser rather than a new Qt window.
            onNewWindowRequested: function(request) {
                Qt.openUrlExternally(request.requestedUrl)
                request.action = WebEngineNewWindowRequest.IgnoreRequest
            }

            // Destroy and recreate the WebEngineView when the Chromium renderer
            // process terminates abnormally. NormalTerminationStatus is ignored
            // (intentional shutdown). mpv is stopped so the reloaded home page
            // does not have orphaned audio/video playing behind it.
            onRenderProcessTerminated: function(terminationStatus, exitCode) {
                if (terminationStatus === WebEngineView.NormalTerminationStatus) return
                var name = terminationStatus === WebEngineView.AbnormalTerminationStatus ? "Abnormal"
                         : terminationStatus === WebEngineView.CrashedTerminationStatus  ? "Crashed"
                         : terminationStatus === WebEngineView.KilledTerminationStatus   ? "Killed"
                         : "Unknown"
                console.log("[shell] web renderer terminated (status=" + name + ", exit=" + exitCode + ")")
                mpv.stop()
                if (rendererCrashCount >= rendererCrashLimit) {
                    console.log("[shell] web renderer crashed too many times — giving up on reload")
                    return
                }
                rendererCrashCount++
                // Destroying the view from inside its own signal handler is
                // unsafe (the engine is mid-cleanup) — the timer does both the
                // destroy and the recreate once this handler has returned.
                rendererReloadTimer.start()
            }

            // Reset the crash counter on every successful page load so that a
            // stable session doesn't accumulate credit toward the limit.
            onLoadingChanged: function(loadingInfo) {
                if (loadingInfo.status === WebEngineView.LoadSucceededStatus)
                    rendererCrashCount = 0
            }

            // Forward JS console output to the Qt process stdout so it's visible
            // in the terminal alongside Go backend logs.
            onJavaScriptConsoleMessage: function(level, message, lineNumber, sourceID) {
                // Drop console.trace / console.groupEnd lines — pure noise.
                if (message === "console.trace" || message === "console.groupEnd") return
                // Strip %c CSS-format arguments that Qt concatenates into the message
                // string (e.g. styled console.group output from Vidstack). This turns
                // "%cERROR%c ... background: hsl(...); color: white; ..." into
                // "ERROR ... [vidstack] ..." so it's readable in the terminal.
                var out = message
                if (out.indexOf("%c") !== -1) {
                    out = out.replace(/%c/g, "")
                    // Pass 1: remove semicolon-terminated CSS blocks (e.g. "background: hsl(...); color: white;")
                    out = out.replace(/\s*(?:background|color|padding(?:-\w+)?|font-size|border(?:-\w+)?|font-weight):[^;]+;/g, "")
                    // Pass 2: remove bare "color: word" args that have no semicolon
                    // (Qt appends each console.log arg with a space, so these appear as "color: gray Value")
                    out = out.replace(/\s*color:\s+\w+(?=\s|$)/g, "")
                    out = out.replace(/\s+/g, " ").trim()
                }
                if (!out) return
                var prefix = "[js] "
                if (level === 1) prefix = "[js:warn] "
                else if (level >= 2) prefix = "[js:err] "
                console.log(prefix + out)
            }
        }
    }
}
