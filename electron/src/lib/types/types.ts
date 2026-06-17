import { Media } from "$lib/types/tmdb";

export type Page =
  | { type: "home" }
  | { type: "myList" }
  | { type: "explore" }
  | { type: "settings" }
  | { type: "query"; query: string }
  | { type: "mediaView"; media: Media };


export interface UpcomingItem {
  tmdbId: number;
  title: string;
  posterPath: string;
  season: number;
  episode: number;
  episodeName: string;
  airDate: string; // YYYY-MM-DD from TMDB
}

export type TVEpisode = {
  episode_number: number;
  name: string;
  overview: string;
  still_path: string;
  air_date: string;
};
