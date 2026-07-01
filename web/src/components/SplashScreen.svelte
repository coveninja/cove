<script lang="ts">
  import { onMount } from "svelte";
  import { fade } from "svelte/transition";
  import { animate } from "animejs";
  import CoveIcon from "../assets/CoveIcon.svelte";

  let iconEl = $state<HTMLDivElement | null>(null);

  onMount(() => {
    if (!iconEl) return;
    animate(iconEl, {
      opacity: [0.35, 1],
      duration: 650,
      easing: "easeInOutQuad",
      loop: true,
      alternate: true,
    });
  });
</script>

<div
  transition:fade={{ duration: 300 }}
  class="fixed inset-0 z-[100] flex items-center justify-center bg-background"
>
  <div bind:this={iconEl}>
    <CoveIcon size={96} />
  </div>
</div>
