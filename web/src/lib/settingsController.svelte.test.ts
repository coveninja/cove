import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getAddons: vi.fn(),
  addAddon: vi.fn(),
  toggleAddon: vi.fn(),
  removeAddon: vi.fn(),
  toggleCatalog: vi.fn(),
  refreshAddon: vi.fn(),
  getNuvioRepos: vi.fn(),
  addNuvioRepo: vi.fn(),
  setNuvioRepoEnabled: vi.fn(),
  removeNuvioRepo: vi.fn(),
  refreshNuvioRepo: vi.fn(),
  setNuvioScraperEnabled: vi.fn(),
  testDiscoveryAlgorithm: vi.fn(),
  revealRemoteAccessToken: vi.fn(),
  speedtestUrl: vi.fn(() => "/api/speedtest"),
  // Settings store
  load: vi.fn(),
  save: vi.fn(),
  getCurrent: vi.fn(),
  subscribe: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));
vi.mock("$lib/stores/settings", () => ({
  settings: {
    load: mocks.load,
    save: mocks.save,
    getCurrent: mocks.getCurrent,
    subscribe: mocks.subscribe,
  },
}));

import { SettingsController } from "$lib/settingsController.svelte";
import type { AddonEntry } from "$lib/types/addons";
import { KindProvider } from "$lib/types/addons";
import type { Repo as NuvioRepo, Scraper as NuvioScraper } from "$lib/types/nuvio";
import type { Settings } from "$lib/types/settings";

const BASE = {
  uiLanguage: "en",
  remoteAccessToken: "",
  updatedAt: "t0",
  customAlgorithmUrl: "",
} as Settings;

/** Stand-in for the store: subscribe() fires once with `value`, synchronously. */
function storeYields(value: Settings) {
  mocks.subscribe.mockImplementation((run: (v: Settings) => void) => {
    run(value);
    return () => {};
  });
  mocks.getCurrent.mockReturnValue(value);
}

function addon(id: string, over: Partial<AddonEntry> = {}): AddonEntry {
  return {
    id,
    url: `https://${id}/manifest.json`,
    enabled: true,
    kind: KindProvider,
    ...over,
  } as AddonEntry;
}

function scraper(id: string, enabled = false): NuvioScraper {
  return { id, name: id, enabled } as NuvioScraper;
}

function repo(id: string, enabled: boolean, scrapers: NuvioScraper[]): NuvioRepo {
  return { id, enabled, scrapers } as NuvioRepo;
}

function make(): SettingsController {
  let ctl!: SettingsController;
  $effect.root(() => {
    ctl = new SettingsController();
  });
  return ctl;
}

beforeEach(() => {
  vi.useFakeTimers();
  for (const fn of Object.values(mocks)) fn.mockReset();
  mocks.speedtestUrl.mockReturnValue("/api/speedtest");
  mocks.getAddons.mockResolvedValue([]);
  mocks.getNuvioRepos.mockResolvedValue([]);
  mocks.load.mockResolvedValue(undefined);
  mocks.save.mockResolvedValue(true);
  storeYields(BASE);
});

describe("init", () => {
  it("seeds the draft from the store and kicks off both fetches", async () => {
    const ctl = make();
    await ctl.init();
    expect(ctl.draft?.uiLanguage).toBe("en");
    expect(mocks.getAddons).toHaveBeenCalled();
    expect(mocks.getNuvioRepos).toHaveBeenCalled();
  });

  it("reads the native auto-update preference when the shell exposes one", async () => {
    vi.stubGlobal("__coveApp", { getAutoUpdateEnabled: () => false });
    const ctl = make();
    await ctl.init();
    expect(ctl.autoUpdateEnabled).toBe(false);
    vi.unstubAllGlobals();
  });

  it("leaves auto-update on when the shell has no such method", async () => {
    const ctl = make();
    await ctl.init();
    expect(ctl.autoUpdateEnabled).toBe(true);
  });
});

describe("patch", () => {
  it("replaces the draft object so deriveds re-run", async () => {
    const ctl = make();
    await ctl.init();
    const before = ctl.draft;
    ctl.patch("uiLanguage", "de");
    expect(ctl.draft?.uiLanguage).toBe("de");
    expect(ctl.draft).not.toBe(before);
  });

  it("is a no-op before the draft loads", () => {
    const ctl = make();
    ctl.patch("uiLanguage", "de");
    expect(ctl.draft).toBeNull();
  });
});

describe("handleSave", () => {
  it("flags an error and stays unsaved when the store rejects the write", async () => {
    const ctl = make();
    await ctl.init();
    mocks.save.mockResolvedValue(false);
    await ctl.handleSave();
    expect(ctl.saved).toBe(false);
    expect(ctl.saveError).not.toBeNull();
  });

  it("shows the saved flag and clears it after two seconds", async () => {
    const ctl = make();
    await ctl.init();
    await ctl.handleSave();
    expect(ctl.saved).toBe(true);
    vi.advanceTimersByTime(2000);
    expect(ctl.saved).toBe(false);
  });

  it("pulls server-generated fields back into the draft", async () => {
    const ctl = make();
    await ctl.init();
    storeYields({ ...BASE, remoteAccessToken: "***", updatedAt: "t1" });
    await ctl.handleSave();
    expect(ctl.draft?.remoteAccessToken).toBe("***");
    expect(ctl.draft?.updatedAt).toBe("t1");
  });

  it("reloads the page instead of finishing when the UI language changed", async () => {
    const reload = vi.fn();
    vi.stubGlobal("location", { reload });
    const ctl = make();
    await ctl.init();
    ctl.patch("uiLanguage", "de");
    await ctl.handleSave();
    expect(reload).toHaveBeenCalled();
    expect(ctl.saved).toBe(false);
    vi.unstubAllGlobals();
  });
});

describe("handleReset", () => {
  it("drops the draft and reloads it from the server", async () => {
    const ctl = make();
    await ctl.init();
    ctl.patch("uiLanguage", "de");
    ctl.handleReset();
    expect(ctl.draft).toBeNull();
    await vi.waitFor(() => expect(ctl.draft?.uiLanguage).toBe("en"));
  });
});

describe("addons", () => {
  it("falls back to an empty list when the fetch fails", async () => {
    mocks.getAddons.mockRejectedValue(new Error("nope"));
    const ctl = make();
    await ctl.loadAddons();
    expect(ctl.addons).toEqual([]);
  });

  it("lists only provider addons in providerAddons", async () => {
    mocks.getAddons.mockResolvedValue([
      addon("a"),
      addon("b", { kind: "subtitles" }),
    ]);
    const ctl = make();
    await ctl.loadAddons();
    expect(ctl.providerAddons.map((a) => a.id)).toEqual(["a"]);
  });

  it("replaces an existing entry when re-adding the same id", async () => {
    const ctl = make();
    ctl.addons = [addon("a", { enabled: false })];
    ctl.addAddonUrl = " https://a/manifest.json ";
    mocks.addAddon.mockResolvedValue(addon("a", { enabled: true }));
    await ctl.handleAddAddon();
    expect(mocks.addAddon).toHaveBeenCalledWith("https://a/manifest.json");
    expect(ctl.addons).toHaveLength(1);
    expect(ctl.addons[0].enabled).toBe(true);
    expect(ctl.addAddonUrl).toBe("");
  });

  it("surfaces the add error and keeps the url for a retry", async () => {
    const ctl = make();
    ctl.addAddonUrl = "https://bad/manifest.json";
    mocks.addAddon.mockRejectedValue(new Error("404"));
    await ctl.handleAddAddon();
    expect(ctl.addAddonError).toBe("404");
    expect(ctl.addAddonUrl).toBe("https://bad/manifest.json");
    expect(ctl.addAddonLoading).toBe(false);
  });

  it("ignores an empty url", async () => {
    const ctl = make();
    ctl.addAddonUrl = "   ";
    await ctl.handleAddAddon();
    expect(mocks.addAddon).not.toHaveBeenCalled();
  });

  it("toggles only the matching id+url pair", async () => {
    const ctl = make();
    ctl.addons = [addon("a"), addon("b")];
    await ctl.handleToggleAddon(ctl.addons[0]);
    expect(ctl.addons[0].enabled).toBe(false);
    expect(ctl.addons[1].enabled).toBe(true);
  });

  it("removes only the matching id+url pair", async () => {
    const ctl = make();
    ctl.addons = [addon("a"), addon("b")];
    await ctl.handleRemoveAddon(ctl.addons[0]);
    expect(ctl.addons.map((a) => a.id)).toEqual(["b"]);
  });

  it("records a disabled catalog under the addon", async () => {
    const ctl = make();
    ctl.addons = [addon("a")];
    await ctl.handleToggleCatalog(ctl.addons[0], "top", false);
    expect(ctl.addons[0].disabledCatalogs).toEqual({ top: true });
  });

  it("clears the refreshing marker even when the refresh throws", async () => {
    const ctl = make();
    ctl.addons = [addon("a")];
    mocks.refreshAddon.mockRejectedValue(new Error("boom"));
    await ctl.handleRefreshAddon(ctl.addons[0]);
    expect(ctl.refreshingAddonId).toBeNull();
  });
});

describe("nuvio repos", () => {
  it("lists enabled scrapers of enabled repos, prefixed and deduped", () => {
    const ctl = make();
    ctl.nuvioRepos = [
      repo("r1", true, [scraper("alpha", true), scraper("beta", false)]),
      repo("r2", true, [scraper("alpha", true)]),
      repo("r3", false, [scraper("gamma", true)]),
    ];
    expect(ctl.nuvioProviderOptions).toEqual(["Nuvio: alpha"]);
  });

  it("toggles a repo without touching the others", async () => {
    const ctl = make();
    ctl.nuvioRepos = [repo("r1", true, []), repo("r2", true, [])];
    await ctl.handleToggleRepo(ctl.nuvioRepos[0]);
    expect(ctl.nuvioRepos[0].enabled).toBe(false);
    expect(ctl.nuvioRepos[1].enabled).toBe(true);
  });

  it("removes a repo by id", async () => {
    const ctl = make();
    ctl.nuvioRepos = [repo("r1", true, []), repo("r2", true, [])];
    await ctl.handleRemoveRepo(ctl.nuvioRepos[0]);
    expect(ctl.nuvioRepos.map((r) => r.id)).toEqual(["r2"]);
  });

  it("asks for confirmation before enabling a scraper", () => {
    const ctl = make();
    const r = repo("r1", true, [scraper("alpha", false)]);
    ctl.nuvioRepos = [r];
    ctl.requestEnableScraper(r, r.scrapers[0]);
    expect(ctl.pendingConfirm).toEqual({ repoId: "r1", scraperId: "alpha" });
    expect(mocks.setNuvioScraperEnabled).not.toHaveBeenCalled();
  });

  it("disables a scraper immediately, with no confirmation", () => {
    const ctl = make();
    const r = repo("r1", true, [scraper("alpha", true)]);
    ctl.nuvioRepos = [r];
    ctl.requestEnableScraper(r, r.scrapers[0]);
    expect(ctl.pendingConfirm).toBeNull();
    expect(mocks.setNuvioScraperEnabled).toHaveBeenCalledWith(
      "r1",
      "alpha",
      false,
    );
  });

  it("clears the confirmation once the scraper is set", async () => {
    const ctl = make();
    const r = repo("r1", true, [scraper("alpha", false)]);
    ctl.nuvioRepos = [r];
    ctl.pendingConfirm = { repoId: "r1", scraperId: "alpha" };
    await ctl.handleSetScraperEnabled(r, r.scrapers[0], true);
    expect(ctl.pendingConfirm).toBeNull();
    expect(ctl.nuvioRepos[0].scrapers[0].enabled).toBe(true);
  });
});

describe("handleTestAlgorithm", () => {
  it("does nothing without a url", async () => {
    const ctl = make();
    await ctl.init();
    await ctl.handleTestAlgorithm();
    expect(mocks.testDiscoveryAlgorithm).not.toHaveBeenCalled();
  });

  it("stores the failure when the request throws", async () => {
    const ctl = make();
    await ctl.init();
    ctl.patch("customAlgorithmUrl", "https://algo.test/x");
    mocks.testDiscoveryAlgorithm.mockRejectedValue(new Error("timeout"));
    await ctl.handleTestAlgorithm();
    expect(ctl.algorithmTestResult).toEqual({ ok: false, error: "timeout" });
    expect(ctl.testingAlgorithm).toBe(false);
  });
});

describe("remote access token", () => {
  it("fetches the real token on first reveal, then just toggles visibility", async () => {
    mocks.revealRemoteAccessToken.mockResolvedValue("secret");
    const ctl = make();
    await ctl.handleRevealToken();
    expect(ctl.revealedToken).toBe("secret");
    expect(ctl.tokenVisible).toBe(true);

    await ctl.handleRevealToken();
    expect(ctl.tokenVisible).toBe(false);
    expect(mocks.revealRemoteAccessToken).toHaveBeenCalledTimes(1);
  });

  it("leaves the token hidden when the reveal fails", async () => {
    mocks.revealRemoteAccessToken.mockRejectedValue(new Error("403"));
    vi.spyOn(console, "error").mockImplementation(() => {});
    const ctl = make();
    await ctl.handleRevealToken();
    expect(ctl.revealedToken).toBeNull();
    expect(ctl.revealingToken).toBe(false);
  });

  it("forgets a local reveal once the server clears the token", async () => {
    const ctl = make();
    await ctl.init();
    ctl.revealedToken = "secret";
    ctl.tokenVisible = true;

    ctl.clearRevealOnTokenReset();
    expect(ctl.revealedToken).toBeNull();
    expect(ctl.tokenVisible).toBe(false);
  });

  it("keeps the reveal while the server still reports a token", async () => {
    storeYields({ ...BASE, remoteAccessToken: "***" });
    const ctl = make();
    await ctl.init();
    ctl.revealedToken = "secret";
    ctl.clearRevealOnTokenReset();
    expect(ctl.revealedToken).toBe("secret");
  });
});

describe("runSpeedTest", () => {
  it("writes the measured bandwidth into the draft", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ blob: async () => ({ size: 1_000_000 }) }),
    );
    const ctl = make();
    await ctl.init();
    await ctl.runSpeedTest();
    expect(ctl.draft?.measuredBandwidthMbps).toBeGreaterThan(0);
    expect(ctl.testingSpeed).toBe(false);
    vi.unstubAllGlobals();
  });

  it("reports a failure without clobbering the draft", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    const ctl = make();
    await ctl.init();
    await ctl.runSpeedTest();
    expect(ctl.speedTestError).not.toBeNull();
    expect(ctl.testingSpeed).toBe(false);
    vi.unstubAllGlobals();
  });

  it("does nothing before the draft loads", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const ctl = make();
    await ctl.runSpeedTest();
    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});
