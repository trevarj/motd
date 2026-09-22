// Start the shared gallery, then run in the pinned sprite-studies shell.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const out = path.resolve("build/sound-previews/qa");
await mkdir(out, { recursive: true });
const profile = await mkdtemp(path.join(out, "chromium-"));
const browser = spawn("chromium", ["--headless", "--mute-audio", "--disable-gpu", "--disable-dev-shm-usage", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
let stderr = "";
browser.stderr.on("data", data => { stderr += data; });
let socket;
const deadline = setTimeout(() => { console.error(stderr.slice(-1800)); browser.kill(); process.exit(1); }, 60000);
try {
  for (let attempt = 0; attempt < 100 && !stderr.includes("DevTools listening"); attempt++) await delay(100);
  const port = /DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//.exec(stderr)?.[1];
  assert.ok(port, stderr.slice(-1800));
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === "page").webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  const pending = new Map();
  const exceptions = [];
  let nextId = 0;
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") exceptions.push(message.params.exceptionDetails);
    if (message.id && pending.has(message.id)) {
      const [resolve, reject] = pending.get(message.id);
      pending.delete(message.id);
      message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result);
    }
  };
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    pending.set(id, [resolve, reject]);
    socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async (expression, userGesture = false) => {
    const result = await call("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true, userGesture });
    assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`, true);
  const change = (selector, value, type = "change") => evaluate(`(() => { const node = document.querySelector(${JSON.stringify(selector)}); node.value = ${JSON.stringify(value)}; node.dispatchEvent(new Event(${JSON.stringify(type)}, { bubbles: true })); })()`, true);
  const until = async (expression, timeout = 10000) => {
    const started = Date.now();
    while (Date.now() - started < timeout) {
      if (await evaluate(expression)) return;
      await delay(75);
    }
    assert.fail(`Timed out: ${expression}`);
  };
  const capture = async name => {
    const shot = await call("Page.captureScreenshot", { format: "png" });
    await writeFile(path.join(out, `${name}.png`), Buffer.from(shot.data, "base64"));
  };
  await call("Runtime.enable");
  await call("Page.enable");
  await call("Page.addScriptToEvaluateOnNewDocument", { source: `
    window.__soundStarts = [];
    const originalStart = AudioBufferSourceNode.prototype.start;
    AudioBufferSourceNode.prototype.start = function(...args) {
      window.__soundStarts.push({ rate: this.playbackRate.value, frames: this.buffer.length });
      return originalStart.apply(this, args);
    };
  ` });
  await call("Emulation.setDeviceMetricsOverride", { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await call("Page.navigate", { url: "http://127.0.0.1:4173/sounds" });
  await until(`document.querySelectorAll('.candidate').length === 4`);
  assert.equal(await evaluate("window.__soundStarts.length"), 0, "Nothing should autoplay");
  assert.equal(await evaluate("document.querySelector('#receive-candidate').value"), "soft-glass");
  assert.equal(await evaluate("document.querySelector('#master').value"), "70");
  assert.equal(await evaluate("document.querySelector('#variation').value"), "musical");
  assert.equal(await evaluate("document.querySelector('#receive-melody').value"), "homecoming");
  assert.equal(await evaluate("document.querySelector('#receive-melody').options.length"), 5);
  assert.equal(await evaluate("document.querySelector('#notice').hidden"), true);
  await capture("desktop-collection");

  // Decode the actual served files and render the maximum simultaneous mix offline.
  const audio = await evaluate(`(async () => {
    const catalog = await (await fetch('/api/sounds')).json();
    const context = new OfflineAudioContext(1, 20000, 48000);
    let maxPeak = 0;
    let decoded = 0;
    let sample;
    for (const asset of catalog.assets) {
      const buffer = await context.decodeAudioData(await (await fetch(asset.url)).arrayBuffer());
      const data = buffer.getChannelData(0);
      if (data[0] !== 0 || data.at(-1) !== 0) throw new Error('Nonzero audio boundary: ' + asset.id);
      if (asset.candidate !== 'current') {
        for (const value of data) maxPeak = Math.max(maxPeak, Math.abs(value));
        sample = buffer;
      }
      decoded++;
    }
    for (let i = 0; i < 5; i++) { const source = context.createBufferSource(); source.buffer = sample; source.connect(context.destination); source.start(0); }
    const mixed = (await context.startRendering()).getChannelData(0);
    return { decoded, maxPeak, mixPeak: Math.max(...mixed.map(Math.abs)) };
  })()`);
  assert.equal(audio.decoded, 122);
  assert.ok(audio.maxPeak > .01 && audio.maxPeak <= .161, JSON.stringify(audio));
  assert.ok(audio.mixPeak > .01 && audio.mixPeak < 1, JSON.stringify(audio));

  await click('[data-candidate="16bit-synth"] [data-audition="melody"]');
  await until(`document.querySelectorAll('#chat-log .played').length === 5 && document.querySelector('#playback-state').textContent === 'Ready'`);
  const consoleRates = await evaluate("window.__soundStarts.slice(-5).map(item => item.rate)");
  [0, 4, 7, 4, 0].forEach((semitones, index) => assert.ok(Math.abs(consoleRates[index] - 2 ** (semitones / 12)) < .0001));
  assert.deepEqual(await evaluate(`[...document.querySelectorAll('#chat-log small')].map(node => node.textContent)`), ["C5", "E5", "G5", "E5", "C5"].map(note => `Sounded · ${note}`));

  for (let index = 0; index < 5; index++) {
    await click('[data-play="receive"]');
    await until(`document.querySelectorAll('#chat-log .played').length === 1 && document.querySelector('#playback-state').textContent === 'Ready'`);
  }
  const manualRates = await evaluate("window.__soundStarts.slice(-5).map(item => item.rate)");
  [0, 4, 7, 2, 0].forEach((semitones, index) => assert.ok(Math.abs(manualRates[index] - 2 ** (semitones / 12)) < .0001, "Manual Receive taps should advance the phrase"));

  // Each selectable phrase plays its actual five note intervals when manually
  // auditioned, rather than only changing the card copy.
  await change("#receive-candidate", "soft-glass");
  const phrases = {
    homecoming: [0, 4, 7, 2, 0],
    climb: [0, 2, 4, 5, 0],
    relay: [0, 4, 2, 5, 0],
    beacon: [0, 2, 7, 4, 0],
    victory: [0, 4, 5, 7, 0],
  };
  for (const [melody, semitones] of Object.entries(phrases)) {
    await change("#receive-melody", melody);
    for (let index = 0; index < 5; index++) {
      await click('[data-play="receive"]');
      await until(`document.querySelectorAll('#chat-log .played').length === 1 && document.querySelector('#playback-state').textContent === 'Ready'`);
    }
    const phraseRates = await evaluate("window.__soundStarts.slice(-5).map(item => item.rate)");
    semitones.forEach((interval, index) => assert.ok(Math.abs(phraseRates[index] - 2 ** (interval / 12)) < .0001, `${melody} note ${index + 1}`));
  }
  await change("#receive-melody", "victory");
  assert.match(await evaluate("document.querySelector('[data-candidate=soft-glass] [data-melody-notes]').textContent"), /Victory: D6 · F#6 · G6 · A6 · D6/);

  await click('[data-candidate="terminal-tick"] .choose');
  assert.equal(await evaluate("document.querySelector('#send-candidate').value"), "terminal-tick");
  await change("#receive-candidate", "arcade-pluck");
  await change("#receive-tone", "bright");
  await change("#receive-pitch", "4", "input");
  await click('[data-play="receive"]');
  await until(`document.querySelectorAll('#chat-log .played').length === 1 && document.querySelector('#playback-state').textContent === 'Ready'`);
  assert.ok(Math.abs(await evaluate("window.__soundStarts.at(-1).rate") - 2 ** (4 / 12)) < .0001);
  await call("Page.reload");
  await until(`document.querySelector('#receive-candidate')?.value === 'arcade-pluck'`);
  assert.equal(await evaluate("document.querySelector('#receive-tone').value"), "bright");
  assert.equal(await evaluate("document.querySelector('#receive-melody').value"), "victory");
  assert.equal(await evaluate("window.__soundStarts.length"), 0);
  await click("#send-enabled");
  await click('[data-play="send"]');
  await until(`document.querySelectorAll('#chat-log .muted').length === 1`);
  assert.equal(await evaluate("window.__soundStarts.length"), 0);
  await click("#reset");
  await change("#variation", "natural");
  assert.equal(await evaluate("document.querySelector('#receive-melody').disabled"), true);
  assert.equal(await evaluate("document.querySelector('#receive-melody-note').hidden"), false);
  await click('[data-play="burst"]');
  await until(`document.querySelectorAll('#chat-log .message').length === 12 && document.querySelector('#playback-state').textContent === 'Ready'`);
  assert.equal(await evaluate("document.querySelectorAll('#chat-log .played').length"), 7);
  assert.equal(await evaluate("document.querySelectorAll('#chat-log .capped').length"), 5);
  assert.equal(await evaluate("document.querySelectorAll('#chat-log .send.played').length"), 1);
  const takes = await evaluate(`[...document.querySelectorAll('#chat-log .receive.played')].slice(0,5).map(node => node.querySelector('small').textContent)`);
  assert.equal(new Set(takes).size, 5, "The five incoming cues should use distinct takes");
  await evaluate("document.querySelector('.workbench').scrollIntoView()");
  await capture("desktop-burst");

  await change("#variation", "musical");
  assert.equal(await evaluate("document.querySelector('#receive-melody').disabled"), false);
  await click('[data-play="conversation"]');
  await until(`document.querySelectorAll('#chat-log .message').length === 6 && document.querySelector('#playback-state').textContent === 'Ready'`);
  const rates = await evaluate("window.__soundStarts.slice(-6).map(item => item.rate)");
  const expected = [1, 1, 2 ** (4 / 12), 2 ** (7 / 12), 1, 1];
  rates.forEach((rate, index) => assert.ok(Math.abs(rate - expected[index]) < .0001, `Musical rate ${index}: ${rate}`));
  await click('[data-play="burst"]');
  await until(`document.querySelectorAll('#chat-log .played').length > 0`);
  await click("#stop");
  const stoppedAt = await evaluate("window.__soundStarts.length");
  await delay(400);
  assert.equal(await evaluate("window.__soundStarts.length"), stoppedAt);

  // Exercise the real player with an intentionally delayed asset load.
  const cancellation = await evaluate(`(async () => {
    const { StudyPlayer } = await import('/sounds/gallery/player.mjs');
    const catalog = await (await fetch('/api/sounds')).json();
    const events = [];
    const player = new StudyPlayer(catalog, { onEvent: event => events.push(event) });
    const originalFetch = window.fetch;
    window.fetch = async (...args) => { if (String(args[0]).startsWith('/api/sound/')) await new Promise(resolve => setTimeout(resolve, 100)); return originalFetch(...args); };
    const before = window.__soundStarts.length;
    try {
      const loading = player.start([[0, 'receive', 'Should never play']]);
      await new Promise(resolve => setTimeout(resolve, 20));
      player.stop();
      await loading;
      await new Promise(resolve => setTimeout(resolve, 150));
      return { starts: window.__soundStarts.length - before, events: events.length };
    } finally { window.fetch = originalFetch; player.stop(); await player.context?.close(); }
  })()`, true);
  assert.deepEqual(cancellation, { starts: 0, events: 0 });

  await click('[data-reference="receive"]');
  await until(`document.querySelectorAll('#chat-log .played').length === 1 && document.querySelector('#playback-state').textContent === 'Ready'`);
  await click("#use-reference");
  assert.equal(await evaluate("document.querySelector('#receive-tone').disabled"), true);
  await click("#copy-settings");
  assert.match(await evaluate("document.querySelector('#notice').textContent"), /copied|Copy the selected/);
  await click("#reset");
  await call("Emulation.setDeviceMetricsOverride", { width: 390, height: 844, deviceScaleFactor: 1, mobile: false });
  await evaluate("window.scrollTo(0,0)");
  assert.ok(await evaluate("document.documentElement.scrollWidth <= innerWidth"), "Mobile layout overflows");
  await capture("mobile-collection");
  await evaluate("document.querySelector('.workbench').scrollIntoView()");
  await capture("mobile-controls");
  await evaluate("document.querySelector('#receive-candidate').focus()");
  await call("Input.dispatchKeyEvent", { type: "keyDown", key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 });
  await call("Input.dispatchKeyEvent", { type: "keyUp", key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 });
  assert.equal(await evaluate("document.activeElement.id"), "receive-melody");

  // Missing-file behavior is exercised without changing the real generated assets.
  const missing = await evaluate(`(async () => {
    const { StudyPlayer } = await import('/sounds/gallery/player.mjs');
    const events = [];
    const player = new StudyPlayer({ assets: [] }, { onEvent: event => events.push(event) });
    await player.start([[0,'receive','Missing']]);
    await player.context?.close();
    return events;
  })()`, true);
  assert.match(missing[0].error, /missing/);
  assert.deepEqual(exceptions, []);
  console.log(`PASS: ${audio.decoded} decoded WAVs, non-clipping mix, controls/persistence, natural/musical modes, five-cue burst/reset, Stop during playback/load, reference, missing assets, keyboard and mobile layout.`);
} finally {
  clearTimeout(deadline);
  socket?.close();
  browser.kill();
}
