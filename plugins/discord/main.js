"use strict";

let settings = cove.settings.all();
let lastPlayback = null;
let lastSignature = "";
let lastSentAt = 0;
let presenceVisible = false;

function setting(key, fallback) {
  const value = settings[key];
  return typeof value === "boolean" ? value : fallback;
}

function episodeLabel(activity) {
  if (!setting("show_episode", true)) return "";
  const parts = [];
  if (Number.isFinite(activity.season) && Number.isFinite(activity.episode)) {
    parts.push(`S${String(activity.season).padStart(2, "0")} E${String(activity.episode).padStart(2, "0")}`);
  }
  if (activity.episodeTitle) parts.push(String(activity.episodeTitle));
  return parts.join(" · ");
}

function playbackLabel(activity) {
  if (!setting("show_playback_state", true)) return "";
  if (activity.reconnecting) return "Reconnecting";
  if (activity.paused && activity.phase === "playing") return "Paused";
  if (activity.phase === "opening" || activity.phase === "resolving") return "Opening";
  if (activity.phase === "choosing") return "Choosing a source";
  return "";
}

function buildActivity(activity) {
  const episode = episodeLabel(activity);
  const playback = playbackLabel(activity);
  const state = [episode, playback].filter(Boolean).join(" · ");
  const artwork = setting("show_artwork", true) &&
    typeof activity.artworkUrl === "string" &&
    activity.artworkUrl.length <= 300 &&
    activity.artworkUrl.startsWith("https://image.tmdb.org/t/p/")
      ? activity.artworkUrl
      : "";
  const result = {
    type: 3,
    details: setting("show_title", true) && activity.title ? String(activity.title) : "Watching with Cove",
    state: state || "Watching with Cove",
    assets: artwork ? {
      large_image: artwork,
      large_text: activity.title ? String(activity.title) : "Watching with Cove",
      small_image: "cove",
      small_text: "Cove"
    } : {
      large_image: "cove",
      large_text: "Cove"
    },
    instance: false
  };

  if (
    setting("show_progress", true) &&
    activity.phase === "playing" &&
    !activity.paused &&
    Number.isFinite(activity.positionSeconds) &&
    Number.isFinite(activity.durationSeconds) &&
    activity.durationSeconds > activity.positionSeconds
  ) {
    const speed = Number.isFinite(activity.speed) && activity.speed > 0 ? activity.speed : 1;
    result.timestamps = {
      end: Math.floor(Date.now() / 1000 + (activity.durationSeconds - activity.positionSeconds) / speed)
    };
  }
  return result;
}

function clearPresence() {
  if (presenceVisible) cove.discord.clear();
  presenceVisible = false;
  lastSignature = "";
  lastSentAt = 0;
}

function onPlaybackChanged(activity) {
  lastPlayback = activity;
  if (!activity || !activity.active || activity.phase === "idle" || activity.phase === "failed") {
    clearPresence();
    return;
  }

  const presence = buildActivity(activity);
  const signature = JSON.stringify({
    details: presence.details,
    state: presence.state,
    phase: activity.phase,
    paused: activity.paused,
    reconnecting: activity.reconnecting,
    artwork: presence.assets.large_image,
    duration: activity.durationSeconds,
    speed: activity.speed
  });
  const now = Date.now();
  if (signature === lastSignature && now - lastSentAt < 15000) return;

  cove.discord.setActivity(presence);
  presenceVisible = true;
  lastSignature = signature;
  lastSentAt = now;
}

module.exports = {
  activate() {},
  deactivate() {
    clearPresence();
  },
  onPlaybackChanged,
  settingsChanged(next) {
    settings = Object.assign({}, settings, next || {});
    lastSignature = "";
    if (lastPlayback) onPlaybackChanged(lastPlayback);
  }
};
