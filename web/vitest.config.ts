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
        // Measure the whole TS surface, not a hand-picked subset — otherwise
        // untested modules never appear in the denominator and coverage can
        // never regress. Excludes are generated or vendored code only.
        include: ["src/**/*.ts"],
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
