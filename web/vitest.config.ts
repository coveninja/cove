import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config";

export default mergeConfig(
  viteConfig,
  defineConfig({
    // Component tests run in jsdom and must resolve Svelte's browser runtime;
    // the default SSR condition exposes a server-only mount() placeholder.
    resolve: {
      conditions: ["browser"],
    },
    test: {
      environment: "jsdom",
      exclude: ["e2e/**", "node_modules/**", "dist/**"],
      coverage: {
        provider: "v8",
        reporter: ["text", "json-summary", "lcov"],
        // Measure both logic modules and Svelte components. Omitting .svelte
        // files made the report look healthy while most of the UI was absent
        // from the denominator. Excludes are generated or vendored code only.
        include: ["src/**/*.{ts,svelte}"],
        exclude: [
          "src/lib/types/**", // tygo-generated from Go structs
          "src/lib/components/ui/**", // vendored shadcn-svelte
          "**/*.test.ts",
        ],
      },
      restoreMocks: true,
    },
  }),
);
