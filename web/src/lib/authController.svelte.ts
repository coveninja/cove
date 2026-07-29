import { api } from "$lib/api";
import * as m from "$lib/paraglide/messages.js";
import { auth } from "$lib/stores/auth.svelte";
import { libraryChanged } from "$lib/stores/library";
import { settings } from "$lib/stores/settings";

export type AuthView =
  | "choose"
  | "login"
  | "otp-email"
  | "otp-code"
  | "register"
  | "register-otp"
  | "success";

interface AuthControllerOptions {
  onDone: (onboardingDone: boolean) => void;
  showRegistrationSuccess?: boolean;
}

/** Shared authentication form state and mutations for dialog and TV panel. */
export class AuthController {
  view = $state<AuthView>("choose");
  loading = $state(false);
  error = $state("");
  email = $state("");
  password = $state("");
  profileName = $state("");
  otpCode = $state("");
  otpEmail = $state("");
  pendingEmail = $state("");
  pendingPassword = $state("");
  pendingProfileName = $state("");

  #onDone: (onboardingDone: boolean) => void;
  #showRegistrationSuccess: boolean;

  constructor(options: AuthControllerOptions) {
    this.#onDone = options.onDone;
    this.#showRegistrationSuccess = options.showRegistrationSuccess ?? false;
  }

  reset(): void {
    this.email = "";
    this.password = "";
    this.profileName = "";
    this.otpCode = "";
    this.otpEmail = "";
    this.error = "";
    this.loading = false;
  }

  setView(view: AuthView): void {
    this.reset();
    this.view = view;
  }

  async login(): Promise<void> {
    if (!this.email || !this.password) {
      this.error = m.auth_required_credentials();
      return;
    }
    this.loading = true;
    this.error = "";
    try {
      const response = await api.authLogin(this.email, this.password);
      await auth.setSession(
        response.access_token,
        this.email,
        response.profiles,
        response.active,
        response.refresh_token,
      );
      await this.#finish(response.onboarding_done);
    } catch (error) {
      this.#setError(error);
    } finally {
      this.loading = false;
    }
  }

  async sendOTP(): Promise<void> {
    if (!this.email) {
      this.error = m.auth_required_email();
      return;
    }
    this.loading = true;
    this.error = "";
    try {
      await api.authSendOTP(this.email);
      this.otpEmail = this.email;
      this.view = "otp-code";
    } catch (error) {
      this.#setError(error);
    } finally {
      this.loading = false;
    }
  }

  async verifyOTP(): Promise<void> {
    if (this.loading || !this.otpCode || this.otpCode.length < 8) return;
    this.loading = true;
    this.error = "";
    try {
      const response = await api.authVerifyOTP(this.otpEmail, this.otpCode);
      await auth.setSession(
        response.access_token,
        this.otpEmail,
        response.profiles,
        response.active,
        response.refresh_token,
      );
      await this.#finish(response.onboarding_done);
    } catch (error) {
      this.#setError(error);
      this.otpCode = "";
    } finally {
      this.loading = false;
    }
  }

  async register(): Promise<void> {
    if (!this.email || !this.password) {
      this.error = m.auth_required_credentials();
      return;
    }
    this.loading = true;
    this.error = "";
    try {
      await api.authRegister(
        this.email,
        this.password,
        this.profileName || undefined,
      );
      this.pendingEmail = this.email;
      this.pendingPassword = this.password;
      this.pendingProfileName = this.profileName;
      this.otpCode = "";
      this.view = "register-otp";
    } catch (error) {
      this.#setError(error);
    } finally {
      this.loading = false;
    }
  }

  async confirmRegistration(): Promise<void> {
    if (this.loading || !this.otpCode || this.otpCode.length < 8) return;
    this.loading = true;
    this.error = "";
    try {
      const response = await api.authConfirmRegister(
        this.pendingEmail,
        this.otpCode,
        this.pendingPassword,
        this.pendingProfileName || undefined,
      );
      await auth.setSession(
        response.access_token,
        this.pendingEmail,
        [response.profile],
        response.profile,
        response.refresh_token,
      );
      await settings.load();
      libraryChanged.update((generation) => generation + 1);
      if (this.#showRegistrationSuccess) this.view = "success";
      else this.#onDone(false);
    } catch (error) {
      this.#setError(error);
      this.otpCode = "";
    } finally {
      this.loading = false;
    }
  }

  escapeBack(): boolean {
    if (this.view === "otp-code") {
      const previousEmail = this.otpEmail;
      this.setView("otp-email");
      this.email = previousEmail;
      return true;
    }
    if (this.view === "register-otp") {
      this.setView("register");
      this.email = this.pendingEmail;
      this.profileName = this.pendingProfileName;
      return true;
    }
    if (
      this.view === "login" ||
      this.view === "otp-email" ||
      this.view === "register"
    ) {
      this.setView("choose");
      return true;
    }
    return false;
  }

  async #finish(onboardingDone = false): Promise<void> {
    await settings.load();
    libraryChanged.update((generation) => generation + 1);
    this.#onDone(onboardingDone);
  }

  #setError(error: unknown): void {
    this.error = error instanceof Error ? error.message : String(error);
  }
}
