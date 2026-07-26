<script lang="ts">
  import {
    api,
    type LibraryStats,
    type DiscoverInsights,
    type Taste,
    type Person,
    type ActivityStats,
  } from "$lib/api";
  import { auth } from "$lib/stores/auth.svelte";
  import { libraryChanged } from "$lib/stores/library";
  import { focusable, focusGroup } from "../focus/actions";
  import AuthDialog from "../../components/AuthDialog.svelte";
  import ConfirmDialog from "../../components/ConfirmDialog.svelte";
  import PersonCard from "../../components/cards/PersonCard.svelte";
  import ActivityHero from "../../components/insights/ActivityHero.svelte";
  import ActivityStreaks from "../../components/insights/ActivityStreaks.svelte";
  import ActivityCalendar from "../../components/insights/ActivityCalendar.svelte";
  import ActivityBars from "../../components/insights/ActivityBars.svelte";
  import StudioFootprint from "../../components/insights/StudioFootprint.svelte";
  import TasteSignalView from "../../components/insights/TasteSignalView.svelte";
  import {
    activityDayLabels,
    activityHourLabels,
    activityMonthLabels,
  } from "../../components/insights/utils";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import {
    RefreshCw,
    LogOut,
    LogIn,
    Trash2,
    Check,
    Film,
    Sparkles,
    Info,
    Users,
    BarChart3,
  } from "lucide-svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    visible = true,
    onSelectPerson,
  }: { visible?: boolean; onSelectPerson: (p: Person) => void } = $props();

  // ── Account card state ──────────────────────────────────────────────────────
  let syncing = $state(false);
  let authOpen = $state(false);

  async function sync(): Promise<void> {
    syncing = true;
    try {
      await api.authSync();
      libraryChanged.update((n) => n + 1);
    } catch (e) {
      console.error("sync:", e);
    } finally {
      syncing = false;
    }
  }

  async function logout(): Promise<void> {
    await api.authLogout();
    await auth.logout();
  }

  // ── Profile switching ────────────────────────────────────────────────────────
  async function switchProfile(id: string): Promise<void> {
    try {
      await api.profileActivate(id);
      window.location.reload();
    } catch (e) {
      console.error("switch profile:", e);
    }
  }

  // ── Delete flow state ────────────────────────────────────────────────────────
  let deleteTarget = $state<{ id: string; name: string } | null>(null);
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);

  async function confirmDelete(): Promise<void> {
    if (!deleteTarget) return;
    deleting = true;
    deleteError = null;
    try {
      await api.profileDelete(deleteTarget.id);
      const res = await api.profilesList();
      auth.setProfiles(
        res.profiles,
        res.profiles.find((p) => p.id === res.active_profile_id) ??
          res.profiles[0],
      );
      libraryChanged.update((n) => n + 1);
      deleteTarget = null;
    } catch (e) {
      deleteError = e instanceof Error ? e.message : String(e);
    } finally {
      deleting = false;
    }
  }

  // ── Insights data ────────────────────────────────────────────────────────────
  let stats = $state<LibraryStats | null>(null);
  let insights = $state<DiscoverInsights | null>(null);
  let activity = $state<ActivityStats | null>(null);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  let initialLoadDone = false;
  let fetchVer = 0;

  $effect(() => {
    if (!visible) return;
    void $libraryChanged;

    const ver = ++fetchVer;

    Promise.all([
      api.libraryStats(),
      api.discoverInsights(),
      api.activityStats().catch(() => null as ActivityStats | null),
    ])
      .then(([s, i, a]) => {
        if (ver !== fetchVer) return;
        stats = s;
        insights = i;
        activity = a;
        loadPeople(i.top_people);
      })
      .catch((e) => {
        if (ver !== fetchVer) return;
        loadError = e instanceof Error ? e.message : String(e);
      })
      .finally(() => {
        if (!initialLoadDone) {
          initialLoadDone = true;
          loading = false;
        }
      });
  });

  const hasProfile = $derived((insights?.signals_used ?? 0) > 0);
  const hasActivity = $derived(activity !== null && activity.total_seconds > 0);

  // ── Top people ────────────────────────────────────────────────────────────────
  type PeopleSlot = { id: number; name: string; person: Person | null };
  let peopleSlots = $state<PeopleSlot[]>([]);

  function loadPeople(tastes: Taste[]): void {
    peopleSlots = tastes.map((t) => ({ id: t.id, name: t.name, person: null }));
    for (const t of tastes) {
      api
        .getPerson(t.id)
        .then((d) => {
          const i = peopleSlots.findIndex((s) => s.id === t.id);
          if (i === -1) return;
          peopleSlots[i] = {
            ...peopleSlots[i],
            person: {
              id: d.id,
              name: d.name,
              profile_path: d.profile_path,
              known_for_department: d.known_for_department,
              popularity: 0,
              known_for: [],
            },
          };
        })
        .catch(() => {});
    }
  }

  function displayPerson(slot: PeopleSlot): Person {
    return (
      slot.person ?? {
        id: slot.id,
        name: slot.name,
        profile_path: "",
        known_for_department: "",
        popularity: 0,
        known_for: [],
      }
    );
  }

  // ── Chart helpers (duplicated from InsightsPage — no shared module exists) ───
  type Slice = { label: string; value: number; color: string; count?: number };

  const palette = [
    "#6366f1",
    "#ec4899",
    "#22c55e",
    "#f59e0b",
    "#06b6d4",
    "#94a3b8",
  ];

  function conic(slices: Slice[]): string {
    const total = slices.reduce((s, x) => s + x.value, 0) || 1;
    let acc = 0;
    const stops = slices.map((s) => {
      const start = (acc / total) * 100;
      acc += s.value;
      const end = (acc / total) * 100;
      return `${s.color} ${start}% ${end}%`;
    });
    return `conic-gradient(${stops.join(", ")})`;
  }

  function genreSlices(list: Taste[]): Slice[] {
    const top = list.slice(0, 5).map((g, i) => ({
      label: g.name,
      value: Math.abs(g.score),
      color: palette[i],
    }));
    const rest = list.slice(5);
    if (rest.length > 0) {
      top.push({
        label: m.account_other(),
        value: rest.reduce((a, g) => a + Math.abs(g.score), 0),
        color: palette[5],
      });
    }
    return top;
  }

  function mvSlices(s: LibraryStats): Slice[] {
    return [
      {
        label: m.my_list_movies(),
        value: s.movie_share,
        color: palette[0],
        count: s.by_type.movie ?? 0,
      },
      {
        label: m.my_list_shows(),
        value: s.tv_share,
        color: palette[1],
        count: s.by_type.tv ?? 0,
      },
    ];
  }

  const statusMeta = [
    { key: "watching", label: m.my_list_watching() },
    { key: "finished", label: m.my_list_finished() },
    { key: "watch_later", label: m.my_list_watch_later() },
    { key: "dropped", label: m.my_list_dropped() },
  ];

  function statusSlices(s: LibraryStats): Slice[] {
    return statusMeta
      .map((m, i) => ({
        label: m.label,
        value: s.by_status[m.key] ?? 0,
        color: palette[i],
        count: s.by_status[m.key] ?? 0,
      }))
      .filter((x) => x.value > 0);
  }

  const weights = [
    { label: m.account_weight_finished(), value: "+1.5" },
    { label: m.account_weight_watched_end(), value: "+1.0" },
    { label: m.account_weight_watching(), value: "+0.5" },
    { label: m.account_weight_watch_later(), value: "+0.5" },
    { label: m.account_weight_rating(), value: "±1.5" },
    { label: m.account_weight_dropped(), value: "−2.0" },
    { label: m.account_weight_not_interested(), value: "−2.0" },
  ];

  // ── Activity chart data ───────────────────────────────────────────────────────
  const MONTH_SHORT = activityMonthLabels();
  const DOW_SHORT = activityDayLabels();
  const HOUR_LABELS = activityHourLabels();

  const monthItems = $derived(
    activity
      ? activity.by_month_this_year.map((v, i) => ({ label: MONTH_SHORT[i], value: v }))
      : [],
  );
  const monthSecondary = $derived(
    activity && activity.last_year_seconds > 0 ? activity.by_month_last_year : undefined,
  );
  const dowItems = $derived(
    activity
      ? activity.by_day_of_week.map((v, i) => ({ label: DOW_SHORT[i], value: v }))
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
  const titlesThisYear = $derived(activity?.titles_watched_this_year ?? []);

  // ── People grid column count (must match CSS grid-template-columns) ──────────
  const PEOPLE_COLS = 5;
</script>

<!--
  Root: native scroll container. Never shadcn ScrollArea.
  data-tv-scroll-anchor on each major section tells focusEl() to scroll the
  whole section into view when the invisible anchor inside it gains focus.
-->
<div class="h-full overflow-y-auto scrollbar-none [&::-webkit-scrollbar]:hidden">
  <div class="mx-auto max-w-5xl px-16 py-16 flex flex-col gap-10">

    <!-- ══════════════════════════════════════════════════════════════════════
         ACCOUNT SECTION
         Anchor is ungrouped so Down triggers navigateBetweenGroups → jumps
         to the nearest focusGroup below (profiles column group). The section
         ring via :focus-within gives users a visible "you are here" cue.
    ═══════════════════════════════════════════════════════════════════════ -->
    <section
      data-tv-scroll-anchor
      class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
    >
      <!-- Invisible 1×1 scroll-stop anchor; opacity-0 passes checkVisibility() -->
      <div
        use:focusable
        class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
        aria-label={m.account_section()}
      ></div>

      <div class="flex flex-col gap-6">
        <header>
          <h1 class="text-4xl font-bold tracking-tight">{m.account_title()}</h1>
          <p class="mt-1 text-lg text-white/50">
            {m.account_page_description()}
          </p>
        </header>

        <!-- Account card -->
        <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-4">
          <h2 class="text-xl font-semibold">{m.nav_account()}</h2>
          {#if auth.session}
            <p class="text-base text-white/60 truncate">{auth.session.email}</p>
            <div
              use:focusGroup={{ id: "tv-account-actions", policy: { type: "row" } }}
              class="flex gap-4"
            >
              <button
                type="button"
                use:focusable={{ groupId: "tv-account-actions" }}
                onclick={sync}
                disabled={syncing}
                class="flex items-center gap-2 rounded-xl bg-white/10 px-6 py-3 text-base font-medium transition-colors focus:bg-white/25 focus:outline-none disabled:opacity-50"
              >
                {#if syncing}
                  <Spinner class="size-5" />
                {:else}
                  <RefreshCw class="size-5" />
                {/if}
                {m.account_sync()}
              </button>
              <button
                type="button"
                use:focusable={{ groupId: "tv-account-actions" }}
                onclick={logout}
                class="flex items-center gap-2 rounded-xl bg-white/5 px-6 py-3 text-base font-medium text-white/60 transition-colors focus:bg-white/20 focus:text-white focus:outline-none"
              >
                <LogOut class="size-5" />
                {m.common_sign_out()}
              </button>
            </div>
          {:else}
            <p class="text-base text-white/60">
              {m.account_guest_sync()}
            </p>
            <button
              type="button"
              use:focusable
              onclick={() => (authOpen = true)}
              class="flex w-fit items-center gap-2 rounded-xl bg-white px-6 py-3 text-base font-semibold text-black transition-colors focus:bg-white/80 focus:outline-none"
            >
              <LogIn class="size-5" />
              {m.onboarding_sign_in()}
            </button>
          {/if}
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════════════════════════════════
         PROFILES SECTION
         Profile rows in a column focusGroup so Up/Down navigates between
         them. Delete buttons are discovered by the engine as native
         focusables inside the group container.
    ═══════════════════════════════════════════════════════════════════════ -->
    <section
      data-tv-scroll-anchor
      class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
    >
      <div
        use:focusable
        class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
        aria-label={m.account_profiles_section()}
      ></div>

      <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-4">
        <h2 class="text-xl font-semibold">{m.account_profiles()}</h2>
        <div
          use:focusGroup={{ id: "tv-account-profiles", policy: { type: "column" } }}
          class="flex flex-col gap-1"
        >
          {#each auth.profiles as profile (profile.id)}
            <div class="flex items-center justify-between rounded-xl px-2 py-3">
              <button
                type="button"
                use:focusable={{ groupId: "tv-account-profiles" }}
                onclick={() => {
                  if (auth.activeProfile?.id !== profile.id) switchProfile(profile.id);
                }}
                class="flex min-w-0 flex-1 items-center gap-4 text-left rounded-xl px-3 py-2 focus:bg-white/10 focus:outline-none transition-colors"
              >
                <span class="flex size-12 shrink-0 items-center justify-center rounded-full bg-white/15 text-lg font-bold">
                  {profile.name.charAt(0).toUpperCase()}
                </span>
                <span class="flex min-w-0 flex-col">
                  <span class="truncate text-lg font-medium">{profile.name}</span>
                  <span class="flex items-center gap-2 text-sm text-white/50">
                    {#if auth.activeProfile?.id === profile.id}
                      <Check class="size-4 text-green-400" />
                      <span class="text-green-400">{m.account_active()}</span>
                    {/if}
                    {#if profile.is_primary}
                      <span class="rounded border border-white/20 px-2 py-0.5 text-xs text-white/40">
                        {m.account_primary()}
                      </span>
                    {/if}
                  </span>
                </span>
              </button>
              {#if !profile.is_primary}
                <button
                  type="button"
                  use:focusable={{ groupId: "tv-account-profiles" }}
                  onclick={() => {
                    deleteTarget = { id: profile.id, name: profile.name };
                    deleteError = null;
                  }}
                  class="ml-2 flex size-10 shrink-0 items-center justify-center rounded-xl text-white/40 transition-colors focus:bg-red-500/20 focus:text-red-400 focus:outline-none"
                  aria-label={m.account_delete_named({ name: profile.name })}
                >
                  <Trash2 class="size-5" />
                </button>
              {/if}
            </div>
          {/each}
        </div>
        {#if deleteError}
          <p class="text-sm text-red-400">{deleteError}</p>
        {/if}
      </div>
    </section>

    <!-- ══════════════════════════════════════════════════════════════════════
         INSIGHTS — only rendered after data loads
    ═══════════════════════════════════════════════════════════════════════ -->

    {#if loading}
      <section class="flex items-center justify-center py-24">
        <Spinner class="size-12" />
      </section>

    {:else if loadError}
      <section class="rounded-2xl border border-red-500/30 bg-red-500/10 p-6">
        <p class="text-base text-red-300">
          {m.account_load_error({ error: loadError })}
        </p>
      </section>

    {:else}

      <!-- ── Activity sections ────────────────────────────────────────────── -->
      {#if hasActivity && activity}

        <!-- ActivityHero, Streaks, Calendar — one scroll stop for the trio -->
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_activity()}
          ></div>
          <div class="flex flex-col gap-6">
            <ActivityHero {activity} />
            <ActivityStreaks {activity} />
            <ActivityCalendar {activity} />
          </div>
        </section>

        <!-- Charts: month / DOW / hour / year -->
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_insights()}
          ></div>
          <div class="flex flex-col gap-6">
            <!-- By month (full width) -->
            <div class="rounded-2xl bg-white/5 p-6">
              <div class="mb-4 flex items-center gap-2">
                <BarChart3 class="size-5 text-white/60" />
                <h3 class="text-lg font-semibold">{m.account_hours_month()}</h3>
                {#if monthSecondary}
                  <span class="ml-auto text-sm text-white/40">{m.account_vs_last_year()}</span>
                {/if}
              </div>
              <ActivityBars items={monthItems} secondary={monthSecondary} />
            </div>

            <!-- DOW + hour side by side -->
            <div class="grid gap-6 md:grid-cols-2">
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">{m.account_hours_weekday()}</h3>
                <ActivityBars items={dowItems} />
              </div>
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">{m.account_hours_day()}</h3>
                <ActivityBars items={hourItems} compact={true} />
              </div>
            </div>

            <!-- Year over year -->
            {#if showYearChart}
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">{m.account_year_over_year()}</h3>
                <ActivityBars items={yearItems} />
              </div>
            {/if}
          </div>
        </section>
      {/if}

      <!-- ── Library stats + donuts ────────────────────────────────────────── -->
      {#if hasProfile && stats && insights}
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.nav_my_list()}
          ></div>
          <div class="flex flex-col gap-6">
            <header>
              <h2 class="text-3xl font-bold">{m.account_your_insights()}</h2>
              <p class="mt-1 text-base text-white/50">
                {m.account_insights_description()}
              </p>
            </header>

            <!-- Stat tiles -->
            <div class="grid grid-cols-2 gap-4 sm:grid-cols-4">
              {#snippet statTile(value: string, label: string)}
                <div class="rounded-2xl bg-white/5 p-5">
                  <div class="text-3xl font-bold tabular-nums">{value}</div>
                  <div class="mt-1 text-sm text-white/50">{label}</div>
                </div>
              {/snippet}
              {@render statTile(String(stats.total), m.account_in_library())}
              {@render statTile(
                String(stats.by_status.finished ?? 0),
                m.account_finished(),
              )}
              {@render statTile(
                stats.rated ? stats.avg_rating.toFixed(1) : "—",
                m.account_avg_rating({ count: stats.rated }),
              )}
              {@render statTile(
                String(stats.dismissed),
                m.account_not_interested(),
              )}
            </div>

            <!-- Movie vs TV + Watch activity donuts -->
            <div class="grid gap-6 md:grid-cols-2">
              {#snippet donutCard(title: string, slices: Slice[], description?: string)}
                <div class="rounded-2xl bg-white/5 p-6">
                  <h3 class="mb-4 text-lg font-semibold">{title}</h3>
                  {#if description}
                    <p class="mb-4 text-sm text-white/50">{description}</p>
                  {/if}
                  {#if slices.length === 0}
                    <p class="text-sm text-white/40">{m.account_not_enough_signal()}</p>
                  {:else}
                    {@const total = slices.reduce((s, x) => s + x.value, 0) || 1}
                    <div class="flex items-center gap-6">
                      <div
                        class="relative size-32 shrink-0 rounded-full"
                        style="background: {conic(slices)};"
                      >
                        <div class="absolute inset-[24%] rounded-full bg-[#0d0d0d]"></div>
                      </div>
                      <ul class="flex min-w-0 flex-1 flex-col gap-2 text-base">
                        {#each slices as s (s.label)}
                          <li class="flex items-center gap-2">
                            <span
                              class="size-3 shrink-0 rounded-full"
                              style="background: {s.color};"
                            ></span>
                            <span class="truncate">{s.label}</span>
                            <span class="ml-auto shrink-0 text-white/50">
                              {Math.round((s.value / total) * 100)}%{s.count != null
                                ? ` · ${s.count}`
                                : ""}
                            </span>
                          </li>
                        {/each}
                      </ul>
                    </div>
                  {/if}
                </div>
              {/snippet}
              {@render donutCard(
                m.account_enjoy_most(),
                mvSlices(stats),
                m.account_enjoy_description(),
              )}
              {@render donutCard(
                m.account_watch_activity(),
                statusSlices(stats),
                m.account_watch_activity_description(),
              )}
            </div>
          </div>
        </section>
      {/if}

      <!-- ── Titles watched this year ───────────────────────────────────────── -->
      {#if titlesThisYear.length > 0}
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_titles_year()}
          ></div>
          <div class="rounded-2xl bg-white/5 p-6">
            <div class="mb-4 flex items-center gap-2">
              <Film class="size-5 text-white/60" />
              <h3 class="text-lg font-semibold">{m.account_titles_year()}</h3>
              <span class="ml-1 text-sm text-white/40">{m.account_top_watch_time()}</span>
            </div>
            <!-- Horizontal strip — not D-pad-navigable; scroll-stop anchor above handles scroll -->
            <div class="flex gap-4 overflow-x-auto pb-2 scrollbar-none [&::-webkit-scrollbar]:hidden">
              {#each titlesThisYear as t (t.tmdb_id + t.media_type)}
                <div class="shrink-0" style="width: 90px">
                  <div
                    class="relative overflow-hidden rounded-xl bg-white/10"
                    style="height: 135px"
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
                        class="flex h-full items-center justify-center p-2 text-center text-xs text-white/40"
                      >
                        {t.title || `#${t.tmdb_id}`}
                      </div>
                    {/if}
                  </div>
                  <p class="mt-1.5 truncate text-center text-xs text-white/40">
                    {t.title || `#${t.tmdb_id}`}
                  </p>
                </div>
              {/each}
            </div>
          </div>
        </section>
      {/if}

      <!-- ── Studio footprint ──────────────────────────────────────────────── -->
      {#if (insights?.top_studios?.length ?? 0) > 0}
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_studios_most()}
          ></div>
          <StudioFootprint studios={insights!.top_studios} />
        </section>
      {/if}

      <!-- ── Genre donuts ────────────────────────────────────────────────────── -->
      {#if hasProfile && insights}
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.media_genres()}
          ></div>
          <div class="grid gap-6 md:grid-cols-2">
            {#snippet genreDonut(title: string, slices: Slice[])}
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">{title}</h3>
                {#if slices.length === 0}
                  <p class="text-sm text-white/40">{m.account_not_enough_signal()}</p>
                {:else}
                  {@const total = slices.reduce((s, x) => s + x.value, 0) || 1}
                  <div class="flex items-center gap-6">
                    <div
                      class="relative size-32 shrink-0 rounded-full"
                      style="background: {conic(slices)};"
                    >
                      <div class="absolute inset-[24%] rounded-full bg-[#0d0d0d]"></div>
                    </div>
                    <ul class="flex min-w-0 flex-1 flex-col gap-2 text-base">
                      {#each slices as s (s.label)}
                        <li class="flex items-center gap-2">
                          <span
                            class="size-3 shrink-0 rounded-full"
                            style="background: {s.color};"
                          ></span>
                          <span class="truncate">{s.label}</span>
                          <span class="ml-auto shrink-0 text-white/50">
                            {Math.round((s.value / total) * 100)}%
                          </span>
                        </li>
                      {/each}
                    </ul>
                  </div>
                {/if}
              </div>
            {/snippet}
            {@render genreDonut(
              m.account_top_movie_genres(),
              genreSlices(insights.top_movie_genres),
            )}
            {@render genreDonut(
              m.account_top_tv_genres(),
              genreSlices(insights.top_tv_genres),
            )}
          </div>
        </section>

        <!-- ── Taste signal view ───────────────────────────────────────────── -->
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.onboarding_taste()}
          ></div>
          <TasteSignalView {insights} />
        </section>

        <!-- ── Top people (grid-policy focusGroup) ────────────────────────── -->
        {#if peopleSlots.length > 0}
          <section
            data-tv-scroll-anchor
            class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
          >
            <div
              use:focusable
              class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
              aria-label={m.search_people()}
            ></div>
            <div class="rounded-2xl bg-white/5 p-6">
              <div class="mb-6 flex items-center gap-2">
                <Users class="size-5 text-white/60" />
                <h3 class="text-lg font-semibold">{m.account_people_taste()}</h3>
              </div>
              <!--
                grid policy: cols must match the CSS grid-template-columns count.
                PersonCard renders a native <button> which is discovered
                automatically as a native focusable inside the focusGroup container.
              -->
              <div
                use:focusGroup={{
                  id: "tv-account-people",
                  policy: { type: "grid", cols: PEOPLE_COLS },
                }}
                class="grid grid-cols-5 gap-6"
              >
                {#each peopleSlots as slot (slot.id)}
                  <PersonCard
                    person={displayPerson(slot)}
                    onclick={slot.person ? onSelectPerson : undefined}
                  />
                {/each}
              </div>
            </div>
          </section>
        {/if}

        <!-- ── Recommendations explainer ──────────────────────────────────── -->
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1 transition-all focus-within:ring-2 focus-within:ring-white/25"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_recommendations_how()}
          ></div>
          <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-5 text-base text-white/60">
            <div class="flex items-center gap-2">
              <Info class="size-5" />
              <h3 class="text-lg font-semibold text-white">{m.account_recommendations_how()}</h3>
            </div>
            <p>
              {m.account_recommendations_intro({
                count: insights.signals_used,
              })}
            </p>
            <div class="grid grid-cols-2 gap-x-8 gap-y-2">
              {#each weights as wgt (wgt.label)}
                <div class="flex items-center justify-between gap-2">
                  <span>{wgt.label}</span>
                  <span class="font-mono text-sm text-white">{wgt.value}</span>
                </div>
              {/each}
            </div>
            <p>{m.account_recommendations_decay()}</p>
            <p>{m.account_recommendations_negative()}</p>
          </div>
        </section>
      {/if}

      <!-- ── Empty state ────────────────────────────────────────────────────── -->
      {#if !hasActivity && !hasProfile}
        <section
          data-tv-scroll-anchor
          class="relative rounded-2xl p-1"
        >
          <div
            use:focusable
            class="absolute left-0 top-0 h-px w-px opacity-0 pointer-events-none"
            aria-label={m.account_nothing_analyze()}
          ></div>
          <div class="flex flex-col items-center gap-4 rounded-2xl bg-white/5 px-10 py-20 text-center">
            <Sparkles class="size-10 text-white/30" />
            <p class="text-2xl font-semibold">{m.account_nothing_analyze()}</p>
            <p class="max-w-md text-base text-white/50">
              {m.account_empty_description()}
            </p>
          </div>
        </section>
      {/if}
    {/if}

    <!-- Bottom padding so last section isn't flush with the edge -->
    <div class="h-16"></div>
  </div>
</div>

<!-- ── Dialogs ──────────────────────────────────────────────────────────────── -->
{#if deleteTarget}
  <ConfirmDialog
    title={m.account_delete_confirm()}
    body={m.account_delete_body({ name: deleteTarget.name })}
    confirmLabel={m.common_delete()}
    loading={deleting}
    onconfirm={confirmDelete}
    oncancel={() => {
      deleteTarget = null;
      deleteError = null;
    }}
  />
{/if}

{#if authOpen}
  <AuthDialog onclose={() => (authOpen = false)} />
{/if}
