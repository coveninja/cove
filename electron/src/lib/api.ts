import { Media } from "$lib/types/tmdb";
import { Stream } from "$lib/types/addons";

const BASE = "http://localhost:6969/api";

export const api = {
  search: (q: string): Promise<Media[]> =>
    fetch(`${BASE}/search?q=${encodeURIComponent(q)}`).then((r) => r.json()),
  getStreams: (tmdbId: number): Promise<Stream[]> =>
    fetch(`${BASE}/streams?id=${tmdbId}`).then((r) => r.json()),
};
