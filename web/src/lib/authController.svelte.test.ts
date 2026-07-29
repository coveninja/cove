import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  api: {
    authLogin: vi.fn(),
    authSendOTP: vi.fn(),
    authVerifyOTP: vi.fn(),
    authRegister: vi.fn(),
    authConfirmRegister: vi.fn(),
  },
  setSession: vi.fn(),
  settingsLoad: vi.fn(),
  libraryUpdate: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks.api }));
vi.mock("$lib/stores/auth.svelte", () => ({
  auth: { setSession: mocks.setSession },
}));
vi.mock("$lib/stores/settings", () => ({
  settings: { load: mocks.settingsLoad },
}));
vi.mock("$lib/stores/library", () => ({
  libraryChanged: { update: mocks.libraryUpdate },
}));

import { AuthController } from "$lib/authController.svelte";

function make(
  onDone = vi.fn(),
  showRegistrationSuccess = false,
): AuthController {
  let controller!: AuthController;
  $effect.root(() => {
    controller = new AuthController({ onDone, showRegistrationSuccess });
  });
  return controller;
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.settingsLoad.mockResolvedValue(undefined);
  mocks.setSession.mockResolvedValue(undefined);
  mocks.api.authLogin.mockResolvedValue({
    access_token: "access",
    refresh_token: "refresh",
    profiles: [{ id: "profile" }],
    active: { id: "profile" },
  });
  mocks.api.authSendOTP.mockResolvedValue(undefined);
  mocks.api.authVerifyOTP.mockResolvedValue({
    access_token: "access",
    refresh_token: "refresh",
    profiles: [{ id: "profile" }],
    active: { id: "profile" },
  });
  mocks.api.authRegister.mockResolvedValue({ confirmation_required: true });
  mocks.api.authConfirmRegister.mockResolvedValue({
    access_token: "access",
    refresh_token: "refresh",
    profile: { id: "profile" },
  });
});

describe("AuthController", () => {
  it("validates and completes password login", async () => {
    const onDone = vi.fn();
    const controller = make(onDone);
    await controller.login();
    expect(controller.error).toBe("Email and password are required.");

    controller.email = "user@example.com";
    controller.password = "secret";
    await controller.login();

    expect(mocks.api.authLogin).toHaveBeenCalledWith(
      "user@example.com",
      "secret",
    );
    expect(mocks.setSession).toHaveBeenCalledOnce();
    expect(mocks.settingsLoad).toHaveBeenCalledOnce();
    expect(mocks.libraryUpdate).toHaveBeenCalledOnce();
    expect(onDone).toHaveBeenCalledOnce();
  });

  it("forwards completed onboarding after email-code verification", async () => {
    mocks.api.authVerifyOTP.mockResolvedValue({
      access_token: "access",
      refresh_token: "refresh",
      profiles: [{ id: "profile" }],
      active: { id: "profile" },
      onboarding_done: true,
    });
    const onDone = vi.fn();
    const controller = make(onDone);
    controller.otpEmail = "user@example.com";
    controller.otpCode = "12345678";

    await controller.verifyOTP();

    expect(onDone).toHaveBeenCalledWith(true);
  });

  it("does not advance registration until the request succeeds", async () => {
    mocks.api.authRegister.mockRejectedValue(new Error("already registered"));
    const controller = make();
    controller.setView("register");
    controller.email = "user@example.com";
    controller.password = "secret";
    await controller.register();

    expect(controller.view).toBe("register");
    expect(controller.error).toBe("already registered");

    mocks.api.authRegister.mockResolvedValue({ confirmation_required: true });
    await controller.register();
    expect(controller.view).toBe("register-otp");
    expect(controller.pendingEmail).toBe("user@example.com");
  });

  it("supports dialog success and TV immediate completion after confirmation", async () => {
    const dialogDone = vi.fn();
    const dialog = make(dialogDone, true);
    dialog.pendingEmail = "user@example.com";
    dialog.pendingPassword = "secret";
    dialog.otpCode = "12345678";
    await dialog.confirmRegistration();
    expect(dialog.view).toBe("success");
    expect(dialogDone).not.toHaveBeenCalled();

    const tvDone = vi.fn();
    const tv = make(tvDone);
    tv.pendingEmail = "user@example.com";
    tv.pendingPassword = "secret";
    tv.otpCode = "12345678";
    await tv.confirmRegistration();
    expect(tvDone).toHaveBeenCalledOnce();
  });

  it("restores form context while navigating backward", () => {
    const controller = make();
    controller.view = "otp-code";
    controller.otpEmail = "user@example.com";
    expect(controller.escapeBack()).toBe(true);
    expect(controller.view).toBe("otp-email");
    expect(controller.email).toBe("user@example.com");

    controller.view = "register-otp";
    controller.pendingEmail = "new@example.com";
    controller.pendingProfileName = "New";
    expect(controller.escapeBack()).toBe(true);
    expect(controller.view).toBe("register");
    expect(controller.email).toBe("new@example.com");
    expect(controller.profileName).toBe("New");
  });
});
