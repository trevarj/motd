import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Run with nix develop .#sprite-studies -c node tools/export-branding.mjs.
// Raster-only sources retain the approved ceramic finish and original lettering.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const brand = path.join(root, "docs/assets/brand");
const res = path.join(root, "app/src/main/res");
const outputs = [];
function convert(args, input) {
  return execFileSync("magick", args, { input, maxBuffer: 64 * 1024 * 1024 });
}
function png(args, input) {
  return convert([...args, "-depth", "8", "-strip", "PNG32:-"], input);
}
function save(file, data) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, data);
  outputs.push(path.relative(root, file));
}
function resize(data, width, height) {
  return png(["png:-", "-filter", "Lanczos", "-resize", `${width}x${height}!`], data);
}
function layer(data, width, height, x, y) {
  // The input is already sized; extent adds transparent margins without stretching.
  return png(["png:-", "-background", "none", "-gravity", "northwest", "-extent", `${width}x${height}${-x >= 0 ? "+" : ""}${-x}${-y >= 0 ? "+" : ""}${-y}`], data);
}
function over(bottom, top, mode = "Over") {
  // MIFF supports concatenated frames, unlike PNG; keep composition in memory.
  const frames = [bottom, top].map((image) => convert(["png:-", "miff:-"], image));
  return png(["miff:-", "-compose", mode, "-composite"], Buffer.concat(frames));
}
function solid(width, height, color) {
  return png(["-size", `${width}x${height}`, `xc:${color}`]);
}
function cropVisible(data) {
  // Ignore nearly transparent generator residue when measuring optical bounds.
  const bounds = convert(["png:-", "-alpha", "extract", "-threshold", "50%", "-format", "%@", "info:"], data).toString();
  return png(["png:-", "-crop", bounds, "+repage"], data);
}

const source = readFileSync(path.join(brand, "motd-ceramic-master.png"));
const mask = readFileSync(path.join(brand, "motd-symbol-mask.png"));
const croppedMask = cropVisible(mask);
const [maskWidth, maskHeight] = convert(["png:-", "-format", "%w %h", "info:"], croppedMask).toString().split(" ").map(Number);
const canonicalCache = new WeakMap();
function canonicalCrop(data) {
  // Keep only the generated material: the original raster mask owns all geometry.
  if (!canonicalCache.has(data)) {
    canonicalCache.set(data, over(resize(cropVisible(data), maskWidth, maskHeight), croppedMask, "CopyOpacity"));
  }
  return canonicalCache.get(data);
}
const [sourceWidth, sourceHeight] = convert(["png:-", "-format", "%w %h", "info:"], source).toString().split(" ").map(Number);
const pixels = convert(["png:-", "-depth", "8", "rgba:-"], source);
function shade([r, g, b]) {
  // Same polarity-aware affine mapping as ceramicLogoColorMatrix in the app.
  const light = (.2126 * r + .7152 * g + .0722 * b) / 255 >= .5;
  const result = Buffer.from(pixels);
  for (let offset = 0; offset < result.length; offset += 4) {
    const luminance = (.2126 * pixels[offset] + .7152 * pixels[offset + 1] + .0722 * pixels[offset + 2]) / 255;
    for (const [channel, color] of [r, g, b].entries()) {
      result[offset + channel] = Math.round(light ? color * (1 - .8 * luminance) : color + .8 * (255 - color) * luminance);
    }
  }
  return png(["-size", `${sourceWidth}x${sourceHeight}`, "-depth", "8", "rgba:-"], result);
}
const black = shade([0, 0, 0]);
const white = shade([255, 255, 255]);
const terminal = shade([51, 255, 102]);
function normalized(data, size, scale = 1) {
  // Align the approved artwork to the original 192-unit symbol canvas and bounds.
  const cropped = canonicalCrop(data);
  const unit = size / 192;
  const width = Math.round(164 * unit * scale);
  const height = Math.round(158 * unit * scale);
  const x = Math.round((96 + (16 - 96) * scale) * unit);
  const y = Math.round((96 + (24 - 96) * scale) * unit);
  return layer(resize(cropped, width, height), size, size, x, y);
}

save(path.join(brand, "motd-symbol.png"), normalized(black, 512));
save(path.join(brand, "motd-symbol-white.png"), normalized(white, 512));
save(path.join(brand, "motd-favicon.png"), over(solid(64, 64, "white"), normalized(black, 64, .85)));
save(path.join(brand, "motd-app-icon.png"), over(solid(512, 512, "#006C70"), normalized(white, 512, .74)));
save(path.join(root, "docs/assets/logo.png"), over(solid(1024, 1024, "#006C70"), normalized(white, 1024, .74)));

const lettering = readFileSync(path.join(brand, "motd-lettering-master.png"));
for (const [name, ink, inverse] of [["light", black, false], ["dark", white, true]]) {
  const text = inverse ? png(["png:-", "-channel", "RGB", "-negate", "+channel"], lettering) : lettering;
  const mark = layer(normalized(ink, Math.round(192 * .92 * 3)), 1470, 540, Math.round(23.7 * 3), 3);
  save(path.join(brand, `motd-lockup-${name}.png`), over(mark, resize(text, 1470, 540)));
}
// Move the existing lettering as a single raster layer; never redraw the glyphs.
const textCrop = png(["png:-", "-trim", "+repage"], lettering);
const stackedMark = layer(normalized(black, Math.round(192 * .92 * 2)), 1070, 626, 356, 2);
// Original lettering origin changed from (223.9,122) to (150.9,289).
const [textW, textH, textX, textY] = convert(["png:-", "-format", "%@", "info:"], lettering).toString().match(/\d+/g).map(Number);
const stackedText = layer(resize(textCrop, Math.round(textW / 2), Math.round(textH / 2)), 1070, 626,
  Math.round(textX / 2 - 73 * 2), Math.round(textY / 2 + 167 * 2));
save(path.join(brand, "motd-lockup-stacked.png"), over(stackedMark, stackedText));

const densities = [["mdpi", 1], ["hdpi", 1.5], ["xhdpi", 2], ["xxhdpi", 3], ["xxxhdpi", 4]];
for (const [density, factor] of densities) {
  const folder = path.join(res, `drawable-${density}`);
  save(path.join(folder, "motd_logo_mark.png"), resize(canonicalCrop(source), Math.round(96 * factor), Math.round(92 * factor)));
  for (const [name, data] of [["ic_launcher_foreground", white], ["ic_launcher_foreground_light", black], ["ic_launcher_foreground_terminal", terminal], ["ic_launcher_monochrome", mask]]) {
    save(path.join(folder, `${name}.png`), normalized(data, Math.round(108 * factor), .54));
  }
  save(path.join(folder, "ic_splash_logo.png"), normalized(white, Math.round(200 * factor), .66));
  // System notification tinting requires an unshaded white alpha silhouette.
  save(path.join(folder, "ic_notification_motd.png"), resize(mask, Math.round(24 * factor), Math.round(24 * factor)));
  save(path.join(folder, "motd_onboarding_wordmark.png"), resize(textCrop, Math.round(91 * factor), Math.round(31 * factor)));
}
console.log(`Exported ${outputs.length} brand PNGs from the approved raster master.`);
