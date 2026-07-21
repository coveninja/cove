<script lang="ts">
  import { fade } from "svelte/transition";

  let {
    seekFlash,
  }: {
    seekFlash: "left" | "right";
  } = $props();
</script>

<div
  class="pointer-events-none absolute inset-y-0 z-30 flex items-center justify-center {seekFlash === 'left' ? 'left-0 w-1/3' : 'right-0 w-1/3'}"
  transition:fade={{ duration: 120 }}
>
  <div class="rounded-full bg-white/20 px-5 py-3 text-xl font-semibold text-white backdrop-blur-sm">
    {seekFlash === "left" ? "−10s" : "+10s"}
  </div>
</div>
