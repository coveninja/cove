import { get } from "svelte/store";
import { describe, expect, it, vi } from "vitest";

import { libraryChanged } from "$lib/stores/library";

describe("libraryChanged", () => {
  it("starts at zero and notifies subscribers when incremented", () => {
    libraryChanged.set(0);
    const subscriber = vi.fn();
    const unsubscribe = libraryChanged.subscribe(subscriber);

    libraryChanged.update((generation) => generation + 1);

    expect(get(libraryChanged)).toBe(1);
    expect(subscriber).toHaveBeenNthCalledWith(1, 0);
    expect(subscriber).toHaveBeenNthCalledWith(2, 1);

    unsubscribe();
    libraryChanged.set(2);
    expect(subscriber).toHaveBeenCalledTimes(2);
  });
});
