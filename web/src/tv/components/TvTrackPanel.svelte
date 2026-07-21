<script lang="ts">
  import { Check } from "lucide-svelte";
  import { fly } from "svelte/transition";
  import { onMount, tick } from "svelte";
  import { focusable, focusGroup } from "../focus/actions";

  let {
    title,
    items,
    selectedId = null,
    onSelect,
    onClose,
  }: {
    title: string;
    items: { id: string | number; label: string; sublabel?: string; dot?: string; header?: boolean; indent?: boolean }[];
    selectedId?: string | number | null;
    onSelect: (id: string | number) => void;
    onClose: () => void;
  } = $props();

  let panelEl = $state<HTMLDivElement | null>(null);
  // Track the element that opened this panel so we can restore focus on close.
  let openerEl: HTMLElement | null = null;

  onMount(() => {
    openerEl = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;

    // Focus the currently-selected item (or first item) after mount.
    tick().then(() => {
      if (!panelEl) return;
      const selected = panelEl.querySelector<HTMLElement>(
        `[data-track-selected="true"]`,
      );
      if (selected) {
        selected.focus({ preventScroll: true });
      } else {
        const first = panelEl.querySelector<HTMLElement>(
          "[data-tv-focusable], button",
        );
        first?.focus({ preventScroll: true });
      }
    });

    return () => {
      // Restore focus to the button that opened the panel.
      if (openerEl?.isConnected) {
        openerEl.focus({ preventScroll: true });
      }
    };
  });

  function handleKeydown(e: KeyboardEvent): void {
    if (e.key === "Escape") {
      e.stopPropagation();
      onClose();
    }
  }
</script>

<!-- Backdrop -->
<div
  class="fixed inset-0 z-[60] bg-black/40"
  role="presentation"
  onclick={onClose}
  onkeydown={() => {}}
  transition:fly={{ x: 380, duration: 200, opacity: 1 }}
></div>

<!-- Side panel -->
<div
  bind:this={panelEl}
  class="fixed right-0 top-0 z-[61] flex h-full w-[380px] flex-col bg-neutral-900 text-white shadow-2xl"
  role="dialog"
  tabindex={-1}
  aria-label={title}
  onkeydown={handleKeydown}
  use:focusGroup={{ id: "tv-track-panel", policy: { type: "column" }, trapFocus: true }}
  transition:fly={{ x: 380, duration: 220, opacity: 1 }}
>
  <!-- Header -->
  <div class="flex shrink-0 items-center justify-between border-b border-white/10 px-6 py-5">
    <p class="text-lg font-semibold">{title}</p>
  </div>

  <!-- Item list -->
  <div class="flex-1 overflow-y-auto py-2">
    {#each items as item (item.id)}
      {#if item.header}
        <p class="px-6 pt-4 pb-1 text-xs font-semibold uppercase tracking-widest text-white/40 {item.indent ? 'pl-9 pt-3 text-[10px] normal-case tracking-wide text-white/30' : ''}">
          {item.label}
        </p>
      {:else}
        <button
          type="button"
          class="flex min-h-[56px] w-full items-center gap-3 px-6 py-3 text-left transition-colors hover:bg-white/8 focus:bg-white/10"
          data-track-selected={selectedId === item.id ? "true" : "false"}
          onclick={() => {
            onSelect(item.id);
            onClose();
          }}
          use:focusable={{ groupId: "tv-track-panel" }}
        >
          {#if item.dot}
            <span class="size-2 shrink-0 rounded-full {item.dot}"></span>
          {/if}
          <span class="flex-1 min-w-0">
            <span class="block text-sm font-medium leading-snug">{item.label}</span>
            {#if item.sublabel}
              <span class="block text-xs text-white/50 leading-snug">{item.sublabel}</span>
            {/if}
          </span>
          {#if selectedId === item.id}
            <Check class="size-5 shrink-0 text-white" />
          {/if}
        </button>
      {/if}
    {/each}
  </div>
</div>
