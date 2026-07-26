<script lang="ts">
  import {untrack} from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import type {Media} from "$lib/types/tmdb";
  import {api} from "$lib/api";
  import {ScrollArea} from "$lib/components/ui/scroll-area/index.js";
  import {Button} from "$lib/components/ui/button";
  import * as Select from "$lib/components/ui/select/index.js";
  import ExploreCarousel from "./ExploreCarousel.svelte";
  import SmallRecommendations from "./SmallRecommendations.svelte";
  import * as ButtonGroup from "$lib/components/ui/button-group/index.js";

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

  // The single ExploreCarousel at the top (popular or selected genre).
  // Starts idle (loading: false); the $effect sets loading: true right before
  // the fetch begins so skeletons only appear once a request is actually in flight.
  let featuredRow = $state<Row>({
    key: "featured",
    header: m.home_popular_movies(),
    medias: [],
    loading: false,
  });

  // SmallRecommendations rows — one per genre, always present regardless of chip selection.
  let genreRows = $state<Row[]>([]);

  // ── Featured row: reload when mediaType or selectedGenreId changes ──────
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

  // ── Genre rows: reload only when mediaType changes ───────────────────────
  // selectedGenreId only affects the featured carousel, not these rows.
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
</script>

<div class="flex h-full flex-col overflow-hidden pt-18">
  <!-- Header + type tabs + genre chips (outside ScrollArea for horizontal scroll) -->
  <div class="flex shrink-0 flex-col gap-3 px-6 pb-4">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold tracking-tight">{m.explore_title()}</h1>
      <div class="flex gap-4">
        <Select.Root
                type="single"
                value={selectedGenreId !== null ? String(selectedGenreId) : ""}
                onValueChange={(v) => (selectedGenreId = v ? Number(v) : null)}
        >
          <Select.Trigger class="w-48">
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
        <ButtonGroup.Root>
          <Button
                  variant={mediaType === "movie" ? "secondary" : "ghost"}
                  size="default"
                  onclick={() => (mediaType = "movie")}
          >
            {m.explore_movies()}
          </Button>
          <Button
                  variant={mediaType === "tv" ? "secondary" : "ghost"}
                  size="default"
                  onclick={() => (mediaType = "tv")}
          >
            {m.explore_tv()}
          </Button>
        </ButtonGroup.Root>

      </div>
    </div>
  </div>

  <!-- Scrollable content -->
  <ScrollArea class="min-h-0 flex-1">
    <div class="flex flex-col gap-6 pb-16">
      <!-- Single ExploreCarousel: popular or the selected genre -->
      <ExploreCarousel
        header={featuredRow.header}
        medias={featuredRow.medias}
        loading={featuredRow.loading}
        onSelect={onSelectMedia}
      />

      <!-- Genre rows via SmallRecommendations; skip the one currently featured above -->
      {#each genreRows.filter((r) => r.key !== `genre-${selectedGenreId}`) as row (row.key)}
        <SmallRecommendations
          header={row.header}
          medias={row.medias}
          loading={row.loading}
          onSelect={onSelectMedia}
          onWatch={onWatch}
        />
      {/each}
    </div>
  </ScrollArea>
</div>
