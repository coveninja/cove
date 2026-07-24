import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  profilesList: vi.fn(),
  clientSessionGet: vi.fn(),
  clientSessionSave: vi.fn(),
  clientSessionDelete: vi.fn(),
  clearInflight: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: apiMock }));
vi.mock("$lib/supabase", () => ({ supabase: null }));

import { AuthStore } from "$lib/stores/auth.svelte";
import type { Profile } from "$lib/types/profiles";

const primary: Profile = {
  id: "primary",
  name: "Primary",
  is_primary: true,
  name_updated_at: "",
};

describe("AuthStore without Supabase configuration", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMock.profilesList.mockResolvedValue({
      profiles: [primary],
      active_profile_id: primary.id,
    });
    apiMock.clientSessionGet.mockResolvedValue({
      accessToken: "saved-access",
      refreshToken: "saved-refresh",
      email: "saved@example.test",
    });
    apiMock.clientSessionSave.mockResolvedValue(undefined);
    apiMock.clientSessionDelete.mockResolvedValue(undefined);
  });

  it("restores the backend session without registering a refresh listener", async () => {
    const store = new AuthStore();

    await store.init();

    expect(store.authToken).toBe("saved-access");
    expect(store.activeProfile).toEqual(primary);
  });

  it("persists and clears sessions using only the backend store", async () => {
    const store = new AuthStore();

    await store.setSession(
      "new-access",
      "new@example.test",
      [primary],
      primary,
      "new-refresh",
    );
    await store.logout();

    expect(apiMock.clientSessionSave).toHaveBeenCalledWith({
      accessToken: "new-access",
      refreshToken: "new-refresh",
      email: "new@example.test",
    });
    expect(apiMock.clientSessionDelete).toHaveBeenCalledOnce();
    expect(store.isGuest).toBe(true);
  });
});
