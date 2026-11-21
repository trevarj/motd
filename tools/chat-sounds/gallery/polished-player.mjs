import { ReceiveBurstGate, SAMPLE_RATE, synthesize } from "../studies.mjs";
import { POLISHED_FAMILIES, synthesizePolished } from "../polished.mjs";
import { BURST, CONVERSATION } from "./player.mjs";

const launch = document.querySelector("#families");
const template = document.querySelector("#family");
const status = document.querySelector("#playback");
const stopButton = document.querySelector("#stop");
const volume = document.querySelector("#volume");
const volumeDisplay = document.querySelector("#volume-display");
const phoneBand = document.querySelector("#phone-band");
const easeRepeats = document.querySelector("#ease-repeats");
const buffers = new Map();
let context;
let active;
let request = 0;

function bufferFor(id, cue) {
  const key = `${id}/${cue}`;
  if (!buffers.has(key)) {
    const samples = id === "soft-glass" ? synthesize(id, cue, 2, "balanced") : synthesizePolished(id, cue);
    const buffer = context.createBuffer(1, samples.length, SAMPLE_RATE);
    buffer.copyToChannel(samples, 0);
    buffers.set(key, buffer);
  }
  return buffers.get(key);
}

function stop() {
  request++;
  const previous = active;
  active = null;
  if (previous) {
    const now = context.currentTime;
    const end = now + 0.008;
    previous.gain.gain.cancelScheduledValues(now);
    previous.gain.gain.setValueAtTime(previous.gain.gain.value, now);
    previous.gain.gain.linearRampToValueAtTime(0, end);
    for (const source of previous.sources) source.stop(end);
  }
  status.textContent = "Ready";
  stopButton.disabled = true;
}

function output(gain) {
  if (!phoneBand.checked) {
    gain.connect(context.destination);
    return [];
  }
  const high = context.createBiquadFilter();
  high.type = "highpass";
  high.frequency.value = 450;
  high.Q.value = Math.SQRT1_2;
  const low = context.createBiquadFilter();
  low.type = "lowpass";
  low.frequency.value = 5000;
  low.Q.value = Math.SQRT1_2;
  gain.connect(high).connect(low).connect(context.destination);
  return [high, low];
}

function sequence(cue, reference) {
  if (cue === "burst") {
    const gate = new ReceiveBurstGate();
    return BURST.filter(([time, role]) => gate.accept(role, time));
  }
  if (cue === "conversation") {
    const chat = CONVERSATION.slice(0, 4);
    return reference ? chat : [...chat, [3000, "mention"]];
  }
  return [[0, cue]];
}

async function play(family, cue, label) {
  stop();
  const currentRequest = request;
  stopButton.disabled = false;
  status.textContent = `Preparing ${family.label}…`;
  try {
    context ??= new AudioContext({ latencyHint: "interactive" });
    if (context.state !== "running") await context.resume();
    if (currentRequest !== request) return;
    if (context.state !== "running") throw new Error("Browser audio is paused");
    let previousReceive = -Infinity;
    let consecutive = 0;
    const clips = sequence(cue, family.id === "soft-glass").map(([time, role]) => {
      let level = 1;
      if (role === "receive" && easeRepeats.checked) {
        consecutive = time - previousReceive < 400 ? consecutive + 1 : 0;
        previousReceive = time;
        level = 10 ** (-Math.min(consecutive, 4) / 20);
      }
      return [time, bufferFor(family.id, role), level];
    });
    const gain = context.createGain();
    gain.gain.value = Number(volume.value) / 100;
    const filters = output(gain);
    const session = { gain, sources: new Set() };
    active = session;
    const start = context.currentTime + 0.012;
    for (const [time, buffer, level] of clips) {
      const source = context.createBufferSource();
      source.buffer = buffer;
      const trim = level === 1 ? null : context.createGain();
      if (trim) {
        trim.gain.value = level;
        source.connect(trim).connect(gain);
      } else {
        source.connect(gain);
      }
      session.sources.add(source);
      source.onended = () => {
        source.disconnect();
        trim?.disconnect();
        session.sources.delete(source);
        if (session.sources.size !== 0) return;
        gain.disconnect();
        for (const filter of filters) filter.disconnect();
        if (active === session) stop();
      };
      source.start(start + time / 1000);
    }
    status.textContent = `Playing ${family.label} · ${label}`;
  } catch (error) {
    if (currentRequest !== request) return;
    stop();
    status.textContent = `Could not play audio: ${error.message}`;
  }
}

const reference = {
  id: "soft-glass",
  label: "Soft Glass",
  description: "The previous app default, one fixed balanced take for comparison.",
};
for (const [index, family] of [reference, ...POLISHED_FAMILIES].entries()) {
  const article = template.content.firstElementChild.cloneNode(true);
  article.dataset.family = index;
  article.dataset.voice = family.id;
  article.querySelector(".pack").textContent = index === 0 ? "Previous default" : `Original study 0${index}`;
  const heading = article.querySelector("h3");
  heading.textContent = family.label;
  heading.id = `voice-${family.id}`;
  article.setAttribute("aria-labelledby", heading.id);
  article.querySelector(".description").textContent = family.description;
  if (index === 0) article.querySelector('[data-play="mention"]').remove();
  for (const button of article.querySelectorAll("button")) {
    button.addEventListener("click", () => play(family, button.dataset.play, button.textContent));
  }
  launch.append(article);
}

stopButton.addEventListener("click", stop);
phoneBand.addEventListener("change", stop);
easeRepeats.addEventListener("change", stop);
volume.addEventListener("input", () => {
  volumeDisplay.value = `${volume.value}%`;
  active?.gain.gain.setTargetAtTime(Number(volume.value) / 100, context.currentTime, 0.01);
});
