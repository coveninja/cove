/** Shared Web Audio start chime used by the desktop, mobile, and TV shells. */
export function createPlaybackChime() {
  let audioContext: AudioContext | null = null;

  const context = (): AudioContext => {
    if (!audioContext) audioContext = new AudioContext();
    return audioContext;
  };

  const unlockOnInteraction = (): (() => void) => {
    const unlock = () => {
      context()
        .resume()
        .catch(() => {});
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
    };
    window.addEventListener("pointerdown", unlock);
    window.addEventListener("keydown", unlock);
    return () => {
      window.removeEventListener("pointerdown", unlock);
      window.removeEventListener("keydown", unlock);
    };
  };

  const play = async (): Promise<void> => {
    try {
      const ctx = context();
      if (ctx.state === "suspended") await ctx.resume();

      const now = ctx.currentTime;
      const oscillator = ctx.createOscillator();
      const gain = ctx.createGain();
      oscillator.type = "sine";
      oscillator.frequency.setValueAtTime(180, now);
      oscillator.frequency.exponentialRampToValueAtTime(70, now + 0.15);
      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.exponentialRampToValueAtTime(0.35, now + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.22);
      oscillator.connect(gain);
      gain.connect(ctx.destination);
      oscillator.start(now);
      oscillator.stop(now + 0.25);
      oscillator.addEventListener("ended", () => {
        oscillator.disconnect();
        gain.disconnect();
      });
    } catch (error) {
      console.error("playStartSound failed", error);
    }
  };

  return { play, unlockOnInteraction };
}
