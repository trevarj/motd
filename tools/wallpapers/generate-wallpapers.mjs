#!/usr/bin/env node
// Imagegen supplies complete motifs; periodic raster placement makes the joins exact.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(import.meta.dirname, "../..");
const atlasDir = path.join(root, "tools/wallpapers/atlases");
const outDir = path.join(root, "app/src/main/assets/chat-wallpapers");
const previewDir = path.join(root, "build/wallpaper-previews/export-qa");
const names = ["motd", "deep-space", "retro-gaming", "radio-club", "internet-oddities", "retro-chat", "memes"];
const TILE = 2048;
const flags = new Set(process.argv.slice(2));
if ([...flags].some(flag => !["--check", "--preview"].includes(flag))) throw new Error("Usage: generate-wallpapers.mjs [--check] [--preview]");
const selectedNames = process.env.WALLPAPER_THEME ? names.filter(name => name === process.env.WALLPAPER_THEME) : names;
if (!selectedNames.length) throw new Error("Unknown WALLPAPER_THEME");

function magick(args, input) {
  const result = spawnSync("magick", args, { input, maxBuffer: 160 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(String(result.stderr || result.error?.message || "ImageMagick failed").trim());
  return result.stdout;
}
function alphaImage(args, input) {
  // One process returns dimensions and raw 8-bit alpha, avoiding PNG round trips.
  const output = magick([...args, "-depth", "8", "-format", "%w %h\n", "-write", "info:-", "gray:-"], input);
  const newline = output.indexOf(10);
  const [width, height] = output.subarray(0, newline).toString().split(" ").map(Number);
  const pixels = output.subarray(newline + 1);
  if (!width || !height || pixels.length !== width * height) throw new Error("Invalid alpha image");
  return { width, height, pixels };
}
function extractMotifs(name, atlasName = name) {
  const atlas = alphaImage([path.join(atlasDir, `${atlasName}.png`), "-alpha", "extract"]);
  function dividers(vertical, top = 0, bottom = atlas.height) {
    const length = vertical ? atlas.width : atlas.height;
    const occupied = new Uint8Array(length);
    for (let y = top; y < bottom; y++) for (let x = 0; x < atlas.width; x++) {
      if (atlas.pixels[y * atlas.width + x] > 5) occupied[vertical ? x : y] = 1;
    }
    const positions = [0];
    // Generated sheets are visually aligned, not guaranteed to have exact quarter boundaries.
    // Choose actual empty gutters so a CRT stand or controller cable is never sliced in half.
    for (let index = 1; index < 4; index++) {
      const nominal = index * length / 4, radius = length / 16;
      let best = null, start = null;
      for (let coordinate = Math.floor(nominal - radius); coordinate <= Math.ceil(nominal + radius); coordinate++) {
        if (!occupied[coordinate]) { if (start === null) start = coordinate; }
        else if (start !== null) {
          const width = coordinate - start, middle = Math.floor((start + coordinate) / 2);
          const score = width - Math.abs(middle - nominal) * .2;
          if (width >= 8 && (!best || score > best.score)) best = { middle, score };
          start = null;
        }
      }
      if (start !== null) {
        const end = Math.ceil(nominal + radius), width = end - start, middle = Math.floor((start + end) / 2);
        const score = width - Math.abs(middle - nominal) * .2;
        if (width >= 8 && (!best || score > best.score)) best = { middle, score };
      }
      if (!best) throw new Error(`${name}: no intact ${vertical ? "column" : "row"} gutter ${index}`);
      positions.push(best.middle);
    }
    return [...positions, length];
  }
  const ys = dividers(false);
  const rowDividers = Array.from({ length: 4 }, (_, row) => dividers(true, ys[row], ys[row + 1]));
  const motifs = [];
  for (let row = 0; row < 4; row++) for (let column = 0; column < 4; column++) {
    const xs = rowDividers[row];
    const left = xs[column], top = ys[row], right = xs[column + 1], bottom = ys[row + 1];
    let x0 = right, y0 = bottom, x1 = left, y1 = top;
    for (let y = top; y < bottom; y++) for (let x = left; x < right; x++) {
      if (atlas.pixels[y * atlas.width + x] <= 5) continue;
      x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
    }
    if (x0 <= left + 3 || y0 <= top + 3 || x1 >= right - 4 || y1 >= bottom - 4 || x1 < x0 || y1 < y0) {
      throw new Error(`${name}: motif ${row},${column} is empty or cut by its atlas cell`);
    }
    x0 -= 3; y0 -= 3; x1 += 3; y1 += 3;
    const width = x1 - x0 + 1, height = y1 - y0 + 1, pixels = Buffer.alloc(width * height);
    for (let y = 0; y < height; y++) atlas.pixels.copy(pixels, y * width, (y + y0) * atlas.width + x0, (y + y0) * atlas.width + x0 + width);
    motifs.push({ width, height, pixels });
  }
  return motifs;
}
function random(seed) {
  let value = [...seed].reduce((n, c) => (Math.imul(n, 31) + c.charCodeAt(0)) >>> 0, 0x9e3779b9);
  return () => { value ^= value << 13; value ^= value >>> 17; value ^= value << 5; return (value >>> 0) / 0x100000000; };
}
function transform(motif, size, angle) {
  const stamp = alphaImage(["-size", `${motif.width}x${motif.height}`, "-depth", "8", "gray:-", "-filter", "Lanczos", "-resize", `${size}x${size}`, "-background", "black", "-rotate", String(angle)], motif.pixels);
  // Retain every nonzero alpha pixel here: a low-alpha antialias fringe still
  // participates in collision detection, so adjacent motifs cannot touch.
  stamp.ink = [];
  for (let y = 0; y < stamp.height; y++) for (let x = 0; x < stamp.width; x++) {
    if (stamp.pixels[y * stamp.width + x]) stamp.ink.push([x, y]);
  }
  return stamp;
}
function tileCoordinate(value) {
  value %= TILE;
  return value < 0 ? value + TILE : value;
}
function centerOf(stamp, x, y) {
  return { left: x - Math.floor(stamp.width / 2), top: y - Math.floor(stamp.height / 2) };
}
function fits(stamp, x, y, occupied) {
  const { left, top } = centerOf(stamp, x, y);
  for (const [sourceX, sourceY] of stamp.ink) {
    const targetX = tileCoordinate(left + sourceX), targetY = tileCoordinate(top + sourceY);
    if (occupied[targetY * TILE + targetX]) return false;
  }
  return true;
}
function occupy(stamp, x, y, occupied) {
  const { left, top } = centerOf(stamp, x, y);
  // A three-pixel toroidal dilation keeps independent doodles visibly apart
  // while letting their transparent bounding-box interiors interleave.
  for (const [sourceX, sourceY] of stamp.ink) for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) {
    const targetX = tileCoordinate(left + sourceX + dx), targetY = tileCoordinate(top + sourceY + dy);
    occupied[targetY * TILE + targetX] = 1;
  }
}
function arrange(name, motifs, details) {
  const next = random(`periodic-v4:${name}`), major = [], tiny = [], occupied = new Uint8Array(TILE * TILE);
  for (let index = 0; index < 180; index++) {
    const motifIndex = index < 16 ? index : Math.floor(next() * motifs.length);
    // First-cycle doodles retain the major visual vocabulary. Later repeats
    // vary from 115px to 200px, while the separate detail layer supplies the
    // genuinely small marks instead of shrinking every major motif.
    const size = Math.round(index < 16 ? 145 + next() * 50 : 115 + next() * 85);
    let inserted = false;
    const angle = Math.round(next() * 36 - 18);
    const stamp = transform(motifs[motifIndex], size, angle);
    // Anchor complete motifs across a corner and each axis; subsequent packing is irregular.
    const anchor = [[0, 0], [0, TILE / 2], [TILE / 2, 0]][index];
    for (let attempt = 0; attempt < 900; attempt++) {
      const [x, y] = anchor ?? [Math.floor(next() * TILE), Math.floor(next() * TILE)];
      if (!fits(stamp, x, y, occupied)) { if (anchor) break; else continue; }
      major.push({ ...stamp, x, y, motif: motifIndex, angle });
      occupy(stamp, x, y, occupied);
      inserted = true;
      break;
    }
    if (!inserted && index < 16) throw new Error(`${name}: could not place every unique motif`);
  }
  if (major.length < 130) throw new Error(`${name}: insufficient major motif density (${major.length})`);
  for (let index = 0; index < 145; index++) {
    const size = 25 + Math.round(next() * 40), stamp = transform(details[Math.floor(next() * details.length)], size, Math.round(next() * 70 - 35));
    for (let attempt = 0; attempt < 900; attempt++) {
      const x = Math.floor(next() * TILE), y = Math.floor(next() * TILE);
      if (!fits(stamp, x, y, occupied)) continue;
      tiny.push({ ...stamp, x, y });
      occupy(stamp, x, y, occupied);
      break;
    }
  }
  if (tiny.length < 100) throw new Error(`${name}: insufficient tiny detail density (${tiny.length})`);
  return { placed: [...major, ...tiny], major: major.length, tiny: tiny.length };
}
const blend = (destination, source) => source + Math.round(destination * (255 - source) / 255);
function periodicTile(placed) {
  const pixels = Buffer.alloc(TILE * TILE);
  for (const stamp of placed) {
    const left = stamp.x - Math.floor(stamp.width / 2), top = stamp.y - Math.floor(stamp.height / 2);
    for (let y = 0; y < stamp.height; y++) for (let x = 0; x < stamp.width; x++) {
      const source = stamp.pixels[y * stamp.width + x];
      if (!source) continue;
      const target = ((top + y + TILE) % TILE) * TILE + (left + x + TILE) % TILE;
      pixels[target] = blend(pixels[target], source);
    }
  }
  return pixels;
}
function independentScene(placed) {
  const width = TILE * 3, pixels = Buffer.alloc(width * width);
  // Independent method: translated whole motifs with ordinary clipping, without modulo mapping.
  for (const stamp of placed) for (let row = -1; row <= 3; row++) for (let column = -1; column <= 3; column++) {
    const left = stamp.x - Math.floor(stamp.width / 2) + column * TILE;
    const top = stamp.y - Math.floor(stamp.height / 2) + row * TILE;
    for (let y = Math.max(0, -top); y < Math.min(stamp.height, width - top); y++) {
      for (let x = Math.max(0, -left); x < Math.min(stamp.width, width - left); x++) {
        const source = stamp.pixels[y * stamp.width + x];
        if (!source) continue;
        const target = (top + y) * width + left + x;
        pixels[target] = blend(pixels[target], source);
      }
    }
  }
  return pixels;
}
function verifyPeriodicity(tile, scene, name) {
  const width = TILE * 3;
  for (let y = 0; y < width; y++) for (let column = 0; column < 3; column++) {
    const expected = tile.subarray((y % TILE) * TILE, (y % TILE + 1) * TILE);
    const actual = scene.subarray(y * width + column * TILE, y * width + (column + 1) * TILE);
    if (!expected.equals(actual)) throw new Error(`${name}: broken periodic placement at row ${y}, column ${column}`);
  }
  const ink = tile.reduce((total, alpha) => total + alpha, 0) / (255 * tile.length);
  if (ink < .025 || ink > .5) throw new Error(`${name}: unexpected ink coverage ${ink}`);
  return ink;
}
function encode(pixels, size) {
  return magick(["-size", `${size}x${size}`, "-depth", "8", "gray:-", "-alpha", "copy", "-channel", "RGB", "-evaluate", "set", "0", "+channel", "-depth", "8", "-strip", "-define", "png:color-type=4", "-define", "png:compression-level=9", "png:-"], pixels);
}
const outputs = [];
for (const name of selectedNames) {
  const motifs = extractMotifs(name), details = extractMotifs("details", "details"), arrangement = arrange(name, motifs, details), placed = arrangement.placed;
  const pixels = periodicTile(placed), scene = independentScene(placed);
  const ink = verifyPeriodicity(pixels, scene, name);
  const png = encode(pixels, TILE);
  const decoded = alphaImage(["png:-", "-alpha", "extract"], png);
  if (decoded.width !== TILE || decoded.height !== TILE || !decoded.pixels.equals(pixels)) throw new Error(`${name}: PNG encoding changed the verified periodic alpha`);
  const target = path.join(outDir, `${name}.png`);
  if (flags.has("--check") && (!fs.existsSync(target) || !fs.readFileSync(target).equals(png))) throw new Error(`Stale export: ${target}`);
  outputs.push({ target, png });
  if (flags.has("--preview")) {
    fs.mkdirSync(previewDir, { recursive: true });
    const scenePng = encode(scene, TILE * 3);
    fs.writeFileSync(path.join(previewDir, `${name}-3x3-white.png`), magick(["png:-", "-background", "white", "-alpha", "remove", "-alpha", "off", "-resize", "1536x1536", "png:-"], scenePng));
    fs.writeFileSync(path.join(previewDir, `${name}-3x3-dark.png`), magick(["png:-", "-channel", "RGB", "-evaluate", "set", "85%", "+channel", "-background", "#10131a", "-alpha", "remove", "-alpha", "off", "-resize", "1536x1536", "png:-"], scenePng));
    fs.writeFileSync(path.join(previewDir, `${name}-offset.png`), magick(["png:-", "-crop", `${TILE}x${TILE}+${TILE / 2}+${TILE / 2}`, "+repage", "-background", "white", "-alpha", "remove", "-alpha", "off", "-resize", "1024x1024", "png:-"], scenePng));
  }
  console.log(`${flags.has("--check") ? "verified" : "prepared"} ${name}: 16 complete motifs, ${arrangement.major} major + ${arrangement.tiny} tiny placements, ${(ink * 100).toFixed(1)}% ink; all 3x3 pixels match`);
}
// Validate every requested theme before replacing any shipped asset.
if (!flags.has("--check")) for (const { target, png } of outputs) {
  fs.mkdirSync(outDir, { recursive: true });
  fs.writeFileSync(`${target}.tmp`, png);
  fs.renameSync(`${target}.tmp`, target);
}
