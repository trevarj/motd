#!/usr/bin/env node
// Normalizes imagegen motif sheets before the deterministic wallpaper compositor uses them.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "../..");
const atlasDir = path.join(root, "tools/wallpapers/atlases");
const knownThemes = ["linux"];
const threshold = 12;
const flags = new Set(process.argv.slice(2).filter(argument => argument.startsWith("--")));
const requestedThemes = process.argv.slice(2).filter(argument => !argument.startsWith("--"));

if ([...flags].some(flag => flag !== "--check") || requestedThemes.some(theme => !knownThemes.includes(theme))) {
  throw new Error(`Usage: normalize-atlases.mjs [--check] [${knownThemes.join("|")}]`);
}
const themes = requestedThemes.length ? requestedThemes : knownThemes;

function magick(args) {
  const result = spawnSync("magick", args, { encoding: "buffer", maxBuffer: 160 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(String(result.stderr || result.error?.message || "ImageMagick failed").trim());
  return result.stdout;
}

function dimensions(file) {
  const values = magick([file, "-format", "%w %h", "info:"]).toString().trim().split(" ").map(Number);
  if (values.length !== 2 || values.some(value => !Number.isInteger(value) || value < 1)) throw new Error(`Could not read dimensions: ${file}`);
  return { width: values[0], height: values[1] };
}

function rawRgba(file, expected) {
  const pixels = magick([file, "-depth", "8", "rgba:-"]);
  if (pixels.length !== expected.width * expected.height * 4) throw new Error(`Could not decode RGBA pixels: ${file}`);
  return pixels;
}

function cellBounds(width, height, row, column) {
  const left = Math.floor(column * width / 4);
  const right = Math.floor((column + 1) * width / 4);
  const top = Math.floor(row * height / 4);
  const bottom = Math.floor((row + 1) * height / 4);
  return { left, top, right, bottom, width: right - left, height: bottom - top };
}

function normalize(source, destination) {
  const original = dimensions(source);
  const work = fs.mkdtempSync(path.join(os.tmpdir(), "motd-atlas-normalize-"));
  try {
    const originalAlpha = path.join(work, "original-alpha.png");
    const inkness = path.join(work, "inkness.png");
    const alpha = path.join(work, "alpha.png");
    const alphaCanvas = path.join(work, "canvas-0.png");
    // A white or gray imagegen fill becomes transparent while true black ink keeps its alpha.
    magick([source, "-alpha", "extract", "-depth", "8", originalAlpha]);
    magick([source, "-alpha", "off", "-fx", "1-max(max(r,g),b)", "-colorspace", "gray", "-depth", "8", inkness]);
    magick([originalAlpha, inkness, "-compose", "Multiply", "-composite", "-black-threshold", `${threshold / 255 * 100}%`, "-alpha", "off", "-depth", "8", alpha]);
    magick(["-size", `${original.width}x${original.height}`, "xc:black", "-depth", "8", alphaCanvas]);

    let canvas = alphaCanvas;
    for (let row = 0; row < 4; row++) for (let column = 0; column < 4; column++) {
      const cell = cellBounds(original.width, original.height, row, column);
      const crop = path.join(work, `crop-${row}-${column}.png`);
      const scaled = path.join(work, `scaled-${row}-${column}.png`);
      const nextCanvas = path.join(work, `canvas-${row}-${column}.png`);
      magick([alpha, "-crop", `${cell.width}x${cell.height}+${cell.left}+${cell.top}`, "+repage", "-trim", "+repage", "-depth", "8", crop]);
      const sourceSize = dimensions(crop);
      const scale = Math.min(1, Math.floor(cell.width * .6) / sourceSize.width, Math.floor(cell.height * .6) / sourceSize.height);
      if (!(scale > 0)) throw new Error(`${path.basename(source)} cell ${row + 1},${column + 1} has no visible ink`);
      const scaledWidth = Math.max(1, Math.floor(sourceSize.width * scale));
      const scaledHeight = Math.max(1, Math.floor(sourceSize.height * scale));
      magick([crop, "-filter", "Lanczos", "-resize", `${scaledWidth}x${scaledHeight}!`, "+repage", "-depth", "8", scaled]);
      const x = cell.left + Math.floor((cell.width - scaledWidth) / 2);
      const y = cell.top + Math.floor((cell.height - scaledHeight) / 2);
      magick([canvas, scaled, "-geometry", `+${x}+${y}`, "-compose", "Copy", "-composite", "-depth", "8", nextCanvas]);
      canvas = nextCanvas;
    }

    const temporary = `${destination}.tmp.png`;
    try {
      magick(["-size", `${original.width}x${original.height}`, "xc:black", canvas, "-alpha", "off", "-compose", "CopyOpacity", "-composite", "-strip", "-define", "png:color-type=6", "-define", "png:compression-level=9", temporary]);
      const inkPixels = validate(temporary, original);
      fs.renameSync(temporary, destination);
      return { original, inkPixels };
    } finally {
      fs.rmSync(temporary, { force: true });
    }
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

function validate(file, expected) {
  const actual = dimensions(file);
  if (actual.width !== expected.width || actual.height !== expected.height) throw new Error(`${path.basename(file)} dimensions changed`);
  const pixels = rawRgba(file, actual);
  let totalInk = 0;
  for (let row = 0; row < 4; row++) for (let column = 0; column < 4; column++) {
    const cell = cellBounds(actual.width, actual.height, row, column);
    let minX = cell.right, minY = cell.bottom, maxX = cell.left - 1, maxY = cell.top - 1;
    let centerHasTransparency = false;
    const centerLeft = cell.left + Math.floor(cell.width * .3);
    const centerRight = cell.right - Math.floor(cell.width * .3);
    const centerTop = cell.top + Math.floor(cell.height * .3);
    const centerBottom = cell.bottom - Math.floor(cell.height * .3);
    for (let y = cell.top; y < cell.bottom; y++) for (let x = cell.left; x < cell.right; x++) {
      const offset = (y * actual.width + x) * 4;
      const [red, green, blue, alpha] = pixels.subarray(offset, offset + 4);
      if (alpha && (red !== 0 || green !== 0 || blue !== 0)) throw new Error(`${path.basename(file)} has non-black visible pixels`);
      if (!alpha && x >= centerLeft && x < centerRight && y >= centerTop && y < centerBottom) centerHasTransparency = true;
      if (!alpha) continue;
      totalInk++;
      minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
    }
    if (maxX < minX || !centerHasTransparency) throw new Error(`${path.basename(file)} cell ${row + 1},${column + 1} is empty or lacks transparent interior samples`);
    const minimumX = Math.ceil(cell.width * .15);
    const minimumY = Math.ceil(cell.height * .15);
    if (minX - cell.left < minimumX || cell.right - 1 - maxX < minimumX || minY - cell.top < minimumY || cell.bottom - 1 - maxY < minimumY) {
      throw new Error(`${path.basename(file)} cell ${row + 1},${column + 1} violates the 15% transparent edge margin`);
    }
  }
  return totalInk;
}

for (const theme of themes) {
  const file = path.join(atlasDir, `${theme}.png`);
  if (!fs.existsSync(file)) throw new Error(`Missing atlas: ${file}`);
  const original = dimensions(file);
  const inkPixels = flags.has("--check") ? validate(file, original) : normalize(file, file).inkPixels;
  console.log(`${flags.has("--check") ? "verified" : "normalized"} ${theme}: ${original.width}x${original.height}, 16 cells, ${inkPixels} black ink pixels`);
}
