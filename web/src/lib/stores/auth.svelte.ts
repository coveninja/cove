import type { Profile, AuthSession } from "$lib/types/auth";
import { supabase } from "$lib/supabase";
import { api } from "$lib/api";

export class AuthStore {
  session = $state<AuthSession | null>(null);
  profiles = $state<Profile[]>([]);
  activeProfile = $state<Profile | null>(null);

  // Private: JWT for injection into API requests.
  #token = $state<string | null>(null);

  // Guards init() against running twice concurrently (e.g. a second onMount
  // somewhere) — profilesList/clientSessionGet aren't safe to fire in flight
  // twice, and onAuthStateChange would end up registered more than once.
  #initialized = false;

  // Last access token actually persisted via clientSessionSave. Restoring a
  // session in init() calls supabase.auth.setSession(), which immediately
  // fires onAuthStateChange with that exact same session — without this,
  // that handler would needlessly re-save the identical token.
  #lastSavedToken: string | null = null;

  // Handle for the onAuthStateChange subscription so we can unsubscribe
  // before re-registering if init() is somehow called more than once.
  #authSubscription: { unsubscribe: () => void } | null = null;

  get isGuest(): boolean {
    return this.session === null;
  }

  get authToken(): string | null {
    return this.#token;
  }

  async init(): Promise<void> {
    if (this.#initialized) return;
    this.#initialized = true;

    try {
      const data = await api.profilesList();
      this.profiles = data.profiles;
      this.activeProfile =
        data.profiles.find((p) => p.id === data.active_profile_id) ??
        data.profiles[0] ??
        null;
    } catch (e) {
      console.error("[auth] init: load profiles:", e);
    }

    // Restore session from the Go backend's persistent file store.
    // More reliable than Qt WebEngine localStorage, which may be in-memory.
    try {
      const saved = await api.clientSessionGet();
      console.log("[auth] init: restoring session for", saved.email);
      this.#token = saved.accessToken;
      this.session = { accessToken: saved.accessToken, email: saved.email };
      this.#lastSavedToken = saved.accessToken;

      // Hand to Supabase JS for token refresh management. Await this before
      // init() resolves so startup sync cannot race an expired persisted
      // access token. setSession() returns the refreshed session when it had
      // to rotate the token, so apply and persist that result directly before
      // registering the ongoing auth-state listener below.
      if (supabase) {
        try {
          const result = await supabase.auth.setSession({
            access_token: saved.accessToken,
            refresh_token: saved.refreshToken,
          });
          if (result.error) {
            console.error(
              "[auth] init: supabase.auth.setSession failed:",
              result.error,
            );
          }
          const refreshed = result.data?.session;
          if (refreshed) {
            const email = refreshed.user.email ?? saved.email;
            this.#token = refreshed.access_token;
            this.session = {
              accessToken: refreshed.access_token,
              email,
            };
            if (
              refreshed.access_token !== saved.accessToken ||
              refreshed.refresh_token !== saved.refreshToken
            ) {
              api.clearInflight();
              await api
                .clientSessionSave({
                  accessToken: refreshed.access_token,
                  refreshToken: refreshed.refresh_token,
                  email,
                })
                .catch(console.error);
            }
            this.#lastSavedToken = refreshed.access_token;
          }
        } catch (e) {
          console.error("[auth] init: supabase.auth.setSession failed:", e);
        }
      }
    } catch {
      console.log("[auth] init: no persisted session");
    }

    if (!supabase) return;

    // Unsubscribe any existing listener before registering a new one — prevents
    // duplicate handlers if init() is somehow re-entered.
    this.#authSubscription?.unsubscribe();

    // Keep the backend file in sync when Supabase refreshes the access token.
    // Only clear on explicit SIGNED_OUT.
    const { data: { subscription } } = supabase.auth.onAuthStateChange((event, s) => {
      console.log(`[auth] onAuthStateChange: event=${event}, session=${s ? s.user.email : "null"}`);
      if (s) {
        this.#token = s.access_token;
        this.session = { accessToken: s.access_token, email: s.user.email ?? "" };
        // Clear coalesced GET responses so the new token's identity can't
        // receive a response that was in-flight for the previous one.
        api.clearInflight();
        // The setSession() call above (restoring a persisted session) fires
        // this handler immediately with the identical token — skip the
        // redundant re-save; only a genuine refresh should write again.
        if (s.access_token === this.#lastSavedToken) return;
        this.#lastSavedToken = s.access_token;
        api.clientSessionSave({
          accessToken: s.access_token,
          refreshToken: s.refresh_token,
          email: s.user.email ?? "",
        }).catch(console.error);
      } else if (event === "SIGNED_OUT") {
        this.#token = null;
        this.session = null;
        this.#lastSavedToken = null;
        api.clearInflight();
        api.clientSessionDelete().catch(console.error);
      }
    });
    this.#authSubscription = subscription;
  }

  async setSession(
    accessToken: string,
    email: string,
    profs: Profile[],
    active: Profile,
    refreshToken?: string,
  ): Promise<void> {
    this.#token = accessToken;
    this.session = { accessToken, email };
    this.profiles = profs;
    this.activeProfile = active;
    // Clear coalesced GET responses so in-flight fetches from the previous
    // session can't land under the new identity.
    api.clearInflight();
    if (refreshToken) {
      console.log("[auth] setSession: saving session for", email);
      await api.clientSessionSave({ accessToken, refreshToken, email });
      this.#lastSavedToken = accessToken;
      console.log("[auth] setSession: session saved");
      // Also tell Supabase JS so it can set up its refresh timer. This fires
      // onAuthStateChange with the same token we just saved above; #lastSavedToken
      // being set already skips the redundant re-save there.
      if (supabase) {
        supabase.auth
          .setSession({ access_token: accessToken, refresh_token: refreshToken })
          .catch(console.error);
      }
    } else {
      console.warn("[auth] setSession: no refreshToken — session will not persist");
    }
  }

  setProfiles(profs: Profile[], active: Profile): void {
    this.profiles = profs;
    this.activeProfile = active;
    // A profile switch changes which dataset the backend serves — clear
    // coalesced responses so the new profile's requests hit the network fresh.
    api.clearInflight();
  }

  async logout(): Promise<void> {
    this.#token = null;
    this.session = null;
    this.#lastSavedToken = null;
    api.clearInflight();
    await api.clientSessionDelete().catch(console.error);
    if (supabase) await supabase.auth.signOut();
  }
}

export const auth = new AuthStore();
