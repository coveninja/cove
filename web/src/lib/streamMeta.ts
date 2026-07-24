// $lib/streamMeta.ts
//
// Parses codec and audio-language metadata out of addon-supplied stream
// names/titles. There is no structured field for either — release names are
// the only source — so this is heuristic by design: a missing tag means
// "unknown", never "absent". Shared by streamSelection (ranking) and the
// stream-list UIs (badges / disabled rows).

import type { Stream } from "$lib/types/addons";

export interface ParsedStreamMeta {
  /** Video codec advertised by the release name, if any. */
  codec: "h264" | "h265" | "av1" | "vp9" | "unknown";
  /** 10-bit depth marker ("x265 10bit", "Hi10P", "Main 10"). */
  is10bit: boolean;
  /** Any Dolby Vision marker. */
  isDolbyVision: boolean;
  /** Dolby Vision Profile 5 — no HDR10 fallback layer, renders green/purple
   * on displays/decoders without DV support. */
  isDvProfile5: boolean;
  /** HDR10 / HDR10+ (not DV-only). */
  isHDR: boolean;
  /** ISO 639-1 audio languages advertised by the release name. */
  langs: string[];
  /** MULTi / dual-audio release — treated as matching any language pref. */
  isMulti: boolean;
  /** False when no language token of any kind was found — callers must treat
   * that as "unknown language", not "wrong language". */
  hasLangTags: boolean;
}

// ── Codec detection ──────────────────────────────────────────────────────────

const AV1_RE = /\bav1\b/i;
const VP9_RE = /\bvp9\b/i;
const H265_RE = /\b(?:x\.?265|h\.?265|hevc)\b/i;
const H264_RE = /\b(?:x\.?264|h\.?264|avc)\b/i;
// 10-bit HEVC ("x265 10bit", "HEVC Main 10", "Hi10P") software-decodes at
// well under realtime on phone SoCs whose decoder lacks the Main 10 profile.
const TEN_BIT_RE = /10.?bits?\b|\bhi10p?\b|\bmain ?10\b/i;
const DV_RE = /dolby.?vision|\bdovi\b/i;
// Bare "DV" is ambiguous (DV camcorder format, release-group initials), so it
// only counts as Dolby Vision alongside 4K/UHD/HDR context in the same text.
const DV_BARE_RE = /\bdv\b/i;
const UHD_CONTEXT_RE = /\b(?:4k|2160p?|uhd|hdr)\b/i;
const DV_P5_RE = /\b(?:dv.?p5|dvp5|profile.?5)\b/i;
const HDR_RE = /\bhdr(?:10)?\+?\b/i;

// ── Language detection ───────────────────────────────────────────────────────

const MULTI_RE =
  /\bmulti\b|\bdual[ .\-_]?audio\b|\bdual\b|\bvff?[ .\-_]?multi\b/i;

// Word tokens commonly found in release names → ISO 639-1. Both ISO 639-2
// forms (bibliographic + terminological) and full English names are covered.
// VOSTFR is deliberately absent: it means French *subtitles* over original
// audio, handled separately below.
const LANG_TOKENS: [RegExp, string][] = [
  [/\b(?:eng|english)\b/i, "en"],
  [/\b(?:fre|fra|french|truefrench|vf[fqi2]?)\b/i, "fr"],
  [/\b(?:ger|deu|german)\b/i, "de"],
  [/\b(?:ita|italian)\b/i, "it"],
  [/\b(?:spa|esp|spanish|castellano|latino)\b/i, "es"],
  [/\b(?:por|portuguese|dublado)\b/i, "pt"],
  [/\b(?:jpn|japanese)\b/i, "ja"],
  [/\b(?:kor|korean)\b/i, "ko"],
  [/\b(?:chi|zho|chinese|mandarin)\b/i, "zh"],
  [/\b(?:rus|russian)\b/i, "ru"],
  [/\b(?:pol|polish|lektor)\b/i, "pl"],
  [/\b(?:nld|dut|dutch)\b/i, "nl"],
  [/\b(?:hin|hindi)\b/i, "hi"],
  [/\b(?:ara|arabic)\b/i, "ar"],
  [/\b(?:tur|turkish)\b/i, "tr"],
  [/\b(?:ukr|ukrainian)\b/i, "uk"],
  [/\b(?:swe|swedish)\b/i, "sv"],
  [/\b(?:nor|norwegian)\b/i, "no"],
  [/\b(?:dan|danish)\b/i, "da"],
  [/\b(?:fin|finnish)\b/i, "fi"],
  [/\b(?:ell|gre|greek)\b/i, "el"],
  [/\b(?:ces|cze|czech)\b/i, "cs"],
  [/\b(?:hun|hungarian)\b/i, "hu"],
  [/\b(?:ron|rum|romanian)\b/i, "ro"],
  [/\b(?:tha|thai)\b/i, "th"],
  [/\b(?:vie|vietnamese)\b/i, "vi"],
  [/\b(?:ind|indonesian)\b/i, "id"],
];

// Flag emoji (regional-indicator pairs) some addons (Torrentio et al.) put in
// titles → ISO 639-1 of that country's dominant audio language.
const FLAG_LANGS: [string, string][] = [
  ["🇬🇧", "en"],
  ["🇺🇸", "en"],
  ["🇦🇺", "en"],
  ["🏴󠁧󠁢󠁥󠁮󠁧󠁿", "en"],
  ["🇫🇷", "fr"],
  ["🇩🇪", "de"],
  ["🇮🇹", "it"],
  ["🇪🇸", "es"],
  ["🇲🇽", "es"],
  ["🇦🇷", "es"],
  ["🇵🇹", "pt"],
  ["🇧🇷", "pt"],
  ["🇯🇵", "ja"],
  ["🇰🇷", "ko"],
  ["🇨🇳", "zh"],
  ["🇹🇼", "zh"],
  ["🇭🇰", "zh"],
  ["🇷🇺", "ru"],
  ["🇵🇱", "pl"],
  ["🇳🇱", "nl"],
  ["🇮🇳", "hi"],
  ["🇸🇦", "ar"],
  ["🇦🇪", "ar"],
  ["🇹🇷", "tr"],
  ["🇺🇦", "uk"],
  ["🇸🇪", "sv"],
  ["🇳🇴", "no"],
  ["🇩🇰", "da"],
  ["🇫🇮", "fi"],
  ["🇬🇷", "el"],
  ["🇨🇿", "cs"],
  ["🇭🇺", "hu"],
  ["🇷🇴", "ro"],
  ["🇹🇭", "th"],
  ["🇻🇳", "vi"],
  ["🇮🇩", "id"],
];

// VOSTFR = original audio + French subs. Counts as a French-friendly release
// (adds "fr") but not dual audio.
const VOSTFR_RE = /\bvostfr\b/i;

function detectCodec(text: string): ParsedStreamMeta["codec"] {
  if (AV1_RE.test(text)) return "av1";
  if (VP9_RE.test(text)) return "vp9";
  if (H265_RE.test(text)) return "h265";
  if (H264_RE.test(text)) return "h264";
  return "unknown";
}

function detectLangs(text: string): { langs: string[]; isMulti: boolean } {
  const langs = new Set<string>();
  const isMulti = MULTI_RE.test(text);
  for (const [flag, lang] of FLAG_LANGS) {
    if (text.includes(flag)) langs.add(lang);
  }
  for (const [re, lang] of LANG_TOKENS) {
    if (re.test(text)) langs.add(lang);
  }
  if (VOSTFR_RE.test(text)) langs.add("fr");
  return { langs: [...langs], isMulti };
}

// ── Badge labels ─────────────────────────────────────────────────────────────

const CODEC_NAMES: Record<
  Exclude<ParsedStreamMeta["codec"], "unknown">,
  string
> = {
  h264: "H.264",
  h265: "H.265",
  av1: "AV1",
  vp9: "VP9",
};

/** Human label for the codec badge ("H.265 10-bit", "AV1"), or null when
 * nothing codec-ish was parsed. A bare 10-bit marker still gets a label —
 * it's the detail that matters for hardware-decode support. */
export function codecLabel(meta: ParsedStreamMeta): string | null {
  if (meta.codec === "unknown") return meta.is10bit ? "10-bit" : null;
  const name = CODEC_NAMES[meta.codec];
  return meta.is10bit ? `${name} 10-bit` : name;
}

/** Human label for the language badge ("MULTi", "IT/EN"), or null when no
 * language tags were parsed. Capped at three languages to keep rows tidy. */
export function langLabel(meta: ParsedStreamMeta): string | null {
  if (meta.isMulti) return "MULTi";
  if (meta.langs.length === 0) return null;
  const shown = meta.langs.slice(0, 3).map((l) => l.toUpperCase());
  const extra = meta.langs.length - shown.length;
  return extra > 0 ? `${shown.join("/")}+${extra}` : shown.join("/");
}

// Parsing runs several regexes over every candidate and is re-invoked on each
// re-rank (probe pass, UI derives), so results are memoized per stream identity.
const metaCache = new Map<string, ParsedStreamMeta>();

export function parseStreamMeta(stream: Stream): ParsedStreamMeta {
  // A few addons return catalog-only rows without a URL/info-hash. Using the
  // title alone made unrelated rows with the same generic title (or no title)
  // share cached metadata, so include both display fields for that fallback.
  const key =
    stream.url || stream.infoHash || `${stream.name}\0${stream.title}`;
  const cached = metaCache.get(key);
  if (cached) return cached;

  const text = `${stream.name} ${stream.title}`;
  const codec = detectCodec(text);
  const is10bit = TEN_BIT_RE.test(text);
  const isDolbyVision =
    DV_RE.test(text) || (DV_BARE_RE.test(text) && UHD_CONTEXT_RE.test(text));
  const { langs, isMulti } = detectLangs(text);

  const meta: ParsedStreamMeta = {
    codec,
    is10bit,
    isDolbyVision,
    isDvProfile5: isDolbyVision && DV_P5_RE.test(text),
    isHDR: HDR_RE.test(text),
    langs,
    isMulti,
    hasLangTags: isMulti || langs.length > 0,
  };
  metaCache.set(key, meta);
  return meta;
}
