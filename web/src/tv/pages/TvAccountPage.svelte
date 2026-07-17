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
      const profile = await api.profileActivate(id);
      const profs = await api.profilesList();
      auth.setProfiles(
        profs.profiles,
        profs.profiles.find((p) => p.id === profile.id) ?? profile,
      );
      libraryChanged.update((n) => n + 1);
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
        label: "Other",
        value: rest.reduce((a, g) => a + Math.abs(g.score), 0),
        color: palette[5],
      });
    }
    return top;
  }

  function mvSlices(s: LibraryStats): Slice[] {
    return [
      {
        label: "Movies",
        value: s.movie_share,
        color: palette[0],
        count: s.by_type.movie ?? 0,
      },
      {
        label: "TV",
        value: s.tv_share,
        color: palette[1],
        count: s.by_type.tv ?? 0,
      },
    ];
  }

  const statusMeta = [
    { key: "watching", label: "Watching" },
    { key: "finished", label: "Finished" },
    { key: "watch_later", label: "Watch later" },
    { key: "dropped", label: "Dropped" },
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
    { label: "Finished a title", value: "+1.5" },
    { label: "Watched to the end", value: "+1.0" },
    { label: "Currently watching", value: "+0.5" },
    { label: "Saved to watch later", value: "+0.5" },
    { label: "Each ★ above / below 3", value: "±1.5" },
    { label: "Dropped", value: "−2.0" },
    { label: "Not interested", value: "−2.0" },
  ];

  // ── Activity chart data ───────────────────────────────────────────────────────
  const MONTH_SHORT = [
    "Jan","Feb","Mar","Apr","May","Jun",
    "Jul","Aug","Sep","Oct","Nov","Dec",
  ];
  const DOW_SHORT = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];

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
          label:
            i === 0 ? "12a" : i < 12 ? `${i}a` : i === 12 ? "12p" : `${i - 12}p`,
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
        aria-label="Account section"
      ></div>

      <div class="flex flex-col gap-6">
        <header>
          <h1 class="text-4xl font-bold tracking-tight">My Account</h1>
          <p class="mt-1 text-lg text-white/50">
            Manage your account, profiles, and viewing insights.
          </p>
        </header>

        <!-- Account card -->
        <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-4">
          <h2 class="text-xl font-semibold">Account</h2>
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
                Sync now
              </button>
              <button
                type="button"
                use:focusable={{ groupId: "tv-account-actions" }}
                onclick={logout}
                class="flex items-center gap-2 rounded-xl bg-white/5 px-6 py-3 text-base font-medium text-white/60 transition-colors focus:bg-white/20 focus:text-white focus:outline-none"
              >
                <LogOut class="size-5" />
                Sign out
              </button>
            </div>
          {:else}
            <p class="text-base text-white/60">
              You're browsing as a guest. Sign in to sync your library across devices.
            </p>
            <button
              type="button"
              use:focusable
              onclick={() => (authOpen = true)}
              class="flex w-fit items-center gap-2 rounded-xl bg-white px-6 py-3 text-base font-semibold text-black transition-colors focus:bg-white/80 focus:outline-none"
            >
              <LogIn class="size-5" />
              Sign in / Create account
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
        aria-label="Profiles section"
      ></div>

      <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-4">
        <h2 class="text-xl font-semibold">Profiles</h2>
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
                      <span class="text-green-400">Active</span>
                    {/if}
                    {#if profile.is_primary}
                      <span class="rounded border border-white/20 px-2 py-0.5 text-xs text-white/40">
                        Primary
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
                  aria-label={`Delete profile ${profile.name}`}
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
        <p class="text-base text-red-300">Couldn't load your profile: {loadError}</p>
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
            aria-label="Activity overview section"
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
            aria-label="Viewing charts section"
          ></div>
          <div class="flex flex-col gap-6">
            <!-- By month (full width) -->
            <div class="rounded-2xl bg-white/5 p-6">
              <div class="mb-4 flex items-center gap-2">
                <BarChart3 class="size-5 text-white/60" />
                <h3 class="text-lg font-semibold">Hours by Month</h3>
                {#if monthSecondary}
                  <span class="ml-auto text-sm text-white/40">vs last year</span>
                {/if}
              </div>
              <ActivityBars items={monthItems} secondary={monthSecondary} />
            </div>

            <!-- DOW + hour side by side -->
            <div class="grid gap-6 md:grid-cols-2">
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">Hours by Day of Week</h3>
                <ActivityBars items={dowItems} />
              </div>
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">Hours by Hour of Day</h3>
                <ActivityBars items={hourItems} compact={true} />
              </div>
            </div>

            <!-- Year over year -->
            {#if showYearChart}
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">Year over Year</h3>
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
            aria-label="Library stats section"
          ></div>
          <div class="flex flex-col gap-6">
            <header>
              <h2 class="text-3xl font-bold">Your Insights</h2>
              <p class="mt-1 text-base text-white/50">
                Your watch history, taste profile, and what makes you unique.
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
              {@render statTile(String(stats.total), "in library")}
              {@render statTile(String(stats.by_status.finished ?? 0), "finished")}
              {@render statTile(
                stats.rated ? stats.avg_rating.toFixed(1) : "—",
                `avg rating (${stats.rated} rated)`,
              )}
              {@render statTile(String(stats.dismissed), "not interested")}
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
                    <p class="text-sm text-white/40">Not enough signal yet.</p>
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
                "What You Enjoy Most",
                mvSlices(stats),
                "Share of what you've finished or are watching.",
              )}
              {@render donutCard(
                "Watch Activity",
                statusSlices(stats),
                "Where your titles sit",
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
            aria-label="Titles watched this year section"
          ></div>
          <div class="rounded-2xl bg-white/5 p-6">
            <div class="mb-4 flex items-center gap-2">
              <Film class="size-5 text-white/60" />
              <h3 class="text-lg font-semibold">Titles watched this year</h3>
              <span class="ml-1 text-sm text-white/40">Your top picks by watch time</span>
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
            aria-label="Studio footprint section"
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
            aria-label="Genre breakdown section"
          ></div>
          <div class="grid gap-6 md:grid-cols-2">
            {#snippet genreDonut(title: string, slices: Slice[])}
              <div class="rounded-2xl bg-white/5 p-6">
                <h3 class="mb-4 text-lg font-semibold">{title}</h3>
                {#if slices.length === 0}
                  <p class="text-sm text-white/40">Not enough signal yet.</p>
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
            {@render genreDonut("Top Movie Genres", genreSlices(insights.top_movie_genres))}
            {@render genreDonut("Top TV Genres", genreSlices(insights.top_tv_genres))}
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
            aria-label="Taste signals section"
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
              aria-label="People section"
            ></div>
            <div class="rounded-2xl bg-white/5 p-6">
              <div class="mb-6 flex items-center gap-2">
                <Users class="size-5 text-white/60" />
                <h3 class="text-lg font-semibold">Cast &amp; crew you gravitate to</h3>
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
            aria-label="How recommendations work section"
          ></div>
          <div class="rounded-2xl bg-white/5 p-6 flex flex-col gap-5 text-base text-white/60">
            <div class="flex items-center gap-2">
              <Info class="size-5" />
              <h3 class="text-lg font-semibold text-white">How your recommendations are built</h3>
            </div>
            <p>
              Your profile is built from
              <span class="font-semibold text-white">{insights.signals_used}</span>
              titles you've actively engaged with. Each becomes a like/dislike weight,
              which is spread across that title's genres and keywords:
            </p>
            <div class="grid grid-cols-2 gap-x-8 gap-y-2">
              {#each weights as wgt (wgt.label)}
                <div class="flex items-center justify-between gap-2">
                  <span>{wgt.label}</span>
                  <span class="font-mono text-sm text-white">{wgt.value}</span>
                </div>
              {/each}
            </div>
            <p>
              Older signals fade over time — a favorite from a year ago still counts at
              roughly half strength, leveling off at a floor so a multi-year-old favorite
              is never fully forgotten.
            </p>
            <p>
              A single dropped or "not interested" title needs to be a much stronger signal
              before it steers you away from an entire genre; once two or more titles agree,
              a milder signal is enough. Rating a title below ★3 always counts as a
              dislike, even if you finished it.
            </p>
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
            aria-label="Empty insights section"
          ></div>
          <div class="flex flex-col items-center gap-4 rounded-2xl bg-white/5 px-10 py-20 text-center">
            <Sparkles class="size-10 text-white/30" />
            <p class="text-2xl font-semibold">Nothing to analyze yet</p>
            <p class="max-w-md text-base text-white/50">
              Watch a few titles and your stats will start appearing here. Finish,
              rate, or drop titles to build your taste profile.
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
    title="Delete profile?"
    body={`"${deleteTarget.name}" and all of its watch history, library and settings will be permanently deleted. This cannot be undone.`}
    confirmLabel="Delete"
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
