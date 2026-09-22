/**
 * Browser-safe definitions and synthesis for the chat-sound listening studies.
 * The generator imports this module too; keep Node APIs out of this file.
 */
export const SAMPLE_RATE = 48_000;

const phraseTemplates = [
  {id: "homecoming", label: "Homecoming", description: "The original reply phrase.", original: true},
  {id: "climb", label: "Climb", description: "A small rising answer.", motif: [0, 2, 4, 5, 0], degrees: [1, 2, 3, 4, 1]},
  {id: "relay", label: "Relay", description: "A playful up-and-back signal.", motif: [0, 4, 2, 5, 0], degrees: [1, 3, 2, 4, 1]},
  {id: "beacon", label: "Beacon", description: "A pentatonic call from the high point.", motif: [0, 2, 7, 4, 0], degrees: [1, 2, 5, 3, 1]},
  {id: "victory", label: "Victory", description: "A bright platformer-style finish.", motif: [0, 4, 5, 7, 0], degrees: [1, 3, 4, 5, 1]},
];

function makeMelodies({motif, degrees, scale}) {
  return phraseTemplates.map(template => {
    const phrase = template.original ? { ...template, motif, degrees } : template;
    return {
      id: phrase.id,
      label: phrase.label,
      description: phrase.description,
      motif: phrase.motif,
      degrees: phrase.degrees,
      notes: phrase.degrees.map(degree => scale[degree - 1]),
    };
  });
}

function candidate(config) {
  const melodies = makeMelodies(config);
  const original = melodies[0];
  // Keep the original top-level phrase fields for manifest consumers from the
  // first listening round. New consumers should use `melodies`.
  return {...config, motif: original.motif, degrees: original.degrees, notes: original.notes, melodies};
}

export const CANDIDATES = [
  candidate({id: "soft-glass", pack: "Soft Pop", label: "Soft glass", description: "A gentle glassy pluck with a soft edge.", rootNote: "D6", motif: [0, 4, 7, 2, 0], degrees: [1, 3, 5, 2, 1], scale: ["D6", "E6", "F#6", "G6", "A6", "B6", "C#7"]}),
  candidate({id: "terminal-tick", pack: "Terminal", label: "Terminal tick", description: "A restrained console confirmation tick.", rootNote: "B5", motif: [0, 7, 4, 2, 0], degrees: [1, 5, 3, 2, 1], scale: ["B5", "C#6", "D#6", "E6", "F#6", "G#6", "A#6"]}),
  candidate({id: "arcade-pluck", pack: "Bubble / Arcade", label: "Arcade pluck", description: "A compact arcade-like pluck.", rootNote: "Ab5", motif: [0, 4, 2, 7, 0], degrees: [1, 3, 2, 5, 1], scale: ["Ab5", "Bb5", "C6", "Db6", "Eb6", "F6", "G6"]}),
  candidate({id: "16bit-synth", pack: "16-bit Synth", label: "16-bit synth", description: "A warm layered console synth with a platformer-like reply phrase.", rootNote: "C5", motif: [0, 4, 7, 4, 0], degrees: [1, 3, 5, 3, 1], scale: ["C5", "D5", "E5", "F5", "G5", "A5", "B5"]}),
];

export const TONES = ["warm", "balanced", "bright"];

export const DEFAULT_SETTINGS = Object.freeze({
  version: 1,
  master: 70,
  variation: "musical",
  send: {candidate: "soft-glass", enabled: true, volume: 100, pitch: 0, tone: "balanced"},
  receive: {candidate: "soft-glass", enabled: true, volume: 100, pitch: 0, tone: "balanced", melody: "homecoming"},
});

const candidateIds = new Set(CANDIDATES.map(({id}) => id));
const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
const finiteNumber = (value, fallback) => Number.isFinite(value) ? value : fallback;

export function sanitizeSettings(value) {
  const source = value && typeof value === "object" ? value : {};
  const makeCue = (cue) => {
    const input = source[cue] && typeof source[cue] === "object" ? source[cue] : {};
    const defaults = DEFAULT_SETTINGS[cue];
    const soundCandidate = input.candidate === "current" || candidateIds.has(input.candidate) ? input.candidate : defaults.candidate;
    const knownMelodies = CANDIDATES.find(({id}) => id === soundCandidate)?.melodies ?? [];
    return {
      candidate: soundCandidate,
      enabled: typeof input.enabled === "boolean" ? input.enabled : defaults.enabled,
      volume: Math.round(clamp(finiteNumber(input.volume, defaults.volume), 0, 100)),
      pitch: Math.round(clamp(finiteNumber(input.pitch, defaults.pitch), -4, 4)),
      tone: TONES.includes(input.tone) ? input.tone : defaults.tone,
      ...(cue === "receive" ? {melody: knownMelodies.some(({id}) => id === input.melody) ? input.melody : DEFAULT_SETTINGS.receive.melody} : {}),
    };
  };
  return {
    version: 1,
    master: Math.round(clamp(finiteNumber(source.master, DEFAULT_SETTINGS.master), 0, 100)),
    variation: ["natural", "fixed", "musical"].includes(source.variation) ? source.variation : DEFAULT_SETTINGS.variation,
    send: makeCue("send"),
    receive: makeCue("receive"),
  };
}

/** Limits noisy receive bursts while leaving send feedback immediate. */
export class ReceiveBurstGate {
  #accepted = 0;
  #lastReceiveAt = null;

  accept(cue, nowMs) {
    if (cue !== "receive") return true;
    if (!Number.isFinite(nowMs)) return false;
    if (this.#lastReceiveAt === null || nowMs - this.#lastReceiveAt >= 2_000) {
      this.#accepted = 0;
    }
    this.#lastReceiveAt = nowMs;
    if (this.#accepted >= 5) return false;
    this.#accepted += 1;
    return true;
  }

  reset() {
    this.#accepted = 0;
    this.#lastReceiveAt = null;
  }
}

/** Picks five related takes without repetition for a given send/receive stream. */
export class VariantPicker {
  #bags = {send: [], receive: []};
  #last = {send: null, receive: null};
  #musical = new Map();
  #randomSource;

  constructor(random = Math.random) {
    this.#randomSource = typeof random === "function" ? random : Math.random;
  }

  #random() {
    return clamp(finiteNumber(this.#randomSource(), 0.5), 0, 0.999999999);
  }

  #refill(cue) {
    const bag = [0, 1, 2, 3, 4];
    for (let i = bag.length - 1; i > 0; i--) {
      const j = Math.floor(this.#random() * (i + 1));
      [bag[i], bag[j]] = [bag[j], bag[i]];
    }
    if (bag[0] === this.#last[cue]) [bag[0], bag[1]] = [bag[1], bag[0]];
    this.#bags[cue] = bag;
  }

  next(cue, mode = "natural", candidateId = "soft-glass", melodyId = "homecoming") {
    const safeCue = cue === "receive" ? "receive" : "send";
    if (mode === "fixed") return {take: 2, semitones: 0};
    if (mode === "musical") {
      if (safeCue === "send") return {take: 2, semitones: 0};
      const candidate = CANDIDATES.find(({id}) => id === candidateId) ?? CANDIDATES[0];
      const melody = candidate.melodies.find(({id}) => id === melodyId) ?? candidate.melodies[0];
      const key = `${candidate.id}:${melody.id}`;
      const index = (this.#musical.get(key) ?? 0) % melody.motif.length;
      this.#musical.set(key, index + 1);
      return {take: 2, semitones: melody.motif[index], melody: melody.id, note: melody.notes[index], degree: melody.degrees[index]};
    }
    if (this.#bags[safeCue].length === 0) this.#refill(safeCue);
    const take = this.#bags[safeCue].shift();
    this.#last[safeCue] = take;
    return {take, semitones: 0};
  }

  reset() {
    this.#bags = {send: [], receive: []};
    this.#last = {send: null, receive: null};
    this.#musical = new Map();
  }
}

function hash(...parts) {
  let state = 0x811c9dc5;
  for (const part of parts.join("|")) {
    state ^= part.charCodeAt(0);
    state = Math.imul(state, 0x01000193) >>> 0;
  }
  return state || 1;
}

function noiseSource(seed) {
  let state = seed >>> 0;
  let low = 0;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    const white = (state >>> 0) / 0x80000000 - 1;
    low = low * 0.78 + white * 0.22;
    return {white, low, high: white - low};
  };
}

const sine = (frequency, time, phase = 0) => Math.sin(Math.PI * 2 * frequency * time + phase);
const toneMix = {
  warm: {fundamental: 1.0, harmonic: 0.20, noise: 0.55},
  balanced: {fundamental: 1.0, harmonic: 0.42, noise: 0.82},
  bright: {fundamental: 0.92, harmonic: 0.68, noise: 1.08},
};

const configs = {
  // These are send fundamentals. Receive is a perfect fourth above each one.
  "soft-glass": {duration: 0.112, pitch: 880, type: "glass"}, // A5 -> D6
  "terminal-tick": {duration: 0.075, pitch: 739.988845, type: "tick"}, // F#5 -> B5
  "arcade-pluck": {duration: 0.115, pitch: 622.253967, type: "arcade"}, // Eb5 -> Ab5
  "16bit-synth": {duration: 0.132, pitch: 392, type: "console"}, // G4 -> C5
};

function shape(type, time, frequency, n, mix, variant) {
  const decay = Math.exp(-time / ({glass: 0.043, tick: 0.016, arcade: 0.040, console: 0.052}[type]));
  // Take two is the neutral, nominal-pitch reference. Outer glass/arcade
  // takes are about ±12 cents, enough to keep natural variation alive.
  const detune = 1 + variant * 0.007;
  switch (type) {
    case "glass": return decay * (0.60 * sine(frequency * detune, time) + mix.harmonic * 0.35 * sine(frequency * 2.76, time, 0.2) + mix.noise * 0.10 * n.high);
    case "tick": return decay * (0.42 * sine(frequency, time) + mix.noise * 0.48 * n.high + mix.harmonic * 0.12 * sine(frequency * 2.1, time));
    case "arcade": return decay * (0.65 * sine(frequency * detune, time) + mix.harmonic * 0.20 * sine(frequency * 2, time) + 0.17 * sine(frequency * 0.5, time));
    case "console": {
      const pulse = Math.tanh(2.4 * (sine(frequency * detune, time) + 0.34 * sine(frequency * 3 * detune, time))) * 0.56;
      const triangle = (2 / Math.PI) * Math.asin(sine(frequency * 0.5, time, 0.12)) * 0.34;
      const bell = sine(frequency * 2.01, time, 0.4) * Math.exp(-time / 0.014) * 0.22;
      const chorus = sine(frequency * 1.006, time, -0.18) * 0.18;
      return decay * (pulse + triangle + bell + chorus * mix.harmonic);
    }
    default: return 0;
  }
}

/** Synthesizes one mono take. It is deterministic and deliberately has no I/O. */
export function synthesize(candidateId, cue, take = 0, tone = "balanced") {
  const config = configs[candidateId];
  if (!config) throw new Error(`Unknown sound candidate: ${candidateId}`);
  if (cue !== "send" && cue !== "receive") throw new Error(`Unknown cue: ${cue}`);
  if (!Number.isInteger(take) || take < 0 || take > 4) throw new Error(`Unknown take: ${take}`);
  if (!TONES.includes(tone)) throw new Error(`Unknown tone: ${tone}`);
  const duration = clamp(config.duration + (cue === "receive" ? 0.012 : 0) + (take - 2) * 0.0015, 0.07, 0.14);
  const frames = Math.round(duration * SAMPLE_RATE);
  const raw = new Float32Array(frames);
  const getNoise = noiseSource(hash(candidateId, cue, take, tone));
  const cueRatio = cue === "receive" ? 2 ** (5 / 12) : 1;
  const variation = (take - 2) / 2;
  const mix = toneMix[tone];
  const attack = 0.0025;
  const release = 0.016;
  let sumSquares = 0;
  for (let frame = 0; frame < frames; frame++) {
    const time = frame / SAMPLE_RATE;
    const edge = Math.min(1, time / attack) * Math.min(1, Math.max(0, (duration - time) / release));
    const value = shape(config.type, time, config.pitch * cueRatio, getNoise(), mix, variation) * edge;
    raw[frame] = value;
    sumSquares += value * value;
  }
  const rms = Math.sqrt(sumSquares / frames) || 1;
  // A modest RMS leaves room for five overlapping receive cues. The value also
  // keeps the sharpest terminal take below the fixed 0.16 peak ceiling.
  let scale = 0.0195 / rms;
  let peak = 0;
  for (const value of raw) peak = Math.max(peak, Math.abs(value));
  scale = Math.min(scale, 0.15 / peak);
  for (let frame = 0; frame < frames; frame++) raw[frame] *= scale;
  raw[0] = 0;
  raw[frames - 1] = 0;
  return raw;
}
