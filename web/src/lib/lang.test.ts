import { describe, expect, it } from "vitest";

import { ISO_639_2_TO_1, langMatches, normalizeLang } from "$lib/lang";

describe("language normalization", () => {
  it.each([
    ["jpn", "ja"],
    ["JPN", "ja"],
    ["fre", "fr"],
    ["fra", "fr"],
    ["ger", "de"],
    ["deu", "de"],
    ["chi", "zh"],
    ["zho", "zh"],
    ["pt-BR", "pt"],
    ["zh_Hans", "zh"],
    ["  ENG-us  ", "en"],
    ["qaa", "qaa"],
    ["", ""],
    ["   ", ""],
    [null, ""],
    [undefined, ""],
  ])("normalizes %j to %j", (input, expected) => {
    expect(normalizeLang(input)).toBe(expected);
  });

  it("keeps bibliographic and terminological aliases mapped consistently", () => {
    expect(ISO_639_2_TO_1.fre).toBe(ISO_639_2_TO_1.fra);
    expect(ISO_639_2_TO_1.ger).toBe(ISO_639_2_TO_1.deu);
    expect(ISO_639_2_TO_1.dut).toBe(ISO_639_2_TO_1.nld);
    expect(ISO_639_2_TO_1.rum).toBe(ISO_639_2_TO_1.ron);
  });

  it.each([
    ["jpn", "ja-JP", true],
    ["ENG", "en_us", true],
    ["ger", "deu", true],
    ["zh-Hans", "zho", true],
    ["en", "es", false],
    ["", "", false],
    [null, undefined, false],
    ["", "eng", false],
  ])("matches %j and %j as %j", (left, right, expected) => {
    expect(langMatches(left, right)).toBe(expected);
  });
});
