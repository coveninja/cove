<script lang="ts">
  import type { UpdateCheckResult } from "$lib/api";
  import { api } from "$lib/api";
  import { Download, RotateCcw, X } from "lucide-svelte";

  let {
    info,
    ondismiss,
  }: { info: UpdateCheckResult; ondismiss: () => void } = $props();

  type Phase = "idle" | "downloading" | "restarting" | "error";
  let phase: Phase = $state("idle");
  let errorMsg: string = $state("");

  async function update() {
    phase = "downloading";
    errorMsg = "";
    try {
      await api.applyUpdate();
      // Backend exited with code 42 after flushing the 200 response.
      // The Qt shell will restart the process momentarily.
      phase = "restarting";
    } catch (e) {
      // A network error here almost certainly means the process exited normally
      // mid-response flush (the 250 ms window between extraction and os.Exit).
      // Treat it as a successful restart rather than a failure.
      if (e instanceof TypeError && /fetch|network/i.test(String(e))) {
        phase = "restarting";
      } else {
        errorMsg = e instanceof Error ? e.message : String(e);
        phase = "error";
      }
    }
  }
</script>

<!-- Fixed bottom-right notification card -->
<div
  class="fixed bottom-6 right-6 z-50 w-80 rounded-xl border border-white/10 bg-background/95 p-5 shadow-2xl backdrop-blur-sm"
  role="dialog"
  aria-label="Update available"
>
  {#if phase === "idle"}
    <!-- Header -->
    <div class="mb-3 flex items-start justify-between gap-2">
      <div class="flex items-center gap-2">
        <Download class="h-4 w-4 shrink-0 text-primary" />
        <span class="text-sm font-semibold">Update Available</span>
      </div>
      <button
        class="text-muted-foreground hover:text-foreground transition-colors"
        onclick={ondismiss}
        aria-label="Dismiss"
      >
        <X class="h-4 w-4" />
      </button>
    </div>

    <!-- Version info -->
    <p class="mb-1 text-xs text-muted-foreground">
      {info.current_version} → <span class="font-medium text-foreground">{info.latest_version}</span>
    </p>
    {#if info.release_name}
      <p class="mb-4 text-xs text-muted-foreground">{info.release_name}</p>
    {:else}
      <div class="mb-4"></div>
    {/if}

    <!-- Actions -->
    <div class="flex gap-2">
      <button
        class="flex-1 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground transition-opacity hover:opacity-90"
        onclick={update}
      >
        Update Now
      </button>
      <button
        class="rounded-lg border border-white/10 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
        onclick={ondismiss}
      >
        Later
      </button>
    </div>

  {:else if phase === "downloading"}
    <div class="flex items-center gap-3">
      <!-- Simple CSS spinner -->
      <div
        class="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-primary/30 border-t-primary"
      ></div>
      <div>
        <p class="text-sm font-medium">Downloading update…</p>
        <p class="text-xs text-muted-foreground">This may take a moment</p>
      </div>
    </div>

  {:else if phase === "restarting"}
    <div class="flex items-center gap-3">
      <RotateCcw class="h-4 w-4 shrink-0 animate-spin text-primary" />
      <div>
        <p class="text-sm font-medium">Restarting…</p>
        <p class="text-xs text-muted-foreground">The app will restart momentarily</p>
      </div>
    </div>

  {:else if phase === "error"}
    <div class="mb-3 flex items-start justify-between gap-2">
      <span class="text-sm font-semibold text-destructive">Update failed</span>
      <button
        class="text-muted-foreground hover:text-foreground transition-colors"
        onclick={ondismiss}
        aria-label="Dismiss"
      >
        <X class="h-4 w-4" />
      </button>
    </div>
    <p class="mb-3 text-xs text-muted-foreground">{errorMsg}</p>
    <button
      class="w-full rounded-lg border border-white/10 px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
      onclick={() => { phase = "idle"; }}
    >
      Try again
    </button>
  {/if}
</div>
