#!/usr/bin/env node
/** Local-only server for the sticker preview. */
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const stickerDir = path.dirname(fileURLToPath(import.meta.url));
const galleryDir = path.join(stickerDir, "gallery");
const mime = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml"],
]);
const assetName = /^[0-9]{2}-[a-z0-9-]+\.(?:png|svg)$/;
const target = "https://github.com/trevarj/motd/releases";

function preferredEdition() {
  if (fs.existsSync(path.join(stickerDir, "prompts-v3.json"))) return { id: "v3", prompt: "prompts-v3.json", exports: "exports-v3" };
  if (fs.existsSync(path.join(stickerDir, "prompts-v2.json"))) return { id: "v2", prompt: "prompts-v2.json", exports: "exports-v2" };
  return { id: "v1", prompt: "prompts.json", exports: "exports" };
}

function send(response, status, headers, body, headOnly) {
  response.writeHead(status, { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", ...headers });
  response.end(headOnly ? undefined : body);
}

function sendFile(response, file, headOnly) {
  const type = mime.get(path.extname(file));
  let body;
  try { body = fs.readFileSync(file); } catch { return send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly); }
  if (!type) return send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
  return send(response, 200, { "Content-Type": type, "Content-Length": body.byteLength }, body, headOnly);
}

function safeAsset(directory, name) {
  if (!assetName.test(name)) return null;
  const candidate = path.join(directory, name);
  try {
    const base = fs.realpathSync(directory);
    const real = fs.realpathSync(candidate);
    const relative = path.relative(base, real);
    return relative.startsWith("..") || path.isAbsolute(relative) ? null : real;
  } catch { return null; }
}

function apiModel() {
  const edition = preferredEdition();
  const prompts = JSON.parse(fs.readFileSync(path.join(stickerDir, edition.prompt), "utf8"));
  let exported = { assets: [] };
  try { exported = JSON.parse(fs.readFileSync(path.join(stickerDir, edition.exports, "manifest.json"), "utf8")); } catch { /* build has not run yet */ }
  const bySlug = new Map(exported.assets.filter(asset => asset.available).map(asset => [asset.slug, asset]));
  return {
    edition: edition.id,
    target: exported.target ?? target,
    stickers: prompts.map(sticker => {
      const asset = bySlug.get(sticker.slug);
      return {
        slug: sticker.slug,
        title: sticker.title,
        available: Boolean(asset),
        status: asset ? "QR integrated" : "Awaiting generated artwork",
        shape: sticker.shape,
        paletteName: sticker.paletteName,
        palette: sticker.palette,
        artworkUrl: asset ? `/${edition.exports}/${asset.slug}.png` : `/artwork/${sticker.slug}.png`,
        svgUrl: asset ? `/${edition.exports}/${asset.slug}.svg` : null,
        pngUrl: asset ? `/${edition.exports}/${asset.slug}.png` : null,
      };
    }),
  };
}

export function createServer() {
  const staticFiles = new Map([
    ["/", "index.html"], ["/index.html", "index.html"],
    ["/gallery", "index.html"], ["/gallery/", "index.html"],
    ["/gallery.css", "gallery.css"], ["/gallery.js", "gallery.js"],
    ["/gallery/gallery.css", "gallery.css"], ["/gallery/gallery.js", "gallery.js"],
  ]);
  return http.createServer((request, response) => {
    const method = request.method ?? "GET";
    if (method !== "GET" && method !== "HEAD") return send(response, 405, { "Allow": "GET, HEAD", "Content-Type": "text/plain; charset=utf-8" }, "Method not allowed\n", method === "HEAD");
    const headOnly = method === "HEAD";
    let url;
    try { url = new URL(request.url ?? "/", "http://127.0.0.1"); } catch { return send(response, 400, { "Content-Type": "text/plain; charset=utf-8" }, "Invalid request URL\n", headOnly); }
    if (url.pathname === "/api/stickers") return send(response, 200, { "Content-Type": "application/json; charset=utf-8" }, `${JSON.stringify(apiModel())}\n`, headOnly);
    if (url.pathname === "/prompts.json") return sendFile(response, path.join(stickerDir, "prompts.json"), headOnly);
    if (url.pathname === "/prompts-v2.json") return sendFile(response, path.join(stickerDir, "prompts-v2.json"), headOnly);
    if (url.pathname === "/prompts-v3.json") return sendFile(response, path.join(stickerDir, "prompts-v3.json"), headOnly);
    if (url.pathname === "/exports/manifest.json") return sendFile(response, path.join(stickerDir, "exports", "manifest.json"), headOnly);
    if (url.pathname === "/exports-v2/manifest.json") return sendFile(response, path.join(stickerDir, "exports-v2", "manifest.json"), headOnly);
    if (url.pathname === "/exports-v3/manifest.json") return sendFile(response, path.join(stickerDir, "exports-v3", "manifest.json"), headOnly);
    if (url.pathname.startsWith("/artwork/")) {
      const file = safeAsset(path.join(stickerDir, "artwork"), url.pathname.slice("/artwork/".length));
      return file ? sendFile(response, file, headOnly) : send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
    }
    if (url.pathname.startsWith("/exports/")) {
      const file = safeAsset(path.join(stickerDir, "exports"), url.pathname.slice("/exports/".length));
      return file ? sendFile(response, file, headOnly) : send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
    }
    if (url.pathname.startsWith("/exports-v2/")) {
      const file = safeAsset(path.join(stickerDir, "exports-v2"), url.pathname.slice("/exports-v2/".length));
      return file ? sendFile(response, file, headOnly) : send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
    }
    if (url.pathname.startsWith("/exports-v3/")) {
      const file = safeAsset(path.join(stickerDir, "exports-v3"), url.pathname.slice("/exports-v3/".length));
      return file ? sendFile(response, file, headOnly) : send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
    }
    const staticFile = staticFiles.get(url.pathname);
    return staticFile ? sendFile(response, path.join(galleryDir, staticFile), headOnly) : send(response, 404, { "Content-Type": "text/plain; charset=utf-8" }, "Not found\n", headOnly);
  });
}

function portFromArgs(args) {
  if (!args.length) return 4174;
  if (args.length !== 2 || args[0] !== "--port") throw new Error("Usage: node stickers/server.mjs [--port 4174]");
  const port = Number(args[1]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("--port must be from 1 to 65535");
  return port;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = portFromArgs(process.argv.slice(2));
  createServer().listen(port, "127.0.0.1", () => console.log(`Sticker gallery: http://127.0.0.1:${port}`));
}
