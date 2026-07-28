import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  api: {
    authSync: vi.fn(),
    authLogout: vi.fn(),
    profilesList: vi.fn(),
    profileActivate: vi.fn(),
    profileRename: vi.fn(),
    profileCreate: vi.fn(),
    profileDelete: vi.fn(),
  },
  logout: vi.fn(),
  setProfiles: vi.fn(),
  libraryUpdate: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks.api }));
vi.mock("$lib/stores/auth.svelte", () => ({
  auth: { logout: mocks.logout, setProfiles: mocks.setProfiles },
}));
vi.mock("$lib/stores/library", () => ({
  libraryChanged: { update: mocks.libraryUpdate },
}));

import {
  activateAccountProfile,
  createAccountProfile,
  deleteAccountProfile,
  logoutAccount,
  refreshAccountProfiles,
  renameAccountProfile,
  syncAccount,
} from "$lib/accountActions";

beforeEach(() => {
  vi.clearAllMocks();
  for (const fn of Object.values(mocks.api)) fn.mockResolvedValue(undefined);
  mocks.logout.mockResolvedValue(undefined);
  mocks.api.profilesList.mockResolvedValue({
    profiles: [{ id: "one" }, { id: "two" }],
    active_profile_id: "two",
  });
});

describe("account actions", () => {
  it("syncs and notifies library consumers", async () => {
    await syncAccount();
    expect(mocks.api.authSync).toHaveBeenCalledOnce();
    expect(mocks.libraryUpdate).toHaveBeenCalledOnce();
  });

  it("logs out both the backend and local auth store", async () => {
    await logoutAccount();
    expect(mocks.api.authLogout).toHaveBeenCalledOnce();
    expect(mocks.logout).toHaveBeenCalledOnce();
  });

  it("refreshes and selects the active profile", async () => {
    await refreshAccountProfiles();
    expect(mocks.setProfiles).toHaveBeenCalledWith(
      [{ id: "one" }, { id: "two" }],
      { id: "two" },
    );
  });

  it("activates, creates, renames, and deletes profiles through shared operations", async () => {
    await activateAccountProfile("two");
    await createAccountProfile("Created");
    await renameAccountProfile("two", "Renamed");
    await deleteAccountProfile("one");

    expect(mocks.api.profileActivate).toHaveBeenCalledWith("two");
    expect(mocks.api.profileCreate).toHaveBeenCalledWith("Created");
    expect(mocks.api.profileRename).toHaveBeenCalledWith("two", "Renamed");
    expect(mocks.api.profileDelete).toHaveBeenCalledWith("one");
    expect(mocks.api.profilesList).toHaveBeenCalledTimes(3);
    expect(mocks.libraryUpdate).toHaveBeenCalledOnce();
  });
});
