<script lang="ts">
  import { House, Bookmark, Flame, Search, CircleUser, Settings } from "lucide-svelte";
  import type { ComponentType } from "svelte";
  import type { Page } from "$lib/types/types";
  import { focusable, focusGroup } from "../focus/actions";

  let {
    currentPage,
    onNavigate,
  }: {
    currentPage: Page;
    onNavigate: (page: Page) => void;
  } = $props();

  type Tab = {
    label: string;
    makePage: () => Page;
    /** Page types that should highlight this tab */
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
      label: "Settings",
      makePage: () => ({ type: "settings" }),
      activeFor: ["settings"],
      Icon: Settings,
    },
    {
      label: "Account",
      makePage: () => ({ type: "account" }),
      activeFor: ["account"],
      Icon: CircleUser,
    },
  ];

  function isActive(tab: Tab): boolean {
    return tab.activeFor.includes(currentPage.type);
  }
</script>
<nav
  class="group flex p-4 flex-col justify-center w-12 h-full transition-[width] duration-220 ease-[ease] focus-within:w-41"
  use:focusGroup={{ id: "side-nav", policy: { type: "column" } }}
  aria-label="Main navigation"
>
  {#each tabs as tab (tab.label)}
    {@const active = isActive(tab)}
    {@const Icon = tab.Icon}
    <button
      type="button"
      class="flex items-center gap-3.5 py-3 px-4 rounded-full w-full bg-transparent border-0 cursor-pointer whitespace-nowrap transition-colors duration-150 text-left focus:bg-accent/18 focus:text-accent {active ? 'text-accent' : 'text-[#666] hover:text-[#ccc]'}"
      use:focusable={{ groupId: "side-nav" }}
      onclick={() => onNavigate(tab.makePage())}
      aria-label={tab.label}
      aria-current={active ? "page" : undefined}
    >
      <Icon class="size-6 shrink-0" />
      <span class="text-[0.9rem] font-medium opacity-0 transition-opacity duration-150 delay-[80ms] group-focus-within:opacity-100">{tab.label}</span>
    </button>
  {/each}
</nav>
