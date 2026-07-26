<script lang="ts">
  import * as m from "$lib/paraglide/messages.js";
  import type { ActivityStats } from "$lib/api";
  import { fmtHours } from "./utils";
  import * as Card from "$lib/components/ui/card/index.js";
  import { Flame, Trophy, Clock } from "lucide-svelte";

  let { activity }: { activity: ActivityStats } = $props();
</script>

<section class="grid grid-cols-3 gap-3">
  <Card.Root>
    <Card.Content class="flex flex-col items-center gap-1 p-5 text-center">
      <Flame class="mb-1 size-5 text-orange-400" />
      <div class="text-3xl font-bold tabular-nums">
        {activity.current_streak}
      </div>
      <div class="text-xs text-muted-foreground">{m.account_day_streak()}</div>
    </Card.Content>
  </Card.Root>

  <Card.Root>
    <Card.Content class="flex flex-col items-center gap-1 p-5 text-center">
      <Trophy class="mb-1 size-5 text-amber-400" />
      <div class="text-3xl font-bold tabular-nums">
        {activity.longest_streak}
      </div>
      <div class="text-xs text-muted-foreground">{m.account_longest_streak()}</div>
    </Card.Content>
  </Card.Root>

  <Card.Root>
    <Card.Content class="flex flex-col items-center gap-1 p-5 text-center">
      <Clock class="mb-1 size-5 text-indigo-400" />
      <div class="text-3xl font-bold tabular-nums">
        {fmtHours(activity.avg_seconds_per_active_day)}
      </div>
      <div class="text-xs text-muted-foreground">{m.account_average_active_day()}</div>
    </Card.Content>
  </Card.Root>
</section>
