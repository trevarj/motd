import {SAMPLE_RATE} from "./studies.mjs";

export const POLISHED_FAMILIES = [
  {id: "felt-ceramic", label: "Felt ceramic", description: "A felt-tipped touch on glazed ceramic, with a clear rim and a short, rounded body."},
  {id: "reed", label: "Soft reed", description: "A breath of air opens into a mellow reed tone. Send settles down; receive lifts."},
  {id: "hollow-wood", label: "Hollow wood", description: "A dry, low wooden knock. A lighter contact just ahead of the incoming knock gives it depth."},
  {id: "muted-nylon", label: "Muted nylon", description: "One fingertip pluck on a nylon string: send is stopped short, receive rings free, and a muted pickup marks a mention."},
];

const turn = 2 * Math.PI;
const sine = (cycles) => Math.sin(turn * cycles);
const decay = (t, seconds) => Math.exp(-t / seconds);
const smooth = (x) => (x <= 0 ? 0 : x >= 1 ? 1 : x * x * (3 - 2 * x));

// A short cluster of midband partials adds contact texture without a broadband impulse.
function grain(t) {
  return (sine(1193 * t + 0.17) + sine(1811 * t + 0.43) + sine(2377 * t + 0.61) + sine(2897 * t + 0.28)) / 4;
}

function ceramic(t, incoming) {
  const f = incoming ? 815 : 680;
  return smooth(t / 0.003) * (
    sine(f * t) * decay(t, incoming ? 0.037 : 0.031) +
    0.20 * sine(2.38 * f * t + 0.12) * decay(t, 0.013) +
    0.055 * sine(3.11 * f * t + 0.23) * decay(t, 0.008)
  );
}

function reed(t, incoming) {
  // Integrate the pitch curve so changing frequency never jumps oscillator phase.
  const start = incoming ? 740 : 880;
  const end = incoming ? 900 : 750;
  const phase = end * t + (start - end) * 0.018 * -Math.expm1(-t / 0.018);
  const breath = 0.20 * grain(t) * decay(t, 0.007) * smooth(t / 0.002);
  return breath + smooth(t / 0.009) * (
    sine(phase) * decay(t, incoming ? 0.040 : 0.032) +
    0.18 * sine(2 * phase + 0.08) * decay(t, 0.022) + 0.05 * sine(3 * phase) * decay(t, 0.012)
  );
}

function wood(t, incoming) {
  const f = incoming ? 625 : 560;
  return smooth(t / 0.002) * (
    sine(f * t) * decay(t, 0.023) + 0.42 * sine(2.37 * f * t + 0.09) * decay(t, 0.011) +
    0.18 * grain(t) * decay(t, 0.003)
  );
}

function nylon(t, incoming) {
  const phase = (incoming ? 574 : 456) * t;
  // Plucking halfway along the string suppresses even modes. Moving toward
  // the bridge brings the octave back; the fingertip damps the upper modes first.
  const modes = incoming
    ? sine(phase + 0.25) * decay(t, 0.042) + 0.55 * sine(2 * phase + 0.25) * decay(t, 0.025)
    : sine(phase + 0.25) * decay(t, 0.022);
  return smooth(t / 0.004) * (modes - 0.16 * sine(3 * phase + 0.25) * decay(t, 0.013));
}

const voices = new Map([
  ["felt-ceramic", {strike: ceramic, send: 0.092, receive: 0.108, gap: 0.092}],
  ["reed", {strike: reed, send: 0.105, receive: 0.120, gap: 0.095}],
  ["hollow-wood", {strike: wood, send: 0.076, receive: 0.087, gap: 0.081}],
  ["muted-nylon", {strike: nylon, send: 0.078, receive: 0.135, gap: 0.083}],
]);

export function synthesizePolished(familyId, cue) {
  const voice = voices.get(familyId);
  if (!voice) throw new RangeError(`Unknown polished family: ${familyId}`);
  if (cue !== "send" && cue !== "receive" && cue !== "mention") throw new RangeError(`Unknown polished cue: ${cue}`);
  const incoming = cue !== "send";
  const lead = cue === "mention" ? voice.gap : familyId === "hollow-wood" && incoming ? 0.022 : 0;
  const length = incoming ? voice.receive : voice.send;
  const samples = new Float32Array(Math.round((lead + length) * SAMPLE_RATE));
  let peak = 0;
  let squares = 0;
  for (let i = 0; i < samples.length; i++) {
    const t = i / SAMPLE_RATE;
    const main = t < lead ? 0 : voice.strike(t - lead, incoming);
    let pickup = 0;
    if (lead && t < lead) {
      // A shortened, muted contact leaves space before the main stroke.
      pickup = (cue === "mention" ? 0.60 : 0.34) * voice.strike(t * 1.18, false) * smooth((lead - t) / 0.008);
    }
    const value = (main + pickup) * smooth((samples.length - 1 - i) / (SAMPLE_RATE * 0.012));
    samples[i] = value;
    squares += value * value;
    peak = Math.max(peak, Math.abs(value));
  }
  const targetRms = cue === "send" ? 0.0175 : 0.0195;
  const gain = Math.min(targetRms / Math.sqrt(squares / samples.length), 0.13 / peak);
  for (let i = 0; i < samples.length; i++) samples[i] *= gain;
  return samples;
}
