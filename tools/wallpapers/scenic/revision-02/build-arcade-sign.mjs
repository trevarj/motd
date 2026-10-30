// The v3 MOTD marquee is constructed from approved semantic brand masks.
// Its rare OFF state redraws only named tube, halo, and puddle-reflection pixels.
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const directory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(directory, "../../../..");
const width = 256;
const height = 448;
const rawLength = width * height * 4;
const hex = color => color.match(/\w\w/g).map(value => Number.parseInt(value, 16));
const toHex = values => "#" + values.map(value => value.toString(16).padStart(2, "0")).join("");
const point = (x, y) => y * width + x;
const rgba = (pixels, x, y) => [...pixels.subarray(point(x, y) * 4, point(x, y) * 4 + 3)];
const colorAt = (pixels, x, y) => toHex(rgba(pixels, x, y));
const set = (pixels, x, y, color) => {
  assert.ok(Number.isInteger(x) && Number.isInteger(y) && x >= 0 && x < width && y >= 0 && y < height);
  pixels.set([...hex(color), 255], point(x, y) * 4);
};
const pixelList = map => [...map.keys()].map(key => {
  const [x, y] = key.split(",").map(Number);
  return [x, y];
});

function raster(file, colors = 192) {
  const bytes = execFileSync("magick", [file, "-filter", "box", "-resize", width + "x" + height + "!", "-colors", String(colors), "rgba:-"], { maxBuffer: rawLength + 1024 });
  assert.equal(bytes.length, rawLength, "Unexpected canonical raster dimensions");
  return new Uint8Array(bytes);
}
function alphaMask(file, maskWidth, maskHeight, crop = null) {
  const bytes = execFileSync("magick", [file, "-alpha", "extract", ...(crop ? ["-crop", crop, "+repage"] : []), "-trim", "+repage", "-filter", "point", "-resize", maskWidth + "x" + maskHeight + "!", "-threshold", "50%", "-depth", "8", "gray:-"]);
  assert.equal(bytes.length, maskWidth * maskHeight, "Unexpected brand mask dimensions");
  return { width: maskWidth, height: maskHeight, pixels: bytes };
}
function regionsFor(source, study) {
  const buckets = new Map();
  for (let index = 0; index < source.length; index += 4) {
    const key = source[index] + "," + source[index + 1] + "," + source[index + 2];
    const bucket = buckets.get(key) ?? [[], [], []];
    for (let channel = 0; channel < 3; channel++) bucket[channel].push(study[index + channel]);
    buckets.set(key, bucket);
  }
  const median = values => values.sort((a, b) => a - b)[Math.floor(values.length / 2)];
  const map = new Map([...buckets].map(([key, channels]) => [key, channels.map(median)]));
  const output = new Uint8Array(source);
  for (let index = 0; index < source.length; index += 4) {
    const key = source[index] + "," + source[index + 1] + "," + source[index + 2];
    output.set([...map.get(key), 255], index);
  }
  return output;
}
function glyphRanges(mask) {
  const ranges = [];
  let start = null;
  for (let x = 0; x <= mask.width; x++) {
    let occupied = false;
    if (x < mask.width) for (let y = 0; y < mask.height; y++) occupied ||= mask.pixels[y * mask.width + x] > 0;
    if (occupied && start === null) start = x;
    if (!occupied && start !== null) { ranges.push([start, x - 1]); start = null; }
  }
  assert.equal(ranges.length, 4, "The lockup must supply exactly m, o, t, d without a leading slash");
  return ranges;
}
function semanticLogo() {
  const symbol = alphaMask(path.join(root, "docs/assets/brand/motd-symbol-mask.png"), 34, 34);
  // The standalone wordmark includes /motd. The lockup's lettering omits that extra slash.
  const word = alphaMask(path.join(root, "docs/assets/brand/motd-lockup-dark.png"), 78, 26, "720x300+660+100");
  const pieces = [
    { name: "symbol", mask: symbol, origin: [70, 102] },
    ...glyphRanges(word).map(([from, to], index) => ({ name: ["m", "o", "t", "d"][index], mask: word, origin: [110, 107], minX: from, maxX: to })),
  ];
  const tube = new Map();
  for (const piece of pieces) {
    const { mask, origin: [left, top], minX = 0, maxX = mask.width - 1 } = piece;
    for (let y = 0; y < mask.height; y++) for (let x = minX; x <= maxX; x++) {
      if (!mask.pixels[y * mask.width + x]) continue;
      const neighbours = [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]];
      const edge = neighbours.some(([nx, ny]) => nx < minX || nx > maxX || ny < 0 || ny >= mask.height || !mask.pixels[ny * mask.width + nx]);
      if (edge) tube.set((left + x) + "," + (top + y), piece.name);
    }
  }
  const halo = new Map();
  for (const key of tube.keys()) {
    const [x, y] = key.split(",").map(Number);
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const neighbour = (x + dx) + "," + (y + dy);
      if (!tube.has(neighbour) && x + dx >= 0 && x + dx < width && y + dy >= 0 && y + dy < height) halo.set(neighbour, "halo");
    }
  }
  return { tube, halo, pieces };
}
function writePng(file, pixels) {
  const result = spawnSync("magick", ["-size", width + "x" + height, "-depth", "8", "rgba:-", "-define", "png:color-type=6", file], { input: pixels });
  assert.equal(result.status, 0, result.stderr?.toString() || "ImageMagick failed");
}
function renderProofs(light, dark, offLight, offDark) {
  const proofDir = path.join(directory, "proofs");
  mkdirSync(proofDir, { recursive: true });
  const entries = [
    ["light-rest", light], ["light-off", offLight],
    ["dark-rest", dark], ["dark-off", offDark],
  ];
  for (const [name, pixels] of entries) writePng(path.join(proofDir, "neon-arcade-v3-" + name + ".png"), pixels);
  for (const mode of ["light", "dark"]) execFileSync("magick", [
    path.join(proofDir, "neon-arcade-v3-" + mode + "-rest.png"),
    path.join(proofDir, "neon-arcade-v3-" + mode + "-off.png"), "+append",
    path.join(proofDir, "neon-arcade-v3-" + mode + "-comparison.png"),
  ]);
  execFileSync("magick", [
    path.join(proofDir, "neon-arcade-v3-light-comparison.png"),
    path.join(proofDir, "neon-arcade-v3-dark-comparison.png"), "-append", "-scale", "200%",
    path.join(proofDir, "neon-arcade-v3-rest-off-grid.png"),
  ]);
}
function differenceMap(rest, off) {
  const changed = new Map();
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    if (colorAt(rest, x, y) !== colorAt(off, x, y)) changed.set(x + "," + y, [x, y, colorAt(off, x, y)]);
  }
  return changed;
}

export function buildArcadeSign() {
  const source = raster(path.join(directory, "neon-arcade-v3-source.png"));
  const study = raster(path.join(directory, "neon-arcade-v3-night-study.png"));
  const light = new Uint8Array(source);
  const dark = regionsFor(source, study);
  const cleanDark = new Uint8Array(dark);
  const offLight = new Uint8Array(light);
  const offDark = new Uint8Array(dark);
  const { tube, halo, pieces } = semanticLogo();
  const allOwned = new Set([...tube.keys(), ...halo.keys()]);
  const colors = {
    light: { cyan: "#3bd9e8", pink: "#e84f9a", cyanHalo: "#337986", pinkHalo: "#80435f", hardware: "#4f555c" },
    dark: { cyan: "#73f3f6", pink: "#ff6dbb", cyanHalo: "#216f7d", pinkHalo: "#782657", hardware: "#40464e" },
  };
  for (const [mode, pixels, off] of [["light", light, offLight], ["dark", dark, offDark]]) {
    for (const [key, role] of tube) {
      const [x, y] = key.split(",").map(Number);
      set(pixels, x, y, role === "symbol" ? colors[mode].cyan : colors[mode].pink);
      set(off, x, y, colors[mode].hardware);
    }
    for (const key of halo.keys()) {
      const [x, y] = key.split(",").map(Number);
      // The halo belongs only to its nearby tube. A cyan tube wins at joins.
      const adjacentSymbol = [[-1,-1],[0,-1],[1,-1],[-1,0],[1,0],[-1,1],[0,1],[1,1]]
        .some(([dx, dy]) => tube.get((x + dx) + "," + (y + dy)) === "symbol");
      set(pixels, x, y, adjacentSymbol ? colors[mode].cyanHalo : colors[mode].pinkHalo);
      // OFF restores the canonical signboard pixel; it cannot leave a colored halo.
    }
  }
  // A small, explicit reflection is physically tied to the sign, within the existing puddle.
  const reflection = [
    [99, 372, "symbol"], [100, 372, "symbol"], [101, 372, "symbol"],
    [132, 372, "word"], [133, 372, "word"], [134, 372, "word"], [135, 372, "word"],
    [101, 375, "symbol"], [102, 375, "symbol"], [103, 375, "symbol"], [104, 375, "symbol"],
    [130, 377, "word"], [131, 377, "word"], [132, 377, "word"], [133, 377, "word"], [134, 377, "word"],
  ];
  for (const [mode, pixels] of [["light", light], ["dark", dark]]) {
    for (const [x, y, role] of reflection) {
      set(pixels, x, y, role === "symbol" ? colors[mode].cyanHalo : colors[mode].pinkHalo);
      allOwned.add(x + "," + y);
    }
  }
  const lightOff = differenceMap(light, offLight);
  const darkOff = differenceMap(dark, offDark);
  assert.deepEqual(new Set(lightOff.keys()), allOwned, "Each changed daylight sign pixel must be declared");
  assert.deepEqual(new Set(darkOff.keys()), allOwned, "Each changed night sign pixel must be declared");
  assert.ok(allOwned.size < 4096, "Full sign event must fit the event contract");
  const event = {
    id: "motd-sign-full-off",
    label: "The full MOTD neon sign turns off, then returns",
    frames: [
      { durationMs: 180, light: [...lightOff.values()], dark: [...darkOff.values()] },
      { durationMs: 140, light: [], dark: [] },
    ],
  };
  const document = {
    schemaVersion: 1, scheduleSeed: "neon-arcade-v3-full-sign",
    width, height, initialDelayMs: [20000, 40000], quietIntervalMs: [45000, 90000], events: [event],
  };
  writePng(path.join(directory, "neon-arcade-v3-light.png"), light);
  writePng(path.join(directory, "neon-arcade-v3-dark.png"), dark);
  writePng(path.join(directory, "neon-arcade-v3-off-light.png"), offLight);
  writePng(path.join(directory, "neon-arcade-v3-off-dark.png"), offDark);
  writeFileSync(path.join(directory, "neon-arcade-v3-events.json"), JSON.stringify(document, null, 2) + "\n");
  writeFileSync(path.join(directory, "neon-arcade-v3-materials.json"), JSON.stringify({
    schemaVersion: 1, scene: "neon-arcade-v3", width, height,
    geometrySource: "neon-arcade-v3-source.png",
    nightPalette: "same-coordinate source palette mapping from neon-arcade-v3-night-study.png",
    brandSources: ["docs/assets/brand/motd-symbol-mask.png", "docs/assets/brand/motd-lockup-dark.png"],
    tubePieces: Object.fromEntries(pieces.map(piece => [piece.name, pixelList(new Map([...tube].filter(([, value]) => value === piece.name)))])),
    haloPixels: pixelList(halo), reflectionPixels: reflection.map(([x, y]) => [x, y]),
    offHardware: colors.light.hardware, offFrame: "Every tube, colored halo, and sign reflection returns to the non-emissive source or neutral hardware pixel.",
  }, null, 2) + "\n");
  renderProofs(light, dark, offLight, offDark);
  return { light, dark, offLight, offDark, cleanLight: source, cleanDark, document, allOwned, tube, halo };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) buildArcadeSign();
