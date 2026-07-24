import { describe, expect, it } from "vitest";

import { mediaFromEntry } from "$lib/mediaFromEntry";

describe("mediaFromEntry", () => {
  it("maps provided movie fields and supplies display-safe defaults", () => {
    expect(
      mediaFromEntry({
        id: 11,
        media_type: "movie",
        poster_path: "/poster.jpg",
        title: "A Movie",
      }),
    ).toEqual({
      id: 11,
      media_type: "movie",
      poster_path: "/poster.jpg",
      title: "A Movie",
      vote_average: 0,
      overview: "",
    });
  });

  it("preserves explicit values without synthesizing the other title field", () => {
    const result = mediaFromEntry({
      id: 22,
      media_type: "tv",
      poster_path: "",
      name: "A Show",
      vote_average: 0,
      overview: "",
    });

    expect(result).toMatchObject({
      id: 22,
      name: "A Show",
      vote_average: 0,
      overview: "",
    });
    expect("title" in result).toBe(false);
  });

  it("keeps both title variants when the source explicitly provides both", () => {
    expect(
      mediaFromEntry({
        id: 33,
        media_type: "tv",
        poster_path: "/show.jpg",
        title: "Localized",
        name: "Original",
        vote_average: 8.4,
        overview: "Summary",
      }),
    ).toMatchObject({
      title: "Localized",
      name: "Original",
      vote_average: 8.4,
      overview: "Summary",
    });
  });
});
