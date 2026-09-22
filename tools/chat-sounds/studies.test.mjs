import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {CANDIDATES, DEFAULT_SETTINGS, ReceiveBurstGate, SAMPLE_RATE, TONES, VariantPicker, sanitizeSettings, synthesize} from "./studies.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const output = path.join(root, "build/sound-previews");
const rms = (samples) => Math.sqrt(samples.reduce((sum, value) => sum + value * value, 0) / samples.length);
const peak = (samples) => Math.max(...samples.map(Math.abs));

for (const candidate of CANDIDATES) {
  for (const cue of ["send", "receive"]) {
    const baseline = synthesize(candidate.id, cue, 0, "balanced");
    assert.equal(baseline.constructor, Float32Array);
    assert.equal(baseline.length, synthesize(candidate.id, cue, 0, "balanced").length);
    assert.ok(baseline.length >= SAMPLE_RATE * 0.07 && baseline.length <= SAMPLE_RATE * 0.14);
    assert.equal(baseline[0], 0);
    assert.equal(baseline.at(-1), 0);
    assert.ok(peak(baseline) <= 0.16);
    for (const tone of TONES.filter((value) => value !== "balanced")) {
      assert.notDeepEqual([...baseline], [...synthesize(candidate.id, cue, 0, tone)]);
    }
    const levels = [0, 1, 2, 3, 4].map((take) => rms(synthesize(candidate.id, cue, take, "balanced")));
    assert.ok(20 * Math.log10(Math.max(...levels) / Math.min(...levels)) < 0.5);
  }
}
assert.equal(CANDIDATES.length, 4);
assert.deepEqual(CANDIDATES.map(({id}) => id), ["soft-glass", "terminal-tick", "arcade-pluck", "16bit-synth"]);
for (const candidate of CANDIDATES) {
  assert.equal(candidate.melodies.length, 5);
  assert.equal(candidate.motif.length, 5);
  assert.equal(candidate.degrees.length, 5);
  assert.equal(candidate.notes.length, 5);
  assert.equal(candidate.motif[0], 0);
  assert.equal(candidate.motif.at(-1), 0);
  assert.deepEqual(candidate.melodies.map(({id}) => id), ["homecoming", "climb", "relay", "beacon", "victory"]);
  for (const melody of candidate.melodies) {
    assert.equal(melody.motif.length, 5);
    assert.equal(melody.degrees.length, 5);
    assert.equal(melody.notes.length, 5);
    assert.equal(melody.degrees[0], 1);
    assert.equal(melody.degrees.at(-1), 1);
    assert.equal(melody.notes[0], candidate.rootNote);
    assert.equal(melody.notes.at(-1), candidate.rootNote);
  }
}
assert.notDeepEqual([...synthesize("soft-glass", "send", 0, "balanced")], [...synthesize("16bit-synth", "send", 0, "balanced")]);

const repaired = sanitizeSettings({master: 102, variation: "wrong", send: {candidate: "nope", enabled: "yes", volume: -4, pitch: 9, tone: "no"}});
assert.equal(repaired.master, 100);
assert.equal(repaired.variation, "musical");
assert.deepEqual(repaired.send, {candidate: "soft-glass", enabled: true, volume: 0, pitch: 4, tone: "balanced"});
assert.deepEqual(sanitizeSettings(null), DEFAULT_SETTINGS);
assert.equal(sanitizeSettings({receive: {candidate: "current"}}).receive.candidate, "current");
assert.equal(sanitizeSettings({receive: {candidate: "arcade-pluck", melody: "victory"}}).receive.melody, "victory");
assert.equal(sanitizeSettings({receive: {candidate: "arcade-pluck", melody: "not-real"}}).receive.melody, "homecoming");
assert.equal(sanitizeSettings({receive: {candidate: "current", melody: "relay"}}).receive.melody, "homecoming");

const gate = new ReceiveBurstGate();
for (let i = 0; i < 5; i++) assert.equal(gate.accept("receive", i * 10), true);
assert.equal(gate.accept("send", 100), true);
assert.equal(gate.accept("receive", 100), false);
assert.equal(gate.accept("receive", 2_099), false);
assert.equal(gate.accept("receive", 4_099), true);

const picker = new VariantPicker();
const natural = Array.from({length: 5}, () => picker.next("receive", "natural").take);
assert.deepEqual([...natural].sort(), [0, 1, 2, 3, 4]);
const firstNext = picker.next("receive", "natural").take;
assert.notEqual(firstNext, natural.at(-1));
const sendNatural = Array.from({length: 5}, () => picker.next("send", "natural").take);
assert.deepEqual([...sendNatural].sort(), [0, 1, 2, 3, 4]);
assert.equal(picker.next("send", "fixed").take, 2);
assert.deepEqual(Array.from({length: 4}, () => picker.next("receive", "musical").semitones), [0, 4, 7, 2]);
assert.deepEqual(picker.next("send", "musical"), {take: 2, semitones: 0});
assert.deepEqual(picker.next("receive", "fixed"), {take: 2, semitones: 0});
for (const candidate of CANDIDATES) {
  for (const melody of candidate.melodies) {
    picker.reset();
    const phrase = Array.from({length: 5}, () => picker.next("receive", "musical", candidate.id, melody.id));
    assert.deepEqual(phrase.map(({semitones}) => semitones), melody.motif);
    assert.deepEqual(phrase.map(({note}) => note), melody.notes);
    assert.ok(phrase.every(({take}) => take === 2));
    assert.deepEqual(picker.next("receive", "musical", candidate.id, melody.id), {take: 2, semitones: melody.motif[0], melody: melody.id, note: melody.notes[0], degree: 1});
  }
}
picker.reset();
assert.equal(picker.next("receive", "musical", "soft-glass", "climb").semitones, 0);
assert.equal(picker.next("receive", "musical", "soft-glass", "victory").semitones, 0);
assert.equal(picker.next("receive", "musical", "soft-glass", "climb").semitones, 2);
const highestPhraseSemitone = Math.max(...CANDIDATES.flatMap(candidate => candidate.melodies.flatMap(melody => melody.motif)));
assert.ok(2 ** ((highestPhraseSemitone + 4) / 12) < 2, "highest phrase note plus user pitch is within SoundPool's 2x rate limit");
picker.reset();
assert.deepEqual(picker.next("receive", "musical"), {take: 2, semitones: 0, melody: "homecoming", note: "D6", degree: 1});

execFileSync("node", ["tools/chat-sounds/generate-studies.mjs"], {cwd: root, stdio: "inherit"});
const manifest = JSON.parse(fs.readFileSync(path.join(output, "manifest.json"), "utf8"));
assert.equal(manifest.assets.length, 120);
for (const asset of manifest.assets) {
  const sample = fs.readFileSync(path.join(output, asset.file));
  assert.equal(sample.subarray(0, 4).toString(), "RIFF");
  assert.equal(sample.subarray(8, 12).toString(), "WAVE");
  assert.equal(sample.readUInt32LE(24), SAMPLE_RATE);
  assert.equal(sample.readInt16LE(44), 0);
  assert.equal(sample.readInt16LE(sample.length - 2), 0);
  assert.ok(asset.peak <= 0.16);
}
execFileSync("node", ["tools/chat-sounds/generate-studies.mjs", "--check"], {cwd: root, stdio: "inherit"});
console.log("sound study tests passed");
