import fs from "node:fs";
import path from "node:path";
import { CANDIDATES, TONES } from "../studies.mjs";

const studyDirectory = "build/sound-previews";
const validId = /^[a-z0-9-]{1,100}$/;
const staticFiles = new Map([
  ["/sounds", ["gallery/index.html", "text/html; charset=utf-8"]],
  ["/sounds/", ["gallery/index.html", "text/html; charset=utf-8"]],
  ["/sounds/style.css", ["gallery/style.css", "text/css; charset=utf-8"]],
  ["/sounds/app.mjs", ["gallery/app.mjs", "text/javascript; charset=utf-8"]],
  ["/sounds/gallery/player.mjs", ["gallery/player.mjs", "text/javascript; charset=utf-8"]],
  ["/sounds/studies.mjs", ["studies.mjs", "text/javascript; charset=utf-8"]],
]);

// Resolve both the directory and file: a symlink must not turn a preview into a file server.
function confinedFile(root, directory, name) {
  try {
    const realRoot = fs.realpathSync(root);
    const realDirectory = fs.realpathSync(path.join(root, directory));
    const file = fs.realpathSync(path.join(realDirectory, name));
    for (const relative of [path.relative(realRoot, realDirectory), path.relative(realDirectory, file)]) {
      if (relative === ".." || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) return null;
    }
    const stat = fs.statSync(file);
    return stat.isFile() ? { file, version: `${stat.size}-${Math.floor(stat.mtimeMs)}` } : null;
  } catch {
    return null;
  }
}

function readManifest(root) {
  const resolved = confinedFile(root, studyDirectory, "manifest.json");
  if (!resolved) return { assets: [], error: "Sound studies have not been generated. Run: nix develop .#sprite-studies -c node tools/chat-sounds/generate-studies.mjs" };
  try {
    const manifest = JSON.parse(fs.readFileSync(resolved.file, "utf8"));
    if (manifest.version !== 1 || !Array.isArray(manifest.assets)) throw new Error("shape");
    return { assets: manifest.assets, error: null };
  } catch {
    return { assets: [], error: "The sound study manifest is invalid. Regenerate the studies and refresh." };
  }
}

function validAsset(item) {
  if (!item || typeof item !== "object" || !CANDIDATES.some(candidate => candidate.id === item.candidate)) return false;
  if (!["send", "receive"].includes(item.cue) || !TONES.includes(item.tone) || !Number.isInteger(item.take) || item.take < 0 || item.take > 4) return false;
  const id = `${item.candidate}-${item.cue}-${item.tone}-${item.take}`;
  return item.id === id && item.file === `${id}.wav`;
}

function publicAsset(item, resolved) {
  return {
    id: item.id, candidate: item.candidate, cue: item.cue, take: item.take, tone: item.tone,
    url: `/api/sound/${item.id}?v=${resolved.version}`,
  };
}

export function buildSoundGallery(root) {
  const manifest = readManifest(root);
  const assets = [];
  const seen = new Set();
  for (const item of manifest.assets) {
    if (!validAsset(item) || seen.has(item.id)) continue;
    const resolved = confinedFile(root, studyDirectory, item.file);
    if (resolved) {
      assets.push(publicAsset(item, resolved));
      seen.add(item.id);
    }
  }
  for (const cue of ["send", "receive"]) {
    const resolved = confinedFile(root, "app/src/main/res/raw", `chat_${cue}.wav`);
    if (resolved) assets.push(publicAsset({ id: `current-${cue}`, candidate: "current", cue, take: 0, tone: "balanced" }, resolved));
  }
  return {
    error: manifest.error,
    candidates: CANDIDATES.map(candidate => ({ ...candidate, available: assets.filter(asset => asset.candidate === candidate.id).length === 30 })),
    referenceAvailable: assets.filter(asset => asset.candidate === "current").length === 2,
    assets,
  };
}

export function resolveSound(root, id) {
  if (typeof id !== "string" || !validId.test(id)) return null;
  if (["current-send", "current-receive"].includes(id)) {
    return confinedFile(root, "app/src/main/res/raw", `chat_${id.slice(8)}.wav`);
  }
  const item = readManifest(root).assets.find(asset => validAsset(asset) && asset.id === id);
  return item ? confinedFile(root, studyDirectory, item.file) : null;
}

export function handleSoundRoute(root, pathname, response, headOnly) {
  function send(status, type, body) {
    response.writeHead(status, { "Content-Type": type, "Content-Length": Buffer.byteLength(body), "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
    response.end(headOnly ? undefined : body);
  }
  function sendFile(resolved, type) {
    if (!resolved) return send(404, "text/plain; charset=utf-8", "Not found\n");
    try { send(200, type, fs.readFileSync(resolved.file)); }
    catch { send(404, "text/plain; charset=utf-8", "Not found\n"); }
  }
  if (pathname === "/api/sounds") {
    send(200, "application/json; charset=utf-8", JSON.stringify(buildSoundGallery(root)));
  } else if (pathname.startsWith("/api/sound/")) {
    let id;
    try { id = decodeURIComponent(pathname.slice("/api/sound/".length)); }
    catch { send(400, "text/plain; charset=utf-8", "Invalid sound id\n"); return true; }
    sendFile(resolveSound(root, id), "audio/wav");
  } else if (staticFiles.has(pathname)) {
    const [file, type] = staticFiles.get(pathname);
    sendFile(confinedFile(root, "tools/chat-sounds", file), type);
  } else return false;
  return true;
}
