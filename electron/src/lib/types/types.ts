import { Media } from "$lib/types/tmdb";

export type Page =
  | { type: "home" }
  | { type: "myList" }
  | { type: "explore" }
  | { type: "query"; query: string }
  | { type: "mediaView"; media: Media };
