<!-- TV fork of components/LibraryStatusPanel.svelte.
     The desktop panel uses a bits-ui Popover that portals its content to
     document.body, which places the status options outside the tv-detail focus
     group (trapFocus:true). On TV none of those options are D-pad-reachable.
     This fork replaces the Popover with TvTrackPanel, which renders inline and
     manages its own focus trap + keyboard handling independently. -->
<script lang="ts">
  import { api, statusLabel, type LibraryStatus, STATUS_COLORS } from "$lib/api";
  import type { LibraryEntry } from "$lib/types/library";
  import type { Media } from "$lib/types/tmdb";
  import { Button } from "$lib/components/ui/button/index.js";
  import { BookmarkIcon, BookmarkPlus } from "lucide-svelte";
  import { libraryChanged } from "$lib/stores/library";
  import TvTrackPanel from "./player/TvTrackPanel.svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    libraryEntry,
    media,
    lastAiredSeason = null,
    lastAiredEpisode = null,
    class: className = "",
  } = $props<{
    libraryEntry: LibraryEntry | null;
    media: Media;
    lastAiredSeason?: number | null;
    lastAiredEpisode?: number | null;
    class?: string;
  }>();

  const title = $derived(media.media_type === "tv" ? media.name : media.title);

  let panelOpen = $state(false);

  const statusItems = $derived(
    (["watch_later", "watching", "finished", "dropped"] as LibraryStatus[]).map((value) => ({
      id: value,
      label: statusLabel(value),
      dot: STATUS_COLORS[value as LibraryStatus].dot,
    })),
  );

  async function handleStatus(status: LibraryStatus): Promise<void> {
    try {
      if (libraryEntry?.status === status) {
        await api.libraryRemove(media.id, media.media_type);
        libraryEntry = null;
      } else if (libraryEntry) {
        libraryEntry = await api.librarySetStatus(
          media.id,
          media.media_type,
          status,
        );
      } else {
        libraryEntry = await api.libraryUpsert({
          tmdb_id: media.id,
          media_type: media.media_type,
          title,
          poster_path: media.poster_path ?? "",
          vote_average: media.vote_average ?? 0,
          last_air_date: media.last_air_date ?? "",
          last_aired_season: lastAiredSeason,
          last_aired_episode: lastAiredEpisode,
          status,
        });
      }
      libraryChanged.update((n) => n + 1);
    } catch (e) {
      console.error("library status:", e);
    }
  }
</script>

<Button
  variant="secondary"
  size="icon"
  class={className}
  onclick={() => (panelOpen = true)}
>
  {#if libraryEntry}
    <BookmarkIcon class="size-4 {STATUS_COLORS[libraryEntry.status as LibraryStatus].text}" />
  {:else}
    <BookmarkPlus />
  {/if}
</Button>

{#if panelOpen}
  <TvTrackPanel
    title={m.my_list_title()}
    items={statusItems}
    selectedId={libraryEntry?.status ?? null}
    onSelect={(id) => handleStatus(id as LibraryStatus)}
    onClose={() => (panelOpen = false)}
  />
{/if}
