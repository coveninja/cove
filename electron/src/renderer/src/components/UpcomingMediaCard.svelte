<script lang="ts">
  import { Tv } from "lucide-svelte";
  import type { Media } from "$lib/types/tmdb";
  import type { UpcomingItem } from "$lib/types/types";
  import { SvelteDate } from "svelte/reactivity";
  import { Badge } from "$lib/components/ui/badge/index.js";
  let { item, onSelectMedia } = $props();

  function isToday(dateStr: string): boolean {
    const date = new Date(dateStr + "T00:00:00");
    const today = new Date();
    return date.toDateString() === today.toDateString();
  }

  function toMedia(item: UpcomingItem): Media {
    return {
      id: item.tmdbId,
      media_type: "tv",
      name: item.title,
      poster_path: item.posterPath,
      overview: "",
      vote_average: 0,
    } as unknown as Media;
  }

  function formatAirDate(dateStr: string): string {
    const date = new SvelteDate(dateStr + "T00:00:00");
    const today = new SvelteDate();
    today.setHours(0, 0, 0, 0);
    const diffDays = Math.round(
      (date.getTime() - today.getTime()) / 86_400_000,
    );
    if (diffDays === 0) return "Today";
    if (diffDays === 1) return "Tomorrow";
    if (diffDays < 0)
      return date.toLocaleDateString(undefined, {
        month: "short",
        day: "numeric",
      });
    if (diffDays <= 6)
      return date.toLocaleDateString(undefined, { weekday: "long" });
    return date.toLocaleDateString(undefined, {
      month: "short",
      day: "numeric",
    });
  }

  let media = $derived(toMedia(item));

  // Prefer the episode still (what's actually coming) over the show's
  const imageSrc = $derived(item.stillPath || item.posterPath);
</script>

<button
  onclick={() => onSelectMedia(media)}
  class="group flex w-70 shrink-0 flex-col gap-2 rounded-2xl text-left"
  style="scroll-snap-align: start;"
>
  <span class="relative block overflow-hidden rounded-md">
    {#if imageSrc}
      <img
        src={imageSrc}
        alt={item.title}
        class="aspect-video w-full object-cover transition-transform duration-200 group-hover:scale-105"
      />
    {:else}
      <div
        class="flex aspect-video w-full items-center justify-center bg-secondary"
      >
        <Tv class="size-8 text-muted-foreground/40" />
      </div>
    {/if}
    <span class="absolute inset-x-0 top-0 block px-2 pt-1.5">
      <Badge
        variant="secondary"
        class="block bg-card/80 text-[11px] font-semibold tracking-wide {isToday(
          item.airDate,
        )
          ? 'text-accent'
          : 'text-white'}"
      >
        {formatAirDate(item.airDate)}
      </Badge>
    </span>
    <span
      class="absolute inset-x-0 bottom-0 block px-2 pt-24 pb-1.5"
      style="background: linear-gradient(to top, rgba(0,0,0,0.85) 0%, transparent 100%)"
    >
      <span class="block">
        <span
          class="block truncate text-sm leading-tight font-semibold text-white"
        >
          {item.title}
        </span>
        <span class="block truncate text-xs text-muted-foreground">
          S{item.season}E{item.episode}{item.episodeName
            ? ` · ${item.episodeName}`
            : ""}
        </span>
      </span>
    </span>
  </span>
</button>
