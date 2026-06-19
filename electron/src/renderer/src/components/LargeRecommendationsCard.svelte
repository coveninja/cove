<script lang="ts">
  import { Button } from "$lib/components/ui/button/index.js";
  import { Star } from "lucide-svelte";
  import type { Details, Media, MediaImages } from "$lib/types/tmdb";
  import { Badge } from "$lib/components/ui/badge/index.js";
  import { api } from "$lib/api";
  import {
    formatRating,
    formatRuntime,
    getImageOpt,
    getVideoOpt,
  } from "$lib/utils";
  import { animate, splitText, stagger } from "animejs";
  import { onDestroy } from "svelte";
  import { fly } from "svelte/transition";
  import LibraryStatusPanel from "./LibraryStatusPanel.svelte";
  import type { LibraryEntry } from "$lib/types/library";
  import PlayerSimple from "./PlayerSimple.svelte";

  let testMedia1: Media = {
    id: 1339713,
    title: "Obsession",
    name: "Obsession",
    overview:
      'After breaking the mysterious "One Wish Willow" to win his crush\'s heart, a hopeless romantic finds himself getting exactly what he asked for but soon discovers that some desires come at a dark, sinister price.',
    release_date: "2025-03-27",
    first_air_date: "",
    vote_average: 7.9,
    media_type: "movie",
    trailer_url: "",
    clip_urls: "",
    popularity: 324.5,
  };
  let testMedia2: Media = {
    id: 1233413,
    title: "Sinners",
    name: "Sinners",
    overview:
      "Trying to leave their troubled lives behind, twin brothers return to their hometown to start again, only to discover that an even greater evil is waiting to welcome them back.",
    release_date: "2025-03-27",
    first_air_date: "",
    vote_average: 7.4,
    media_type: "movie",
    trailer_url: "",
    clip_urls: "",
    popularity: 324.5,
  };

  let testMedia3: Media = {
    id: 936075,
    title: "Michael",
    name: "Michael",
    overview:
      "The story of Michael Jackson, one of the most influential artists the world has ever known, and his life beyond the music. His journey from the discovery of his extraordinary talent as the lead of the Jackson Five, to the visionary artist whose creative ambition fueled a relentless pursuit to become the biggest entertainer in the world, highlighting both his life off-stage and some of the most iconic performances from his early solo career.",
    release_date: "2025-03-27",
    first_air_date: "",
    vote_average: 7.4,
    media_type: "movie",
    trailer_url: "",
    clip_urls: "",
    popularity: 324.5,
  };

  let mediaIndex = $state<number>(0);
  let medias = $state<Media[]>([testMedia1, testMedia2, testMedia3]);

  let backdropUrls = $state<string[]>([]);
  let videoClips = $state<string[]>([]);
  let logoUrls = $state<string[]>([]);
  let genres = $state<string[][]>([]);
  let runtimes = $state<string[]>([]);
  let tmdbRatings = $state<string[]>([]);
  let libraryEntries = $state<LibraryEntry[]>([]);

  $effect(() => {
    backdropUrls = new Array(medias.length).fill("");
    videoClips = new Array(medias.length).fill("");
    logoUrls = new Array(medias.length).fill("");
    genres = new Array(medias.length).fill([]);
    runtimes = new Array(medias.length).fill("");
    tmdbRatings = new Array(medias.length).fill("");
    libraryEntries = new Array(medias.length).fill(null);

    for (let i = 0; i < medias.length; i++) {
      api.getImages(medias[i]).then((d: MediaImages) => {
        backdropUrls[i] = getImageOpt(d, "backdrops", {
          iso: "",
          randomize: true,
        });
        logoUrls[i] = getImageOpt(d, "logos", { iso: "en" });
      });
      api.getDetails(medias[i]).then((d: Details) => {
        genres[i] = d.genres.map((g) => g.name);
        runtimes[i] = formatRuntime(d);
        tmdbRatings[i] = formatRating(d);
      });
      api
        .getVideos(medias[i])
        .then((d) => {
          videoClips[i] = getVideoOpt(d, "Clip", {
            iso: "en",
            official: true,
            randomize: true,
          });
        })
        .catch(() => {
          videoClips[i] = null;
        });
      api
        .libraryGet(medias[i].id, medias[i].media_type)
        .then((d) => {
          libraryEntries[i] = d?.entry ? d.entry : null;
        })
        .catch(() => {
          libraryEntries[i] = null;
        });
    }
  });

  function next(): void {
    mediaIndex = (mediaIndex + 1) % medias.length;
  }

  const DURATION = 60000;
  let progress = $state(0);
  let currentAnimation: ReturnType<typeof animate> | null = null;

  function startTimer(durationMs = DURATION): void {
    currentAnimation?.pause();
    const obj = { value: 0 };
    progress = 0;
    currentAnimation = animate(obj, {
      value: 100,
      duration: durationMs,
      ease: "linear",
      onUpdate: () => (progress = obj.value),
      onComplete: next,
    });
  }

  $effect(() => {
    const idx = mediaIndex;
    currentAnimation?.pause();
    currentAnimation = null;
    progress = 0;
    if (!videoClips[idx]) startTimer();
  });

  let overviewEl = $state<HTMLElement | null>(null);

  $effect(() => {
    const idx = mediaIndex;
    const el = overviewEl;
    if (!el) return;

    el.textContent = medias[idx].overview;

    const { words } = splitText(el, {
      words: true,
    });

    animate(words, {
      opacity: ["0", "1"],
      duration: 2000,
      ease: "out(3)",
      delay: stagger(50),
    });
  });

  onDestroy(() => currentAnimation?.pause());
</script>

<div class="relative aspect-video max-h-[75vh] w-full overflow-hidden">
  <!-- {#each backdropUrls as url, i (i)} -->
  <!--   {#if url} -->
  <!--     <img -->
  <!--       class="absolute inset-0 z-0 h-full w-full rounded-b-2xl border-b object-cover transition-opacity duration-1000" -->
  <!--       class:opacity-0={i !== mediaIndex} -->
  <!--       class:opacity-100={i === mediaIndex} -->
  <!--       src={url} -->
  <!--       alt="backdrop" -->
  <!--     /> -->
  <!--   {/if} -->
  <!-- {/each} -->
  {#if videoClips[mediaIndex]}
    <div
      class="absolute inset-0 z-0 overflow-clip rounded-b-2xl border-b"
      in:fly|local={{ duration: 1000 }}
    >
      {#key mediaIndex}
        <PlayerSimple
          src={videoClips[mediaIndex]}
          loop={false}
          controls={false}
          bg={backdropUrls[mediaIndex]}
          onProgress={(cur, dur) => (progress = (cur / dur) * 100)}
          onEnded={() => (mediaIndex = (mediaIndex + 1) % medias.length)}
        />
      {/key}
    </div>
  {/if}

  <div
    class="absolute inset-0 z-10 rounded-b-2xl mask-[linear-gradient(to_right,black,transparent)] [backdrop-filter:blur(120px)] [-webkit-mask-image:linear-gradient(to_right,black,transparent)]"
  ></div>

  <div
    class="absolute inset-0 z-20 rounded-b-2xl border-b bg-linear-to-r from-black/80 from-0% to-transparent to-50% xl:to-40%"
  ></div>

  <div
    class="absolute inset-0 z-30 mt-[10%] mb-[5%] ml-[5%] flex w-[40%] flex-col items-start gap-4 xl:w-[30%] 2xl:w-[25%]"
  >
    <div class="relative h-36 w-full self-center">
      {#each logoUrls as url, i (i)}
        {#if url}
          <img
            class="absolute inset-0 h-full max-w-full object-contain transition-opacity duration-1000"
            class:opacity-0={i !== mediaIndex}
            class:opacity-100={i === mediaIndex}
            src={url}
            alt="logo"
          />
        {/if}
      {/each}
    </div>

    {#key mediaIndex}
      <div
        class="flex w-full flex-row flex-wrap gap-1 px-4 text-xs font-medium text-muted-foreground"
        in:fly={{ x: -20, duration: 400, delay: 200 }}
      >
        <Badge variant="outline">{tmdbRatings[mediaIndex]}</Badge>
        {#each genres[mediaIndex] ?? [] as genre (genre)}
          <Badge variant="outline">{genre}</Badge>
        {/each}
        <Badge variant="outline">{runtimes[mediaIndex]}</Badge>
        <Badge variant="outline">
          <span
            class="flex flex-row items-center gap-1 align-middle text-yellow-500"
          >
            <Star size="12" />
            {medias[mediaIndex]?.vote_average}
          </span>
        </Badge>
      </div>
    {/key}

    <p bind:this={overviewEl} class="px-4 font-medium"></p>

    <div class="mt-auto flex w-full flex-row px-4">
      <Button
        variant="default"
        size="lg"
        class="flex-1 rounded-r-none bg-accent/75 hover:bg-accent"
      >
        Watch
      </Button>
      <LibraryStatusPanel
        libraryEntry={libraryEntries[mediaIndex]}
        media={medias[mediaIndex]}
        size="icon-lg"
        class="rounded-l-none"
      />
    </div>

    <div class="flex w-full flex-row gap-1 px-4">
      {#each medias as _, i (i)}
        <div class="h-1 flex-1 overflow-hidden rounded-full border">
          <div
            class="h-full bg-primary"
            style="width: {i < mediaIndex
              ? 100
              : i === mediaIndex
                ? progress
                : 0}%"
          ></div>
        </div>
      {/each}
    </div>
  </div>
</div>
