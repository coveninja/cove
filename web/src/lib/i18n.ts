import { settings } from "$lib/stores/settings";
import { getLocale, setLocale, type Locale } from "$lib/paraglide/runtime.js";

export type AppLocale = "en" | "tr" | "pt" | "es" | "it" | "de" | "ja";

export interface LocaleDefinition {
  appLocale: AppLocale;
  tmdbLocale:
    "en-US" | "tr-TR" | "pt-BR" | "es-ES" | "it-IT" | "de-DE" | "ja-JP";
  nativeName: string;
  direction: "ltr" | "rtl";
}

export const LOCALES: readonly LocaleDefinition[] = [
  {
    appLocale: "en",
    tmdbLocale: "en-US",
    nativeName: "English",
    direction: "ltr",
  },
  {
    appLocale: "tr",
    tmdbLocale: "tr-TR",
    nativeName: "Türkçe",
    direction: "ltr",
  },
  {
    appLocale: "pt",
    tmdbLocale: "pt-BR",
    nativeName: "Português",
    direction: "ltr",
  },
  {
    appLocale: "es",
    tmdbLocale: "es-ES",
    nativeName: "Español",
    direction: "ltr",
  },
  {
    appLocale: "it",
    tmdbLocale: "it-IT",
    nativeName: "Italiano",
    direction: "ltr",
  },
  {
    appLocale: "de",
    tmdbLocale: "de-DE",
    nativeName: "Deutsch",
    direction: "ltr",
  },
  {
    appLocale: "ja",
    tmdbLocale: "ja-JP",
    nativeName: "日本語",
    direction: "ltr",
  },
];

const localeByCode = new Map(
  LOCALES.map((locale) => [locale.appLocale, locale]),
);

export function normalizeAppLocale(
  value: string | null | undefined,
): AppLocale | null {
  if (!value) return null;
  const base = value.trim().toLowerCase().split(/[-_]/)[0];
  return base === "en" ||
    base === "tr" ||
    base === "pt" ||
    base === "es" ||
    base === "it" ||
    base === "de" ||
    base === "ja"
    ? base
    : null;
}

export function resolveInitialLocale(
  saved: string | null | undefined,
  preferred: readonly string[] = typeof navigator === "undefined"
    ? []
    : navigator.languages,
): AppLocale {
  const persisted = normalizeAppLocale(saved);
  if (persisted) return persisted;
  for (const candidate of preferred) {
    const locale = normalizeAppLocale(candidate);
    if (locale) return locale;
  }
  return "en";
}

export function localeDefinition(
  locale: AppLocale = activeLocale(),
): LocaleDefinition {
  return localeByCode.get(locale) ?? LOCALES[0];
}

export function activeLocale(): AppLocale {
  return normalizeAppLocale(getLocale()) ?? "en";
}

export function intlLocale(locale: AppLocale = activeLocale()): string {
  return localeDefinition(locale).tmdbLocale;
}

export function languageDisplayName(code: string): string {
  if (!code) return code;
  try {
    return (
      new Intl.DisplayNames([intlLocale()], { type: "language" }).of(code) ??
      code
    );
  } catch {
    return code;
  }
}

export function regionDisplayName(code: string): string {
  if (!code) return code;
  try {
    return (
      new Intl.DisplayNames([intlLocale()], { type: "region" }).of(code) ?? code
    );
  } catch {
    return code;
  }
}

export function activateLocale(locale: AppLocale): void {
  setLocale(locale as Locale, { reload: false });
  const definition = localeDefinition(locale);
  document.documentElement.lang = definition.appLocale;
  document.documentElement.dir = definition.direction;
}

export async function initializeLocalization(): Promise<AppLocale> {
  await settings.load();
  const saved = settings.getCurrent().uiLanguage;
  let locale = resolveInitialLocale(saved);

  if (!normalizeAppLocale(saved)) {
    const persisted = await settings.save({ uiLanguage: locale });
    if (!persisted) locale = "en";
  }

  activateLocale(locale);
  return locale;
}

export async function saveLanguageAndReload(
  locale: AppLocale,
): Promise<boolean> {
  const saved = await settings.save({ uiLanguage: locale });
  if (saved) window.location.reload();
  return saved;
}
