<script lang="ts">
  import { tick } from "svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import {
    InputOTP,
    InputOTPGroup,
    InputOTPSlot,
    InputOTPSeparator,
  } from "$lib/components/ui/input-otp/index.js";
  import { api } from "$lib/api";
  import { auth } from "$lib/stores/auth.svelte";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { libraryChanged } from "$lib/stores/library";
  import { settings } from "$lib/stores/settings";
  import { focusFirst } from "../focus/focusStore.svelte";

  let { ondone }: { ondone: () => void } = $props();

  // No "success" view — the account step's connected-account card serves as
  // the success state after ondone() flips authOpen false.
  type View = "choose" | "login" | "otp-email" | "otp-code" | "register" | "register-otp";
  let view = $state<View>("choose");
  let loading = $state(false);
  let error = $state("");

  // Form fields
  let email = $state("");
  let password = $state("");
  let profileName = $state("");
  let otpCode = $state("");
  let otpEmail = $state("");

  // Pending registration — carried from register view into register-otp view
  let pendingEmail = $state("");
  let pendingPassword = $state("");
  let pendingProfileName = $state("");

  function reset(): void {
    email = "";
    password = "";
    profileName = "";
    otpCode = "";
    otpEmail = "";
    error = "";
    loading = false;
  }

  function setView(v: View): void {
    reset();
    view = v;
  }

  // ── Auth operations (identical logic to AuthDialog, ondone() replaces onclose()) ──

  async function login(): Promise<void> {
    if (!email || !password) { error = "Email and password are required."; return; }
    loading = true; error = "";
    try {
      const res = await api.authLogin(email, password);
      await auth.setSession(res.access_token, email, res.profiles, res.active, res.refresh_token);
      await settings.load();
      libraryChanged.update((n) => n + 1);
      ondone();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function sendOTP(): Promise<void> {
    if (!email) { error = "Email is required."; return; }
    loading = true; error = "";
    try {
      await api.authSendOTP(email);
      otpEmail = email;
      view = "otp-code";
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function verifyOTP(): Promise<void> {
    if (loading || !otpCode || otpCode.length < 8) return;
    loading = true; error = "";
    try {
      const res = await api.authVerifyOTP(otpEmail, otpCode);
      await auth.setSession(res.access_token, otpEmail, res.profiles, res.active, res.refresh_token);
      await settings.load();
      libraryChanged.update((n) => n + 1);
      ondone();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      otpCode = "";
    } finally {
      loading = false;
    }
  }

  async function register(): Promise<void> {
    if (!email || !password) { error = "Email and password are required."; return; }
    loading = true; error = "";
    try {
      api.authRegister(email, password, profileName || undefined).then((e) => {
        console.log(e);
      });
      pendingEmail = email;
      pendingPassword = password;
      pendingProfileName = profileName;
      otpCode = "";
      error = "";
      loading = false;
      view = "register-otp";
      return;
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function confirmRegistration(): Promise<void> {
    if (loading || !otpCode || otpCode.length < 8) return;
    loading = true; error = "";
    try {
      const res = await api.authConfirmRegister(
        pendingEmail,
        otpCode,
        pendingPassword,
        pendingProfileName || undefined,
      );
      await auth.setSession(res.access_token, pendingEmail, [res.profile], res.profile, res.refresh_token);
      await settings.load();
      libraryChanged.update((n) => n + 1);
      // Skip "success" view — call ondone() directly; the account step's
      // connected-account card serves as the success confirmation.
      ondone();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      otpCode = "";
    } finally {
      loading = false;
    }
  }

  // ── Exported back-navigation for the page's Escape handler ───────────────────
  // Returns true if it handled the Escape (moved to a previous sub-view);
  // returns false if already at "choose" (caller should close the panel).
  export function escapeBack(): boolean {
    if (view === "otp-code") {
      // Assign AFTER setView — its reset() wipes email/otpEmail.
      const prevEmail = otpEmail;
      setView("otp-email");
      email = prevEmail;
      return true;
    }
    if (view === "register-otp") {
      setView("register");
      email = pendingEmail;
      profileName = pendingProfileName;
      return true;
    }
    if (view === "login" || view === "otp-email" || view === "register") {
      setView("choose");
      return true;
    }
    // Already at "choose"
    return false;
  }

  // ── Focus rescue on sub-view change ──────────────────────────────────────────
  let panelRoot = $state<HTMLElement | null>(null);

  $effect(() => {
    // Reactive dependency: re-run when view changes.
    const _view = view;
    void tick().then(() => {
      const active = document.activeElement;
      if (!active || !active.isConnected || active === document.body) {
        if (panelRoot) focusFirst(panelRoot);
      }
    });
  });
</script>

<!--
  Inline TV auth panel — no fixed/overlay positioning; renders in document flow
  inside the onboarding account step. The parent's focus trap (tv-onboarding
  group) contains it, so D-pad navigation stays inside the overlay naturally.
-->
<div bind:this={panelRoot} class="w-full max-w-xl">

  {#if view === "choose"}
    <h3 class="mb-2 text-2xl font-semibold">Sign in to Cove</h3>
    <p class="mb-6 text-base text-muted-foreground">Sync your library across devices</p>
    <div class="flex flex-col gap-3">
      <Button variant="default" class="h-12 w-full text-lg" onclick={() => setView("login")}>
        Sign in with password
      </Button>
      <Button variant="outline" class="h-12 w-full text-lg" onclick={() => setView("otp-email")}>
        Sign in with email code
      </Button>
      <div class="relative my-1 flex items-center">
        <div class="flex-1 border-t border-border"></div>
        <span class="mx-3 text-sm text-muted-foreground">or</span>
        <div class="flex-1 border-t border-border"></div>
      </div>
      <Button variant="ghost" class="h-12 w-full text-lg" onclick={() => setView("register")}>
        Create account
      </Button>
    </div>

  {:else if view === "login"}
    <Button variant="link" class="mb-4 h-auto p-0 text-base text-muted-foreground" onclick={() => setView("choose")}>
      ← Back
    </Button>
    <h3 class="mb-6 text-2xl font-semibold">Sign in</h3>
    <div class="flex flex-col gap-4">
      <input
        type="email"
        placeholder="Email"
        bind:value={email}
        onkeydown={(e) => e.key === "Enter" && login()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="password"
        placeholder="Password"
        bind:value={password}
        onkeydown={(e) => e.key === "Enter" && login()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={login} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        Sign in
      </Button>
      <Button variant="link" class="h-auto p-0 text-base text-muted-foreground" onclick={() => setView("otp-email")}>
        Sign in with email code instead
      </Button>
    </div>

  {:else if view === "otp-email"}
    <Button variant="link" class="mb-4 h-auto p-0 text-base text-muted-foreground" onclick={() => setView("choose")}>
      ← Back
    </Button>
    <h3 class="mb-2 text-2xl font-semibold">Sign in with email code</h3>
    <p class="mb-6 text-base text-muted-foreground">We'll send a one-time code to your inbox.</p>
    <div class="flex flex-col gap-4">
      <input
        type="email"
        placeholder="Email"
        bind:value={email}
        onkeydown={(e) => e.key === "Enter" && sendOTP()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={sendOTP} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        Send code
      </Button>
    </div>

  {:else if view === "otp-code"}
    <h3 class="mb-2 text-2xl font-semibold">Enter your code</h3>
    <p class="mb-6 text-base text-muted-foreground">
      Check your inbox at <strong>{otpEmail}</strong>
    </p>
    <div class="flex flex-col items-center gap-5">
      <InputOTP maxlength={8} bind:value={otpCode} onComplete={verifyOTP}>
        {#snippet children({ cells })}
          <InputOTPGroup>
            <InputOTPSlot cell={cells[0]} />
            <InputOTPSlot cell={cells[1]} />
            <InputOTPSlot cell={cells[2]} />
            <InputOTPSlot cell={cells[3]} />
          </InputOTPGroup>
          <InputOTPSeparator />
          <InputOTPGroup>
            <InputOTPSlot cell={cells[4]} />
            <InputOTPSlot cell={cells[5]} />
            <InputOTPSlot cell={cells[6]} />
            <InputOTPSlot cell={cells[7]} />
          </InputOTPGroup>
        {/snippet}
      </InputOTP>
      {#if error}<p class="text-sm text-destructive text-center">{error}</p>{/if}
      {#if loading}
        <div class="flex items-center gap-2 text-base text-muted-foreground">
          <Spinner class="size-5" /> Verifying…
        </div>
      {:else}
        <Button class="h-12 w-full text-lg" onclick={verifyOTP} disabled={otpCode.length < 8}>
          Verify
        </Button>
      {/if}
      <Button
        variant="link"
        class="h-auto p-0 text-base text-muted-foreground"
        onclick={() => { const prevEmail = otpEmail; setView("otp-email"); email = prevEmail; }}
      >
        Resend code
      </Button>
    </div>

  {:else if view === "register"}
    <Button variant="link" class="mb-4 h-auto p-0 text-base text-muted-foreground" onclick={() => setView("choose")}>
      ← Back
    </Button>
    <h3 class="mb-6 text-2xl font-semibold">Create account</h3>
    <div class="flex flex-col gap-4">
      <input
        type="text"
        placeholder="Display name (optional)"
        bind:value={profileName}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="email"
        placeholder="Email"
        bind:value={email}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="password"
        placeholder="Password"
        bind:value={password}
        onkeydown={(e) => e.key === "Enter" && register()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={register} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        Create account
      </Button>
      <p class="text-sm text-muted-foreground">
        Your existing library will be synced to the new account.
      </p>
    </div>

  {:else if view === "register-otp"}
    <h3 class="mb-2 text-2xl font-semibold">Check your email</h3>
    <p class="mb-1 text-base text-muted-foreground">We sent a confirmation code to</p>
    <p class="mb-6 text-base font-medium">{pendingEmail}</p>
    <div class="flex flex-col items-center gap-5">
      <InputOTP maxlength={8} bind:value={otpCode} onComplete={confirmRegistration}>
        {#snippet children({ cells })}
          <InputOTPGroup>
            <InputOTPSlot cell={cells[0]} />
            <InputOTPSlot cell={cells[1]} />
            <InputOTPSlot cell={cells[2]} />
            <InputOTPSlot cell={cells[3]} />
          </InputOTPGroup>
          <InputOTPSeparator />
          <InputOTPGroup>
            <InputOTPSlot cell={cells[4]} />
            <InputOTPSlot cell={cells[5]} />
            <InputOTPSlot cell={cells[6]} />
            <InputOTPSlot cell={cells[7]} />
          </InputOTPGroup>
        {/snippet}
      </InputOTP>
      {#if error}<p class="text-sm text-destructive text-center">{error}</p>{/if}
      {#if loading}
        <div class="flex items-center gap-2 text-base text-muted-foreground">
          <Spinner class="size-5" /> Verifying…
        </div>
      {:else}
        <Button class="h-12 w-full text-lg" onclick={confirmRegistration} disabled={otpCode.length < 8}>
          Confirm account
        </Button>
      {/if}
      <Button
        variant="link"
        class="h-auto p-0 text-base text-muted-foreground"
        onclick={() => { setView("register"); email = pendingEmail; profileName = pendingProfileName; }}
      >
        ← Back to registration
      </Button>
    </div>
  {/if}

</div>
