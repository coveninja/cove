import {
  api,
  type ActivityStats,
  type DiscoverInsights,
  type LibraryStats,
  type Person,
  type Taste,
} from "$lib/api";
import * as m from "$lib/paraglide/messages.js";

export interface PeopleSlot {
  id: number;
  name: string;
  person: Person | null;
}

export interface InsightSlice {
  label: string;
  value: number;
  color: string;
  count?: number;
}

const palette = [
  "#6366f1",
  "#ec4899",
  "#22c55e",
  "#f59e0b",
  "#06b6d4",
  "#94a3b8",
];

export class InsightsController {
  stats = $state<LibraryStats | null>(null);
  insights = $state<DiscoverInsights | null>(null);
  activity = $state<ActivityStats | null>(null);
  peopleSlots = $state<PeopleSlot[]>([]);
  loading = $state(true);
  loadError = $state<string | null>(null);

  #initialLoadDone = false;
  #loadSequence = 0;

  async load(): Promise<void> {
    const sequence = ++this.#loadSequence;
    this.loadError = null;
    try {
      const [stats, insights, activity] = await Promise.all([
        api.libraryStats(),
        api.discoverInsights(),
        api.activityStats().catch(() => null as ActivityStats | null),
      ]);
      if (sequence !== this.#loadSequence) return;
      this.stats = stats;
      this.insights = insights;
      this.activity = activity;
      this.#loadPeople(insights.top_people, sequence);
    } catch (error) {
      if (sequence === this.#loadSequence) {
        this.loadError = error instanceof Error ? error.message : String(error);
      }
    } finally {
      if (sequence === this.#loadSequence && !this.#initialLoadDone) {
        this.#initialLoadDone = true;
        this.loading = false;
      }
    }
  }

  #loadPeople(tastes: Taste[], sequence: number): void {
    this.peopleSlots = tastes.map((taste) => ({
      id: taste.id,
      name: taste.name,
      person: null,
    }));
    for (const taste of tastes) {
      api
        .getPerson(taste.id)
        .then((details) => {
          if (sequence !== this.#loadSequence) return;
          const index = this.peopleSlots.findIndex(
            (slot) => slot.id === taste.id,
          );
          if (index === -1) return;
          this.peopleSlots[index] = {
            ...this.peopleSlots[index],
            person: {
              id: details.id,
              name: details.name,
              profile_path: details.profile_path,
              known_for_department: details.known_for_department,
              popularity: 0,
              known_for: [],
            },
          };
        })
        .catch(() => {});
    }
  }
}

export function displayInsightPerson(slot: PeopleSlot): Person {
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

export function insightConicGradient(slices: InsightSlice[]): string {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0) || 1;
  let accumulated = 0;
  const stops = slices.map((slice) => {
    const start = (accumulated / total) * 100;
    accumulated += slice.value;
    const end = (accumulated / total) * 100;
    return `${slice.color} ${start}% ${end}%`;
  });
  return `conic-gradient(${stops.join(", ")})`;
}

export function insightGenreSlices(tastes: Taste[]): InsightSlice[] {
  const slices = tastes.slice(0, 5).map((taste, index) => ({
    label: taste.name,
    value: Math.abs(taste.score),
    color: palette[index],
  }));
  const rest = tastes.slice(5);
  if (rest.length > 0) {
    slices.push({
      label: m.account_other(),
      value: rest.reduce((sum, taste) => sum + Math.abs(taste.score), 0),
      color: palette[5],
    });
  }
  return slices;
}

export function insightMediaSlices(stats: LibraryStats): InsightSlice[] {
  return [
    {
      label: m.my_list_movies(),
      value: stats.movie_share,
      color: palette[0],
      count: stats.by_type.movie ?? 0,
    },
    {
      label: m.my_list_shows(),
      value: stats.tv_share,
      color: palette[1],
      count: stats.by_type.tv ?? 0,
    },
  ];
}

export function insightStatusSlices(stats: LibraryStats): InsightSlice[] {
  const statuses = [
    { key: "watching", label: m.my_list_watching() },
    { key: "finished", label: m.my_list_finished() },
    { key: "watch_later", label: m.my_list_watch_later() },
    { key: "dropped", label: m.my_list_dropped() },
  ];
  return statuses
    .map((status, index) => ({
      label: status.label,
      value: stats.by_status[status.key] ?? 0,
      color: palette[index],
      count: stats.by_status[status.key] ?? 0,
    }))
    .filter((slice) => slice.value > 0);
}

export function insightWeights(): { label: string; value: string }[] {
  return [
    { label: m.account_weight_finished(), value: "+1.5" },
    { label: m.account_weight_watched_end(), value: "+1.0" },
    { label: m.account_weight_watching(), value: "+0.5" },
    { label: m.account_weight_watch_later(), value: "+0.5" },
    { label: m.account_weight_rating(), value: "±1.5" },
    { label: m.account_weight_dropped(), value: "−2.0" },
    { label: m.account_weight_not_interested(), value: "−2.0" },
  ];
}
