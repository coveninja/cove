<script lang="ts">
  import { House, Bookmark, Flame, Search, CircleUser } from "lucide-svelte";
  import type { ComponentType } from "svelte";
  import type { Page } from "$lib/types/types";
  import { animate } from "animejs";

  let {
    currentPage,
    onSelectPage,
  }: {
    currentPage: Page;
    onSelectPage: (page: Page) => void;
  } = $props();

  type Tab = {
    label: string;
    makePage: () => Page;
    /** Page types that should light up this tab */
    activeFor: Page["type"][];
    Icon: ComponentType;
  };

  const tabs: Tab[] = [
    {
      label: "Home",
      makePage: () => ({ type: "home" }),
      activeFor: ["home"],
      Icon: House,
    },
    {
      label: "My List",
      makePage: () => ({ type: "myList" }),
      activeFor: ["myList"],
      Icon: Bookmark,
    },
    {
      label: "Explore",
      makePage: () => ({ type: "explore" }),
      activeFor: ["explore"],
      Icon: Flame,
    },
    {
      label: "Search",
      makePage: () => ({ type: "query", query: "" }),
      activeFor: ["query"],
      Icon: Search,
    },
    {
      label: "Account",
      makePage: () => ({ type: "account" }),
      // Settings page highlights the Account tab so tapping Account is
      // always available even while the user is browsing settings.
      activeFor: ["account", "settings"],
      Icon: CircleUser,
    },
  ];

  let iconEls: (HTMLElement | undefined)[] = $state([]);

  function isActive(tab: Tab): boolean {
    return tab.activeFor.includes(currentPage.type);
  }
</script>

<nav
  class="fixed inset-x-0 bottom-0 z-40 border-t border-border bg-background/95 backdrop-blur-sm"
  style="padding-bottom: var(--safe-bottom);"
>
  <div class="flex">
    {#each tabs as tab, i (tab.label)}
      {@const active = isActive(tab)}
      {@const Icon = tab.Icon}
      <button
        type="button"
        class="flex flex-1 flex-col items-center justify-center gap-[2px] py-3 text-[10px] transition-[color,opacity] active:opacity-70 {active
          ? 'text-accent'
          : 'text-muted-foreground'}"
        onclick={() => {
          if (!active) {
            const el = iconEls[i];
            if (el) animate(el, { scale: [1, 1.35, 1], duration: 300, ease: "outBack" });
          }
          onSelectPage(tab.makePage());
        }}
        aria-label={tab.label}
        aria-current={active ? 'page' : undefined}
      >
        <span bind:this={iconEls[i]}>
          <Icon class="size-5" />
        </span>
        <span>{tab.label}</span>
        <span
          class="h-1 w-1 rounded-full bg-accent transition-opacity duration-150 {active
            ? 'opacity-100'
            : 'opacity-0'}"
        ></span>
      </button>
    {/each}
  </div>
</nav>
