<script lang="ts">
  import { X, Mail, Lock, User, CheckCircle } from "lucide-svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import { Input } from "$lib/components/ui/input/index.js";
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
  import * as m from "$lib/paraglide/messages.js";

  let { onclose }: { onclose: () => void } = $props();

  type View = "choose" | "login" | "otp-email" | "otp-code" | "register" | "register-otp" | "success";
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

  async function login(): Promise<void> {
    if (!email || !password) { error = m.auth_required_credentials(); return; }
    loading = true; error = "";
    try {
      const res = await api.authLogin(email, password);
      await auth.setSession(res.access_token, email, res.profiles, res.active, res.refresh_token);
      await settings.load();
      libraryChanged.update((n) => n + 1);
      onclose();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function sendOTP(): Promise<void> {
    if (!email) { error = m.auth_required_email(); return; }
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
      onclose();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      otpCode = "";
    } finally {
      loading = false;
    }
  }

  async function register(): Promise<void> {
    if (!email || !password) { error = m.auth_required_credentials(); return; }
    loading = true; error = "";
    try {
      api.authRegister(email, password, profileName || undefined).then((e) => {
        console.log(e)
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
      view = "success";
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      otpCode = "";
    } finally {
      loading = false;
    }
  }
</script>

<!-- Backdrop: click-outside-to-close. role="presentation" = decorative overlay;
     Escape closes via the inner dialog's natural keyboard flow. -->
<div
  class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
  role="presentation"
  onclick={(e) => { if (e.target === e.currentTarget) onclose(); }}
  onkeydown={(e) => { if (e.key === "Escape") onclose(); }}
>
  <div class="relative w-full max-w-sm rounded-xl border border-border bg-background p-6 shadow-2xl">
    {#if view !== "success"}
      <button
        aria-label={m.common_close()}
        class="absolute right-4 top-4 text-muted-foreground hover:text-foreground"
        onclick={onclose}
      >
        <X class="size-4" />
      </button>
    {/if}

    {#if view === "choose"}
      <h2 class="mb-1 text-lg font-semibold">{m.auth_sign_in_title()}</h2>
      <p class="mb-6 text-sm text-muted-foreground">{m.auth_sync_description()}</p>
      <div class="flex flex-col gap-3">
        <Button variant="default" class="w-full" onclick={() => setView("login")}>
          {m.auth_sign_in_password()}
        </Button>
        <Button variant="outline" class="w-full" onclick={() => setView("otp-email")}>
          {m.auth_email_code_title()}
        </Button>
        <div class="relative my-1 flex items-center">
          <div class="flex-1 border-t border-border"></div>
          <span class="mx-3 text-xs text-muted-foreground">{m.auth_or()}</span>
          <div class="flex-1 border-t border-border"></div>
        </div>
        <Button variant="ghost" class="w-full" onclick={() => setView("register")}>
          {m.auth_create_account()}
        </Button>
      </div>

    {:else if view === "login"}
      <Button variant="link" class="mb-3 h-auto p-0 text-xs text-muted-foreground" onclick={() => setView("choose")}>
        ← {m.common_back()}
      </Button>
      <h2 class="mb-5 text-lg font-semibold">{m.common_sign_in()}</h2>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <Mail class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={email}
            onkeydown={(e) => e.key === "Enter" && login()}
          />
        </div>
        <div class="relative">
          <Lock class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="password"
            placeholder={m.auth_password()}
            class="pl-9"
            bind:value={password}
            onkeydown={(e) => e.key === "Enter" && login()}
          />
        </div>
        {#if error}<p class="text-xs text-destructive">{error}</p>{/if}
        <Button class="w-full" onclick={login} disabled={loading}>
          {#if loading}<Spinner class="mr-2 size-4" />{/if}
          {m.common_sign_in()}
        </Button>
        <Button variant="link" class="h-auto p-0 text-xs text-muted-foreground" onclick={() => setView("otp-email")}>
          {m.auth_sign_in_email_instead()}
        </Button>
      </div>

    {:else if view === "otp-email"}
      <Button variant="link" class="mb-3 h-auto p-0 text-xs text-muted-foreground" onclick={() => setView("choose")}>
        ← {m.common_back()}
      </Button>
      <h2 class="mb-1 text-lg font-semibold">{m.auth_email_code_title()}</h2>
      <p class="mb-5 text-sm text-muted-foreground">{m.auth_email_code_intro()}</p>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <Mail class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={email}
            onkeydown={(e) => e.key === "Enter" && sendOTP()}
          />
        </div>
        {#if error}<p class="text-xs text-destructive">{error}</p>{/if}
        <Button class="w-full" onclick={sendOTP} disabled={loading}>
          {#if loading}<Spinner class="mr-2 size-4" />{/if}
          {m.auth_send_code()}
        </Button>
      </div>

    {:else if view === "otp-code"}
      <h2 class="mb-1 text-lg font-semibold">{m.auth_enter_code()}</h2>
      <p class="mb-6 text-sm text-muted-foreground">
        {m.auth_check_inbox({ email: otpEmail })}
      </p>
      <div class="flex flex-col items-center gap-4">
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
        {#if error}<p class="text-xs text-destructive text-center">{error}</p>{/if}
        {#if loading}
          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner class="size-4" /> {m.auth_verifying()}
          </div>
        {:else}
          <Button class="w-full" onclick={verifyOTP} disabled={otpCode.length < 8}>
            {m.auth_verify()}
          </Button>
        {/if}
        <Button variant="link" class="h-auto p-0 text-xs text-muted-foreground" onclick={() => { email = otpEmail; setView("otp-email"); }}>
          {m.auth_resend_code()}
        </Button>
      </div>

    {:else if view === "register"}
      <Button variant="link" class="mb-3 h-auto p-0 text-xs text-muted-foreground" onclick={() => setView("choose")}>
        ← {m.common_back()}
      </Button>
      <h2 class="mb-5 text-lg font-semibold">{m.auth_create_account()}</h2>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <User class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="text"
            placeholder={m.auth_display_name()}
            class="pl-9"
            bind:value={profileName}
          />
        </div>
        <div class="relative">
          <Mail class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={email}
          />
        </div>
        <div class="relative">
          <Lock class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            type="password"
            placeholder={m.auth_password()}
            class="pl-9"
            bind:value={password}
            onkeydown={(e) => e.key === "Enter" && register()}
          />
        </div>
        {#if error}<p class="text-xs text-destructive">{error}</p>{/if}
        <Button class="w-full" onclick={register} disabled={loading}>
          {#if loading}<Spinner class="mr-2 size-4" />{/if}
          {m.auth_create_account()}
        </Button>
        <p class="text-xs text-muted-foreground">
          {m.auth_existing_library_sync()}
        </p>
      </div>

    {:else if view === "register-otp"}
      <h2 class="mb-1 text-lg font-semibold">{m.auth_check_email()}</h2>
      <p class="mb-2 text-sm text-muted-foreground">{m.auth_confirmation_sent()}</p>
      <p class="mb-6 text-sm font-medium">{pendingEmail}</p>
      <div class="flex flex-col items-center gap-4">
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
        {#if error}<p class="text-xs text-destructive text-center">{error}</p>{/if}
        {#if loading}
          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner class="size-4" /> {m.auth_verifying()}
          </div>
        {:else}
          <Button class="w-full" onclick={confirmRegistration} disabled={otpCode.length < 8}>
            {m.auth_confirm_account()}
          </Button>
        {/if}
        <Button
          variant="link"
          class="h-auto p-0 text-xs text-muted-foreground"
          onclick={() => { email = pendingEmail; profileName = pendingProfileName; setView("register"); }}
        >
          ← Back to registration
        </Button>
      </div>

    {:else if view === "success"}
      <div class="flex flex-col items-center gap-4 py-4 text-center">
        <CheckCircle class="size-12 text-green-500" />
        <div>
          <h2 class="text-lg font-semibold">{m.auth_welcome()}</h2>
          <p class="mt-1 text-sm text-muted-foreground">{m.auth_account_ready()}</p>
        </div>
        <Button class="w-full" onclick={onclose}>{m.onboarding_get_started()}</Button>
      </div>
    {/if}
  </div>
</div>
