<script lang="ts">
  import type { StudioEntry } from "$lib/api";
  import * as Card from "$lib/components/ui/card/index.js";
  import { Building2 } from "lucide-svelte";

  let { studios }: { studios: StudioEntry[] } = $props();

  const maxCount = $derived(studios.reduce((m, s) => Math.max(m, s.count), 1));
</script>

<Card.Root>
  <Card.Header>
    <Card.Title class="flex items-center gap-2 text-sm">
      <Building2 class="size-4" />
      Studio footprint
    </Card.Title>
    <Card.Description>Studios you watch most</Card.Description>
  </Card.Header>
  <Card.Content class="flex flex-col gap-2">
    {#each studios as studio (studio.id)}
      {@const pct = Math.round((studio.count / maxCount) * 100)}
      <div class="flex items-center gap-3">
        <span class="w-32 shrink-0 truncate text-xs text-muted-foreground">
          {studio.name}
        </span>
        <div class="relative flex-1 overflow-hidden rounded-full">
          <!-- Track -->
          <div class="h-2 w-full rounded-full bg-muted/40"></div>
          <!-- Fill -->
          <div
            class="absolute inset-y-0 left-0 rounded-full bg-indigo-500/60"
            style="width: {pct}%"
          ></div>
        </div>
        <span
          class="w-6 shrink-0 text-right text-xs tabular-nums text-muted-foreground"
        >
          {studio.count}
        </span>
      </div>
    {/each}
  </Card.Content>
</Card.Root>
