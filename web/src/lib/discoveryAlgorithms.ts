// $lib/discoveryAlgorithms.ts
//
// Metadata for the discovery-algorithm picker in Settings. The actual ranking
// logic lives server-side (internal/discover); this just drives the Select UI.

export type DiscoveryAlgorithm = "smart" | "popularity" | "custom";

export const DISCOVERY_ALGORITHMS: {
  value: DiscoveryAlgorithm;
  label: string;
  description: string;
}[] = [
  {
    value: "smart",
    label: "Cove Smart",
    description:
      "Personalized picks based on your taste profile — genres, keywords, cast/crew, and how recently you watched things.",
  },
  {
    value: "popularity",
    label: "Popularity",
    description:
      "Simple trending-first picks with no personalization. Fast and predictable.",
  },
  {
    value: "custom",
    label: "Custom (advanced)",
    description:
      "Send your taste profile and candidate list to your own HTTP endpoint for scoring.",
  },
];
