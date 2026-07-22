<script lang="ts">
  import { Player } from "$lib/player/player.svelte";
  import { settings } from "$lib/stores/settings";
  import * as Popover from "$lib/components/ui/popover";
  import { Button } from "$lib/components/ui/button";
  import { Slider } from "$lib/components/ui/slider/index.js";
  import { Captions } from "lucide-svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { langName, trackLabel } from "$lib/player/trackLabels";
  import type { SubSel } from "$lib/player/subtitles";
  import MenuItem from "./MenuItem.svelte";

  let {
    open = $bindable(false),
    externalSubtitles,
    subSelection,
    onSelect,
    onUpdateStyle,
  }: {
    open?: boolean;
    externalSubtitles: { id: string; url: string; lang: string }[];
    subSelection: SubSel;
    onSelect: (sel: SubSel) => void;
    onUpdateStyle: (patch: {
      subtitleSize?: number;
      subtitlePosition?: number;
      subtitleBackground?: boolean;
    }) => void;
  } = $props();

  // Subtitle menu grouped two levels deep: by source (Embedded tracks from the
  // file vs Add-ons fetched from subtitle addons), then by language within each
  // source. Tracks with no language tag land in "Other". Language groups are
  // sorted alphabetically with "Other" last; empty sources are omitted.
  type SubMenuItem =
    | { kind: "embedded"; key: string; id: number; label: string }
    | { kind: "external"; key: string; id: string; label: string };

  type SubLangGroup = { label: string; items: SubMenuItem[] };
  type SubSourceSection = { source: string; groups: SubLangGroup[] };

  const OTHER = "Other";

  // Bucket items by language name and sort (Other last).
  function groupByLang(
    entries: { lang: string; item: SubMenuItem }[],
  ): SubLangGroup[] {
    const groups = new SvelteMap<string, SubMenuItem[]>();
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

  const subtitleSections = $derived.by((): SubSourceSection[] => {
    const embedded = groupByLang(
      Player.subtitleTracks.map((t) => ({
        lang: t.lang ? langName(t.lang) : t.title || "",
        item: {
          kind: "embedded" as const,
          key: `e${t.id}`,
          id: t.id,
          label: trackLabel(t, "Subtitle"),
        },
      })),
    );
    const external = groupByLang(
      externalSubtitles.map((s) => ({
        lang: s.lang ? langName(s.lang) : "",
        item: {
          kind: "external" as const,
          key: `x${s.id}`,
          id: s.id,
          label: s.lang ? langName(s.lang) : "Subtitle",
        },
      })),
    );

    const sections: SubSourceSection[] = [];
    if (embedded.length) sections.push({ source: "Embedded", groups: embedded });
    if (external.length) sections.push({ source: "Add-ons", groups: external });
    return sections;
  });

  const subtitleLabel = $derived.by(() => {
    // Capture into a const so the discriminated-union narrowing survives into
    // the .find() callbacks below.
    const sel = subSelection;
    if (sel.kind === "off") return "Subtitles";
    if (sel.kind === "embedded") {
      const t = Player.subtitleTracks.find((x) => x.id === sel.id);
      return t ? trackLabel(t, "Subtitle") : "Subtitles";
    }
    const e = externalSubtitles.find((x) => x.id === sel.id);
    return e ? langName(e.lang) : "Subtitles";
  });
</script>

<Popover.Root bind:open>
  <Popover.Trigger>
    {#snippet child({ props })}
      <Button
        {...props}
        variant="ghost"
        size="sm"
        class="gap-1.5 text-white hover:bg-white/15 hover:text-white"
      >
        <Captions class="size-4" />
        <span class="max-w-28 truncate text-xs">{subtitleLabel}</span>
      </Button>
    {/snippet}
  </Popover.Trigger>
  <Popover.Content side="top" align="end" class="w-60 p-1">
    <p class="px-2 py-1.5 text-sm font-semibold text-muted-foreground">Subtitles</p>
    <div class="max-h-72 overflow-y-auto flex flex-col gap-1">
      <MenuItem
        label="Off"
        active={subSelection.kind === "off"}
        onSelect={() => onSelect({ kind: "off" })}
      />
      {#each subtitleSections as section (section.source)}
        <div class="bg-black/50 border flex flex-col rounded-2xl">
          <p
                  class="px-2 pt-2 pb-0.5 text-sm font-semibold tracking-wide text-muted-foreground uppercase bg-card/30"
          >
            {section.source}
          </p>
          {#each section.groups as group (group.label)}
            <p
                    class="px-2 pt-1 pb-0.5 text-xs font-medium tracking-wide text-muted-foreground/60 uppercase bg-card/50"
            >
              {group.label}
            </p>
            {#each group.items as item (item.key)}
              <MenuItem
                      label={item.label}
                      active={(subSelection.kind === "embedded" &&
                item.kind === "embedded" &&
                subSelection.id === item.id) ||
                (subSelection.kind === "external" &&
                  item.kind === "external" &&
                  subSelection.id === item.id)}
                      onSelect={() =>
                item.kind === "embedded"
                  ? onSelect({ kind: "embedded", id: item.id })
                  : onSelect({ kind: "external", id: item.id })}
              />
            {/each}
          {/each}
        </div>
      {/each}
    </div>

    <!-- Style controls (size / position / background box) -->
    <div class="mt-1 border-t border-border px-2 pt-2 pb-1 flex flex-col gap-2">
      <p class="pb-1 text-sm font-semibold tracking-wide text-muted-foreground/70">
        Style
      </p>
      <div class="space-y-3 py-1">
        <div class="space-y-1.5">
          <div class="flex items-center justify-between text-xs">
            <span>Size</span>
            <span class="tabular-nums text-muted-foreground">
              {Math.round($settings?.subtitleSize ?? 100)}%
            </span>
          </div>
          <Slider
            type="single"
            value={$settings?.subtitleSize ?? 100}
            min={50}
            max={200}
            step={10}
            onValueChange={(v) => onUpdateStyle({ subtitleSize: v })}
            aria-label="Subtitle size"
          />
        </div>
        <div class="space-y-1.5">
          <div class="flex items-center justify-between text-xs">
            <span>Position</span>
            <span class="tabular-nums text-muted-foreground">
              {Math.round($settings?.subtitlePosition ?? 8)}%
            </span>
          </div>
          <Slider
            type="single"
            value={$settings?.subtitlePosition ?? 8}
            min={2}
            max={90}
            step={1}
            onValueChange={(v) => onUpdateStyle({ subtitlePosition: v })}
            aria-label="Subtitle position"
          />
        </div>
      </div>
      <MenuItem
        label="Background"
        active={$settings?.subtitleBackground ?? false}
        onSelect={() =>
          onUpdateStyle({
            subtitleBackground: !($settings?.subtitleBackground ?? false),
          })}
      />
    </div>
  </Popover.Content>
</Popover.Root>
