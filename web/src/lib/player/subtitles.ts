// A subtitle selection: off, an embedded mpv track (numeric id), or an external
// add-on subtitle (string id). Shared between the desktop Player orchestrator
// and its SubtitleMenu.
export type SubSel =
  | { kind: "off" }
  | { kind: "embedded"; id: number }
  | { kind: "external"; id: string };
