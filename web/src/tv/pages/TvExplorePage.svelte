<script lang="ts">
  import { untrack } from "svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { activeLocale } from "$lib/i18n";
  import type { Media, MediaImages } from "$lib/types/tmdb";
  import { api } from "$lib/api";
  import { Skeleton } from "$lib/components/ui/skeleton/index.js";
  import TvMediaRow from "../components/TvMediaRow.svelte";
  import { getImageOpt } from "$lib/utils";
  import { Play, Info } from "lucide-svelte";
  import type { LibraryEntry } from "$lib/types/library";
  import { focusGroup, focusable } from "../focus/actions";
  import { getContext } from "svelte";

  // watchMedia and openMediaDetail contexts set by TvApp.
  const watchMedia = getContext<
    ((m: Media, season?: number, episode?: number) => void) | undefined
  >("watchMedia");
  const openDetail = getContext<((m: Media) => void) | undefined>("openMediaDetail");

  type Row = { key: string; header: string; medias: Media[]; loading: boolean };

  let mediaType = $state<"movie" | "tv">("movie");
  let genres = $state<{ id: number; name: string }[]>([]);
  let selectedGenreId = $state<number | null>(null);

  // Featured row: top panel shows popular or selected-genre medias.
  let featuredRow = $state<Row>({
    key: "featured",
    header: m.home_popular_movies(),
    medias: [],
    loading: false,
  });

  // Genre rows below the featured card.
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
  // selectedGenreId reset here is intentional — type switch shows all genres.
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
    watchMedia?.(
      featuredMedia,
      entry?.last_watched_season ?? undefined,
      entry?.last_watched_episode ?? undefined,
    );
  }
</script>

<div class="flex h-full flex-col overflow-hidden gap-2">
  <!--
    Control bar: type toggle + genre filter.
    On TV, Select.Root dropdowns are replaced by horizontally-scrollable
    focusable button rows — simpler and more remote-friendly.
  -->
  <div class="shrink-0 bg-card rounded-2xl px-4 py-2">
    <h1 class="ml-4 text-2xl font-bold">{m.explore_title()}</h1>

    <!--
      Single filter row (one D-pad vertical stop):
      [Movies] [TV Shows]  ·  [All] [genre pills…]
      Type toggle and genre filter merged so Up/Down crosses them as one group.
    -->
    <div
      use:focusGroup={{ id: "explore-filters", policy: { type: "row" }, rememberFocus: true }}
      class="flex gap-2 overflow-x-hidden p-4"
    >
      <!-- Type toggle -->
      {#each [["movie", m.explore_movies()], ["tv", m.explore_tv()]] as [val, label] (val)}
        <button
          type="button"
          use:focusable={{ groupId: "explore-filters" }}
          onclick={() => (mediaType = val as "movie" | "tv")}
          class="shrink-0 rounded-xl px-6 py-2.5 text-base font-medium transition-colors {mediaType ===
          val
            ? 'bg-foreground text-background'
            : 'bg-secondary text-muted-foreground'}"
        >{label}</button>
      {/each}

      <!-- Separator -->
      {#if genres.length > 0}
        <div class="h-8 w-px shrink-0 self-center bg-white/20"></div>

        <!-- Genre pills: All + per-genre -->
        <button
          type="button"
          use:focusable={{ groupId: "explore-filters" }}
          onclick={() => (selectedGenreId = null)}
          class="shrink-0 rounded-xl px-5 py-2 text-sm font-medium transition-colors {selectedGenreId ===
          null
            ? 'bg-foreground text-background'
            : 'bg-secondary text-muted-foreground'}"
        >{m.my_list_all()}</button>
        {#each genres as genre (genre.id)}
          <button
            type="button"
            use:focusable={{ groupId: "explore-filters" }}
            onclick={() => (selectedGenreId = genre.id)}
            class="shrink-0 rounded-xl px-5 py-2 text-sm font-medium transition-colors {selectedGenreId ===
            genre.id
              ? 'bg-foreground text-background'
              : 'bg-secondary text-muted-foreground'}"
          >{genre.name}</button>
        {/each}
      {/if}
    </div>
  </div>

  <!-- Scrollable body -->
  <div
    class="min-h-0 flex-1 overflow-y-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
  >
    <div class="flex flex-col gap-8 pb-12">
      <!-- Featured card: full-width 16:9 backdrop + logo + Watch/Details -->
      <div class="relative w-full overflow-hidden rounded-2xl" style="aspect-ratio: 16/9;">
        {#if featuredRow.loading}
          <Skeleton class="absolute inset-0" />
        {:else if featuredBackdrop}
          <img
            src={featuredBackdrop}
            alt={featuredTitle}
            class="absolute inset-0 h-full w-full object-cover"
          />
        {:else}
          <div class="absolute inset-0 bg-muted"></div>
        {/if}

        <!-- Bottom gradient -->
        <div
          class="absolute inset-0 bg-linear-to-t from-black/85 via-black/20 to-transparent"
        ></div>

        <!-- Logo / title + action row -->
        <div class="absolute inset-x-0 bottom-0 flex flex-col gap-4 p-6">
          {#if featuredLogo}
            <img
              src={featuredLogo}
              alt={featuredTitle}
              class="h-14 max-w-[45%] object-contain object-left drop-shadow"
            />
          {:else if featuredTitle}
            <span class="line-clamp-2 text-xl font-bold text-white drop-shadow">
              {featuredTitle}
            </span>
          {/if}

          {#if !featuredRow.loading && featuredMedia}
            <!--
              Buttons in a row focusGroup so D-pad Left/Right navigates between
              Watch and Details without leaving the featured card area.
            -->
            <div
              use:focusGroup={{ id: "explore-featured", policy: { type: "row" } }}
              class="flex gap-3"
            >
              <button
                type="button"
                use:focusable={{ groupId: "explore-featured" }}
                class="flex h-12 items-center justify-center gap-2 rounded-xl bg-white px-6 text-base font-semibold text-black"
                onclick={watchFeatured}
              >
                <Play class="size-5 fill-current" />
                {m.common_watch()}
              </button>
              <button
                type="button"
                use:focusable={{ groupId: "explore-featured" }}
                class="flex h-12 items-center justify-center gap-2 rounded-xl bg-white/20 px-6 text-base font-semibold text-white backdrop-blur-sm"
                onclick={() => featuredMedia && openDetail?.(featuredMedia)}
              >
                <Info class="size-5" />
                {m.media_details()}
              </button>
            </div>
          {/if}
        </div>
      </div>

      <!-- Genre rows; skip the currently-featured genre -->
      {#each genreRows.filter((r) => r.key !== `genre-${selectedGenreId}`) as row (row.key)}
        <TvMediaRow
          id="explore-{row.key}"
          header={row.header}
          medias={row.medias}
          loading={row.loading}
        />
      {/each}
    </div>
  </div>
</div>
