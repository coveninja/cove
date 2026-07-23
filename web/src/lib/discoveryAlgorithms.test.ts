import { describe, expect, it } from "vitest";

import { DISCOVERY_ALGORITHMS } from "$lib/discoveryAlgorithms";

describe("DISCOVERY_ALGORITHMS", () => {
  it("exposes each supported algorithm once with picker copy", () => {
    expect(DISCOVERY_ALGORITHMS.map(({ value }) => value)).toEqual([
      "smart",
      "popularity",
      "custom",
    ]);
    expect(new Set(DISCOVERY_ALGORITHMS.map(({ value }) => value)).size).toBe(
      DISCOVERY_ALGORITHMS.length,
    );
    expect(
      DISCOVERY_ALGORITHMS.every(
        ({ label, description }) => label.length > 0 && description.length > 0,
      ),
    ).toBe(true);
  });
});
