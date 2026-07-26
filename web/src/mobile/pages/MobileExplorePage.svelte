<script lang="ts">
  import { untrack } from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { activeLocale } from "$lib/i18n";
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import * as Select from "$lib/components/ui/select/index.js";
  import { Skeleton } from "$lib/components/ui/skeleton/index.js";
  import MobileMediaRow from "../components/MobileMediaRow.svelte";
  import { getImageOpt } from "$lib/utils";
  import { Play, Info } from "lucide-svelte";
  import type { LibraryEntry } from "$lib/types/library";
  import { fade } from "svelte/transition";
  import { imageFade } from "../lib/imageFade";

  let {
    onSelectMedia,
    onWatch,
  }: {
    onSelectMedia: (m: Media) => void;
    onWatch?: (m: Media, season?: number, episode?: number) => void;
  } = $props();

  type Row = { key: string; header: string; medias: Media[]; loading: boolean };

  let mediaType = $state<"movie" | "tv">("movie");
  let genres = $state<{ id: number; name: string }[]>([]);
  let selectedGenreId = $state<number | null>(null);

  // Featured row: the top panel shows popular or selected-genre medias.
  let featuredRow = $state<Row>({
    key: "featured",
    header: m.home_popular_movies(),
    medias: [],
    loading: false,
  });

  // Genre rows below the featured panel.
  let genreRows = $state<Row[]>([]);

  // Art + library state for the first featured media.
  let featuredBackdrop = $state("");
  let featuredLogo = $state("");
  let featuredLibraryEntry = $state<LibraryEntry | null>(null);

  const featuredMedia = $derived(featuredRow.medias[0] ?? null);
  const featuredTitle = $derived(featuredMedia?.title ?? featuredMedia?.name ?? "");

  // Reload art whenever the featured media changes.
  $effect(() => {
    const media = featuredMedia;
    if (!media) {
      featuredBackdrop = "";
      featuredLogo = "";
      featuredLibraryEntry = null;
      return;
    }
    const id = media.id;
    featuredBackdrop = "";
    featuredLogo = "";
    featuredLibraryEntry = null;

    api.getImages(media).then((d: MediaImages) => {
      if (featuredMedia?.id !== id) return;
      featuredBackdrop = getImageOpt(d, "backdrops", { iso: "" });
      featuredLogo = getImageOpt(d, "logos", { iso: activeLocale() });
    });

    api
      .libraryGet(media.id, media.media_type)
      .then((d) => {
        if (featuredMedia?.id !== id) return;
        featuredLibraryEntry = d?.entry ?? null;
      })
      .catch(() => {
        featuredLibraryEntry = null;
      });
  });

  // Featured row: reload when mediaType or selectedGenreId changes.
  $effect(() => {
    const type = mediaType;
    const genreId = selectedGenreId;
    const genreList = untrack(() => genres);

    const genre = genreId !== null ? genreList.find((g) => g.id === genreId) : null;
    const header = genre
      ? type === "movie"
        ? m.explore_genre_movies({ genre: genre.name })
        : m.explore_genre_shows({ genre: genre.name })
      : type === "movie"
        ? m.home_popular_movies()
        : m.explore_popular_shows();

    untrack(() => {
      featuredRow = { key: "featured", header, medias: [], loading: true };
    });

    const load =
      genreId !== null
        ? () => api.discoverByGenre(type, genreId, { limit: 20 })
        : () => api.discover(type, { limit: 20 });

    load()
      .then((medias) => {
        featuredRow = { key: "featured", header, medias, loading: false };
      })
      .catch(() => {
        featuredRow = { key: "featured", header, medias: [], loading: false };
      });
  });

  // Genre rows: reload only when mediaType changes.
  // selectedGenreId reset here is intentional — type switch should show all genres.
  $effect(() => {
    const type = mediaType;
    selectedGenreId = null;
    genres = [];
    genreRows = [];

    api
      .genreList(type)
      .then((list) => {
        genres = list;

        genreRows = list.slice(0, 8).map((g) => ({
          key: `genre-${g.id}`,
          header:
            type === "movie"
              ? m.explore_genre_movies({ genre: g.name })
              : m.explore_genre_shows({ genre: g.name }),
          medias: [] as Media[],
          loading: true,
        }));

        for (const genre of list.slice(0, 8)) {
          const key = `genre-${genre.id}`;
          api
            .discoverByGenre(type, genre.id, { limit: 20 })
            .then((medias) => {
              genreRows = untrack(() => genreRows).map((r) =>
                r.key === key ? { ...r, medias, loading: false } : r,
              );
            })
            .catch(() => {
              genreRows = untrack(() => genreRows).map((r) =>
                r.key === key ? { ...r, loading: false } : r,
              );
            });
        }
      })
      .catch(() => {
        genres = [];
        genreRows = [];
      });
  });

  function watchFeatured(): void {
    if (!featuredMedia) return;
    const entry = featuredLibraryEntry;
    onWatch?.(
      featuredMedia,
      entry?.last_watched_season ?? undefined,
      entry?.last_watched_episode ?? undefined,
    );
  }
</script>

<!-- Own safe-area top padding — no pt-18 or top-18 needed -->
<div
  class="flex h-full flex-col overflow-hidden"
  style="padding-top: calc(var(--safe-top) + 0.75rem);"
>
  <!-- Compact header: title + genre dropdown + Movies/TV toggle -->
  <div class="flex shrink-0 items-center justify-between gap-2 px-4 pb-3">
    <h1 class="text-xl font-semibold tracking-tight">{m.explore_title()}</h1>

    <div class="flex items-center gap-2">
      <!-- Genre dropdown -->
      <Select.Root
        type="single"
        value={selectedGenreId !== null ? String(selectedGenreId) : ""}
        onValueChange={(v) => (selectedGenreId = v ? Number(v) : null)}
      >
        <Select.Trigger class="h-9 w-32 text-xs">
          {selectedGenreId !== null
            ? (genres.find((g) => g.id === selectedGenreId)?.name ?? m.explore_genre())
            : m.explore_all_genres()}
        </Select.Trigger>
        <Select.Content>
          <Select.Item value="">{m.explore_all_genres()}</Select.Item>
          {#each genres as genre (genre.id)}
            <Select.Item value={String(genre.id)}>{genre.name}</Select.Item>
          {/each}
        </Select.Content>
      </Select.Root>

      <!-- Movies / TV segmented toggle -->
      <div class="flex overflow-hidden rounded-lg border">
        <button
          type="button"
          class="px-3 py-2 text-xs font-medium transition-colors {mediaType === 'movie'
            ? 'bg-secondary text-foreground'
            : 'text-muted-foreground'}"
          onclick={() => (mediaType = "movie")}
        >{m.explore_movies()}</button>
        <button
          type="button"
          class="px-3 py-2 text-xs font-medium transition-colors {mediaType === 'tv'
            ? 'bg-secondary text-foreground'
            : 'text-muted-foreground'}"
          onclick={() => (mediaType = "tv")}
        >{m.explore_tv()}</button>
      </div>
    </div>
  </div>

  <!-- Scrollable body -->
  <div
    class="min-h-0 flex-1 overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="flex flex-col gap-5 pb-8">
      <!-- Featured card: full-width aspect-video, backdrop + logo + Watch/Details -->
      <div class="px-4">
        <div
          role="button"
          tabindex="0"
          class="relative w-full cursor-pointer overflow-hidden rounded-2xl"
          style="aspect-ratio: 16/9;"
          onclick={() => featuredMedia && onSelectMedia(featuredMedia)}
          onkeydown={(e) =>
            e.key === "Enter" && featuredMedia && onSelectMedia(featuredMedia)}
        >
          {#if featuredRow.loading}
            <Skeleton class="absolute inset-0" />
          {:else if featuredBackdrop}
            {#key featuredBackdrop}
              <img
                use:imageFade
                in:fade={{ duration: 300 }}
                src={featuredBackdrop}
                alt={featuredTitle}
                class="absolute inset-0 h-full w-full object-cover"
              />
            {/key}
          {:else}
            <div class="absolute inset-0 bg-muted"></div>
          {/if}

          <!-- Bottom gradient -->
          <div
            class="absolute inset-0 bg-linear-to-t from-black/85 via-black/20 to-transparent"
          ></div>

          <!-- Logo / title + action row -->
          <div class="absolute inset-x-0 bottom-0 flex flex-col gap-3 p-4">
            {#if featuredLogo}
              <img
                src={featuredLogo}
                alt={featuredTitle}
                class="h-10 max-w-[55%] object-contain object-left drop-shadow"
              />
            {:else if featuredTitle}
              <span class="line-clamp-2 text-base font-bold text-white drop-shadow">
                {featuredTitle}
              </span>
            {/if}

            {#if !featuredRow.loading && featuredMedia}
              <!-- Buttons stop propagation so the outer div's onclick doesn't double-fire -->
              <div class="flex gap-2">
                <button
                  type="button"
                  class="flex h-10 flex-1 items-center justify-center gap-1.5 rounded-lg bg-white text-sm font-semibold text-black active:scale-95 active:brightness-90 transition-[transform,filter] duration-75"
                  onclick={(e) => {
                    e.stopPropagation();
                    watchFeatured();
                  }}
                >
                  <Play class="size-4 fill-current" />
                  {m.common_watch()}
                </button>
                <button
                  type="button"
                  class="flex h-10 flex-1 items-center justify-center gap-1.5 rounded-lg bg-white/20 text-sm font-semibold text-white backdrop-blur-sm active:scale-95 active:brightness-90 transition-[transform,filter] duration-75"
                  onclick={(e) => {
                    e.stopPropagation();
                    onSelectMedia(featuredMedia!);
                  }}
                >
                  <Info class="size-4" />
                  {m.media_details()}
                </button>
              </div>
            {/if}
          </div>
        </div>
      </div>

      <!-- Genre rows; skip whichever genre is currently in the featured panel -->
      {#each genreRows.filter((r) => r.key !== `genre-${selectedGenreId}`) as row (row.key)}
        <MobileMediaRow
          header={row.header}
          medias={row.medias}
          loading={row.loading}
          onSelect={onSelectMedia}
          onWatch={onWatch}
        />
      {/each}
    </div>
  </div>
</div>
