import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  profilesList: vi.fn(),
  clientSessionGet: vi.fn(),
  clientSessionSave: vi.fn(),
  clientSessionDelete: vi.fn(),
  clearInflight: vi.fn(),
}));

const supabaseMock = vi.hoisted(() => ({
  auth: {
    setSession: vi.fn(),
    onAuthStateChange: vi.fn(),
    signOut: vi.fn(),
  },
}));

vi.mock("$lib/api", () => ({ api: apiMock }));
vi.mock("$lib/supabase", () => ({ supabase: supabaseMock }));

import { AuthStore } from "$lib/stores/auth.svelte";
import type { Profile } from "$lib/types/profiles";

const primary: Profile = {
  id: "primary",
  name: "Primary",
  is_primary: true,
  name_updated_at: "",
};
const secondary: Profile = {
  id: "secondary",
  name: "Secondary",
  is_primary: false,
  name_updated_at: "",
};

interface TestSupabaseSession {
  access_token: string;
  refresh_token: string;
  user: { email?: string | null };
}

let authCallback:
  ((event: string, session: TestSupabaseSession | null) => void) | undefined;
const unsubscribe = vi.fn();

describe("AuthStore", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authCallback = undefined;
    apiMock.profilesList.mockResolvedValue({
      profiles: [primary, secondary],
      active_profile_id: secondary.id,
    });
    apiMock.clientSessionGet.mockResolvedValue({
      accessToken: "saved-access",
      refreshToken: "saved-refresh",
      email: "saved@example.test",
    });
    apiMock.clientSessionSave.mockResolvedValue(undefined);
    apiMock.clientSessionDelete.mockResolvedValue(undefined);
    supabaseMock.auth.setSession.mockResolvedValue({});
    supabaseMock.auth.signOut.mockResolvedValue({});
    supabaseMock.auth.onAuthStateChange.mockImplementation(
      (callback: typeof authCallback) => {
        authCallback = callback;
        return { data: { subscription: { unsubscribe } } };
      },
    );
  });

  it("restores profiles and a persisted session exactly once", async () => {
    const store = new AuthStore();

    await Promise.all([store.init(), store.init()]);

    expect(apiMock.profilesList).toHaveBeenCalledTimes(1);
    expect(apiMock.clientSessionGet).toHaveBeenCalledTimes(1);
    expect(store.activeProfile).toEqual(secondary);
    expect(store.session).toEqual({
      accessToken: "saved-access",
      email: "saved@example.test",
    });
    expect(store.authToken).toBe("saved-access");
    expect(store.isGuest).toBe(false);
    expect(supabaseMock.auth.setSession).toHaveBeenCalledWith({
      access_token: "saved-access",
      refresh_token: "saved-refresh",
    });
    expect(supabaseMock.auth.onAuthStateChange).toHaveBeenCalledTimes(1);
  });

  it("falls back to the first profile and tolerates a missing persisted session", async () => {
    const store = new AuthStore();
    const consoleLog = vi
      .spyOn(console, "log")
      .mockImplementation(() => undefined);
    apiMock.profilesList.mockResolvedValue({
      profiles: [primary, secondary],
      active_profile_id: "missing",
    });
    apiMock.clientSessionGet.mockRejectedValue(new Error("not signed in"));

    await store.init();

    expect(store.activeProfile).toEqual(primary);
    expect(store.isGuest).toBe(true);
    expect(consoleLog).toHaveBeenCalledWith(
      "[auth] init: no persisted session",
    );
    expect(supabaseMock.auth.onAuthStateChange).toHaveBeenCalledOnce();
  });

  it("uses a null active profile for an empty profile list", async () => {
    const store = new AuthStore();
    apiMock.profilesList.mockResolvedValue({
      profiles: [],
      active_profile_id: "",
    });

    await store.init();

    expect(store.profiles).toEqual([]);
    expect(store.activeProfile).toBeNull();
  });

  it("continues restoring auth when profile loading fails", async () => {
    const store = new AuthStore();
    const error = new Error("profiles unavailable");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    apiMock.profilesList.mockRejectedValue(error);

    await store.init();

    expect(consoleError).toHaveBeenCalledWith(
      "[auth] init: load profiles:",
      error,
    );
    expect(store.session?.accessToken).toBe("saved-access");
  });

  it("keeps a restored local session when Supabase setup fails", async () => {
    const store = new AuthStore();
    const error = new Error("Supabase unavailable");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    supabaseMock.auth.setSession.mockRejectedValue(error);

    await store.init();
    await Promise.resolve();

    expect(store.session?.accessToken).toBe("saved-access");
    expect(consoleError).toHaveBeenCalledWith(
      "[auth] init: supabase.auth.setSession failed:",
      error,
    );
  });

  it("persists genuine token refreshes but skips the restore echo", async () => {
    const store = new AuthStore();
    await store.init();

    authCallback?.("INITIAL_SESSION", {
      access_token: "saved-access",
      refresh_token: "saved-refresh",
      user: { email: "saved@example.test" },
    });
    expect(apiMock.clientSessionSave).not.toHaveBeenCalled();

    authCallback?.("TOKEN_REFRESHED", {
      access_token: "fresh-access",
      refresh_token: "fresh-refresh",
      user: { email: "saved@example.test" },
    });
    await Promise.resolve();

    expect(store.authToken).toBe("fresh-access");
    expect(apiMock.clearInflight).toHaveBeenCalledTimes(2);
    expect(apiMock.clientSessionSave).toHaveBeenCalledWith({
      accessToken: "fresh-access",
      refreshToken: "fresh-refresh",
      email: "saved@example.test",
    });
  });

  it("normalizes a missing Supabase email and absorbs refresh persistence errors", async () => {
    const store = new AuthStore();
    const error = new Error("disk full");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    apiMock.clientSessionSave.mockRejectedValue(error);
    await store.init();

    authCallback?.("TOKEN_REFRESHED", {
      access_token: "fresh-access",
      refresh_token: "fresh-refresh",
      user: { email: null },
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(store.session).toEqual({
      accessToken: "fresh-access",
      email: "",
    });
    expect(apiMock.clientSessionSave).toHaveBeenCalledWith({
      accessToken: "fresh-access",
      refreshToken: "fresh-refresh",
      email: "",
    });
    expect(consoleError).toHaveBeenCalledWith(error);
  });

  it("only clears local and persisted state for an explicit sign-out", async () => {
    const store = new AuthStore();
    await store.init();

    authCallback?.("TOKEN_REFRESHED", null);
    expect(store.isGuest).toBe(false);
    expect(apiMock.clientSessionDelete).not.toHaveBeenCalled();

    authCallback?.("SIGNED_OUT", null);
    await Promise.resolve();

    expect(store.isGuest).toBe(true);
    expect(store.authToken).toBeNull();
    expect(apiMock.clientSessionDelete).toHaveBeenCalledTimes(1);
  });

  it("sets, persists, and forwards a new session to Supabase", async () => {
    const store = new AuthStore();

    await store.setSession(
      "new-access",
      "new@example.test",
      [primary],
      primary,
      "new-refresh",
    );

    expect(store.session).toEqual({
      accessToken: "new-access",
      email: "new@example.test",
    });
    expect(store.profiles).toEqual([primary]);
    expect(store.activeProfile).toEqual(primary);
    expect(apiMock.clearInflight).toHaveBeenCalledTimes(1);
    expect(apiMock.clientSessionSave).toHaveBeenCalledWith({
      accessToken: "new-access",
      refreshToken: "new-refresh",
      email: "new@example.test",
    });
    expect(supabaseMock.auth.setSession).toHaveBeenCalledWith({
      access_token: "new-access",
      refresh_token: "new-refresh",
    });
  });

  it("keeps an unpersisted session usable when no refresh token is provided", async () => {
    const store = new AuthStore();
    const consoleWarn = vi
      .spyOn(console, "warn")
      .mockImplementation(() => undefined);

    await store.setSession(
      "temporary-access",
      "temporary@example.test",
      [primary],
      primary,
    );

    expect(store.authToken).toBe("temporary-access");
    expect(apiMock.clientSessionSave).not.toHaveBeenCalled();
    expect(supabaseMock.auth.setSession).not.toHaveBeenCalled();
    expect(consoleWarn).toHaveBeenCalledWith(
      "[auth] setSession: no refreshToken — session will not persist",
    );
  });

  it("clears request identity on profile changes and logout", async () => {
    const store = new AuthStore();
    store.setProfiles([primary, secondary], secondary);

    expect(store.activeProfile).toEqual(secondary);
    expect(apiMock.clearInflight).toHaveBeenCalledTimes(1);

    await store.logout();
    expect(store.isGuest).toBe(true);
    expect(apiMock.clientSessionDelete).toHaveBeenCalledTimes(1);
    expect(supabaseMock.auth.signOut).toHaveBeenCalledTimes(1);
    expect(apiMock.clearInflight).toHaveBeenCalledTimes(2);
  });

  it("still signs out of Supabase when deleting the persisted session fails", async () => {
    const store = new AuthStore();
    const error = new Error("delete failed");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    apiMock.clientSessionDelete.mockRejectedValue(error);

    await store.logout();

    expect(store.isGuest).toBe(true);
    expect(consoleError).toHaveBeenCalledWith(error);
    expect(supabaseMock.auth.signOut).toHaveBeenCalledOnce();
  });
});
