// $lib/player/trackList.ts
//
// Builders for the mobile and TV players' audio/subtitle pickers. Both shells
// carried byte-identical copies of every function here; the desktop player
// renders its track menus differently and doesn't use them.
//
// All pure — the components keep the one-line $derived wrappers that feed
// these the current Player track lists.

import { SvelteMap } from "svelte/reactivity";

import * as m from "$lib/paraglide/messages.js";
import { langName, trackLabel } from "$lib/player/trackLabels";
import type { MpvTrack } from "$lib/player/player.svelte";

/** An external (addon-supplied) subtitle, as passed to the player. */
export interface ExternalSubtitle {
  id: string;
  url: string;
  lang: string;
}

/** One row of the flat subtitle list: Off, then embedded, then external. */
export type SubItem =
  | { kind: "off"; id: "off"; label: string }
  | { kind: "embedded"; id: number; label: string }
  | { kind: "external"; id: string; label: string };

/** One row of the grouped subtitle list, including its section headers. */
export type SubRowItem = {
  id: string | number;
  label: string;
  header?: boolean;
  indent?: boolean;
};

/** Audio tracks ordered by their display label. */
export function sortAudioTracks(tracks: readonly MpvTrack[]): MpvTrack[] {
  return [...tracks].sort((a, b) =>
    trackLabel(a, "Audio").localeCompare(trackLabel(b, "Audio")),
  );
}

/** Flat subtitle item list: Off + embedded + external. */
export function subtitleItems(
  subtitleTracks: readonly MpvTrack[],
  externalSubtitles: readonly ExternalSubtitle[],
): SubItem[] {
  const items: SubItem[] = [
    { kind: "off", id: "off", label: m.player_subtitles_off() },
  ];
  for (const t of subtitleTracks) {
    items.push({
      kind: "embedded",
      id: t.id,
      label: trackLabel(t, "Subtitle"),
    });
  }
  for (const s of externalSubtitles) {
    items.push({
      kind: "external",
      id: s.id,
      label: `${langName(s.lang)} · OpenSubtitles`,
    });
  }
  return items;
}

/**
 * Groups entries by language label, keeping "Other" last and the rest
 * alphabetical. Untagged tracks fall into the "Other" bucket.
 */
export function groupByLang<T>(
  entries: { lang: string; item: T }[],
): { label: string; items: T[] }[] {
  const OTHER = m.player_other();
  const groups = new SvelteMap<string, T[]>();
  for (const { lang, item } of entries) {
    const g = lang || OTHER;
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g)!.push(item);
  }
  return [...groups.entries()]
    .sort((a, b) =>
      a[0] === OTHER ? 1 : b[0] === OTHER ? -1 : a[0].localeCompare(b[0]),
    )
    .map(([label, items]) => ({ label, items }));
}

/**
 * Grouped subtitle list for the picker: Off, then a per-source header
 * (Embedded / Addons) with per-language subheaders under each.
 */
export function subtitleRows(
  subtitleTracks: readonly MpvTrack[],
  externalSubtitles: readonly ExternalSubtitle[],
): SubRowItem[] {
  const rows: SubRowItem[] = [{ id: "off", label: m.player_subtitles_off() }];

  if (subtitleTracks.length > 0) {
    rows.push({
      id: "hdr-embedded",
      label: m.player_embedded(),
      header: true,
    });
    const embGroups = groupByLang(
      subtitleTracks.map((t) => ({
        lang: t.lang ? langName(t.lang) : t.title || "",
        item: {
          id: t.id as string | number,
          label: trackLabel(t, "Subtitle"),
        },
      })),
    );
    for (const g of embGroups) {
      rows.push({
        id: `hdr-embedded-${g.label}`,
        label: g.label,
        header: true,
        indent: true,
      });
      for (const item of g.items) rows.push(item);
    }
  }

  if (externalSubtitles.length > 0) {
    rows.push({
      id: "hdr-addons",
      label: m.player_addons(),
      header: true,
    });
    const extGroups = groupByLang(
      externalSubtitles.map((s) => ({
        lang: s.lang ? langName(s.lang) : "",
        item: {
          id: s.id as string | number,
          label: langName(s.lang) || m.player_subtitle(),
        },
      })),
    );
    for (const g of extGroups) {
      rows.push({
        id: `hdr-addons-${g.label}`,
        label: g.label,
        header: true,
        indent: true,
      });
      for (const item of g.items) rows.push(item);
    }
  }

  return rows;
}
