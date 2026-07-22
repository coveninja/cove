import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "jsdom",
      exclude: ["e2e/**", "node_modules/**", "dist/**"],
      coverage: {
        provider: "v8",
        reporter: ["text", "json-summary", "lcov"],
        include: [
          "src/lib/api.ts",
          "src/lib/sync.ts",
          "src/lib/stores/**/*.ts",
        ],
      },
      restoreMocks: true,
    },
  }),
);
