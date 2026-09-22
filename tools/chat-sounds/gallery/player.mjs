import { ReceiveBurstGate, VariantPicker, sanitizeSettings } from "../studies.mjs";

export const CONVERSATION = [
  [0, "receive", "Hey, have you tried the new sounds?"],
  [850, "send", "Trying them now."],
  [1550, "receive", "This one feels softer."],
  [2250, "receive", "And the tiny variations help."],
  [3200, "send", "Let's hear a busy chat too."],
  [4500, "receive", "A little breathing room."],
];
export const BURST = [
  ...Array.from({ length: 9 }, (_, index) => [index * 190, "receive", `Incoming message ${index + 1}`]),
  [1650, "send", "My send still sounds."],
  [2500, "receive", "Still busy: this extends the quiet timer."],
  [4650, "receive", "Two seconds of quiet: sounds resume."],
];
// A bouncy audition rhythm; real chats advance one note per incoming message.
export const MELODY = [0, 180, 480, 660, 1020].map((time, index) => [time, "receive", `Phrase note ${index + 1}`]);

/** Owns a single audition session; generation tokens prevent late loads from playing after Stop. */
export class StudyPlayer {
  constructor(catalog, { onEvent = () => {}, onState = () => {} } = {}) {
    this.catalog = catalog;
    this.onEvent = onEvent;
    this.onState = onState;
    this.settings = sanitizeSettings(null);
    this.gate = new ReceiveBurstGate();
    this.variants = new VariantPicker();
    this.cache = new Map();
    this.voices = [];
    this.timers = new Set();
    this.epoch = 0;
    this.pending = 0;
    this.running = false;
    this.context = null;
    this.played = 0;
    this.suppressed = 0;
    this.lastReceiveAt = null;
    this.auditionSignature = null;
  }

  setSettings(settings) {
    this.stop();
    this.settings = sanitizeSettings(settings);
  }

  stop({ preserveSequence = false } = {}) {
    this.epoch++;
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
    this.pending = 0;
    this.running = false;
    for (const { source, gain } of this.voices) {
      // A short release avoids a click when a user interrupts a cue.
      const now = this.context.currentTime;
      gain.gain.cancelScheduledValues(now);
      gain.gain.setValueAtTime(gain.gain.value, now);
      gain.gain.linearRampToValueAtTime(0, now + 0.006);
      try { source.stop(now + 0.006); } catch { /* The voice may have just ended. */ }
    }
    this.voices = [];
    if (!preserveSequence) {
      this.gate.reset();
      this.variants.reset();
      this.lastReceiveAt = null;
      this.auditionSignature = null;
    }
    this.onState({ running: false });
  }

  async ready() {
    if (!this.context) this.context = new AudioContext({ latencyHint: "interactive" });
    if (this.context.state !== "running") await this.context.resume();
    if (this.context.state !== "running") throw new Error("Audio is paused by the browser. Press a preview button again.");
  }

  async load(id) {
    if (!this.cache.has(id)) {
      const asset = this.catalog.assets.find(item => item.id === id);
      if (!asset) throw new Error("This sound is missing. Regenerate the studies and refresh.");
      const promise = (async () => {
        const response = await fetch(asset.url);
        if (!response.ok) throw new Error(`Could not load sound (${response.status}). Refresh to try again.`);
        const buffer = await this.context.decodeAudioData(await response.arrayBuffer());
        // The original WAVs are louder; use the same peak ceiling for an honest comparison.
        if (id.startsWith("current-")) {
          let peak = 0;
          for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
            for (const value of buffer.getChannelData(channel)) peak = Math.max(peak, Math.abs(value));
          }
          const scale = peak > 0.16 ? 0.16 / peak : 1;
          for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
            const samples = buffer.getChannelData(channel);
            for (let index = 0; index < samples.length; index++) samples[index] *= scale;
          }
        }
        return buffer;
      })();
      this.cache.set(id, promise);
      promise.catch(() => this.cache.delete(id));
    }
    return this.cache.get(id);
  }

  async preload(settings) {
    const ids = new Set();
    for (const cue of ["send", "receive"]) {
      const choice = settings[cue];
      if (!choice.enabled) continue;
      if (choice.candidate === "current") ids.add(`current-${cue}`);
      else for (let take = 0; take < 5; take++) ids.add(`${choice.candidate}-${cue}-${choice.tone}-${take}`);
    }
    await Promise.all([...ids].map(id => this.load(id)));
  }

  async start(sequence, { audition = false, settings = this.settings } = {}) {
    const selected = sanitizeSettings(settings);
    const signature = JSON.stringify(selected);
    // Repeated manual Receive taps should walk the phrase just like chat arrivals.
    this.stop({ preserveSequence: audition && signature === this.auditionSignature });
    this.auditionSignature = audition ? signature : null;
    const epoch = this.epoch;
    this.played = 0;
    this.suppressed = 0;
    this.running = true;
    this.onState({ running: true, loading: true });
    try {
      await this.ready();
      if (epoch !== this.epoch) return;
      await this.preload(selected);
      if (epoch !== this.epoch) return;
      this.pending = sequence.length;
      this.onState({ running: true, loading: false });
      for (const [delay, cue, text] of sequence) {
        const timer = setTimeout(() => {
          this.timers.delete(timer);
          if (epoch !== this.epoch) return;
          this.pending--;
          this.play(cue, { text, audition, settings: selected, epoch });
        }, delay);
        this.timers.add(timer);
      }
    } catch (error) {
      if (epoch !== this.epoch) return;
      this.stop();
      this.onEvent({ error: error.message });
    }
  }

  async play(cue, { text, audition, settings, epoch }) {
    const now = performance.now();
    if (cue === "receive") {
      if (settings.variation === "musical" && (this.lastReceiveAt === null || now - this.lastReceiveAt >= 2000)) this.variants.reset();
      this.lastReceiveAt = now;
    }
    const allowed = audition || this.gate.accept(cue, now);
    const choice = settings[cue];
    if (!allowed || !choice.enabled || choice.volume === 0 || settings.master === 0) {
      if (!allowed) this.suppressed++;
      this.onEvent({ cue, text, status: allowed ? "muted" : "capped", played: this.played, suppressed: this.suppressed });
      this.finishIfIdle();
      return;
    }
    try {
      const variant = this.variants.next(cue, settings.variation, choice.candidate, choice.melody);
      const id = choice.candidate === "current" ? `current-${cue}` : `${choice.candidate}-${cue}-${choice.tone}-${variant.take}`;
      const buffer = await this.load(id);
      if (epoch !== this.epoch) return;
      // Bound the simultaneous mix, independently of the five-message burst policy.
      if (this.voices.length >= 5) {
        const oldest = this.voices.shift();
        oldest.source.stop();
      }
      const source = this.context.createBufferSource();
      const gain = this.context.createGain();
      source.buffer = buffer;
      source.playbackRate.value = 2 ** ((choice.pitch + variant.semitones) / 12);
      gain.gain.value = settings.master / 100 * choice.volume / 100;
      source.connect(gain).connect(this.context.destination);
      const voice = { source, gain };
      this.voices.push(voice);
      source.onended = () => {
        source.disconnect();
        gain.disconnect();
        this.voices = this.voices.filter(item => item !== voice);
        if (epoch === this.epoch) this.finishIfIdle();
      };
      source.start();
      this.played++;
      this.onEvent({ cue, text, status: "played", variation: settings.variation, pitch: choice.pitch, take: variant.take, semitones: variant.semitones, melody: variant.melody, note: variant.note, degree: variant.degree, candidate: choice.candidate, played: this.played, suppressed: this.suppressed });
    } catch (error) {
      if (epoch !== this.epoch) return;
      this.stop();
      this.onEvent({ error: error.message });
    }
  }

  finishIfIdle() {
    if (this.pending === 0 && this.voices.length === 0) {
      this.running = false;
      this.onState({ running: false });
    }
  }
}
