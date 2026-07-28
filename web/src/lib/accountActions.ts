import { api } from "$lib/api";
import { auth } from "$lib/stores/auth.svelte";
import { libraryChanged } from "$lib/stores/library";

function libraryMutationFinished(): void {
  libraryChanged.update((generation) => generation + 1);
}

export async function syncAccount(): Promise<void> {
  await api.authSync();
  libraryMutationFinished();
}

export async function logoutAccount(): Promise<void> {
  await api.authLogout();
  await auth.logout();
}

export async function refreshAccountProfiles(): Promise<void> {
  const response = await api.profilesList();
  auth.setProfiles(
    response.profiles,
    response.profiles.find(
      (profile) => profile.id === response.active_profile_id,
    ) ?? response.profiles[0],
  );
}

export async function activateAccountProfile(id: string): Promise<void> {
  await api.profileActivate(id);
}

export async function renameAccountProfile(
  id: string,
  name: string,
): Promise<void> {
  await api.profileRename(id, name);
  await refreshAccountProfiles();
}

export async function createAccountProfile(name: string): Promise<void> {
  await api.profileCreate(name);
  await refreshAccountProfiles();
}

export async function deleteAccountProfile(id: string): Promise<void> {
  await api.profileDelete(id);
  await refreshAccountProfiles();
  libraryMutationFinished();
}
