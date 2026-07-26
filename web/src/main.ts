import { mount } from "svelte";

import "./assets/app.css";

import { isAndroid, isTvMode } from "$lib/platform";
import { initializeLocalization } from "$lib/i18n";

async function init() {
  // Locale must be active before any shell mounts. This prevents a flash of
  // English and ensures the first metadata requests use the profile language.
  await initializeLocalization();

  // The Qt shell's --tv flag appends ?tvui=1 to the initial URL. Persist the
  // preference to localStorage so later reloads don't need the param, then
  // strip it from the address bar so it doesn't accumulate across history
  // entries.
  const params = new URLSearchParams(location.search);
  if (params.get("tvui") === "1") {
    localStorage.setItem("cove-tv-ui", "1");
    params.delete("tvui");
    const newSearch = params.toString();
    history.replaceState(
      null,
      "",
      location.pathname + (newSearch ? "?" + newSearch : ""),
    );
  }

  const target = document.getElementById("app")!;
  if (isTvMode()) {
    const { default: TvApp } = await import("./tv/TvApp.svelte");
    mount(TvApp, { target });
  } else if (isAndroid()) {
    const { default: MobileApp } = await import("./mobile/MobileApp.svelte");
    mount(MobileApp, { target });
  } else {
    const { default: App } = await import("./App.svelte");
    mount(App, { target });
  }
}

init();
