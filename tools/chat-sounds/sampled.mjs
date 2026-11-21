import {spawnSync} from "node:child_process";
import {readFileSync, writeFileSync} from "node:fs";
import {join} from "node:path";

export const SAMPLED_FAMILIES = [
  {id: "rosewood-bars", label: "Rosewood bars", description: "Soft mallets on xylophone bars, close and dry.", cues: {
    send: {label: "Send", description: "One low, stopped knock."},
    receive: {label: "Receive", description: "A higher bar left to resonate."},
    notification: {label: "Notification", description: "Two strokes on one bar, the second with more weight."},
  }},
  {id: "harp-threads", label: "Harp threads", description: "Fingertip plucks with a warm string body and a little edge.", cues: {
    send: {label: "Send", description: "A single string caught almost as soon as it speaks."},
    receive: {label: "Receive", description: "Two strings brushed together, the upper one ringing free."},
    notification: {label: "Notification", description: "A high string answers with its lower octave after a short gap."},
  }},
];

// Zero-based GM programs; notes are [start, hold, MIDI key, velocity], in 5 ms ticks.
const scores = {
  "rosewood-bars": {program: 13,
    send: {ms: 100, notes: [[0, 5, 64, 48]]},
    receive: {ms: 170, notes: [[0, 24, 69, 54]]},
    notification: {ms: 300, notes: [[0, 6, 77, 47], [24, 21, 77, 73]]},
  },
  "harp-threads": {program: 46,
    send: {ms: 115, notes: [[0, 6, 62, 53]]},
    receive: {ms: 245, notes: [[0, 17, 62, 37], [4, 28, 69, 53]]},
    notification: {ms: 350, notes: [[0, 9, 79, 65], [28, 25, 67, 76]]},
  },
};

function midi(program, notes) {
  // Format 0, one track, 100 ticks per quarter at MIDI's default 120 BPM.
  const events = notes.flatMap(([start, hold, key, velocity]) => [
    [start, 0x90, key, velocity], [start + hold, 0x80, key, 0],
  ]).sort((a, b) => a[0] - b[0]);
  const track = [0, 0xc0, program];
  let previous = 0;
  for (const [tick, status, key, velocity] of events) {
    const delta = tick - previous;
    if (delta >= 128) throw new RangeError("Cue exceeds single-byte MIDI delta");
    track.push(delta, status, key, velocity);
    previous = tick;
  }
  track.push(0, 0xff, 0x2f, 0);
  const header = Buffer.from("4d546864000000060000000100644d54726b00000000", "hex");
  header.writeUInt32BE(track.length, 18);
  return Buffer.concat([header, Buffer.from(track)]);
}

export function renderSampled(familyId, cue, soundfont, directory) {
  if (!Object.hasOwn(scores, familyId)) throw new RangeError(`Unknown sampled family: ${familyId}`);
  const {program, ...voices} = scores[familyId];
  if (!Object.hasOwn(voices, cue)) throw new RangeError(`Unknown sampled cue: ${cue}`);
  const {ms, notes} = voices[cue];
  const score = join(directory, "cue.mid");
  const raw = join(directory, "cue.raw");
  writeFileSync(score, midi(program, notes));
  const render = spawnSync("fluidsynth", ["-niq", "-f", "/dev/null", "-R", "0", "-C", "0", "-r", "48000",
    "-T", "raw", "-O", "float", "-E", "little", "-F", raw, soundfont, score], {encoding: "utf8"});
  if (render.error) throw render.error;
  if (render.status !== 0 || /error:|failed to load/i.test(render.stderr)) {
    throw new Error(`FluidSynth (${render.signal ?? render.status}): ${render.stderr.trim()}`);
  }

  // Keep 1 ms before the first audible sample, rather than gating the mallet or finger attack.
  const stereo = readFileSync(raw);
  const mono = (i) => (stereo.readFloatLE(i * 8) + stereo.readFloatLE(i * 8 + 4)) / 2;
  let start = 0;
  while (start < stereo.length / 8 && Math.abs(mono(start)) < 0.00003) start++;
  const frames = ms * 48;
  start = Math.max(0, start - 48);
  if (start + frames > stereo.length / 8) throw new Error(`Silent or incomplete render: ${familyId}/${cue}`);
  const samples = new Float32Array(frames);
  let squares = 0;
  let peak = 0;
  for (let i = 0; i < frames; i++) {
    const tail = Math.min(1, (frames - 1 - i) / (48 * (cue === "send" ? 22 : 35)));
    const v = mono(start + i) * Math.min(1, i / 48) * tail * tail * (3 - 2 * tail);
    samples[i] = v;
    squares += v * v;
    peak = Math.max(peak, Math.abs(v));
  }
  if (!(squares > 0) || !Number.isFinite(squares)) throw new Error(`Invalid render: ${familyId}/${cue}`);
  const targetRms = cue === "send" ? 0.0175 : cue === "receive" ? 0.0195 : 0.027;
  const gain = Math.min(targetRms / Math.sqrt(squares / frames), (cue === "notification" ? 0.17 : 0.13) / peak);
  // Canonical 48 kHz mono PCM16 header; RIFF and data sizes depend on the cue.
  const header = Buffer.from("524946460000000057415645666d7420100000000100010080bb000000770100020010006461746100000000", "hex");
  const pcm = Buffer.allocUnsafe(frames * 2);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.writeUInt32LE(pcm.length, 40);
  for (let i = 0; i < frames; i++) pcm.writeInt16LE(Math.round(samples[i] * gain * 32767), i * 2);
  return Buffer.concat([header, pcm]);
}
