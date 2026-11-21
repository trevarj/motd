import {spawnSync} from "node:child_process";
import {join} from "node:path";
import {CANDIDATES, candidate} from "./studies.mjs";

const FRAMES_PER_MS = 48;
const levels = {
  send: {label: "Send", rms: 0.0175, peak: 0.13},
  receive: {label: "Receive", rms: 0.0195, peak: 0.13},
  notification: {label: "Notification", rms: 0.027, peak: 0.17},
};

// Strokes are [take, start ms, length ms, tail taper ms, relative peak].
// Each stroke uses a separate recording at its original pitch.
const families = [
  {id: "wire-zither", label: "Wire zither", source: {low: ["zither-low", "highshelf=f=5000:g=-5"], high: ["zither-high"]},
    description: "Recorded psaltery strings with a quick, human pluck and a thin wire shimmer.", cues: {
      send: {description: "One short, lower string pluck.", strokes: [["low", 0, 130, 16]]},
      receive: {description: "One higher string, allowed to ring longer.", strokes: [["high", 0, 223, 22]]},
      notification: {description: "Two separate strings rise from a low pluck to a bright answer.", strokes: [["low", 0, 115, 20, 0.90], ["high", 120, 230, 24]]},
    }},
  {id: "soft-vibes", label: "Soft vibes", source: {low: ["vibes-low"], high: ["vibes-high"]},
    description: "Recorded vibraphone with a gentle mallet strike and a soft metallic ring.", cues: {
      send: {description: "One low, brief mallet tone.", strokes: [["low", 0, 132, 50]]},
      receive: {description: "A higher mallet tone with a longer metallic tail.", strokes: [["high", 0, 260, 80]]},
      notification: {description: "A higher mallet tone answers the lower bar.", strokes: [["low", 0, 132, 50, 0.75], ["high", 140, 255, 80]]},
    }},
  {id: "mallet-xylophone", label: "Mallet Xylophone", source: {low: ["xylo-low"], high: ["xylo-high"]},
    description: "A softly struck xylophone, with a brief wooden ring after each mallet tap.", cues: {
      send: {description: "One short tap on the G5 bar.", strokes: [["low", 0, 150, 38]]},
      receive: {description: "The C6 bar rings on its own.", strokes: [["high", 0, 190, 48]]},
      notification: {description: "A light high tap settles onto the lower bar.", strokes: [["high", 0, 140, 40, 0.78], ["low", 150, 215, 50]]},
    }},
  {id: "dry-woodblock", label: "Dry Woodblock", source: {soft: ["woodblock-soft"], answer: ["woodblock-answer"]},
    description: "Two quiet strikes on a woodblock, with no other instrument underneath.", cues: {
      send: {description: "A single dry tap.", strokes: [["soft", 0, 108, 18]]},
      receive: {description: "A different strike, with room for the block to resonate.", strokes: [["answer", 0, 154, 24]]},
      notification: {description: "A light pickup tap just before the answering knock.", strokes: [["soft", 0, 70, 16, 0.68], ["answer", 75, 155, 24]]},
    }},
];

function decode(path, filter = "") {
  const result = spawnSync("ffmpeg", ["-nostdin", "-hide_banner", "-loglevel", "error", "-xerror",
    "-i", path, "-map", "0:a:0", "-ac", "1", "-ar", "48000", "-af", filter ? `highpass=f=38,${filter}` : "highpass=f=38",
    "-c:a", "pcm_f32le", "-f", "f32le", "pipe:1"], {maxBuffer: 16 * 1024 * 1024});
  if (result.error || result.status !== 0) {
    throw new Error(`FFmpeg could not decode ${path}: ${result.error?.message ?? (result.stderr.toString().trim() || result.signal || result.status)}`);
  }

  const pcm = result.stdout;
  if (!pcm.length || pcm.length % 4) throw new Error(`Empty or incomplete audio take: ${path}`);
  const frames = pcm.length / 4;
  const energy = new Float64Array(Math.ceil(frames / FRAMES_PER_MS));
  let loudest = 0;
  let peak = 0;
  for (let block = 0; block < energy.length; block++) {
    const first = block * FRAMES_PER_MS;
    const end = Math.min(frames, first + FRAMES_PER_MS);
    let squares = 0;
    for (let i = first; i < end; i++) {
      const v = pcm.readFloatLE(i * 4);
      if (!Number.isFinite(v)) throw new Error(`Non-finite audio in ${path}`);
      squares += v * v;
      peak = Math.max(peak, Math.abs(v));
    }
    energy[block] = squares / (end - first);
    loudest = Math.max(loudest, energy[block]);
  }
  // Find a quiet 1 ms onset window; keep 4 ms of lead-in rather than gating the attack.
  const threshold = Math.max(0.00003 ** 2, loudest * 0.015 ** 2);
  const onset = energy.findIndex(value => value >= threshold);
  const start = Math.max(0, onset - 4) * FRAMES_PER_MS;
  if (onset < 0 || frames - onset * FRAMES_PER_MS < 25 * FRAMES_PER_MS) throw new Error(`Silent or too short: ${path}`);
  return {pcm, start, frames, peak};
}

function render(takes, strokes, role, id, damping = 1) {
  const duration = Math.max(...strokes.map(([, offset, ms]) => offset + Math.round(ms * damping)));
  if (!Number.isInteger(duration) || duration < 1 || duration > 400) throw new RangeError(`Invalid duration: ${id}/${role}`);
  const frames = duration * FRAMES_PER_MS;
  const mix = new Float32Array(frames);
  for (const [note, offset, ms, taper, weight = 1] of strokes) {
    const {pcm, start, frames: available, peak} = takes[note];
    const length = Math.min(Math.round(ms * damping) * FRAMES_PER_MS, available - start);
    const base = offset * FRAMES_PER_MS;
    const gain = weight / peak;
    for (let i = 0; i < length; i++) {
      const head = Math.min(1, i / (0.5 * FRAMES_PER_MS));
      const tail = Math.min(1, (length - 1 - i) / (taper * FRAMES_PER_MS));
      mix[base + i] += pcm.readFloatLE((start + i) * 4) * gain * head * tail * tail * (3 - 2 * tail);
    }
  }

  let squares = 0;
  let peak = 0;
  for (const v of mix) {
    squares += v * v;
    peak = Math.max(peak, Math.abs(v));
  }
  if (!(squares > 0) || !Number.isFinite(squares)) throw new Error(`Silent or invalid cue: ${id}/${role}`);
  const level = levels[role];
  const peakLimit = Math.floor(level.peak * 32767) / 32767;
  const gain = Math.min(level.rms / Math.sqrt(squares / frames), peakLimit / peak);

  // Canonical 48 kHz mono PCM16 WAV header, followed by little-endian samples.
  const wav = Buffer.allocUnsafe(44 + frames * 2);
  wav.write("524946460000000057415645666d7420100000000100010080bb000000770100020010006461746100000000", "hex");
  wav.writeUInt32LE(wav.length - 8, 4);
  wav.writeUInt32LE(wav.length - 44, 40);
  for (let i = 0; i < frames; i++) wav.writeInt16LE(Math.round(mix[i] * gain * 32767), 44 + i * 2);
  return wav;
}

export function loadRecordedFamilies(directory) {
  return families.map(({id, label, description, source, cues}) => {
    const takes = decodeFamily(source, directory);
    return {id, label, description, cues: Object.fromEntries(Object.entries(cues).map(([role, cue]) => [role, {
      label: levels[role].label, description: cue.description, audio: render(takes, cue.strokes, role, id),
    }]))};
  });
}

function decodeFamily(source, directory, toneFilter = "") {
  return Object.fromEntries(Object.entries(source).map(([note, [file, filter]]) => [
    note,
    decode(join(directory, `${file}.wav`), [filter, toneFilter].filter(Boolean).join(",")),
  ]));
}

const scales = {
  "soft-vibes": ["A5", "B5", "C#6", "D6", "E6", "F#6", "G#6"],
  "wire-zither": ["C6", "D6", "E6", "F6", "G6", "A6", "B6"],
  "mallet-xylophone": ["C6", "D6", "E6", "F6", "G6", "A6", "B6"],
  "dry-woodblock": ["F#6", "G#6", "A#6", "B6", "C#7", "D#7", "E#7"],
};

const toneFilters = {warm: "highshelf=f=2000:g=-5", balanced: "", bright: "highshelf=f=2000:g=4"};
const naturalDamping = [0.86, 0.94, 1, 1.03, 0.90];

export function loadRecordedAppVoices(directory, tones) {
  if (!directory) throw new Error("Generate Android chat sounds from nix develop .#sound-authoring");
  const {motif, degrees} = CANDIDATES[0];
  return families.map(({id, label, description, source, cues}) => {
    const scale = scales[id];
    const catalog = candidate({id, pack: "Acoustic", label, description, rootNote: scale[0], scale, motif, degrees});
    const assets = tones.flatMap(tone => {
      const takes = decodeFamily(source, directory, toneFilters[tone]);
      return ["send", "receive"].flatMap(cue => naturalDamping.map((damping, take) => ({
        cue, tone, take, audio: render(takes, cues[cue].strokes, cue, id, damping),
      })));
    });
    return {catalog, assets};
  });
}
