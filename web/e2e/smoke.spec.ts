import { expect, test, type Page } from "@playwright/test";

type Profile = {
  id: string;
  name: string;
  is_primary: boolean;
  name_updated_at: string;
};

const primary: Profile = {
  id: "profile-1",
  name: "Primary",
  is_primary: true,
  name_updated_at: "2026-01-01T00:00:00Z",
};

const secondary: Profile = {
  id: "profile-2",
  name: "Secondary",
  is_primary: false,
  name_updated_at: "2026-01-01T00:00:00Z",
};

function searchTitle(id: number, type: "movie" | "tv", popularity: number) {
  return {
    id,
    title: type === "movie" ? `Movie ${id}` : "",
    name: type === "tv" ? `TV ${id}` : "",
    overview: "",
    release_date: "",
    first_air_date: "",
    poster_path: `https://images.test/${type}-${id}.jpg`,
    vote_average: popularity / 10,
    media_type: type,
    trailer_url: "",
    clip_urls: "",
    images: [],
    popularity,
  };
}

const unifiedSearchResults = {
  movies: [
    searchTitle(101, "movie", 10),
    searchTitle(102, "movie", 80),
    searchTitle(103, "movie", 30),
    searchTitle(104, "movie", 60),
  ],
  tv: [
    searchTitle(201, "tv", 20),
    searchTitle(202, "tv", 70),
    searchTitle(203, "tv", 40),
    searchTitle(204, "tv", 50),
  ],
  people: [
    {
      id: 301,
      name: "Search Person",
      profile_path: "",
      known_for_department: "Acting",
      popularity: 10,
      known_for: [],
    },
  ],
  providers: [
    {
      provider_id: 401,
      provider_name: "Search Provider",
      logo_path: "",
      display_priority: 1,
    },
  ],
  title_order: [
    "tv:201",
    "movie:101",
    "tv:202",
    "movie:102",
    "tv:203",
    "movie:103",
    "tv:204",
    "movie:104",
  ],
};

async function mockBackend(
  page: Page,
  options: {
    savedSession?: boolean;
    profiles?: Profile[];
    syncPushError?: string;
  } = {},
): Promise<void> {
  const profiles = (options.profiles ?? [primary]).map((profile) => ({
    ...profile,
  }));

  await page.route("http://127.0.0.1:6969/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;

    if (path === "/api/profiles" && request.method() === "GET") {
      await route.fulfill({
        json: { profiles, active_profile_id: primary.id },
      });
      return;
    }
    if (path === "/api/client-session") {
      if (request.method() !== "GET") {
        await route.fulfill({ status: 204, body: "" });
      } else if (options.savedSession) {
        await route.fulfill({
          json: {
            accessToken: "saved-access-token",
            refreshToken: "saved-refresh-token",
            email: "saved@example.com",
          },
        });
      } else {
        await route.fulfill({ status: 404, body: "" });
      }
      return;
    }
    if (path === "/api/settings") {
      await route.fulfill({ json: { onboardingDone: true } });
      return;
    }
    if (path === "/api/update/check") {
      await route.fulfill({ json: { available: false } });
      return;
    }
    if (path === "/api/auth/login") {
      await route.fulfill({
        json: {
          access_token: "new-access-token",
          refresh_token: "new-refresh-token",
          profiles: [primary],
          active: primary,
        },
      });
      return;
    }
    if (path === "/api/auth/sync") {
      await route.fulfill({
        json: {
          status: "ok",
          library_generation: 1,
          push_error: options.syncPushError ?? "",
        },
      });
      return;
    }
    if (path === `/api/profiles/${secondary.id}/activate`) {
      await route.fulfill({ json: secondary });
      return;
    }
    if (path.startsWith("/api/profiles/") && request.method() === "PATCH") {
      const id = path.slice("/api/profiles/".length);
      const profile = profiles.find((candidate) => candidate.id === id);
      const body = request.postDataJSON() as { name?: string };
      if (!profile || !body.name?.trim()) {
        await route.fulfill({ status: 400, body: "name required" });
        return;
      }
      profile.name = body.name.trim();
      await route.fulfill({ json: { id, name: profile.name } });
      return;
    }
    if (
      path === "/api/discover" ||
      path === "/api/library" ||
      path === "/api/catalogs"
    ) {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === "/api/discover/insights") {
      await route.fulfill({
        json: {
          signals_used: 0,
          top_movie_genres: [],
          top_tv_genres: [],
          top_keywords: [],
        },
      });
      return;
    }
    if (path === "/api/library/stats") {
      await route.fulfill({ json: { movie_share: 0, tv_share: 0 } });
      return;
    }
    if (path === "/api/search/multi") {
      await route.fulfill({ json: unifiedSearchResults });
      return;
    }
    if (path === "/api/keywords") {
      await route.fulfill({
        json: [
          { id: 501, name: "Time Travel" },
          { id: 502, name: "Science Fiction" },
        ],
      });
      return;
    }
    if (path === "/api/images") {
      await route.fulfill({
        json: { backdrops: [], logos: [], posters: [] },
      });
      return;
    }
    if (path === "/api/quality/batch") {
      await route.fulfill({
        status: 200,
        contentType: "application/x-ndjson",
        body: "",
      });
      return;
    }

    await route.fulfill({ status: 404, body: "" });
  });
}

async function expectCategorizedSearchOrder(page: Page): Promise<void> {
  const sections = page.locator("[data-search-section]:visible");
  await expect(sections).toHaveCount(5);
  expect(
    await sections.evaluateAll((elements) =>
      elements.map((element) => element.getAttribute("data-search-section")),
    ),
  ).toEqual(["top-results", "people", "providers", "movies", "tv"]);
}

async function fillSearch(page: Page, mode: "desktop" | "mobile" | "tv") {
  if (mode === "desktop") {
    const input = page.getByPlaceholder("Search...");
    await page.getByRole("search").hover();
    await expect(input).toBeEnabled();
    await input.fill("unified");
  } else {
    await page.getByLabel("Search", { exact: true }).click();
    await page.getByPlaceholder("Search movies & TV…").fill("unified");
  }
  await expect(
    page.getByRole("heading", { name: "Top Results", exact: true }),
  ).toBeVisible();
}

test("desktop shell boots with an unavailable optional backend", async ({
  page,
}) => {
  const errors: Error[] = [];
  page.on("pageerror", (error) => errors.push(error));
  await mockBackend(page);

  await page.goto("/");

  await expect(page).toHaveTitle("Cove");
  await expect(page.getByLabel("Account")).toBeVisible({ timeout: 30_000 });
  expect(errors).toEqual([]);
});

test("a guest can sign in and persist the client session", async ({ page }) => {
  await mockBackend(page);
  await page.goto("/");

  await page.getByLabel("Account").click();
  await page.getByRole("button", { name: "Sign in / Create account" }).click();
  await page.getByRole("button", { name: "Sign in with password" }).click();
  await page.getByPlaceholder("Email").fill("user@example.com");
  await page.getByPlaceholder("Password").fill("correct horse battery staple");

  const sessionSave = page.waitForRequest(
    (request) =>
      request.url().endsWith("/api/client-session") &&
      request.method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await sessionSave;

  await page.getByLabel("Account").click();
  await expect(
    page.getByText("user@example.com", { exact: true }).last(),
  ).toBeVisible();
});

test("switching profile calls the activation endpoint", async ({ page }) => {
  await mockBackend(page, {
    savedSession: true,
    profiles: [primary, secondary],
  });
  await page.goto("/");
  await page.getByLabel("Account").click();

  const activation = page.waitForRequest(
    (request) =>
      request.url().endsWith(`/api/profiles/${secondary.id}/activate`) &&
      request.method() === "POST",
  );
  await page.getByRole("button", { name: /Secondary/ }).click();

  expect((await activation).method()).toBe("POST");
});

test("My Account uses explicit profile actions and supports renaming", async ({
  page,
}) => {
  let activationRequests = 0;
  page.on("request", (request) => {
    if (
      request.url().endsWith(`/api/profiles/${secondary.id}/activate`) &&
      request.method() === "POST"
    ) {
      activationRequests += 1;
    }
  });

  await mockBackend(page, {
    savedSession: true,
    profiles: [primary, secondary],
  });
  await page.goto("/");
  await page.getByLabel("Account").click();
  await page.getByRole("button", { name: "Manage account & insights" }).click();
  await expect(page.getByRole("heading", { name: "My Account" })).toBeVisible();

  const secondaryRow = page.getByRole("listitem", {
    name: "Profile Secondary",
  });
  await expect(
    secondaryRow.getByRole("button", { name: "Switch to Secondary" }),
  ).toBeVisible();

  await secondaryRow.getByText("Secondary", { exact: true }).click();
  expect(activationRequests).toBe(0);

  await secondaryRow.getByRole("button", { name: "Rename Secondary" }).click();
  await secondaryRow
    .getByRole("textbox", { name: "Profile name for Secondary" })
    .fill("Family");

  const rename = page.waitForRequest(
    (request) =>
      request.url().endsWith(`/api/profiles/${secondary.id}`) &&
      request.method() === "PATCH",
  );
  await secondaryRow.getByRole("button", { name: "Save" }).click();
  expect((await rename).postDataJSON()).toEqual({ name: "Family" });

  const familyRow = page.getByRole("listitem", { name: "Profile Family" });
  await expect(familyRow).toBeVisible();

  const activation = page.waitForRequest(
    (request) =>
      request.url().endsWith(`/api/profiles/${secondary.id}/activate`) &&
      request.method() === "POST",
  );
  await familyRow.getByRole("button", { name: "Switch to Family" }).click();
  await activation;
  expect(activationRequests).toBe(1);
});

test("a restored session surfaces a deduplicated sync push error", async ({
  page,
}) => {
  await mockBackend(page, {
    savedSession: true,
    syncPushError: "remote upload rejected",
  });

  await page.goto("/");

  await expect(
    page.getByText("Sync issue: some data failed to upload"),
  ).toBeVisible({ timeout: 30_000 });
});

test("desktop search leads with six unified results and keeps full categories", async ({
  page,
}) => {
  await mockBackend(page);
  await page.goto("/");
  await fillSearch(page, "desktop");

  await expectCategorizedSearchOrder(page);
  const grid = page.locator(
    '[data-search-section="top-results"]:visible [data-search-grid="top-results"]',
  );
  await expect(grid).toHaveClass(/xl:grid-cols-6/);
  await expect(grid.locator(":scope > *")).toHaveCount(6);
  const posters = grid.locator("img");
  await expect(posters).toHaveCount(6);
  expect(
    await posters.evaluateAll((images) => images.map((image) => image.alt)),
  ).toEqual([
    "TV 201",
    "Movie 101",
    "TV 202",
    "Movie 102",
    "TV 203",
    "Movie 103",
  ]);

  await page.getByRole("button", { name: "Relevance", exact: true }).click();
  await page.getByRole("option", { name: "Popularity" }).click();
  await expect(posters.first()).toHaveAttribute("alt", "Movie 102");
  await expect(posters).toHaveCount(6);
  expect(
    await posters.evaluateAll((images) => images.map((image) => image.alt)),
  ).toEqual([
    "Movie 102",
    "TV 202",
    "Movie 104",
    "TV 204",
    "TV 203",
    "Movie 103",
  ]);

  await page.getByLabel("Movies", { exact: true }).click();
  await page.getByLabel("TV", { exact: true }).click();
  await expect(
    page.locator('[data-search-section="top-results"]:visible'),
  ).toHaveCount(0);
  await expect(
    page.locator('[data-search-section="people"]:visible'),
  ).toHaveCount(1);
  await expect(
    page.locator('[data-search-section="providers"]:visible'),
  ).toHaveCount(1);
});

test("Android search renders the unified six as a three-column grid", async ({
  page,
}) => {
  await mockBackend(page);
  await page.goto("/?mobile=1");
  await fillSearch(page, "mobile");

  await expectCategorizedSearchOrder(page);
  const grid = page.locator(
    '[data-search-section="top-results"]:visible [data-search-grid="top-results"]',
  );
  await expect(grid).toHaveClass(/grid-cols-3/);
  await expect(grid.locator(":scope > *")).toHaveCount(6);
});

test("TV search navigates filters, suggestions, top results, and result rows", async ({
  page,
}) => {
  await mockBackend(page);
  await page.goto("/?tv=1");
  await fillSearch(page, "tv");

  await expectCategorizedSearchOrder(page);
  const input = page.getByPlaceholder("Search movies & TV…");
  const filters = page.locator('[data-tv-focus-group="search-filters"] button');
  const suggestions = page.locator(
    '[data-tv-focus-group="search-suggestions"] button',
  );
  const grid = page.locator(
    '[data-search-section="top-results"]:visible [data-search-grid="top-results"]',
  );
  const cards = grid.locator("[data-tv-focusable]");
  await expect(grid).toHaveClass(/grid-cols-6/);
  await expect(cards).toHaveCount(6);

  await expect(input).toBeFocused();
  await page.keyboard.press("ArrowDown");
  const enteredFilterIndex = await filters.evaluateAll((elements) =>
    elements.findIndex((element) => element === document.activeElement),
  );
  expect(enteredFilterIndex).toBeGreaterThanOrEqual(0);
  for (let index = enteredFilterIndex - 1; index >= 0; index -= 1) {
    await page.keyboard.press("ArrowLeft");
    await expect(filters.nth(index)).toBeFocused();
  }
  for (let index = 1; index < 4; index += 1) {
    await page.keyboard.press("ArrowRight");
    await expect(filters.nth(index)).toBeFocused();
  }
  for (let index = 2; index >= 0; index -= 1) {
    await page.keyboard.press("ArrowLeft");
    await expect(filters.nth(index)).toBeFocused();
  }

  await page.keyboard.press("ArrowDown");
  await expect(suggestions.nth(0)).toBeFocused();
  await page.keyboard.press("ArrowRight");
  await expect(suggestions.nth(1)).toBeFocused();

  await page.keyboard.press("ArrowDown");
  expect(
    await cards.evaluateAll((elements) =>
      elements.some((element) => element === document.activeElement),
    ),
  ).toBe(true);

  await page.keyboard.press("ArrowDown");
  await expect(
    page.getByRole("button", { name: "Search Person", exact: true }),
  ).toBeFocused();

  await page.keyboard.press("ArrowDown");
  await expect(
    page.getByRole("button", { name: "Search Provider", exact: true }),
  ).toBeFocused();
});
