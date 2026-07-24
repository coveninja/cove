// Bridge client for the Qt/libmpv shell.
//
// Inside the Cove desktop shell, QtWebEngine injects `qt.webChannelTransport`
// and (via a document-creation user script) the global `QWebChannel`. This
// module connects to the `mpv` object registered on the C++ side exactly once
// and exposes it as reactive Svelte state plus a typed control API.
//
// In a plain browser (e.g. `vite dev` outside the shell) neither global exists;
// `available` stays false and every control is a no-op, so UI can branch on it.

import { isDesktopTvMode } from "$lib/platform";

export interface MpvTrack {
  id: number;
  type: "video" | "audio" | "sub";
  title: string;
  lang: string;
  selected: boolean;
}

// How the video frame is fit to the display. Cycled by the player's aspect
// button. Each maps to a fixed combination of mpv props (see setAspectMode):
//   fit     — whole frame, letterboxed (mpv default)
//   fill    — crop edges to fill the screen, aspect preserved (panscan)
//   stretch — distort to fill the screen (keepaspect=no)
//   zoom    — ~1.2× punch-in on top of fit (video-zoom)
export type AspectMode = "fit" | "fill" | "stretch" | "zoom";

// Cycle order for the aspect button.
export const ASPECT_MODES: readonly AspectMode[] = [
  "fit",
  "fill",
  "stretch",
  "zoom",
];

// The injected globals have no shipped types; describe just what we touch.
interface QtSignal<A extends unknown[]> {
  connect(cb: (...args: A) => void): void;
  disconnect(cb: (...args: A) => void): void;
}

interface MpvBridge {
  // False when libmpv failed to initialize in the shell (broken GL/mpv
  // stack); the shell keeps running but playback is impossible.
  valid?: boolean;

  positionChanged: QtSignal<[number]>;
  durationChanged: QtSignal<[number]>;
  pausedChanged: QtSignal<[boolean]>;
  volumeChanged: QtSignal<[number]>;
  fileLoaded: QtSignal<[]>;
  endReached: QtSignal<[]>;
  tracksChanged: QtSignal<[MpvTrack[]]>;

  play(url: string): void;
  pause(): void;
  resume(): void;
  stop(): void;
  seek(seconds: number): void;
  setAudioTrack(id: number): void;
  setSubtitleTrack(id: number): void;
  addSubtitle(url: string, title: string, lang: string): void;
  setVolume(volume: number): void;
  setFullscreen(fullscreen: boolean): void;
  setMpvProperty(name: string, value: string): void;
  requestState(): void;
  reloadMpvConf?(): void;
}

// Shell bridge: object registered on the WebChannel by the Qt side.
// Currently exposes setTvZoom so the web layer can drive WebEngineView.zoomFactor.
interface ShellBridge {
  setTvZoom(enabled: boolean): void;
}

declare global {
  interface Window {
    qt?: { webChannelTransport: unknown };
    QWebChannel?: new (
      transport: unknown,
      cb: (channel: { objects: { mpv: MpvBridge; shell?: ShellBridge } }) => void,
    ) => void;
  }
}

export class MpvPlayer {
  /** Running inside the Cove shell (the bridge globals are present). */
  available = $state(false);
  /** Channel handshake finished; controls are live. */
  ready = $state(false);

  position = $state(0); // seconds
  duration = $state(0); // seconds
  paused = $state(true);
  volume = $state(100); // 0–100
  ended = $state(false);
  isFullscreen = $state(false);
  playbackSpeed = $state(1);
  aspectMode = $state<AspectMode>("fit");

  audioTracks = $state<MpvTrack[]>([]);
  subtitleTracks = $state<MpvTrack[]>([]);

  #mpv: MpvBridge | null = null;
  #resolveReady!: () => void;

  // After seek() we ignore incoming positionChanged events until this timestamp
  // passes. mpv queues position events before it processes the seek command, so
  // those arrive via the WebChannel and would overwrite the optimistic position,
  // causing the seek bar to snap back to the pre-seek position briefly.
  #seekLockUntil = 0;
  /** Resolves once the bridge is connected; never resolves outside the shell. */
  readonly whenReady: Promise<void> = new Promise((r) => {
    this.#resolveReady = r;
  });

  constructor() {
    this.#connect();
  }

  #connect(): void {
    const transport = window.qt?.webChannelTransport;
    const Channel = window.QWebChannel;
    if (!transport || !Channel) return; // not inside the shell
    this.available = true;

    new Channel(transport, (channel) => {
      // Report the desktop TV-mode preference to the shell every boot: true
      // applies the page zoom that matches Android TV's density scaling, false
      // resets it (a reload reuses the same WebEngineView, so stale zoom from a
      // previous TV-mode session must be cleared explicitly).
      // This runs before the mpv presence/validity checks so zoom works even if
      // mpv failed to initialize.
      channel.objects.shell?.setTvZoom?.(isDesktopTvMode());

      const mpv = channel.objects.mpv;
      if (!mpv) {
        console.error("[player] mpv missing from channel");
        return;
      }
      if (mpv.valid === false) {
        console.warn(
          "[player] mpv failed to initialize in the shell — playback unavailable",
        );
        this.available = false;
        return;
      }
      this.#mpv = mpv;

      mpv.positionChanged.connect((s) => {
        // Discard stale pre-seek events that arrive in the 500 ms window after
        // seek() sets the lock. Accept early if mpv already confirmed a position
        // within 3 s of the seek target (it snaps to the nearest keyframe).
        if (Date.now() < this.#seekLockUntil) {
          if (Math.abs(s - this.position) > 3.0) return;
          this.#seekLockUntil = 0;
        }
        this.position = s;
      });
      mpv.durationChanged.connect((s) => {
        this.duration = s;
      });
      mpv.pausedChanged.connect((p) => (this.paused = p));
      mpv.volumeChanged.connect((v) => (this.volume = v));
      mpv.fileLoaded.connect(() => {
        this.ended = false;
      });
      mpv.endReached.connect(() => (this.ended = true));
      mpv.tracksChanged.connect((tracks) => this.#applyTracks(tracks));

      this.ready = true;
      this.#resolveReady();

      // mpv emitted the initial values of its observed properties before this
      // channel connected, so those first events were missed. Pull the current
      // state now that our handlers are attached — otherwise `paused` stays at
      // its default `true`, which inverts the play/pause button and makes the
      // progress-save effect (gated on !paused) never fire.
      mpv.requestState();
    });
  }

  #applyTracks(tracks: MpvTrack[]): void {
    this.audioTracks = tracks.filter((t) => t.type === "audio");
    this.subtitleTracks = tracks.filter((t) => t.type === "sub");
  }

  // ─── Control API ────────────────────────────────────────────────────────────
  // Slots are fire-and-forget over the channel; reactive state updates arrive
  // back via the observed-property signals. Where it makes the UI feel instant
  // we also set the local state optimistically (it's overwritten by the signal).

  play(url: string): void {
    this.ended = false;
    this.position = 0;
    // duration must reset too: this is a singleton, and mpv only pushes a
    // durationChanged once the NEW file's duration is known — leaving the old
    // file's value here made canPlay (which gates on duration > 0) flip true
    // the instant a new src was set, before anything had actually loaded.
    // Everything keyed on canPlay (loading screen, resume seek, up-next
    // resolution) then ran against the previous file's stale duration/position.
    this.duration = 0;
    // Arm the same lock seek() uses: the Qt WebChannel can still deliver a
    // queued positionChanged from the PREVIOUS stream after this call (mpv
    // emitted it just before processing the load command). Without this,
    // that stale near-end-of-file value overwrites the optimistic 0 above,
    // and if the new file happens to have a similar duration, canPlay flips
    // true with `duration - position` already under the up-next threshold —
    // showing (and autoplay-advancing past) the next episode's up-next
    // overlay within moments of it starting.
    this.#seekLockUntil = Date.now() + 500;
    // mpv keeps `speed` across loadfile on the same instance; a 2× pick from a
    // previous session must not leak into the next one. Reset unconditionally
    // instead of guarding on the current value: play() runs inside the shells'
    // playback $effect, and READING this.playbackSpeed here would register it
    // as a dependency — making every speed change restart the stream.
    this.playbackSpeed = 1;
    this.#mpv?.setMpvProperty("speed", "1");
    this.#mpv?.play(url);
  }

  setPlaybackSpeed(speed: number): void {
    if (!Number.isFinite(speed) || speed <= 0) return;
    this.playbackSpeed = speed;
    this.#mpv?.setMpvProperty("speed", String(speed));
  }

  /** Apply an aspect-fit mode. All three props are set every time so switching
   *  modes fully undoes the previous mode's effect. video-zoom is log2, so
   *  0.263 ≈ 2^0.263 ≈ 1.2× for the "zoom" punch-in. Not reset in play(): the
   *  player components re-apply the per-media saved mode on every src change
   *  (reading aspectMode here would make it a play() dependency and restart the
   *  stream on each aspect change — same hazard the speed reset avoids). */
  setAspectMode(mode: AspectMode): void {
    this.aspectMode = mode;
    const panscan = mode === "fill" ? "1.0" : "0";
    const keepaspect = mode === "stretch" ? "no" : "yes";
    const zoom = mode === "zoom" ? "0.263" : "0";
    // "fill" (panscan) and "zoom" (video-zoom) scale the video past the window
    // and crop the overflow. mpv otherwise anchors subtitles to that oversized
    // video rectangle, so bottom subs land in the cropped-off region and vanish
    // off-screen. Forcing margins clamps subs to the visible window instead;
    // reset to mpv's defaults (force-margins off) for the non-cropping modes.
    const forceMargins = mode === "fill" || mode === "zoom" ? "yes" : "no";
    this.#mpv?.setMpvProperty("panscan", panscan);
    this.#mpv?.setMpvProperty("keepaspect", keepaspect);
    this.#mpv?.setMpvProperty("video-zoom", zoom);
    this.#mpv?.setMpvProperty("sub-use-margins", "yes");
    this.#mpv?.setMpvProperty("sub-ass-force-margins", forceMargins);
  }

  /** Apply the user's subtitle-style preferences to mpv. All three apply live
   *  and persist across loadfile on this instance, so callers re-run this only
   *  when the settings change or the bridge becomes ready.
   *    sizePct        — 50–200, percentage (maps to sub-scale, 1.0 = normal)
   *    posFromBottom  — 2–90, percent up from the bottom edge (sub-pos counts
   *                     from the top where 100 = bottom, so invert)
   *    background     — draw an opaque box behind the text */
  setSubtitleStyle(
    sizePct: number,
    posFromBottom: number,
    background: boolean,
  ): void {
    this.#mpv?.setMpvProperty("sub-scale", String(sizePct / 100));
    this.#mpv?.setMpvProperty("sub-pos", String(100 - posFromBottom));
    this.#mpv?.setMpvProperty(
      "sub-border-style",
      background ? "opaque-box" : "outline-and-shadow",
    );
  }

  /** Advance to the next aspect mode in ASPECT_MODES and return it (so callers
   *  can persist the choice). */
  cycleAspectMode(): AspectMode {
    const i = ASPECT_MODES.indexOf(this.aspectMode);
    const next = ASPECT_MODES[(i + 1) % ASPECT_MODES.length];
    this.setAspectMode(next);
    return next;
  }

  pause(): void {
    this.paused = true; // optimistic; pausedChanged confirms
    this.#mpv?.pause();
  }

  resume(): void {
    this.paused = false; // optimistic; pausedChanged confirms
    this.#mpv?.resume();
  }

  togglePause(): void {
    if (this.paused) this.resume();
    else this.pause();
  }

  stop(): void {
    this.position = 0;
    this.duration = 0;
    this.#mpv?.stop();
  }

  seek(seconds: number): void {
    if (!Number.isFinite(seconds)) return;
    const clamped = this.duration
      ? Math.max(0, Math.min(seconds, this.duration))
      : Math.max(0, seconds);
    this.position = clamped; // optimistic; positionChanged confirms
    this.#seekLockUntil = Date.now() + 500; // suppress stale pre-seek events
    this.#mpv?.seek(clamped);
  }

  setVolume(volume: number): void {
    if (!Number.isFinite(volume)) return;
    const clamped = Math.max(0, Math.min(volume, 100));
    this.volume = clamped;
    this.#mpv?.setVolume(clamped);
  }

  /** mpv audio track id (from `audioTracks[].id`). */
  setAudioTrack(id: number): void {
    this.#mpv?.setAudioTrack(id);
  }

  /** mpv subtitle track id; pass a negative id to turn subtitles off. */
  setSubtitleTrack(id: number): void {
    this.#mpv?.setSubtitleTrack(id);
  }

  /** Load an external subtitle (e.g. OpenSubtitles URL) and select it. */
  addSubtitle(url: string, title = "", lang = ""): void {
    this.#mpv?.addSubtitle(url, title, lang);
  }

  setFullscreen(fullscreen: boolean): void {
    if (this.isFullscreen === fullscreen) return;
    this.isFullscreen = fullscreen;
    this.#mpv?.setFullscreen(fullscreen);
  }

  toggleFullscreen(): void {
    this.setFullscreen(!this.isFullscreen);
  }

  /** Re-reads mpv.conf on disk and applies it without a full restart.
   *  Delegates to the shell's reloadMpvConf slot; no-op outside the shell or
   *  on older builds that don't expose the slot yet. */
  reloadMpvConf(): void {
    this.#mpv?.reloadMpvConf?.();
  }
}

// Preserve the Player instance across Vite HMR module re-evaluations.
// Creating a second QWebChannel with the same transport overwrites the
// transport's onmessage handler, which silently breaks signal delivery for
// both channels (positionChanged stops reaching JS even though C++ emits it).
// import.meta.hot.data persists across HMR boundary; reuse the same instance.
function makeOrReusePlayer(): MpvPlayer {
  const hotData = import.meta.hot?.data;
  if (hotData) {
    hotData.player ??= new MpvPlayer();
    return hotData.player as MpvPlayer;
  }
  return new MpvPlayer();
}
export const Player = makeOrReusePlayer();
