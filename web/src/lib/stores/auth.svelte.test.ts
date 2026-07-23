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
});
