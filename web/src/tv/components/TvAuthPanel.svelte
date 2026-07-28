<script lang="ts">
  import { tick } from "svelte";
  import { Button } from "$lib/components/ui/button/index.js";
  import {
    InputOTP,
    InputOTPGroup,
    InputOTPSlot,
    InputOTPSeparator,
  } from "$lib/components/ui/input-otp/index.js";
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { focusFirst } from "../focus/focusStore.svelte";
  import * as m from "$lib/paraglide/messages.js";
  import { AuthController, type AuthView } from "$lib/authController.svelte";

  let { ondone }: { ondone: () => void } = $props();

  const controller = new AuthController({ onDone: () => ondone() });
  const view = $derived(controller.view);
  const loading = $derived(controller.loading);
  const error = $derived(controller.error);
  const otpCode = $derived(controller.otpCode);
  const otpEmail = $derived(controller.otpEmail);
  const pendingEmail = $derived(controller.pendingEmail);
  const setView = (view: AuthView) => controller.setView(view);
  const login = () => controller.login();
  const sendOTP = () => controller.sendOTP();
  const verifyOTP = () => controller.verifyOTP();
  const register = () => controller.register();
  const confirmRegistration = () => controller.confirmRegistration();

  // ── Exported back-navigation for the page's Escape handler ───────────────────
  // Returns true if it handled the Escape (moved to a previous sub-view);
  // returns false if already at "choose" (caller should close the panel).
  export function escapeBack(): boolean {
    return controller.escapeBack();
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
    <h3 class="mb-2 text-2xl font-semibold">{m.auth_sign_in_title()}</h3>
    <p class="mb-6 text-base text-muted-foreground">
      {m.auth_sync_description()}
    </p>
    <div class="flex flex-col gap-3">
      <Button
        variant="default"
        class="h-12 w-full text-lg"
        onclick={() => setView("login")}
      >
        {m.auth_sign_in_password()}
      </Button>
      <Button
        variant="outline"
        class="h-12 w-full text-lg"
        onclick={() => setView("otp-email")}
      >
        {m.auth_email_code_title()}
      </Button>
      <div class="relative my-1 flex items-center">
        <div class="flex-1 border-t border-border"></div>
        <span class="mx-3 text-sm text-muted-foreground">{m.auth_or()}</span>
        <div class="flex-1 border-t border-border"></div>
      </div>
      <Button
        variant="ghost"
        class="h-12 w-full text-lg"
        onclick={() => setView("register")}
      >
        {m.auth_create_account()}
      </Button>
    </div>
  {:else if view === "login"}
    <Button
      variant="link"
      class="mb-4 h-auto p-0 text-base text-muted-foreground"
      onclick={() => setView("choose")}
    >
      ← {m.common_back()}
    </Button>
    <h3 class="mb-6 text-2xl font-semibold">{m.common_sign_in()}</h3>
    <div class="flex flex-col gap-4">
      <input
        type="email"
        placeholder={m.common_email()}
        bind:value={controller.email}
        onkeydown={(e) => e.key === "Enter" && login()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="password"
        placeholder={m.auth_password()}
        bind:value={controller.password}
        onkeydown={(e) => e.key === "Enter" && login()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={login} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        {m.common_sign_in()}
      </Button>
      <Button
        variant="link"
        class="h-auto p-0 text-base text-muted-foreground"
        onclick={() => setView("otp-email")}
      >
        {m.auth_sign_in_email_instead()}
      </Button>
    </div>
  {:else if view === "otp-email"}
    <Button
      variant="link"
      class="mb-4 h-auto p-0 text-base text-muted-foreground"
      onclick={() => setView("choose")}
    >
      ← {m.common_back()}
    </Button>
    <h3 class="mb-2 text-2xl font-semibold">{m.auth_email_code_title()}</h3>
    <p class="mb-6 text-base text-muted-foreground">
      {m.auth_email_code_intro()}
    </p>
    <div class="flex flex-col gap-4">
      <input
        type="email"
        placeholder={m.common_email()}
        bind:value={controller.email}
        onkeydown={(e) => e.key === "Enter" && sendOTP()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={sendOTP} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        {m.auth_send_code()}
      </Button>
    </div>
  {:else if view === "otp-code"}
    <h3 class="mb-2 text-2xl font-semibold">{m.auth_enter_code()}</h3>
    <p class="mb-6 text-base text-muted-foreground">
      {m.auth_check_inbox({ email: otpEmail })}
    </p>
    <div class="flex flex-col items-center gap-5">
      <InputOTP
        maxlength={8}
        bind:value={controller.otpCode}
        onComplete={verifyOTP}
      >
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
      {#if error}<p class="text-sm text-destructive text-center">
          {error}
        </p>{/if}
      {#if loading}
        <div class="flex items-center gap-2 text-base text-muted-foreground">
          <Spinner class="size-5" />
          {m.auth_verifying()}
        </div>
      {:else}
        <Button
          class="h-12 w-full text-lg"
          onclick={verifyOTP}
          disabled={otpCode.length < 8}
        >
          {m.auth_verify()}
        </Button>
      {/if}
      <Button
        variant="link"
        class="h-auto p-0 text-base text-muted-foreground"
        onclick={() => controller.escapeBack()}
      >
        {m.auth_resend_code()}
      </Button>
    </div>
  {:else if view === "register"}
    <Button
      variant="link"
      class="mb-4 h-auto p-0 text-base text-muted-foreground"
      onclick={() => setView("choose")}
    >
      ← {m.common_back()}
    </Button>
    <h3 class="mb-6 text-2xl font-semibold">{m.auth_create_account()}</h3>
    <div class="flex flex-col gap-4">
      <input
        type="text"
        placeholder={m.auth_display_name()}
        bind:value={controller.profileName}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="email"
        placeholder={m.common_email()}
        bind:value={controller.email}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      <input
        type="password"
        placeholder={m.auth_password()}
        bind:value={controller.password}
        onkeydown={(e) => e.key === "Enter" && register()}
        class="h-12 rounded-lg border border-border bg-input px-4 text-lg text-foreground placeholder:text-muted-foreground focus:border-accent focus:outline-none"
      />
      {#if error}<p class="text-sm text-destructive">{error}</p>{/if}
      <Button class="h-12 w-full text-lg" onclick={register} disabled={loading}>
        {#if loading}<Spinner class="mr-2 size-5" />{/if}
        {m.auth_create_account()}
      </Button>
      <p class="text-sm text-muted-foreground">
        {m.auth_existing_library_sync()}
      </p>
    </div>
  {:else if view === "register-otp"}
    <h3 class="mb-2 text-2xl font-semibold">{m.auth_check_email()}</h3>
    <p class="mb-1 text-base text-muted-foreground">
      {m.auth_confirmation_sent()}
    </p>
    <p class="mb-6 text-base font-medium">{pendingEmail}</p>
    <div class="flex flex-col items-center gap-5">
      <InputOTP
        maxlength={8}
        bind:value={controller.otpCode}
        onComplete={confirmRegistration}
      >
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
      {#if error}<p class="text-sm text-destructive text-center">
          {error}
        </p>{/if}
      {#if loading}
        <div class="flex items-center gap-2 text-base text-muted-foreground">
          <Spinner class="size-5" />
          {m.auth_verifying()}
        </div>
      {:else}
        <Button
          class="h-12 w-full text-lg"
          onclick={confirmRegistration}
          disabled={otpCode.length < 8}
        >
          {m.auth_confirm_account()}
        </Button>
      {/if}
      <Button
        variant="link"
        class="h-auto p-0 text-base text-muted-foreground"
        onclick={() => controller.escapeBack()}
      >
        {m.auth_back_registration()}
      </Button>
    </div>
  {/if}
</div>
