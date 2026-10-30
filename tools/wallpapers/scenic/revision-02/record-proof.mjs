// Record actual studio canvases at normal wall-clock speed, with timing outside the art.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const args = process.argv.slice(2);
const option = (key, fallback) => args.includes(key) ? args[args.indexOf(key) + 1] : fallback;
const port = option("--port", "4173");
const scene = option("--scene", "cyberpunk-canal");
const triggered = args.includes("--triggered");
const seconds = triggered ? 5 : 120;
const output = option("--output", "build/scenic-revision02/browser-proofs");
await mkdir(output, { recursive: true });
const profile = await mkdtemp(path.join(os.tmpdir(), "motd-proof-"));
const browser = spawn("chromium", ["--headless", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--disable-background-timer-throttling", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
let log = "";
let socket;
browser.stderr.on("data", chunk => { log += chunk; });
try {
  for (let i = 0; i < 100 && !log.includes("DevTools listening"); i++) await delay(100);
  const debugPort = /DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//.exec(log)?.[1];
  assert.ok(debugPort, log.slice(-1000));
  const pages = await (await fetch("http://127.0.0.1:" + debugPort + "/json/list")).json();
  socket = new WebSocket(pages.find(page => page.type === "page").webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (!pending.has(message.id)) return;
    const [resolve, reject] = pending.get(message.id);
    pending.delete(message.id);
    message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
  };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, [resolve, reject]);
    socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await call("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const waitFor = async expression => {
    for (let i = 0; i < 150; i++) {
      if (await evaluate(expression)) return;
      await delay(100);
    }
    assert.fail("Timed out: " + expression);
  };
  await call("Runtime.enable");
  await call("Page.enable");
  await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1400, deviceScaleFactor: 1, mobile: false });
  await call("Page.navigate", { url: "http://127.0.0.1:" + port + "/scenic?scene=" + encodeURIComponent(scene) });
  await waitFor("document.querySelectorAll('.scene-card').length === 5");
  const index = ["japanese-countryside", "rainy-cafe", "neon-arcade", "retro-computer-room", "cyberpunk-canal"].indexOf(scene);
  assert.ok(index >= 0, "Unknown scene");
  // Start on the requested scene so a prior scene's opaque canvas cannot satisfy readiness.
  await waitFor("document.querySelector('#preview-pair').dataset.scene === " + JSON.stringify(scene) + " && !!document.querySelector('#preview-pair').dataset.scheduleDue && [...document.querySelectorAll('.scenic-art canvas')].length === 2 && [...document.querySelectorAll('.scenic-art canvas')].every(c => !c.hidden && c.getContext('2d').getImageData(128,224,1,1).data[3] === 255)");
  const screenshot = async suffix => {
    const clip = await evaluate("(() => {const r=document.querySelector('#preview-pair').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,scale:1};})()");
    const shot = await call("Page.captureScreenshot", { format: "png", clip, captureBeyondViewport: true });
    await writeFile(path.join(output, scene + "-" + suffix + ".png"), Buffer.from(shot.data, "base64"));
  };
  await screenshot("resting");
  await evaluate("document.querySelector('#chat-visible').click()");
  await screenshot("chat");
  await evaluate("document.querySelector('#chat-visible').click()");
  const startRecording = function (duration, trigger) {
    const canvas = document.createElement("canvas"); canvas.width = 512; canvas.height = 484;
    const ctx = canvas.getContext("2d"); ctx.imageSmoothingEnabled = false;
    const sources = [...document.querySelectorAll(".scenic-art canvas")];
    const chunks = []; const stream = canvas.captureStream(20);
    const recorder = new MediaRecorder(stream, { mimeType: "video/webm;codecs=vp9", videoBitsPerSecond: 1600000 });
    const start = performance.now(); const timeline = []; let prior = "";
    window.proof = { done: false, timeline };
    recorder.ondataavailable = e => chunks.push(e.data);
    recorder.onstop = async () => {
      const bytes = new Uint8Array(await new Blob(chunks, { type: "video/webm" }).arrayBuffer());
      let binary = ""; for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
      window.proof.base64 = btoa(binary); window.proof.done = true; stream.getTracks().forEach(t => t.stop());
    };
    function paint() {
      const elapsed = (performance.now() - start) / 1000;
      ctx.fillStyle = "#10131a"; ctx.fillRect(0, 0, 512, 484);
      sources.forEach((source, i) => ctx.drawImage(source, i * 256, 36, 256, 448));
      ctx.fillStyle = "#eeeeee"; ctx.font = "12px sans-serif";
      ctx.fillText((trigger ? "Triggered event preview · true speed" : "Production cadence · unaltered") + " · " + elapsed.toFixed(1) + " s", 10, 22);
      const state = document.querySelector("#motion-status").textContent + " " + JSON.stringify(sources[0].dataset);
      if (state !== prior) { timeline.push({ elapsed, state }); prior = state; }
      if (elapsed < duration) requestAnimationFrame(paint); else recorder.stop();
    }
    recorder.start(); paint();
    if (trigger) setTimeout(() => document.querySelector("#preview-motion").click(), 1000);
  };
  await evaluate("(" + startRecording.toString() + ")(" + seconds + "," + triggered + ")");
  for (let i = 0; i < seconds + 15; i++) {
    await delay(1000);
    if (await evaluate("window.proof.done")) break;
  }
  assert.ok(await evaluate("window.proof.done"), "Recording did not finish");
  const base64 = await evaluate("window.proof.base64");
  const timeline = await evaluate("window.proof.timeline");
  const name = scene + "-" + (triggered ? "triggered" : "production-120s");
  await writeFile(path.join(output, name + ".webm"), Buffer.from(base64, "base64"));
  await writeFile(path.join(output, name + ".json"), JSON.stringify({ seconds, scene, triggered, timeline }, null, 2) + "\n");
  console.log(JSON.stringify({ output: path.join(output, name + ".webm"), seconds, transitions: timeline.length }));
} finally {
  socket?.close();
  browser.kill("SIGTERM");
  await new Promise(resolve => browser.exitCode !== null ? resolve() : browser.once("exit", resolve));
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}
