import { defineConfig } from "eslint/config";
import tseslint from "@electron-toolkit/eslint-config-ts";
import eslintConfigPrettier from "@electron-toolkit/eslint-config-prettier";
import eslintPluginSvelte from "eslint-plugin-svelte";
import ts from "typescript-eslint";

export default defineConfig(
  { ignores: ["**/node_modules", "**/dist", "**/out"] },
  tseslint.configs.recommended,
  eslintPluginSvelte.configs["flat/recommended"],
  {
    files: ["**/*.svelte"],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
    rules: {
      "prettier/prettier": "off",
    },
  },
  {
    files: ["**/*.{tsx,svelte}"],
    rules: { "svelte/no-unused-svelte-ignore": "off" },
  },
  {
    files: ["**/*.svelte.ts", "**/*.svelte.js"],
    languageOptions: {
      parser: ts.parser,
    },
  },
  eslintConfigPrettier,
);
