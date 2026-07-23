import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  mount: vi.fn(),
  isAndroid: vi.fn(),
  isTvMode: vi.fn(),
}));
const apps = vi.hoisted(() => ({
  desktop: vi.fn(),
  mobile: vi.fn(),
  tv: vi.fn(),
}));

vi.mock("svelte", () => ({ mount: mocks.mount }));
vi.mock("$lib/platform", () => ({
  isAndroid: mocks.isAndroid,
  isTvMode: mocks.isTvMode,
}));
vi.mock("./App.svelte", () => ({ default: apps.desktop }));
vi.mock("./mobile/MobileApp.svelte", () => ({ default: apps.mobile }));
vi.mock("./tv/TvApp.svelte", () => ({ default: apps.tv }));

async function loadEntrypoint(): Promise<HTMLElement> {
  const target = document.createElement("div");
  target.id = "app";
  document.body.replaceChildren(target);
  await import("./main");
  await vi.waitFor(() => expect(mocks.mount).toHaveBeenCalledOnce());
  return target;
}

describe("frontend entrypoint", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
    mocks.isAndroid.mockReturnValue(false);
    mocks.isTvMode.mockReturnValue(false);
    localStorage.clear();
    history.replaceState(null, "", "/");
  });

  it("loads the desktop shell by default", async () => {
    const target = await loadEntrypoint();

    expect(mocks.mount).toHaveBeenCalledWith(apps.desktop, { target });
  });

  it("loads the mobile shell on Android", async () => {
    mocks.isAndroid.mockReturnValue(true);
    const target = await loadEntrypoint();

    expect(mocks.mount).toHaveBeenCalledWith(apps.mobile, { target });
  });

  it("persists TV mode, strips only its query parameter, and prioritizes TV", async () => {
    history.replaceState(null, "", "/browse?tvui=1&source=remote");
    mocks.isTvMode.mockReturnValue(true);
    mocks.isAndroid.mockReturnValue(true);
    const replaceState = vi.spyOn(history, "replaceState");

    const target = await loadEntrypoint();

    expect(localStorage.getItem("cove-tv-ui")).toBe("1");
    expect(replaceState).toHaveBeenCalledWith(
      null,
      "",
      "/browse?source=remote",
    );
    expect(mocks.mount).toHaveBeenCalledWith(apps.tv, { target });
  });

  it("removes the question mark when TV mode was the only query parameter", async () => {
    history.replaceState(null, "", "/?tvui=1");
    mocks.isTvMode.mockReturnValue(true);
    const replaceState = vi.spyOn(history, "replaceState");

    await loadEntrypoint();

    expect(replaceState).toHaveBeenCalledWith(null, "", "/");
  });
});
