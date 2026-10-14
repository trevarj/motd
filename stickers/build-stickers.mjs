#!/usr/bin/env node
/** Packages generated sticker rasters with the deterministic vector QR code. */
import childProcess from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const stickerDir = path.dirname(fileURLToPath(import.meta.url));
const qrFile = path.join(stickerDir, "qr", "motd-release.svg");
const target = "https://github.com/trevarj/motd/releases";
const editions = {
  v1: { prompt: "prompts.json", exports: "exports", qr: { x: 0.80, y: 0.80, size: 0.17 } },
  v2: { prompt: "prompts-v2.json", exports: "exports-v2" },
  v3: { prompt: "prompts-v3.json", exports: "exports-v3" },
};

function fail(message) { throw new Error(`Sticker build: ${message}`); }
function command(program, args) {
  const result = childProcess.spawnSync(program, args, { encoding: "utf8" });
  if (result.error) fail(`${program} is required: ${result.error.message}`);
  if (result.status !== 0) fail(`${program} failed: ${result.stderr || result.stdout}`);
  return result.stdout.trim();
}
function selectedEdition(args) {
  if (!args.length) return fs.existsSync(path.join(stickerDir, editions.v3.prompt)) ? "v3" : fs.existsSync(path.join(stickerDir, editions.v2.prompt)) ? "v2" : "v1";
  if (args.length === 2 && args[0] === "--edition" && editions[args[1]]) return args[1];
  fail("Usage: node stickers/build-stickers.mjs [--edition v1|v2|v3]");
}
function rasterInfo(file, requireAlpha) {
  const [width, height, channels] = command("magick", ["identify", "-format", "%w %h %[channels]", file]).split(/\s+/);
  const numericWidth = Number(width);
  const numericHeight = Number(height);
  if (!Number.isInteger(numericWidth) || !Number.isInteger(numericHeight) || numericWidth < 1 || numericHeight < 1) fail(`${path.basename(file)} has invalid dimensions`);
  if (requireAlpha && !channels.includes("a")) fail(`${path.basename(file)} must preserve transparent pixels outside the die-cut sticker`);
  return { width: numericWidth, height: numericHeight, channels };
}
function inlineQr() {
  const source = fs.readFileSync(qrFile, "utf8");
  const opening = source.match(/<svg\b[^>]*viewBox="([^"]+)"[^>]*>/i);
  const content = source.match(/<svg\b[^>]*>([\s\S]*)<\/svg>\s*$/i);
  if (!opening || !content) fail("qr/motd-release.svg must be an SVG with a viewBox");
  return { viewBox: opening[1], content: content[1].trim() };
}
function dataUri(file) { return `data:image/png;base64,${fs.readFileSync(file).toString("base64")}`; }
function qrPixelBox(width, height, placement) {
  const x = Math.round(width * placement.x);
  const y = Math.round(height * placement.y);
  const size = Math.round(width * placement.size);
  if (x < 0 || y < 0 || size < 1 || x + size > width || y + size > height) fail("QR placement extends beyond the generated canvas");
  return { x, y, size };
}
function assertOpaqueQrPanel(source, width, height, placement) {
  const { x, y, size } = qrPixelBox(width, height, placement);
  const [alphaMinimum, alphaMean] = command("magick", [source, "-alpha", "extract", "-crop", `${size}x${size}+${x}+${y}`, "+repage", "-format", "%[fx:minima] %[fx:mean]", "info:"]).split(/\s+/).map(Number);
  // Image generation can leave isolated semitransparent paper speckles. The full QR panel
  // must still be effectively opaque; a true silhouette crossing lowers its mean markedly.
  if (!Number.isFinite(alphaMinimum) || !Number.isFinite(alphaMean) || alphaMinimum < 0.85 || alphaMean < 0.98) fail(`${path.basename(source)} QR panel must remain wholly inside the opaque die-cut artwork`);
}
function finalSvg({ title, source, width, height, qr, placement }) {
  const { x: qrX, y: qrY, size: qrWidth } = qrPixelBox(width, height, placement);
  const qrHeight = qrWidth;
  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title">
  <title id="title">motd sticker: ${title}</title>
  <image width="${width}" height="${height}" href="${dataUri(source)}"/>
  <svg x="${qrX}" y="${qrY}" width="${qrWidth}" height="${qrHeight}" viewBox="${qr.viewBox}" preserveAspectRatio="xMidYMid meet" aria-label="QR code for motd releases">
    ${qr.content}
  </svg>
</svg>
`;
}
function parsePrompts(edition) {
  const promptPath = path.join(stickerDir, editions[edition].prompt);
  let stickers;
  try { stickers = JSON.parse(fs.readFileSync(promptPath, "utf8")); } catch (error) { fail(`cannot parse ${editions[edition].prompt}: ${error.message}`); }
  if (!Array.isArray(stickers) || stickers.length !== 6) fail(`${editions[edition].prompt} must contain exactly six stickers`);
  const slugs = new Set();
  for (const sticker of stickers) {
    if (!sticker || typeof sticker !== "object" || typeof sticker.slug !== "string" || !/^[0-9]{2}-[a-z0-9-]+$/.test(sticker.slug)) fail("each sticker needs a numbered lowercase slug");
    if (slugs.has(sticker.slug)) fail(`duplicate slug: ${sticker.slug}`);
    slugs.add(sticker.slug);
    if (typeof sticker.title !== "string" || !sticker.title) fail(`${sticker.slug} needs a title`);
    if (sticker.qrTarget !== target) fail(`${sticker.slug} must use the motd releases URL`);
    if (typeof sticker.artwork !== "string" || sticker.artwork.includes("..")) fail(`${sticker.slug} has an unsafe artwork path`);
    if (edition !== "v1") {
      if (!["circle", "rectangle", "rounded-rectangle"].includes(sticker.shape)) fail(`${sticker.slug} needs a supported die-cut shape`);
      const placement = sticker.qr;
      if (!placement || ![placement.x, placement.y, placement.size].every(Number.isFinite) || placement.x < 0 || placement.y < 0 || placement.size <= 0 || placement.x + placement.size > 1 || placement.y + placement.size > 1) fail(`${sticker.slug} has invalid QR placement`);
      if (edition === "v3" && (typeof sticker.paletteName !== "string" || !sticker.paletteName || !Array.isArray(sticker.palette) || !sticker.palette.every(color => typeof color === "string" && /^#[0-9a-f]{6}$/i.test(color)))) fail(`${sticker.slug} needs a named hexadecimal palette`);
    }
  }
  return stickers;
}
function build(edition) {
  const config = editions[edition];
  const qr = inlineQr();
  const stickers = parsePrompts(edition);
  const exportDir = path.join(stickerDir, config.exports);
  fs.mkdirSync(exportDir, { recursive: true });
  const assets = [];
  for (const sticker of stickers) {
    const source = path.resolve(stickerDir, sticker.artwork);
    if (!source.startsWith(`${stickerDir}${path.sep}`)) fail(`${sticker.slug} artwork escapes stickers`);
    if (!fs.existsSync(source)) {
      assets.push({ slug: sticker.slug, title: sticker.title, shape: sticker.shape, available: false, reason: "Awaiting generated artwork" });
      continue;
    }
    const { width, height, channels } = rasterInfo(source, edition !== "v1");
    const placement = sticker.qr ?? config.qr;
    if (edition !== "v1") assertOpaqueQrPanel(source, width, height, placement);
    const svg = path.join(exportDir, `${sticker.slug}.svg`);
    const png = path.join(exportDir, `${sticker.slug}.png`);
    fs.writeFileSync(svg, finalSvg({ title: sticker.title, source, width, height, qr, placement }));
    command("magick", ["-background", "none", svg, "-resize", `${width}x${height}!`, png]);
    const finalChannels = command("magick", ["identify", "-format", "%[channels]", png]);
    if (edition !== "v1" && !finalChannels.includes("a")) fail(`${sticker.slug} PNG export lost its die-cut transparency`);
    assets.push({ slug: sticker.slug, title: sticker.title, shape: sticker.shape, paletteName: sticker.paletteName, palette: sticker.palette, available: true, source: path.relative(stickerDir, source), svg: path.relative(stickerDir, svg), png: path.relative(stickerDir, png), width, height, channels, qr: { target, ...placement, errorCorrection: "M", quietZoneModules: 4, dataModules: 29 } });
  }
  fs.writeFileSync(path.join(exportDir, "manifest.json"), `${JSON.stringify({ edition, target, assets }, null, 2)}\n`);
  console.log(`Built ${assets.filter(asset => asset.available).length}/${assets.length} ${edition} QR-integrated sticker exports in ${path.relative(process.cwd(), exportDir)}`);
}

build(selectedEdition(process.argv.slice(2)));
