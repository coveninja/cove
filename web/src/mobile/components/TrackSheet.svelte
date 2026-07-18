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
  }: {
    title: string;
    items: { id: string | number; label: string; sublabel?: string }[];
    selectedId?: string | number | null;
    onSelect: (id: string | number) => void;
    onClose: () => void;
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
  let listEl = $state<HTMLElement | null>(null);

  onMount(() => {
    const el = panelEl;
    if (!el) return;

    let touchStartY = 0;
    let lastY = 0;
    let lastT = 0;
    let velocity = 0;
    let isDragging = false;

    const onTouchStart = (e: TouchEvent) => {
      // Gate on scroll position: if the list is scrolled down, don't hijack.
      if (listEl && listEl.scrollTop > 2) return;
      touchStartY = e.touches[0].clientY;
      lastY = touchStartY;
      lastT = Date.now();
      velocity = 0;
      isDragging = false;
    };

    const onTouchMove = (e: TouchEvent) => {
      if (!touchStartY) return;
      const y = e.touches[0].clientY;
      const dy = y - touchStartY;
      if (dy < 0) {
        // Upward swipe — cancel drag, let native scroll handle it.
        touchStartY = 0;
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
        touchStartY = 0;
        return;
      }
      // Parse the current drag offset from the inline transform.
      const match = el.style.transform.match(/translateY\(([0-9.]+)px\)/);
      const offset = match ? parseFloat(match[1]) : 0;
      const vel = velocity;
      isDragging = false;
      touchStartY = 0;
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

    el.addEventListener("touchstart", onTouchStart, { passive: true });
    el.addEventListener("touchmove", onTouchMove, { passive: false });
    el.addEventListener("touchend", onTouchEnd, { passive: true });

    return () => {
      el.removeEventListener("touchstart", onTouchStart);
      el.removeEventListener("touchmove", onTouchMove);
      el.removeEventListener("touchend", onTouchEnd);
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
  style="padding-bottom: var(--safe-bottom);"
  in:slideUp={{ duration: 320 }}
  out:fly={{ y: 400, duration: 200, opacity: 1 }}
  onclick={(e) => e.stopPropagation()}
  onkeydown={() => {}}
  role="dialog"
  tabindex={-1}
  aria-label={title}
>
  <!-- Drag handle pill -->
  <div class="flex shrink-0 justify-center pb-1 pt-3">
    <div class="h-1 w-10 rounded-full bg-white/25"></div>
  </div>

  <!-- Sheet title -->
  <p class="shrink-0 px-5 pb-1 pt-3 text-base font-semibold">{title}</p>

  <!-- Item list -->
  <div bind:this={listEl} class="overflow-y-auto pb-3" style="max-height: 65vh;">
    {#each items as item (item.id)}
      <button
        type="button"
        class="flex min-h-[52px] w-full items-center gap-3 px-5 py-3 text-left transition-colors active:bg-white/10"
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
    {/each}
  </div>
</div>
