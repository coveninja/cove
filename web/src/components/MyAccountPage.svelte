<script lang="ts">
  import { api, type Person } from "$lib/api";
  import { auth } from "$lib/stores/auth.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import * as Card from "$lib/components/ui/card/index.js";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import {
    RefreshCw,
    LogOut,
    LogIn,
    Trash2,
    Check,
    Pencil,
  } from "lucide-svelte";
  import { ScrollArea } from "$lib/components/ui/scroll-area/index.js";
  import InsightsPage from "./InsightsPage.svelte";
  import ConfirmDialog from "./ConfirmDialog.svelte";
  import AuthDialog from "./AuthDialog.svelte";

  let {
    visible = true,
    onSelectPerson,
  }: { visible?: boolean; onSelectPerson: (p: Person) => void } = $props();

  // ── Account card state ───────────────────────────────────────────────────────
  let syncing = $state(false);
  let authOpen = $state(false);

  async function sync(): Promise<void> {
    syncing = true;
    try {
      await api.authSync();
      libraryChanged.update((n) => n + 1);
    } catch (e) {
      console.error("sync:", e);
    } finally {
      syncing = false;
    }
  }

  async function logout(): Promise<void> {
    await api.authLogout();
    await auth.logout();
  }

  // ── Profile switching ────────────────────────────────────────────────────────
  let switchingProfileID = $state<string | null>(null);
  let switchError = $state<string | null>(null);

  async function switchProfile(id: string): Promise<void> {
    if (auth.activeProfile?.id === id || switchingProfileID) return;
    switchingProfileID = id;
    switchError = null;
    try {
      await api.profileActivate(id);
      window.location.reload();
    } catch (e) {
      switchError = e instanceof Error ? e.message : String(e);
      switchingProfileID = null;
    }
  }

  async function refreshProfiles(): Promise<void> {
    const res = await api.profilesList();
    auth.setProfiles(
      res.profiles,
      res.profiles.find((p) => p.id === res.active_profile_id) ??
        res.profiles[0],
    );
  }

  // ── Rename flow state ────────────────────────────────────────────────────────
  let renamingProfileID = $state<string | null>(null);
  let renameName = $state("");
  let renaming = $state(false);
  let renameError = $state<string | null>(null);

  function startRename(id: string, name: string): void {
    renamingProfileID = id;
    renameName = name;
    renameError = null;
    switchError = null;
  }

  function cancelRename(): void {
    renamingProfileID = null;
    renameName = "";
    renameError = null;
  }

  async function saveRename(): Promise<void> {
    if (!renamingProfileID || renaming) return;
    const name = renameName.trim();
    if (!name) {
      renameError = "Profile name is required.";
      return;
    }

    const current = auth.profiles.find((p) => p.id === renamingProfileID);
    if (current?.name === name) {
      cancelRename();
      return;
    }

    renaming = true;
    renameError = null;
    try {
      await api.profileRename(renamingProfileID, name);
      await refreshProfiles();
      cancelRename();
    } catch (e) {
      renameError = e instanceof Error ? e.message : String(e);
    } finally {
      renaming = false;
    }
  }

  // ── Delete flow state ────────────────────────────────────────────────────────
  let deleteTarget = $state<{ id: string; name: string } | null>(null);
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);

  async function confirmDelete(): Promise<void> {
    if (!deleteTarget) return;
    deleting = true;
    deleteError = null;
    try {
      await api.profileDelete(deleteTarget.id);
      await refreshProfiles();
      libraryChanged.update((n) => n + 1);
      deleteTarget = null;
    } catch (e) {
      deleteError = e instanceof Error ? e.message : String(e);
    } finally {
      deleting = false;
    }
  }
</script>

<ScrollArea class="h-full">
  <div class="mx-auto mt-24 flex w-full max-w-4xl flex-col gap-6 p-6">
    <!-- Page header -->
    <header class="flex flex-col gap-1">
      <h1 class="text-2xl font-bold">My Account</h1>
      <p class="text-muted-foreground text-sm">
        Manage your account, profiles, and viewing insights.
      </p>
    </header>

    <!-- Account card -->
    <Card.Root>
      <Card.Header>
        <Card.Title>Account</Card.Title>
      </Card.Header>
      <Card.Content class="flex flex-col gap-3">
        {#if auth.session}
          <p class="text-muted-foreground truncate text-sm">
            {auth.session.email}
          </p>
          <div class="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              class="gap-1 text-xs"
              onclick={sync}
              disabled={syncing}
            >
              {#if syncing}<Spinner class="size-3" />{:else}<RefreshCw
                  class="size-3"
                />{/if}
              Sync now
            </Button>
            <Button
              variant="ghost"
              size="sm"
              class="text-muted-foreground gap-1 text-xs"
              onclick={logout}
            >
              <LogOut class="size-3" /> Sign out
            </Button>
          </div>
        {:else}
          <p class="text-muted-foreground text-sm">
            You're browsing as a guest. Sign in to sync your library across
            devices.
          </p>
          <Button
            variant="default"
            size="sm"
            class="w-fit gap-1 text-xs"
            onclick={() => (authOpen = true)}
          >
            <LogIn class="size-3" /> Sign in / Create account
          </Button>
        {/if}
      </Card.Content>
    </Card.Root>

    <!-- Profiles card -->
    <Card.Root>
      <Card.Header>
        <Card.Title>Profiles</Card.Title>
      </Card.Header>
      <Card.Content
        class="flex flex-col gap-1"
        role="list"
        aria-label="Profiles"
      >
        {#each auth.profiles as profile (profile.id)}
          <div
            role="listitem"
            aria-label={`Profile ${profile.name}`}
            class="flex items-center justify-between gap-3 rounded-md px-2 py-2"
          >
            <div class="flex min-w-0 flex-1 items-center gap-3">
              <span
                class="bg-secondary flex size-8 shrink-0 items-center justify-center rounded-full text-sm font-semibold"
              >
                {profile.name.charAt(0).toUpperCase()}
              </span>
              {#if renamingProfileID === profile.id}
                <div class="flex min-w-0 flex-1 flex-col gap-1">
                  <Input
                    type="text"
                    class="h-8"
                    aria-label={`Profile name for ${profile.name}`}
                    bind:value={renameName}
                    disabled={renaming}
                    onkeydown={(event) => {
                      if (event.key === "Enter") saveRename();
                      if (event.key === "Escape") cancelRename();
                    }}
                    autofocus
                  />
                  {#if renameError}
                    <span class="text-destructive text-xs">{renameError}</span>
                  {/if}
                </div>
              {:else}
                <span class="flex min-w-0 flex-col">
                  <span class="truncate text-sm font-medium"
                    >{profile.name}</span
                  >
                  <span
                    class="text-muted-foreground flex items-center gap-1.5 text-xs"
                  >
                    {#if auth.activeProfile?.id === profile.id}
                      <Check class="text-accent size-3" />
                      <span class="text-accent">Active</span>
                    {/if}
                    {#if profile.is_primary}
                      <span
                        class="border-border text-muted-foreground rounded border px-1 py-0.5 text-[10px]"
                        >Primary</span
                      >
                    {/if}
                  </span>
                </span>
              {/if}
            </div>
            <div class="flex shrink-0 items-center gap-1">
              {#if renamingProfileID === profile.id}
                <Button
                  size="xs"
                  onclick={saveRename}
                  disabled={renaming || !renameName.trim()}
                >
                  {#if renaming}<Spinner class="size-3" />{/if}
                  Save
                </Button>
                <Button
                  variant="ghost"
                  size="xs"
                  onclick={cancelRename}
                  disabled={renaming}
                >
                  Cancel
                </Button>
              {:else}
                {#if auth.activeProfile?.id !== profile.id}
                  <Button
                    variant="outline"
                    size="xs"
                    aria-label={`Switch to ${profile.name}`}
                    onclick={() => switchProfile(profile.id)}
                    disabled={switchingProfileID !== null}
                  >
                    {#if switchingProfileID === profile.id}
                      <Spinner class="size-3" />
                    {/if}
                    Switch
                  </Button>
                {/if}
                <Button
                  variant="ghost"
                  size="icon-xs"
                  class="text-muted-foreground"
                  aria-label={`Rename ${profile.name}`}
                  title={`Rename ${profile.name}`}
                  onclick={() => startRename(profile.id, profile.name)}
                >
                  <Pencil class="size-3.5" />
                </Button>
                {#if !profile.is_primary}
                  <Button
                    variant="ghost"
                    size="icon-xs"
                    class="text-muted-foreground hover:text-destructive"
                    aria-label={`Delete ${profile.name}`}
                    title={`Delete ${profile.name}`}
                    onclick={() => {
                      deleteTarget = { id: profile.id, name: profile.name };
                      deleteError = null;
                    }}
                  >
                    <Trash2 class="size-3.5" />
                  </Button>
                {/if}
              {/if}
            </div>
          </div>
        {/each}
        {#if switchError}
          <p class="text-destructive mt-1 text-xs">{switchError}</p>
        {/if}
        {#if deleteError}
          <p class="text-destructive mt-1 text-xs">{deleteError}</p>
        {/if}
      </Card.Content>
    </Card.Root>
  </div>

  <InsightsPage {visible} {onSelectPerson} />
</ScrollArea>

{#if deleteTarget}
  <ConfirmDialog
    title="Delete profile?"
    body={`"${deleteTarget.name}" and all of its watch history, library and settings will be permanently deleted. This cannot be undone.`}
    confirmLabel="Delete"
    loading={deleting}
    onconfirm={confirmDelete}
    oncancel={() => {
      deleteTarget = null;
      deleteError = null;
    }}
  />
{/if}

{#if authOpen}
  <AuthDialog onclose={() => (authOpen = false)} />
{/if}
