import { Media } from "$lib/types/tmdb";

export type Page =
  | { type: "home" }
  | { type: "myList" }
  | { type: "explore" }
  | { type: "settings" }
  | { type: "account" }
  | { type: "query"; query: string }
  | { type: "mediaView"; media: Media }
  | { type: "catalog"; addonId: string; catalogType: string; catalogId: string; name: string };

export interface UpcomingItem {
  tmdbId: number;
  title: string;
  posterPath: string;
  season: number;
  episode: number;
  episodeName: string;
  airDate: string; // YYYY-MM-DD from TMDB
  stillPath: string;
}
