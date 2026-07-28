<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import { type Person } from "$lib/api";
  import * as Card from "$lib/components/ui/card/index.js";
  import PersonCard from "./cards/PersonCard.svelte";
  import ActivityHero from "./insights/ActivityHero.svelte";
  import ActivityStreaks from "./insights/ActivityStreaks.svelte";
  import ActivityCalendar from "./insights/ActivityCalendar.svelte";
  import ActivityBars from "./insights/ActivityBars.svelte";
  import StudioFootprint from "./insights/StudioFootprint.svelte";
  import TasteSignalView from "./insights/TasteSignalView.svelte";
  import {
    activityDayLabels,
    activityHourLabels,
    activityMonthLabels,
  } from "./insights/utils";
  import {
    InsightsController,
    displayInsightPerson as displayPerson,
    insightConicGradient as conic,
    insightGenreSlices as genreSlices,
    insightMediaSlices as mvSlices,
    insightStatusSlices as statusSlices,
    insightWeights,
    type InsightSlice as Slice,
  } from "$lib/insights.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import {
    Film,
    Tv,
    Sparkles,
    Info,
    Activity,
    Users,
    BarChart3,
  } from "lucide-svelte";

  let {
    visible = true,
    onSelectPerson,
  }: { visible?: boolean; onSelectPerson: (p: Person) => void } = $props();

  const controller = new InsightsController();
  const stats = $derived(controller.stats);
  const insights = $derived(controller.insights);
  const activity = $derived(controller.activity);
  const loading = $derived(controller.loading);
  const loadError = $derived(controller.loadError);
  const peopleSlots = $derived(controller.peopleSlots);

  $effect(() => {
    if (!visible) return;
    // Reading $libraryChanged makes this effect re-run when the store bumps.
    void $libraryChanged;

    void controller.load();
  });

  const hasProfile = $derived((insights?.signals_used ?? 0) > 0);
  const hasActivity = $derived(activity !== null && activity.total_seconds > 0);

  const weights = insightWeights();

  // ── Activity chart helpers ────────────────────────────────────────────────
  const MONTH_SHORT = activityMonthLabels();
  const DOW_SHORT = activityDayLabels();
  const HOUR_LABELS = activityHourLabels();

  const monthItems = $derived(
    activity
      ? activity.by_month_this_year.map((v, i) => ({
          label: MONTH_SHORT[i],
          value: v,
        }))
      : [],
  );

  const monthSecondary = $derived(
    activity && activity.last_year_seconds > 0
      ? activity.by_month_last_year
      : undefined,
  );

  const dowItems = $derived(
    activity
      ? activity.by_day_of_week.map((v, i) => ({
          label: DOW_SHORT[i],
          value: v,
        }))
      : [],
  );

  const hourItems = $derived(
    activity
      ? activity.by_hour_of_day.map((v, i) => ({
          label: HOUR_LABELS[i],
          value: v,
        }))
      : [],
  );

  const yearItems = $derived(
    activity
      ? Object.entries(activity.by_year)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([year, secs]) => ({ label: year, value: secs }))
      : [],
  );

  const showYearChart = $derived(yearItems.length > 1);

  // Titles watched this year
  const titlesThisYear = $derived(activity?.titles_watched_this_year ?? []);
</script>

{#snippet stat(value: string, label: string)}
  <Card.Root>
    <Card.Content class="p-4">
      <div class="text-2xl font-semibold">{value}</div>
      <div class="text-xs text-muted-foreground">{label}</div>
    </Card.Content>
  </Card.Root>
{/snippet}

{#snippet donut(slices: Slice[])}
  {#if slices.length === 0}
    <p class="text-xs text-muted-foreground">{m.account_not_enough_signal()}</p>
  {:else}
    {@const total = slices.reduce((s, x) => s + x.value, 0) || 1}
    <div class="flex items-center gap-5">
      <div
        class="relative size-28 shrink-0 rounded-full"
        style="background: {conic(slices)};"
      >
        <div class="absolute inset-[24%] rounded-full bg-card"></div>
      </div>
      <ul class="flex min-w-0 flex-1 flex-col gap-1.5 text-sm">
        {#each slices as s (s.label)}
          <li class="flex items-center gap-2">
            <span
              class="size-2.5 shrink-0 rounded-full"
              style="background: {s.color};"
            ></span>
            <span class="truncate">{s.label}</span>
            <span class="ml-auto shrink-0 text-muted-foreground">
              {Math.round((s.value / total) * 100)}%{s.count != null
                ? ` · ${s.count}`
                : ""}
            </span>
          </li>
        {/each}
      </ul>
    </div>
  {/if}
{/snippet}

{#snippet chartCard(
  title: string,
  Icon: typeof Film,
  slices: Slice[],
  description?: string,
)}
  <Card.Root>
    <Card.Header>
      <Card.Title class="flex items-center gap-2 text-sm">
        <Icon class="size-4" />
        {title}
      </Card.Title>
      {#if description}
        <Card.Description>{description}</Card.Description>
      {/if}
    </Card.Header>
    <Card.Content>
      {@render donut(slices)}
    </Card.Content>
  </Card.Root>
{/snippet}

<div class="mx-auto flex w-full max-w-4xl flex-col gap-6 p-6">
  <header class="flex flex-col gap-1">
    <h1 class="text-2xl font-semibold">{m.account_your_insights()}</h1>
    <p class="text-sm text-muted-foreground">
      {m.account_insights_description()}
    </p>
  </header>

  {#if loading}
    <!-- Initial-load skeleton — not shown on subsequent refetches -->
    <div class="grid gap-4 sm:grid-cols-2">
      {#each Array(4) as _, i (i)}
        <div class="h-40 animate-pulse rounded-xl border bg-muted/40"></div>
      {/each}
    </div>
  {:else if loadError}
    <Card.Root class="border-destructive/40 bg-destructive/10">
      <Card.Content class="p-4 text-sm">
        {m.account_load_error({ error: loadError })}
      </Card.Content>
    </Card.Root>
  {:else}
    <!-- ─── Activity sections (gate: activity && total_seconds > 0) ──────── -->
    {#if hasActivity && activity}
      <ActivityHero {activity} />
      <ActivityStreaks {activity} />
      <ActivityCalendar {activity} />

      <!-- Hours by month / day-of-week / hour-of-day / by-year -->
      <section class="flex flex-col gap-4">
        <!-- By month: full width (has secondary series for last-year ghost) -->
        <Card.Root>
          <Card.Header>
            <Card.Title class="flex items-center gap-2 text-sm">
              <BarChart3 class="size-4" />
              {m.account_hours_month()}
              {#if monthSecondary}
                <span class="ml-auto text-xs font-normal text-muted-foreground"
                  >{m.account_vs_last_year()}</span
                >
              {/if}
            </Card.Title>
          </Card.Header>
          <Card.Content>
            <ActivityBars items={monthItems} secondary={monthSecondary} />
          </Card.Content>
        </Card.Root>

        <!-- Day of week + hour of day side by side -->
        <div class="grid gap-4 md:grid-cols-2">
          <Card.Root>
            <Card.Header>
              <Card.Title class="text-sm"
                >{m.account_hours_weekday()}</Card.Title
              >
            </Card.Header>
            <Card.Content>
              <ActivityBars items={dowItems} />
            </Card.Content>
          </Card.Root>

          <Card.Root>
            <Card.Header>
              <Card.Title class="text-sm">{m.account_hours_day()}</Card.Title>
            </Card.Header>
            <Card.Content>
              <ActivityBars items={hourItems} compact={true} />
            </Card.Content>
          </Card.Root>
        </div>

        <!-- By year (only when more than one year of data) -->
        {#if showYearChart}
          <Card.Root>
            <Card.Header>
              <Card.Title class="text-sm"
                >{m.account_year_over_year()}</Card.Title
              >
            </Card.Header>
            <Card.Content>
              <ActivityBars items={yearItems} />
            </Card.Content>
          </Card.Root>
        {/if}
      </section>
    {/if}

    <!-- ─── Library at a glance + composition donuts (taste gate) ─────── -->
    {#if hasProfile && stats && insights}
      <section class="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {@render stat(String(stats.total), m.account_in_library())}
        {@render stat(
          String(stats.by_status.finished ?? 0),
          m.account_finished(),
        )}
        {@render stat(
          stats.rated ? stats.avg_rating.toFixed(1) : "—",
          m.account_avg_rating({ count: stats.rated }),
        )}
        {@render stat(String(stats.dismissed), m.account_not_interested())}
      </section>

      <section class="grid gap-4 md:grid-cols-2">
        {@render chartCard(
          m.account_enjoy_most(),
          Film,
          mvSlices(stats),
          m.account_enjoy_description(),
        )}
        {@render chartCard(
          m.account_watch_activity(),
          Activity,
          statusSlices(stats),
          m.account_watch_activity_description(),
        )}
      </section>
    {/if}

    <!-- ─── Titles watched this year ──────────────────────────────────── -->
    {#if titlesThisYear.length > 0}
      <Card.Root>
        <Card.Header>
          <Card.Title class="flex items-center gap-2 text-sm">
            <Film class="size-4" />
            {m.account_titles_year()}
          </Card.Title>
          <Card.Description>{m.account_top_watch_time()}</Card.Description>
        </Card.Header>
        <Card.Content>
          <div class="flex gap-3 overflow-x-auto pb-2">
            {#each titlesThisYear as t (t.tmdb_id + t.media_type)}
              <div class="shrink-0" style="width: 72px">
                <div
                  class="relative overflow-hidden rounded-lg bg-muted"
                  style="height: 108px"
                >
                  {#if t.poster_path}
                    <img
                      src={t.poster_path}
                      alt={t.title}
                      class="h-full w-full object-cover"
                      loading="lazy"
                    />
                  {:else}
                    <div
                      class="flex h-full items-center justify-center p-1 text-center text-[9px] text-muted-foreground"
                    >
                      {t.title || `#${t.tmdb_id}`}
                    </div>
                  {/if}
                </div>
                <p
                  class="mt-1 truncate text-center text-[9px] text-muted-foreground"
                >
                  {t.title || `#${t.tmdb_id}`}
                </p>
              </div>
            {/each}
          </div>
        </Card.Content>
      </Card.Root>
    {/if}

    <!-- ─── Studio footprint ───────────────────────────────────────────── -->
    {#if (insights?.top_studios?.length ?? 0) > 0}
      <StudioFootprint studios={insights!.top_studios} />
    {/if}

    <!-- ─── Genre composition donuts (taste gate) ─────────────────────── -->
    {#if hasProfile && insights}
      <section class="grid gap-4 md:grid-cols-2">
        {@render chartCard(
          m.account_top_movie_genres(),
          Film,
          genreSlices(insights.top_movie_genres),
        )}
        {@render chartCard(
          m.account_top_tv_genres(),
          Tv,
          genreSlices(insights.top_tv_genres),
        )}
      </section>

      <!-- ─── Taste signal view: diverging bars + contributors ──────────── -->
      <TasteSignalView {insights} />

      <!-- ─── Top people ─────────────────────────────────────────────────── -->
      {#if peopleSlots.length > 0}
        <Card.Root>
          <Card.Header>
            <Card.Title class="flex items-center gap-2 text-sm">
              <Users class="size-4" />
              {m.account_people_taste()}
            </Card.Title>
          </Card.Header>
          <Card.Content>
            <div class="grid grid-cols-4 gap-4 sm:grid-cols-6 md:grid-cols-8">
              {#each peopleSlots as slot (slot.id)}
                <PersonCard
                  person={displayPerson(slot)}
                  onclick={slot.person ? onSelectPerson : undefined}
                />
              {/each}
            </div>
          </Card.Content>
        </Card.Root>
      {/if}

      <!-- ─── How recommendations are built ────────────────────────────── -->
      <Card.Root class="bg-muted/20">
        <Card.Header>
          <Card.Title class="flex items-center gap-2 text-sm">
            <Info class="size-4" />
            {m.account_recommendations_how()}
          </Card.Title>
        </Card.Header>
        <Card.Content class="flex flex-col gap-4 text-sm text-muted-foreground">
          <p>
            {m.account_recommendations_intro({ count: insights.signals_used })}
          </p>

          <div class="grid grid-cols-2 gap-x-6 gap-y-1.5 sm:grid-cols-3">
            {#each weights as wgt (wgt.label)}
              <div class="flex items-center justify-between gap-2">
                <span>{wgt.label}</span>
                <span class="font-mono text-xs text-foreground"
                  >{wgt.value}</span
                >
              </div>
            {/each}
          </div>

          <p>{m.account_recommendations_decay()}</p>

          <p>{m.account_recommendations_negative()}</p>
        </Card.Content>
      </Card.Root>
    {/if}

    <!-- ─── Empty state: nothing to show at all ───────────────────────── -->
    {#if !hasActivity && !hasProfile}
      <Card.Root>
        <Card.Content class="flex flex-col items-center gap-2 p-10 text-center">
          <Sparkles class="size-6 text-muted-foreground" />
          <p class="font-medium">{m.account_nothing_analyze()}</p>
          <p class="max-w-sm text-sm text-muted-foreground">
            {m.account_empty_description()}
          </p>
        </Card.Content>
      </Card.Root>
    {/if}
  {/if}
</div>
