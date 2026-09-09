import { createReadStream } from "node:fs";
import { realpath, stat } from "node:fs/promises";
import { createServer } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = await realpath(path.dirname(fileURLToPath(import.meta.url)));
const port = Number.parseInt(process.env.PORT ?? "8766", 10);
const types = new Map([
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".png", "image/png"],
]);

function reject(response, status) {
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Type": "text/plain; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(status === 404 ? "Not found\n" : "Bad request\n");
}

async function localFile(requestUrl) {
  const url = new URL(requestUrl, "http://127.0.0.1");
  if (/%(?:2e|2f|5c)/i.test(url.pathname)) throw new Error("encoded traversal");
  const decoded = decodeURIComponent(url.pathname);
  if (decoded.includes("\\") || decoded.split("/").includes("..")) throw new Error("traversal");
  const relative = decoded === "/" ? "index.html" : decoded.replace(/^\/+/, "");
  const candidate = path.resolve(root, relative);
  if (candidate !== root && !candidate.startsWith(`${root}${path.sep}`)) throw new Error("outside root");
  const target = await realpath(candidate);
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) throw new Error("symlink outside root");
  if (!(await stat(target)).isFile()) throw new Error("not a file");
  return target;
}

const server = createServer(async (request, response) => {
  if (request.method !== "GET" && request.method !== "HEAD") return reject(response, 400);
  try {
    const file = await localFile(request.url ?? "/");
    response.writeHead(200, {
      "Cache-Control": "no-store",
      "Content-Type": types.get(path.extname(file).toLowerCase()) ?? "application/octet-stream",
      "X-Content-Type-Options": "nosniff",
    });
    if (request.method === "HEAD") return response.end();
    createReadStream(file).on("error", () => response.destroy()).pipe(response);
  } catch {
    reject(response, 404);
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`motd logo studies: http://127.0.0.1:${port}`);
});
