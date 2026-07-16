#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "../..");
const outputDirectory = path.join(root, "app/src/main/res/raw");
const sampleRate = 48_000;
const dbToAmplitude = (db) => 10 ** (db / 20);
const oscillator = (frequency, time, phase = 0) =>
  Math.sin(2 * Math.PI * frequency * time + phase);
const sendPitch = 1108.73; // C#6
const perfectFourthRatio = 2 ** (5 / 12);

function messagePop(time, noise, pitchRatio) {
  const click = noise * Math.exp(-time / 0.005);
  const body = oscillator(225 * pitchRatio, time, -0.7) * Math.exp(-time / 0.013);
  const tone = oscillator(sendPitch * pitchRatio, time, 0.3) * Math.exp(-time / 0.014);
  const sparkle = oscillator(sendPitch * pitchRatio * 2, time, 0.8) * Math.exp(-time / 0.006);
  return 0.64 * click + 0.29 * body + 0.3 * tone + 0.07 * sparkle;
}

const sounds = {
  chat_send: {
    durationSeconds: 0.06,
    peakDb: -9,
    attackSeconds: 0.0007,
    releaseSeconds: 0.012,
    seed: 0x4d4f5444,
    sample(time, noise) {
      return messagePop(time, noise, 1);
    },
  },
  chat_receive: {
    durationSeconds: 0.06,
    peakDb: -6,
    attackSeconds: 0.0007,
    releaseSeconds: 0.012,
    seed: 0x4d4f5444,
    sample(time, noise) {
      return messagePop(time, noise, perfectFourthRatio);
    },
  },
};

function boundaryEnvelope(time, config) {
  const attack = Math.min(1, time / config.attackSeconds);
  const remaining = config.durationSeconds - time;
  const release = Math.min(1, Math.max(0, remaining / config.releaseSeconds));
  return attack * release;
}

function synthesize(config) {
  const frameCount = Math.round(config.durationSeconds * sampleRate);
  const samples = new Float64Array(frameCount);
  let maxMagnitude = 0;
  let noiseState = config.seed >>> 0;
  let smoothedNoise = 0;

  for (let frame = 0; frame < frameCount; frame++) {
    const time = frame / sampleRate;
    noiseState ^= noiseState << 13;
    noiseState ^= noiseState >>> 17;
    noiseState ^= noiseState << 5;
    const whiteNoise = ((noiseState >>> 0) / 0xffffffff) * 2 - 1;
    smoothedNoise = 0.72 * smoothedNoise + 0.28 * whiteNoise;
    const highPassedNoise = whiteNoise - smoothedNoise;
    const value = config.sample(time, highPassedNoise) * boundaryEnvelope(time, config);
    samples[frame] = value;
    maxMagnitude = Math.max(maxMagnitude, Math.abs(value));
  }

  const targetPeak = dbToAmplitude(config.peakDb);
  const scale = maxMagnitude === 0 ? 0 : targetPeak / maxMagnitude;
  const pcm = Buffer.alloc(frameCount * 2);
  for (let frame = 0; frame < frameCount; frame++) {
    const value = Math.max(-1, Math.min(1, samples[frame] * scale));
    pcm.writeInt16LE(Math.round(value * 32767), frame * 2);
  }
  // Make the click-free boundary explicit and deterministic.
  pcm.writeInt16LE(0, 0);
  pcm.writeInt16LE(0, pcm.length - 2);
  return pcm;
}

function wav(pcm) {
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36, "ascii");
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

const check = process.argv.includes("--check");
let stale = false;
for (const [name, config] of Object.entries(sounds)) {
  const target = path.join(outputDirectory, `${name}.wav`);
  const content = wav(synthesize(config));
  if (check) {
    if (!fs.existsSync(target) || !fs.readFileSync(target).equals(content)) {
      console.error(`stale generated chat sound: ${path.relative(root, target)}`);
      stale = true;
    }
  } else {
    fs.mkdirSync(outputDirectory, {recursive: true});
    fs.writeFileSync(target, content);
  }
}

if (stale) process.exit(1);
if (!check) {
  console.log(`generated ${Object.keys(sounds).length} chat sounds in ${path.relative(root, outputDirectory)}`);
}
