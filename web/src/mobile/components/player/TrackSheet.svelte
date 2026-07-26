<script lang="ts">
  import { Check } from "lucide-svelte";
  import { fly, fade } from "svelte/transition";
  import { onMount } from "svelte";

  let {
    title,
    items,
    selectedId = null,
    onSelect,
    onClose,
    footer,
  }: {
    title: string;
    items: { id: string | number; label: string; sublabel?: string; destructive?: boolean; header?: boolean; indent?: boolean }[];
    selectedId?: string | number | null;
    onSelect: (id: string | number) => void;
    onClose: () => void;
    /** Optional controls rendered in a fixed section below the scrollable list. */
    footer?: import("svelte").Snippet;
  } = $props();

  // Custom springy slide-up entrance transition (outBack easing with overshoot).
  // c1 controls overshoot: 1.70158 is the canonical outBack (~10% of travel,
  // far too bouncy for a full-height slide); 0.55 settles with a subtle ~3%.
  function slideUp(_node: HTMLElement, { duration = 320 }: { duration?: number } = {}) {
    return {
      duration,
      css: (t: number) => {
        const c1 = 0.55;
        const c3 = c1 + 1;
        const eased = 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
        return `transform: translateY(${(1 - eased) * 100}%)`;
      },
    };
  }

  // ── Drag-to-dismiss ────────────────────────────────────────────────────────
  let panelEl = $state<HTMLElement | null>(null);
  let dragHandleEl = $state<HTMLElement | null>(null);

  onMount(() => {
    const el = panelEl;
    const handle = dragHandleEl;
    if (!el || !handle) return;

    let touchStartY: number | null = null;
    let lastY = 0;
    let lastT = 0;
    let velocity = 0;
    let isDragging = false;

    const onTouchStart = (e: TouchEvent) => {
      touchStartY = e.touches[0].clientY;
      lastY = touchStartY;
      lastT = Date.now();
      velocity = 0;
      isDragging = false;
    };

    const onTouchMove = (e: TouchEvent) => {
      if (touchStartY === null) return;
      const y = e.touches[0].clientY;
      const dy = y - touchStartY;
      if (dy < 0) {
        // Upward swipe on the handle is not a dismiss gesture.
        touchStartY = null;
        isDragging = false;
        return;
      }
      const now = Date.now();
      velocity = (y - lastY) / (now - lastT || 1);
      lastY = y;
      lastT = now;
      if (!isDragging && dy > 8) isDragging = true;
      if (isDragging) {
        el.style.transition = "none";
        el.style.transform = `translateY(${dy}px)`;
        e.preventDefault();
      }
    };

    const onTouchEnd = () => {
      if (!isDragging) {
        touchStartY = null;
        return;
      }
      // Parse the current drag offset from the inline transform.
      const match = el.style.transform.match(/translateY\(([0-9.]+)px\)/);
      const offset = match ? parseFloat(match[1]) : 0;
      const vel = velocity;
      isDragging = false;
      touchStartY = null;
      if (offset > 60 || vel > 0.5) {
        // Flick or far-enough drag — dismiss.
        el.style.transform = "";
        el.style.transition = "";
        onClose();
      } else {
        // Snap back with a short ease.
        el.style.transition = "transform 200ms ease";
        el.style.transform = "";
        setTimeout(() => {
          el.style.transition = "";
        }, 200);
      }
    };

    // Only the dedicated handle dismisses the sheet. Touches in the list stay
    // with native scrolling, so pulling down at the top cannot close the panel.
    handle.addEventListener("touchstart", onTouchStart, { passive: true });
    handle.addEventListener("touchmove", onTouchMove, { passive: false });
    handle.addEventListener("touchend", onTouchEnd, { passive: true });

    return () => {
      handle.removeEventListener("touchstart", onTouchStart);
      handle.removeEventListener("touchmove", onTouchMove);
      handle.removeEventListener("touchend", onTouchEnd);
    };
  });
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
  bind:this={panelEl}
  class="fixed inset-x-0 bottom-0 z-[61] flex flex-col rounded-t-2xl bg-neutral-900 text-white shadow-xl"
  style="max-height: calc(100vh - max(0.5rem, var(--safe-top))); padding-bottom: var(--safe-bottom);"
  in:slideUp={{ duration: 320 }}
  out:fly={{ y: 400, duration: 200, opacity: 1 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
  role="dialog"
  tabindex={-1}
  aria-label={title}
>
  <!-- Drag handle pill -->
  <div
    bind:this={dragHandleEl}
    class="flex shrink-0 touch-none justify-center pb-1 pt-3"
    aria-hidden="true"
  >
    <div class="h-1 w-10 rounded-full bg-white/25"></div>
  </div>

  <!-- Sheet title -->
  <p class="shrink-0 px-5 pb-1 pt-3 text-base font-semibold">{title}</p>

  <!-- Item list -->
  <div class="min-h-0 flex-1 overscroll-contain overflow-y-auto pb-3">
    {#each items as item (item.id)}
      {#if item.header}
        <p class="px-5 pt-4 pb-1 text-xs font-semibold uppercase tracking-widest text-white/40 {item.indent ? 'pl-8 pt-3 text-[10px] normal-case tracking-wide text-white/30' : ''}">
          {item.label}
        </p>
      {:else}
        <button
          type="button"
          class="flex min-h-[52px] w-full items-center gap-3 px-5 py-3 text-left transition-colors active:bg-white/10 {item.destructive ? 'text-red-400' : ''}"
          onclick={() => {
            onSelect(item.id);
            onClose();
          }}
        >
          <span class="min-w-0 flex-1">
            <span class="block text-sm font-medium leading-snug">{item.label}</span>
            {#if item.sublabel}
              <span class="block text-xs leading-snug text-white/50">{item.sublabel}</span>
            {/if}
          </span>
          {#if selectedId === item.id}
            <Check class="size-4 shrink-0 text-white" />
          {/if}
        </button>
      {/if}
    {/each}
  </div>

  {#if footer}
    <div class="shrink-0">{@render footer()}</div>
  {/if}
</div>
