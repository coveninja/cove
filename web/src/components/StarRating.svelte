<script lang="ts">
  import { Star } from "lucide-svelte";
  import { api } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import { animate, stagger } from "animejs";
  import { libraryChanged } from "$lib/stores/library";
  import { Button } from "$lib/components/ui/button/index.js";

  let {
    libraryEntry,
    media,
    lastAiredSeason = null,
    lastAiredEpisode = null,
    variant = "button",
  } = $props<{
    libraryEntry: LibraryEntry | null;
    media: Media;
    lastAiredSeason?: number | null;
    lastAiredEpisode?: number | null;
    variant?: "button" | "inline";
  }>();

  const title = $derived(media.media_type === "tv" ? media.name : media.title);
  let hoverRating = $state<number>(0);
  const displayRating = $derived(
    hoverRating > 0 ? hoverRating : (libraryEntry?.rating ?? 0),
  );

  let starRefs = $state<HTMLButtonElement[]>([]);
  let expanded = $state(false);

  const rated = $derived((libraryEntry?.rating ?? 0) > 0);

  function getStarsUpTo(n: number): HTMLButtonElement[] {
    return starRefs.slice(0, n).filter(Boolean);
  }

  function animateHoverIn(star: number): void {
    const prev = hoverRating;
    hoverRating = star;

    if (star > prev) {
      const newTargets = starRefs.slice(prev, star).filter(Boolean);
      animate(newTargets, {
        scale: [1, 1.25, 1],
        duration: 300,
        ease: "outBack",
        delay: stagger(40),
      });
    } else if (star < prev) {
      const removedTargets = starRefs.slice(star, prev).filter(Boolean);
      animate(removedTargets, {
        scale: 1,
        duration: 200,
        ease: "outQuad",
      });
    }
  }

  function animateHoverOut(): void {
    hoverRating = 0;
  }

  function animateRatingSet(star: number): void {
    const targets = getStarsUpTo(star);
    if (!targets.length) return;
    animate(targets, {
      translateY: [0, -8, 2, -4, 0],
      duration: 500,
      ease: "outQuad",
      delay: stagger(70),
    });
  }

  function animateClear(): void {
    if (!starRefs.length) return;
    animate(starRefs.filter(Boolean), {
      translateX: [0, -4, 4, -3, 3, 0],
      duration: 350,
      ease: "outQuad",
    });
  }

  async function handleRating(star: number): Promise<void> {
    try {
      const newRating = libraryEntry?.rating === star ? null : star;

      if (newRating === null) {
        // Drop any hover preview so the cleared state is visible immediately —
        // on touch devices there is no mouseleave to reset it otherwise.
        hoverRating = 0;
        animateClear();
      } else {
        animateRatingSet(star);
      }

      if (!libraryEntry) {
        libraryEntry = await api.libraryUpsert({
          tmdb_id: media.id,
          media_type: media.media_type,
          title,
          poster_path: media.poster_path ?? "",
          vote_average: media.vote_average ?? 0,
          last_air_date: media.last_air_date ?? "",
          last_aired_season: lastAiredSeason,
          last_aired_episode: lastAiredEpisode,
        });
      }

      libraryEntry = await api.librarySetRating(
        media.id,
        media.media_type,
        newRating,
      );

      libraryChanged.update((n) => n + 1);

      // Collapse the expanded row back to the trigger button on touch.
      // On mouse, hoverRating is >0 while the pointer remains over a star, so
      // the row stays open. On touch, hoverRating is never set (the
      // onpointerenter guard rejects non-mouse events), so this collapses
      // immediately after the rating API call resolves.
      if (variant === "button" && hoverRating === 0) expanded = false;
    } catch (e) {
      console.error("library rating:", e);
    }
  }

  // Staggered pop-in animation when the pill expands.
  // Reading both `expanded` and `starRefs` ensures the effect re-runs once
  // after render populates starRefs, but won't re-fire on hover animations
  // because those don't change `expanded`.
  $effect(() => {
    if (expanded && starRefs.length > 0) {
      const targets = starRefs.filter(Boolean);
      if (targets.length) {
        animate(targets, {
          scale: [0, 1],
          duration: 250,
          ease: "outBack",
          delay: stagger(30),
        });
      }
    }
  });
</script>

{#snippet starButtons()}
  {#each [1, 2, 3, 4, 5] as star, i (star)}
    <button
      bind:this={starRefs[i]}
      onclick={(e) => {
        e.stopPropagation();
        handleRating(star);
      }}
      onpointerenter={(e) => {
        // Hover preview is mouse-only: taps fire an emulated mouseenter with
        // no matching leave, which would pin hoverRating and mask the real
        // rating (e.g. an unrate looks like it did nothing on Android).
        if (e.pointerType === "mouse") animateHoverIn(star);
      }}
      aria-label="Rate {star} star{star !== 1 ? 's' : ''}"
      class="rounded p-0.5"
      style="will-change: transform;"
    >
      <Star
        class="size-4 transition-colors duration-150 {displayRating >= star
          ? 'fill-yellow-400 text-yellow-400'
          : 'text-muted-foreground/40'}"
      />
    </button>
  {/each}
{/snippet}

{#if variant === "inline"}
  <div
    class="flex shrink-0 items-center gap-0.5"
    onmouseleave={animateHoverOut}
    role="group"
    aria-label="Rating"
  >
    {@render starButtons()}
  </div>
{:else}
  <div
    class="flex shrink-0 items-center"
    role="group"
    aria-label="Rating"
    onpointerenter={(e) => {
      if (e.pointerType === "mouse") expanded = true;
    }}
    onpointerleave={(e) => {
      if (e.pointerType === "mouse") {
        expanded = false;
        hoverRating = 0;
      }
    }}
    onfocusout={(e) => {
      if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
        expanded = false;
        hoverRating = 0;
      }
    }}
  >
    {#if !expanded}
      <Button
        variant="outline"
        size={rated ? null : "icon"}
        class={rated ? "h-9 w-auto gap-1 px-2.5" : ""}
        onclick={(e: MouseEvent) => {
          e.stopPropagation();
          // Blur before expanding: on touch, the tap focuses this button, and
          // Chromium fires focusout (relatedTarget null) when the expansion
          // swap removes the focused node — which the onfocusout handler
          // below would read as "focus left" and collapse the row before the
          // stars ever appear. Blurring now makes that focusout fire while
          // expanded is still false, so the collapse is a no-op.
          (e.currentTarget as HTMLElement).blur();
          expanded = true;
        }}
        aria-label="Rate"
        title="Rate"
      >
        <div class="">
          <Star class="size-4 {rated ? 'fill-yellow-400 text-yellow-400' : ''}" />
        </div>
      </Button>
    {:else}
      <div
        class="flex h-9 items-center gap-0.5 rounded-full border border-input bg-background px-1.5 shadow-xs"
        role="group"
        onmouseleave={animateHoverOut}
      >
        {@render starButtons()}
      </div>
    {/if}
  </div>
{/if}
