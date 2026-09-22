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
const finalDirParts = ["app", "src", "main", "assets", "chat-wallpapers"];
const validId = /^[a-z0-9][a-z0-9-]{0,63}$/;
const validFile = /^[a-z0-9][a-z0-9._-]{0,127}\.png$/;

export const themes = [
  { id: "motd", label: "MOTD", description: "Logos, wordmarks, and bathroom-wall graffiti. motd rulez!" },
  { id: "deep-space", label: "Deep Space", description: "Astronauts, rovers, planets, and small orbital surprises." },
  { id: "retro-gaming", label: "Retro Gaming", description: "CRTs, LAN cables, floppy disks, and classic PC energy." },
  { id: "radio-club", label: "Radio Club", description: "Radios, cassettes, antennas, and bright signal marks." },
  { id: "internet-oddities", label: "Internet Oddities", description: "Cable snakes, cursor critters, tiny robots, and computer cats." },
  { id: "retro-chat", label: "Retro Chat", description: "Buddy lists, away messages, and the early messenger era." },
  { id: "memes", label: "Memes", description: "A dense archive of chaotic internet reaction faces." },
];

const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".png": "image/png",
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

function assetFromManifest(root, item) {
  if (!item || typeof item !== "object") return null;
  const { id, theme, file } = item;
  if (typeof id !== "string" || typeof theme !== "string" || typeof file !== "string") return null;
  if (!validId.test(id) || !themes.some(candidate => candidate.id === theme) || !validFile.test(file)) return null;
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
    console.log(`Wallpaper gallery: http://127.0.0.1:${port} · Sound studies: http://127.0.0.1:${port}/sounds`);
  });
}
