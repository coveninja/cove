<script lang="ts">
  import { Check } from "lucide-svelte";

  let {
    label,
    active,
    onSelect,
  }: { label: string; active: boolean; onSelect: () => void } = $props();
</script>

<button
  type="button"
  onclick={onSelect}
  class="pl-4 flex w-full items-center gap-2 rounded-2xl px-2 py-1.5 text-left text-sm outline-none transition-colors hover:bg-accent hover:text-accent-foreground focus-visible:bg-accent focus-visible:text-accent-foreground {active
    ? 'font-medium'
    : ''}"
>
  <span class="flex-1 truncate">{label}</span>
  {#if active}<Check class="size-4 shrink-0" />{/if}
</button>
