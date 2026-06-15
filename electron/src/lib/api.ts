import type { Details, Media, MediaImages, MediaVideos } from "$lib/types/tmdb";
import type { Stream } from "$lib/types/addons";

const BASE = "http://localhost:6969/api";

export const api = {
  search: (q: string): Promise<Media[]> =>
    fetch(`${BASE}/search?q=${encodeURIComponent(q)}`).then((r) => r.json()),
  getKeywords: (q: string): Promise<{ id: number; name: string }[]> =>
    fetch(`${BASE}/keywords?q=${encodeURIComponent(q)}`).then((r) => r.json()),
  getStreams: (tmdbId: number): Promise<Stream[]> =>
    fetch(`${BASE}/streams?id=${tmdbId}`).then((r) => r.json()),
  getSimilar: async (media: Media): Promise<Media[]> => {
    return fetch(`${BASE}/similar?id=${media.id}&type=${media.media_type}`)
      .then((r) => r.json())
      .then((d: Media[]) => {
        return d;
      });
  },
  getDetails: async (media: Media): Promise<Details> =>
    fetch(
      `http://localhost:6969/api/details?id=${media.id}&type=${media.media_type}`,
    ).then((r) => r.json()),
  getImages: async (media: Media): Promise<MediaImages> =>
    fetch(`${BASE}/images?id=${media.id}&type=${media.media_type}`).then((r) =>
      r.json(),
    ),
  getVideos: async (media: Media): Promise<MediaVideos> =>
    fetch(`${BASE}/videos?id=${media.id}&type=${media.media_type}`).then((r) =>
      r.json(),
    ),
};
