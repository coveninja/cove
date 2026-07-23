import { describe, expect, it } from "vitest";

import {
  codecLabel,
  langLabel,
  parseStreamMeta,
  type ParsedStreamMeta,
} from "$lib/streamMeta";
import type { Stream } from "$lib/types/addons";

let streamNumber = 0;

function release(text: string, overrides: Partial<Stream> = {}): Stream {
  streamNumber += 1;
  return {
    name: text,
    title: "",
    url: `https://stream.test/${streamNumber}`,
    infoHash: "",
    addonName: "Test",
    ...overrides,
  };
}

function meta(overrides: Partial<ParsedStreamMeta>): ParsedStreamMeta {
  return {
    codec: "unknown",
    is10bit: false,
    isDolbyVision: false,
    isDvProfile5: false,
    isHDR: false,
    langs: [],
    isMulti: false,
    hasLangTags: false,
    ...overrides,
  };
}

describe("parseStreamMeta", () => {
  it.each([
    ["Movie.AV1.2160p", "av1"],
    ["Movie.VP9.1080p", "vp9"],
    ["Movie.x265.1080p", "h265"],
    ["Movie.H.265.HEVC", "h265"],
    ["Movie.x264.1080p", "h264"],
    ["Movie.H.264.AVC", "h264"],
    ["Movie.1080p", "unknown"],
  ] as const)("detects the codec in %s", (text, codec) => {
    expect(parseStreamMeta(release(text)).codec).toBe(codec);
  });

  it("prefers modern codecs and recognizes common 10-bit markers", () => {
    expect(parseStreamMeta(release("AV1 x265 x264 10-bit"))).toMatchObject({
      codec: "av1",
      is10bit: true,
    });
    expect(parseStreamMeta(release("HEVC Main 10")).is10bit).toBe(true);
    expect(parseStreamMeta(release("x264 Hi10P")).is10bit).toBe(true);
  });

  it("distinguishes Dolby Vision context, profile 5, and HDR", () => {
    expect(parseStreamMeta(release("Dolby Vision Profile 5"))).toMatchObject({
      isDolbyVision: true,
      isDvProfile5: true,
      isHDR: false,
    });
    expect(parseStreamMeta(release("DoVi DV-P5 HDR10+"))).toMatchObject({
      isDolbyVision: true,
      isDvProfile5: true,
      isHDR: true,
    });
    expect(
      parseStreamMeta(release("ReleaseGroup.DV.1080p")).isDolbyVision,
    ).toBe(false);
    expect(
      parseStreamMeta(release("ReleaseGroup.DV.2160p")).isDolbyVision,
    ).toBe(true);
  });

  it("collects language words and flags once in a stable order", () => {
    expect(
      parseStreamMeta(release("🇯🇵 English ENG TRUEFRENCH German")).langs,
    ).toEqual(["ja", "en", "fr", "de"]);
    expect(parseStreamMeta(release("Movie.VOSTFR"))).toMatchObject({
      langs: ["fr"],
      isMulti: false,
      hasLangTags: true,
    });
  });

  it("marks multi and dual-audio releases even without named languages", () => {
    expect(parseStreamMeta(release("Movie.MULTi"))).toMatchObject({
      langs: [],
      isMulti: true,
      hasLangTags: true,
    });
    expect(parseStreamMeta(release("Movie.Dual-Audio")).isMulti).toBe(true);
    expect(parseStreamMeta(release("Movie.untagged")).hasLangTags).toBe(false);
  });

  it("memoizes identified streams without conflating fallback-only rows", () => {
    const identified = release("Movie.x265");
    expect(parseStreamMeta(identified)).toBe(parseStreamMeta(identified));

    const first = release("Movie.x264", {
      title: "Generic",
      url: "",
      infoHash: "",
    });
    const second = release("Movie.AV1", {
      title: "Generic",
      url: "",
      infoHash: "",
    });
    expect(parseStreamMeta(first).codec).toBe("h264");
    expect(parseStreamMeta(second).codec).toBe("av1");
  });
});

describe("stream metadata labels", () => {
  it("formats codec labels, including a codec-less 10-bit warning", () => {
    expect(codecLabel(meta({}))).toBeNull();
    expect(codecLabel(meta({ is10bit: true }))).toBe("10-bit");
    expect(codecLabel(meta({ codec: "h265" }))).toBe("H.265");
    expect(codecLabel(meta({ codec: "av1", is10bit: true }))).toBe(
      "AV1 10-bit",
    );
  });

  it("formats language labels and caps long lists", () => {
    expect(langLabel(meta({}))).toBeNull();
    expect(langLabel(meta({ isMulti: true }))).toBe("MULTi");
    expect(langLabel(meta({ langs: ["it", "en"] }))).toBe("IT/EN");
    expect(langLabel(meta({ langs: ["en", "fr", "de", "it", "es"] }))).toBe(
      "EN/FR/DE+2",
    );
  });
});
