// $lib/discoveryAlgorithms.ts
//
// Metadata for the discovery-algorithm picker in Settings. The actual ranking
// logic lives server-side (internal/discover); this just drives the Select UI.
import * as m from "$lib/paraglide/messages.js";

export type DiscoveryAlgorithm = "smart" | "popularity" | "custom";

export const DISCOVERY_ALGORITHMS: {
  value: DiscoveryAlgorithm;
  label: string;
  description: string;
}[] = [
  {
    value: "smart",
    label: m.discovery_smart(),
    description: m.discovery_smart_description(),
  },
  {
    value: "popularity",
    label: m.discovery_popularity(),
    description: m.discovery_popularity_description(),
  },
  {
    value: "custom",
    label: m.discovery_custom(),
    description: m.discovery_custom_description(),
  },
];
