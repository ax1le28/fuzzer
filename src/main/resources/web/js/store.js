// Per-browser conveniences (the task draft, last suite). Storage can be unavailable (private windows), so every
// access is guarded and the app works without it.

const PREFIX = 'promptfuzzer:';

export const store = {
  get(key, fallback = null) {
    try {
      const v = localStorage.getItem(PREFIX + key);
      return v == null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(PREFIX + key, JSON.stringify(value));
    } catch {
      /* storage unavailable: keep working in memory */
    }
  },
};
