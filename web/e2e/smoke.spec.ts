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

async function mockBackend(
  page: Page,
  options: {
    savedSession?: boolean;
    profiles?: Profile[];
    syncPushError?: string;
  } = {},
): Promise<void> {
  const profiles = options.profiles ?? [primary];

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

    await route.fulfill({ status: 404, body: "" });
  });
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
