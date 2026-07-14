import { mount } from "svelte";

import "./assets/app.css";

import { isAndroid, isAndroidTV } from "$lib/platform";

async function init() {
  const target = document.getElementById("app")!;
  if (isAndroidTV()) {
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
