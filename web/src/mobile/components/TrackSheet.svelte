<script lang="ts">
  import { Check } from "lucide-svelte";
  import { fly, fade } from "svelte/transition";

  let {
    title,
    items,
    selectedId = null,
    onSelect,
    onClose,
  }: {
    title: string;
    items: { id: string | number; label: string; sublabel?: string }[];
    selectedId?: string | number | null;
    onSelect: (id: string | number) => void;
    onClose: () => void;
  } = $props();
</script>

<!-- Backdrop -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
  class="fixed inset-0 z-[60] bg-black/60"
  transition:fade={{ duration: 180 }}
  onclick={onClose}
  onkeydown={() => {}}
></div>

<!-- Sheet panel -->
<div
  class="fixed inset-x-0 bottom-0 z-[61] flex flex-col rounded-t-2xl bg-neutral-900 text-white shadow-xl"
  style="padding-bottom: var(--safe-bottom);"
  transition:fly={{ y: 400, duration: 280, opacity: 1 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
  role="dialog"
  tabindex={-1}
  aria-label={title}
>
  <!-- Drag handle pill -->
  <div class="flex justify-center pt-3 pb-1 shrink-0">
    <div class="h-1 w-10 rounded-full bg-white/25"></div>
  </div>

  <!-- Sheet title -->
  <p class="px-5 pb-1 pt-3 text-base font-semibold shrink-0">{title}</p>

  <!-- Item list -->
  <div class="overflow-y-auto pb-3" style="max-height: 65vh;">
    {#each items as item (item.id)}
      <button
        type="button"
        class="flex min-h-[52px] w-full items-center gap-3 px-5 py-3 text-left transition-colors active:bg-white/10"
        onclick={() => {
          onSelect(item.id);
          onClose();
        }}
      >
        <span class="flex-1 min-w-0">
          <span class="block text-sm font-medium leading-snug">{item.label}</span>
          {#if item.sublabel}
            <span class="block text-xs text-white/50 leading-snug">{item.sublabel}</span>
          {/if}
        </span>
        {#if selectedId === item.id}
          <Check class="size-4 shrink-0 text-white" />
        {/if}
      </button>
    {/each}
  </div>
</div>
