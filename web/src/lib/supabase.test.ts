import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const createClient = vi.hoisted(() => vi.fn());

vi.mock("@supabase/supabase-js", () => ({ createClient }));

describe("supabase client initialization", () => {
  beforeEach(() => {
    vi.resetModules();
    createClient.mockReset();
    vi.stubEnv("VITE_SUPABASE_URL", "");
    vi.stubEnv("VITE_SUPABASE_PUBLISHABLE_KEY", "");
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("stays disabled when configuration is absent", async () => {
    const { supabase } = await import("$lib/supabase");

    expect(supabase).toBeNull();
    expect(createClient).not.toHaveBeenCalled();
  });

  it("stays disabled when only the URL is configured", async () => {
    vi.stubEnv("VITE_SUPABASE_URL", "https://project.supabase.co");

    const { supabase } = await import("$lib/supabase");

    expect(supabase).toBeNull();
    expect(createClient).not.toHaveBeenCalled();
  });

  it("creates and exports the configured client", async () => {
    const client = { kind: "supabase-client" };
    createClient.mockReturnValue(client);
    vi.stubEnv("VITE_SUPABASE_URL", "https://project.supabase.co");
    vi.stubEnv("VITE_SUPABASE_PUBLISHABLE_KEY", "publishable-key");

    const { supabase } = await import("$lib/supabase");

    expect(createClient).toHaveBeenCalledWith(
      "https://project.supabase.co",
      "publishable-key",
    );
    expect(supabase).toBe(client);
  });
});
