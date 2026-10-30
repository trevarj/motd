#!/usr/bin/env node
/**
 * Local-only wallpaper study gallery. It intentionally exposes only files
 * registered by the preview manifest or a known final wallpaper filename.
 */
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { handleSoundRoute } from "../../chat-sounds/gallery/server-routes.mjs";

const galleryDir = path.dirname(fileURLToPath(import.meta.url));
const defaultRoot = path.resolve(galleryDir, "../../..");
const previewDirParts = ["build", "wallpaper-previews"];
const atlasDirParts = ["tools", "wallpapers", "atlases"];
const finalDirParts = ["app", "src", "main", "assets", "chat-wallpapers"];
const scenicDirParts = ["tools", "wallpapers", "scenic"];
const validId = /^[a-z0-9][a-z0-9-]{0,63}$/;
const validPngFile = /^[a-z0-9][a-z0-9._-]{0,127}\.png$/;
const validScenicFile = /^[a-z0-9][a-z0-9._-]{0,127}\.(?:png|webp)$/;
const validRevisionPng = /^revision-02\/[a-z0-9][a-z0-9._-]{0,127}\.png$/;
const validRevisionEvents = /^revision-02\/[a-z0-9][a-z0-9._-]{0,127}-events\.json$/;

export const themes = [
  { id: "motd", label: "MOTD", description: "Logos, wordmarks, and bathroom-wall graffiti. motd rulez!" },
  { id: "deep-space", label: "Deep Space", description: "Astronauts, rovers, planets, and small orbital surprises." },
  { id: "retro-gaming", label: "Retro Gaming", description: "CRTs, LAN cables, floppy disks, and classic PC energy." },
  { id: "radio-club", label: "Radio Club", description: "Radios, cassettes, antennas, and bright signal marks." },
  { id: "internet-oddities", label: "Internet Oddities", description: "Cable snakes, cursor critters, tiny robots, and computer cats." },
  { id: "retro-chat", label: "Retro Chat", description: "Buddy lists, away messages, and the early messenger era." },
  { id: "memes", label: "Memes", description: "A dense archive of chaotic internet reaction faces." },
  { id: "linux", label: "Linux", description: "Free-software mascots, terminals, and late-night command lines." },
  { id: "systemcrafters", label: "System Crafters", description: "System Crafters, Emacs, Guix horns, and #systemcrafters." },
];

const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".png": "image/png",
  ".webp": "image/webp",
  ".json": "application/json; charset=utf-8",
};

function safeStat(file) {
  try {
    const stat = fs.statSync(file);
    return stat.isFile() ? stat : null;
  } catch {
    return null;
  }
}

function registeredFile(root, directoryParts, fileName) {
  const directory = path.join(root, ...directoryParts);
  const candidate = path.join(directory, fileName);
  const stat = safeStat(candidate);
  if (!stat) return null;
  try {
    const realDirectory = fs.realpathSync(directory);
    const realFile = fs.realpathSync(candidate);
    const relative = path.relative(realDirectory, realFile);
    if (relative.startsWith("..") || path.isAbsolute(relative)) return null;
    return { file: realFile, stat };
  } catch {
    return null;
  }
}

function versionFor(file, stat = safeStat(file)) {
  return stat ? `${stat.size}-${Math.floor(stat.mtimeMs)}` : "0";
}

function previewManifest(root) {
  const file = path.join(root, ...previewDirParts, "manifest.json");
  const stat = safeStat(file);
  if (!stat) return { assets: [], error: null };
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    if (!Array.isArray(parsed.assets)) {
      return { assets: [], error: "Preview manifest must contain an assets array." };
    }
    return { assets: parsed.assets, error: null };
  } catch {
    return { assets: [], error: "Preview manifest is not valid JSON." };
  }
}

function scenicManifest(root) {
  const file = path.join(root, ...scenicDirParts, "manifest.json");
  const stat = safeStat(file);
  if (!stat) return { scenes: [], error: null };
  try {
    const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
    if (!Array.isArray(parsed.scenes)) return { scenes: [], error: "Scenic manifest must contain a scenes array." };
    return { scenes: parsed.scenes, error: null };
  } catch {
    return { scenes: [], error: "Scenic manifest is not valid JSON." };
  }
}

function pngDimensions(file) {
  try {
    const bytes = fs.readFileSync(file);
    if (bytes.length < 24 || !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return null;
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
  } catch { return null; }
}

function validPixels(pixels, width, height) {
  if (!Array.isArray(pixels) || pixels.length > 4096) return false;
  const seen = new Set();
  return pixels.every(pixel => {
    if (!Array.isArray(pixel) || pixel.length !== 3 || !Number.isInteger(pixel[0]) || !Number.isInteger(pixel[1]) || pixel[0] < 0 || pixel[0] >= width || pixel[1] < 0 || pixel[1] >= height || typeof pixel[2] !== "string" || !/^#[0-9a-fA-F]{6}$/.test(pixel[2])) return false;
    const coordinate = `${pixel[0]},${pixel[1]}`;
    if (seen.has(coordinate)) return false;
    seen.add(coordinate);
    return true;
  });
}

function eventDocument(root, relativePath) {
  if (!validRevisionEvents.test(relativePath)) return null;
  const registered = registeredFile(root, scenicDirParts, relativePath);
  if (!registered) return null;
  try {
    const parsed = JSON.parse(fs.readFileSync(registered.file, "utf8"));
    const interval = value => Array.isArray(value) && value.length === 2 && value.every(Number.isInteger) && value[0] >= 0 && value[0] <= value[1];
    if (!parsed || parsed.schemaVersion !== 1 || parsed.width !== 256 || parsed.height !== 448 || typeof parsed.scheduleSeed !== "string" || !parsed.scheduleSeed.trim() || !interval(parsed.initialDelayMs) || !interval(parsed.quietIntervalMs) || !Array.isArray(parsed.events) || !parsed.events.length || parsed.events.length > 16) return null;
    const ids = new Set();
    for (const event of parsed.events) {
      if (!event || typeof event.id !== "string" || !validId.test(event.id) || ids.has(event.id) || typeof event.label !== "string" || !event.label.trim() || !Array.isArray(event.frames) || event.frames.length < 2 || event.frames.length > 120) return null;
      ids.add(event.id);
      let changed = false;
      for (const frame of event.frames) {
        if (!frame || !Number.isInteger(frame.durationMs) || frame.durationMs < 1 || frame.durationMs > 10000 || !validPixels(frame.light, 256, 448) || !validPixels(frame.dark, 256, 448)) return null;
        changed ||= frame.light.length > 0 || frame.dark.length > 0;
      }
      const finalFrame = event.frames.at(-1);
      if (!changed || finalFrame.light.length || finalFrame.dark.length) return null;
    }
    return { ...parsed, file: registered.file, version: versionFor(registered.file, registered.stat) };
  } catch { return null; }
}

function scenicUrl(relativePath, version) {
  return `/scenic-assets/${relativePath.split("/").map(encodeURIComponent).join("/")}?v=${encodeURIComponent(version)}`;
}

function scenicVersion(root, sceneId, item) {
  if (!item || typeof item !== "object") return null;
  const { id, label, light, dark, motion, paired, events } = item;
  if (typeof id !== "string" || !validId.test(id) || typeof label !== "string" || !label.trim()) return null;
  const pixelEvents = motion === "pixel-events";
  const validImage = pixelEvents ? validRevisionPng : validPngFile;
  if (typeof light !== "string" || typeof dark !== "string" || !validImage.test(light) || !validImage.test(dark)) return null;
  if (motion !== undefined && !["pixel-shimmer", "animated-loop", "pixel-events"].includes(motion)) return null;
  if (paired !== undefined && typeof paired !== "boolean") return null;
  const day = registeredFile(root, scenicDirParts, light);
  const night = registeredFile(root, scenicDirParts, dark);
  if (!day || !night) return null;
  const eventData = pixelEvents && typeof events === "string" ? eventDocument(root, events) : null;
  if (pixelEvents && (!eventData || paired !== false || pngDimensions(day.file)?.width !== 256 || pngDimensions(day.file)?.height !== 448 || pngDimensions(night.file)?.width !== 256 || pngDimensions(night.file)?.height !== 448)) return null;
  const loop = typeof item.loop === "string" && validScenicFile.test(item.loop) ? registeredFile(root, scenicDirParts, item.loop) : null;
  return {
    id,
    label: label.trim().slice(0, 120),
    season: typeof item.season === "string" ? item.season.trim().slice(0, 60) : "",
    motion: motion ?? "none",
    paired: paired === true,
    prompt: typeof item.prompt === "string" ? item.prompt.trim().slice(0, 4000) : "",
    light: { file: day.file, version: versionFor(day.file, day.stat) },
    dark: { file: night.file, version: versionFor(night.file, night.stat) },
    loop: loop ? { file: loop.file, version: versionFor(loop.file, loop.stat) } : null,
    events: eventData ? { file: eventData.file, version: eventData.version, document: eventData } : null,
    lightRelativePath: light,
    darkRelativePath: dark,
    eventsRelativePath: pixelEvents ? events : null,
    sceneId,
  };
}

/** Builds the allowlisted scenic-study model; incomplete versions stay private. */
export function buildScenicGallery(root = defaultRoot) {
  const manifest = scenicManifest(root);
  const scenes = [];
  const seenScenes = new Set();
  for (const item of manifest.scenes) {
    if (!item || typeof item !== "object" || typeof item.id !== "string" || !validId.test(item.id) || seenScenes.has(item.id)) continue;
    if (typeof item.title !== "string" || !item.title.trim() || !Array.isArray(item.versions)) continue;
    const seenVersions = new Set();
    const versions = item.versions.map(version => scenicVersion(root, item.id, version)).filter(version => {
      if (!version || seenVersions.has(version.id)) return false;
      seenVersions.add(version.id);
      return true;
    }).map(version => ({
      id: version.id,
      label: version.label,
      season: version.season,
      motion: version.motion,
      paired: version.paired,
      prompt: version.prompt,
      lightUrl: scenicUrl(version.lightRelativePath, version.light.version),
      darkUrl: scenicUrl(version.darkRelativePath, version.dark.version),
      loopUrl: version.loop ? `/scenic-assets/${encodeURIComponent(path.basename(version.loop.file))}?v=${encodeURIComponent(version.loop.version)}` : "",
      eventsUrl: version.events ? scenicUrl(version.eventsRelativePath, version.events.version) : "",
      eventCount: version.events?.document.events.length ?? 0,
    }));
    if (!versions.length) continue;
    scenes.push({
      id: item.id,
      title: item.title.trim().slice(0, 120),
      description: typeof item.description === "string" ? item.description.trim().slice(0, 500) : "",
      versions,
    });
    seenScenes.add(item.id);
  }
  return { generatedAt: new Date().toISOString(), manifestError: manifest.error, scenes };
}

export function resolveScenicAsset(root, fileName) {
  if (typeof fileName !== "string" || (!validScenicFile.test(fileName) && !validRevisionPng.test(fileName) && !validRevisionEvents.test(fileName))) return null;
  const model = buildScenicGallery(root);
  for (const scene of model.scenes) {
    for (const version of scene.versions) {
      for (const url of [version.lightUrl, version.darkUrl, version.loopUrl, version.eventsUrl]) {
        if (!url) continue;
        const candidate = decodeURIComponent(new URL(url, "http://127.0.0.1").pathname.slice("/scenic-assets/".length));
        if (candidate === fileName) return registeredFile(root, scenicDirParts, fileName);
      }
    }
  }
  return null;
}

function assetFromManifest(root, item) {
  if (!item || typeof item !== "object") return null;
  const { id, theme, file } = item;
  if (typeof id !== "string" || typeof theme !== "string" || typeof file !== "string") return null;
  if (!validId.test(id) || !themes.some(candidate => candidate.id === theme) || !validPngFile.test(file)) return null;
  const registered = registeredFile(root, previewDirParts, file);
  if (!registered) return null;
  return {
    id,
    theme,
    label: typeof item.label === "string" && item.label.trim() ? item.label.trim().slice(0, 120) : id,
    status: typeof item.status === "string" && item.status.trim() ? item.status.trim().slice(0, 40) : "Draft",
    source: "preview",
    file: registered.file,
    version: versionFor(registered.file, registered.stat),
  };
}

/** Returns the complete, safe gallery model. Rebuilt on every request for refresh. */
export function buildGallery(root = defaultRoot) {
  const manifest = previewManifest(root);
  const assets = [];
  const seenIds = new Set();

  for (const item of manifest.assets) {
    const asset = assetFromManifest(root, item);
    if (asset && !seenIds.has(asset.id)) {
      assets.push(asset);
      seenIds.add(asset.id);
    }
  }

  for (const theme of themes) {
    const registered = registeredFile(root, finalDirParts, `${theme.id}.png`);
    const id = `${theme.id}-final`;
    if (registered && !seenIds.has(id)) {
      assets.push({
        id,
        theme: theme.id,
        label: `${theme.label} · Final`,
        status: "Final",
        source: "final",
        file: registered.file,
        version: versionFor(registered.file, registered.stat),
      });
    }
  }

  for (const theme of themes) {
    const registered = registeredFile(root, atlasDirParts, `${theme.id}.png`);
    const id = `${theme.id}-atlas`;
    if (registered && !seenIds.has(id)) {
      assets.push({
        id,
        theme: theme.id,
        label: `${theme.label} · Motif atlas`,
        status: "Atlas",
        source: "atlas",
        file: registered.file,
        version: versionFor(registered.file, registered.stat),
      });
    }
  }

  const publicAssets = assets.map(({ file, ...asset }) => ({
    ...asset,
    url: `/api/artwork/${encodeURIComponent(asset.id)}?v=${encodeURIComponent(asset.version)}`,
  }));
  return {
    generatedAt: new Date().toISOString(),
    manifestError: manifest.error,
    themes: themes.map(theme => ({
      ...theme,
      assets: publicAssets.filter(asset => asset.theme === theme.id),
    })),
  };
}

export function resolveArtwork(root, id) {
  if (!validId.test(id)) return null;
  const manifest = previewManifest(root);
  for (const item of manifest.assets) {
    const asset = assetFromManifest(root, item);
    if (asset?.id === id) return asset;
  }
  for (const theme of themes) {
    if (id !== `${theme.id}-final`) continue;
    const registered = registeredFile(root, finalDirParts, `${theme.id}.png`);
    return registered ? { file: registered.file, version: versionFor(registered.file, registered.stat) } : null;
  }
  for (const theme of themes) {
    if (id !== `${theme.id}-atlas`) continue;
    const registered = registeredFile(root, atlasDirParts, `${theme.id}.png`);
    return registered ? { file: registered.file, version: versionFor(registered.file, registered.stat) } : null;
  }
  return null;
}

function send(response, status, headers, body, headOnly) {
  response.writeHead(status, { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...headers });
  if (!headOnly) response.end(body);
  else response.end();
}

function sendFile(response, file, headOnly) {
  const stat = safeStat(file);
  const type = mimeTypes[path.extname(file)];
  if (!stat || !type) return send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
  try {
    const body = fs.readFileSync(file);
    send(response, 200, { "Content-Type": type, "Content-Length": body.byteLength }, body, headOnly);
  } catch {
    send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
  }
}

export function createServer(root = defaultRoot) {
  const staticFiles = new Map([
    ["/", "index.html"],
    ["/index.html", "index.html"],
    ["/gallery.css", "gallery.css"],
    ["/gallery.js", "gallery.js"],
    ["/scenic", "scenic.html"],
    ["/scenic/", "scenic.html"],
    ["/scenic.html", "scenic.html"],
    ["/scenic.css", "scenic.css"],
    ["/scenic.js", "scenic.js"],
  ]);

  return http.createServer((request, response) => {
    const method = request.method ?? "GET";
    if (method !== "GET" && method !== "HEAD") {
      send(response, 405, { "Allow": "GET, HEAD", "Content-Type": "text/plain; charset=utf-8" }, "Method not allowed\n", method === "HEAD");
      return;
    }
    const headOnly = method === "HEAD";
    let url;
    try {
      url = new URL(request.url ?? "/", "http://127.0.0.1");
    } catch {
      send(response, 400, { "Content-Type": "text/plain; charset=utf-8" }, "Invalid request URL\n", headOnly);
      return;
    }
    if (handleSoundRoute(root, url.pathname, response, headOnly)) return;
    if (url.pathname === "/api/gallery") {
      send(response, 200, { "Content-Type": "application/json; charset=utf-8" }, `${JSON.stringify(buildGallery(root))}\n`, headOnly);
      return;
    }
    if (url.pathname === "/api/scenic") {
      send(response, 200, { "Content-Type": "application/json; charset=utf-8" }, `${JSON.stringify(buildScenicGallery(root))}\n`, headOnly);
      return;
    }
    if (url.pathname.startsWith("/scenic-assets/")) {
      let fileName;
      try {
        fileName = decodeURIComponent(url.pathname.slice("/scenic-assets/".length));
      } catch {
        send(response, 400, { "Content-Type": "text/plain; charset=utf-8" }, "Invalid scenic asset name\n", headOnly);
        return;
      }
      const artwork = resolveScenicAsset(root, fileName);
      if (!artwork) send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
      else sendFile(response, artwork.file, headOnly);
      return;
    }
    if (url.pathname.startsWith("/api/artwork/")) {
      let id;
      try {
        id = decodeURIComponent(url.pathname.slice("/api/artwork/".length));
      } catch {
        send(response, 400, { "Content-Type": "text/plain; charset=utf-8" }, "Invalid artwork id\n", headOnly);
        return;
      }
      const artwork = resolveArtwork(root, id);
      if (!artwork) {
        send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
      } else {
        sendFile(response, artwork.file, headOnly);
      }
      return;
    }
    const file = staticFiles.get(url.pathname);
    if (file) sendFile(response, path.join(galleryDir, file), headOnly);
    else send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
  });
}

function portFromArgs(args) {
  const index = args.indexOf("--port");
  if (index === -1) return 4173;
  const value = Number(args[index + 1]);
  if (!Number.isInteger(value) || value < 1 || value > 65535) throw new Error("--port must be a number from 1 to 65535");
  return value;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = portFromArgs(process.argv.slice(2));
  createServer().listen(port, "127.0.0.1", () => {
    console.log(`Wallpaper gallery: http://127.0.0.1:${port} · Scenic studies: http://127.0.0.1:${port}/scenic · Sound studies: http://127.0.0.1:${port}/sounds`);
  });
}
