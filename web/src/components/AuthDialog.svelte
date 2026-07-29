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
  import { Spinner } from "$lib/components/ui/spinner/index.js";
  import { AuthController, type AuthView } from "$lib/authController.svelte";
  import * as m from "$lib/paraglide/messages.js";

  let {
    onclose,
    onauthdone,
  }: {
    onclose: () => void;
    onauthdone?: (onboardingDone: boolean) => void;
  } = $props();

  const controller = new AuthController({
    onDone: (onboardingDone) => {
      onauthdone?.(onboardingDone);
      onclose();
    },
    showRegistrationSuccess: true,
  });
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
</script>

<!-- Backdrop: click-outside-to-close. role="presentation" = decorative overlay;
     Escape closes via the inner dialog's natural keyboard flow. -->
<div
  class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
  role="presentation"
  onclick={(e) => {
    if (e.target === e.currentTarget) onclose();
  }}
  onkeydown={(e) => {
    if (e.key === "Escape") onclose();
  }}
>
  <div
    class="relative w-full max-w-sm rounded-xl border border-border bg-background p-6 shadow-2xl"
  >
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
      <p class="mb-6 text-sm text-muted-foreground">
        {m.auth_sync_description()}
      </p>
      <div class="flex flex-col gap-3">
        <Button
          variant="default"
          class="w-full"
          onclick={() => setView("login")}
        >
          {m.auth_sign_in_password()}
        </Button>
        <Button
          variant="outline"
          class="w-full"
          onclick={() => setView("otp-email")}
        >
          {m.auth_email_code_title()}
        </Button>
        <div class="relative my-1 flex items-center">
          <div class="flex-1 border-t border-border"></div>
          <span class="mx-3 text-xs text-muted-foreground">{m.auth_or()}</span>
          <div class="flex-1 border-t border-border"></div>
        </div>
        <Button
          variant="ghost"
          class="w-full"
          onclick={() => setView("register")}
        >
          {m.auth_create_account()}
        </Button>
      </div>
    {:else if view === "login"}
      <Button
        variant="link"
        class="mb-3 h-auto p-0 text-xs text-muted-foreground"
        onclick={() => setView("choose")}
      >
        ← {m.common_back()}
      </Button>
      <h2 class="mb-5 text-lg font-semibold">{m.common_sign_in()}</h2>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <Mail
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={controller.email}
            onkeydown={(e) => e.key === "Enter" && login()}
          />
        </div>
        <div class="relative">
          <Lock
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="password"
            placeholder={m.auth_password()}
            class="pl-9"
            bind:value={controller.password}
            onkeydown={(e) => e.key === "Enter" && login()}
          />
        </div>
        {#if error}<p class="text-xs text-destructive">{error}</p>{/if}
        <Button class="w-full" onclick={login} disabled={loading}>
          {#if loading}<Spinner class="mr-2 size-4" />{/if}
          {m.common_sign_in()}
        </Button>
        <Button
          variant="link"
          class="h-auto p-0 text-xs text-muted-foreground"
          onclick={() => setView("otp-email")}
        >
          {m.auth_sign_in_email_instead()}
        </Button>
      </div>
    {:else if view === "otp-email"}
      <Button
        variant="link"
        class="mb-3 h-auto p-0 text-xs text-muted-foreground"
        onclick={() => setView("choose")}
      >
        ← {m.common_back()}
      </Button>
      <h2 class="mb-1 text-lg font-semibold">{m.auth_email_code_title()}</h2>
      <p class="mb-5 text-sm text-muted-foreground">
        {m.auth_email_code_intro()}
      </p>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <Mail
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={controller.email}
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
        {#if error}<p class="text-xs text-destructive text-center">
            {error}
          </p>{/if}
        {#if loading}
          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner class="size-4" />
            {m.auth_verifying()}
          </div>
        {:else}
          <Button
            class="w-full"
            onclick={verifyOTP}
            disabled={otpCode.length < 8}
          >
            {m.auth_verify()}
          </Button>
        {/if}
        <Button
          variant="link"
          class="h-auto p-0 text-xs text-muted-foreground"
          onclick={() => controller.escapeBack()}
        >
          {m.auth_resend_code()}
        </Button>
      </div>
    {:else if view === "register"}
      <Button
        variant="link"
        class="mb-3 h-auto p-0 text-xs text-muted-foreground"
        onclick={() => setView("choose")}
      >
        ← {m.common_back()}
      </Button>
      <h2 class="mb-5 text-lg font-semibold">{m.auth_create_account()}</h2>
      <div class="flex flex-col gap-3">
        <div class="relative">
          <User
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="text"
            placeholder={m.auth_display_name()}
            class="pl-9"
            bind:value={controller.profileName}
          />
        </div>
        <div class="relative">
          <Mail
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="email"
            placeholder={m.common_email()}
            class="pl-9"
            bind:value={controller.email}
          />
        </div>
        <div class="relative">
          <Lock
            class="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="password"
            placeholder={m.auth_password()}
            class="pl-9"
            bind:value={controller.password}
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
      <p class="mb-2 text-sm text-muted-foreground">
        {m.auth_confirmation_sent()}
      </p>
      <p class="mb-6 text-sm font-medium">{pendingEmail}</p>
      <div class="flex flex-col items-center gap-4">
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
        {#if error}<p class="text-xs text-destructive text-center">
            {error}
          </p>{/if}
        {#if loading}
          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner class="size-4" />
            {m.auth_verifying()}
          </div>
        {:else}
          <Button
            class="w-full"
            onclick={confirmRegistration}
            disabled={otpCode.length < 8}
          >
            {m.auth_confirm_account()}
          </Button>
        {/if}
        <Button
          variant="link"
          class="h-auto p-0 text-xs text-muted-foreground"
          onclick={() => controller.escapeBack()}
        >
          {m.auth_back_registration()}
        </Button>
      </div>
    {:else if view === "success"}
      <div class="flex flex-col items-center gap-4 py-4 text-center">
        <CheckCircle class="size-12 text-green-500" />
        <div>
          <h2 class="text-lg font-semibold">{m.auth_welcome()}</h2>
          <p class="mt-1 text-sm text-muted-foreground">
            {m.auth_account_ready()}
          </p>
        </div>
        <Button class="w-full" onclick={onclose}
          >{m.onboarding_get_started()}</Button
        >
      </div>
    {/if}
  </div>
</div>
