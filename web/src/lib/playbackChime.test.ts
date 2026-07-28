import { afterEach, describe, expect, it, vi } from "vitest";
import { createPlaybackChime } from "$lib/playbackChime";

const resume = vi.fn().mockResolvedValue(undefined);
const oscillator = {
  type: "",
  frequency: {
    setValueAtTime: vi.fn(),
    exponentialRampToValueAtTime: vi.fn(),
  },
  connect: vi.fn(),
  start: vi.fn(),
  stop: vi.fn(),
  disconnect: vi.fn(),
  addEventListener: vi.fn((_event: string, callback: () => void) => callback()),
};
const gain = {
  gain: {
    setValueAtTime: vi.fn(),
    exponentialRampToValueAtTime: vi.fn(),
  },
  connect: vi.fn(),
  disconnect: vi.fn(),
};

class FakeAudioContext {
  static instances = 0;
  state = "suspended";
  currentTime = 10;
  destination = {};
  resume = resume;
  createOscillator = () => oscillator;
  createGain = () => gain;

  constructor() {
    FakeAudioContext.instances++;
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
  FakeAudioContext.instances = 0;
});

describe("createPlaybackChime", () => {
  it("synthesizes the shared tone and disconnects completed nodes", async () => {
    vi.stubGlobal("AudioContext", FakeAudioContext);
    const chime = createPlaybackChime();
    await chime.play();

    expect(FakeAudioContext.instances).toBe(1);
    expect(resume).toHaveBeenCalledOnce();
    expect(oscillator.frequency.setValueAtTime).toHaveBeenCalledWith(180, 10);
    expect(oscillator.stop).toHaveBeenCalledWith(10.25);
    expect(oscillator.disconnect).toHaveBeenCalledOnce();
    expect(gain.disconnect).toHaveBeenCalledOnce();
  });

  it("unlocks once on the first pointer or key interaction", () => {
    vi.stubGlobal("AudioContext", FakeAudioContext);
    const chime = createPlaybackChime();
    const cleanup = chime.unlockOnInteraction();

    window.dispatchEvent(new Event("pointerdown"));
    window.dispatchEvent(new Event("keydown"));
    cleanup();

    expect(resume).toHaveBeenCalledOnce();
    expect(FakeAudioContext.instances).toBe(1);
  });
});
