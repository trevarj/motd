#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import {CANDIDATES, SAMPLE_RATE, TONES, synthesize} from "./studies.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const outputDirectory = path.join(root, "build/sound-previews");
const check = process.argv.includes("--check");

function wav(samples) {
  const pcm = Buffer.alloc(samples.length * 2);
  for (let frame = 0; frame < samples.length; frame++) {
    pcm.writeInt16LE(Math.round(Math.max(-1, Math.min(1, samples[frame])) * 32767), frame * 2);
  }
  pcm.writeInt16LE(0, 0);
  pcm.writeInt16LE(0, pcm.length - 2);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVEfmt ", 8, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(SAMPLE_RATE, 24);
  header.writeUInt32LE(SAMPLE_RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36, "ascii");
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

function metrics(samples) {
  let peak = 0;
  let squares = 0;
  for (const sample of samples) {
    peak = Math.max(peak, Math.abs(sample));
    squares += sample * sample;
  }
  return {peak: Number(peak.toFixed(6)), rms: Number(Math.sqrt(squares / samples.length).toFixed(6))};
}

const assets = [];
const files = new Map();
for (const candidate of CANDIDATES) {
  for (const cue of ["send", "receive"]) {
    for (const tone of TONES) {
      for (let take = 0; take < 5; take++) {
        const id = `${candidate.id}-${cue}-${tone}-${take}`;
        const samples = synthesize(candidate.id, cue, take, tone);
        const file = `${id}.wav`;
        assets.push({id, candidate: candidate.id, cue, take, tone, file, durationMs: Math.round(samples.length / SAMPLE_RATE * 1000), ...metrics(samples)});
        files.set(file, wav(samples));
      }
    }
  }
}
const manifest = Buffer.from(`${JSON.stringify({version: 1, candidates: CANDIDATES, assets}, null, 2)}\n`);
files.set("manifest.json", manifest);

let stale = false;
if (!check) {
  const previousManifestPath = path.join(outputDirectory, "manifest.json");
  try {
    const previous = JSON.parse(fs.readFileSync(previousManifestPath, "utf8"));
    const activeCandidates = new Set(CANDIDATES.map(({id}) => id));
    for (const asset of previous.assets ?? []) {
      if (activeCandidates.has(asset.candidate) || typeof asset.file !== "string") continue;
      // Only remove names recorded by our previous manifest, never arbitrary files.
      if (path.basename(asset.file) !== asset.file || !asset.file.endsWith(".wav")) continue;
      fs.rmSync(path.join(outputDirectory, asset.file), {force: true});
    }
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}
for (const [file, content] of files) {
  const target = path.join(outputDirectory, file);
  if (check) {
    if (!fs.existsSync(target) || !fs.readFileSync(target).equals(content)) {
      console.error(`stale sound study: ${path.relative(root, target)}`);
      stale = true;
    }
  } else {
    fs.mkdirSync(outputDirectory, {recursive: true});
    fs.writeFileSync(target, content);
  }
}
if (stale) process.exit(1);
if (!check) console.log(`generated ${assets.length} sound studies in ${path.relative(root, outputDirectory)}`);
