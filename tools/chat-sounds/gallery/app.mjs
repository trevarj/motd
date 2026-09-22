import { CANDIDATES, sanitizeSettings, synthesize } from "./studies.mjs";
import { StudyPlayer, CONVERSATION, BURST, MELODY } from "./gallery/player.mjs";

const storageKey = "motd-sound-studies-v2";
const $ = selector => document.querySelector(selector);
let settings;
try { settings = sanitizeSettings(JSON.parse(localStorage.getItem(storageKey))); }
catch { settings = sanitizeSettings(null); }
let player;
let catalog;

function notice(message) {
  $("#notice").textContent = message;
  $("#notice").hidden = !message;
}

function el(tag, properties = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(properties)) {
    if (name === "className") node.className = value;
    else node.setAttribute(name, value);
  }
  node.append(...children);
  return node;
}

function button(label, action) {
  const node = el("button", { type: "button" }, label);
  node.addEventListener("click", action);
  return node;
}

function clearLog() {
  $("#chat-log").replaceChildren();
  $("#played-count").textContent = "0 sounded";
  $("#suppressed-count").textContent = "0 capped";
}

function onEvent(event) {
  if (event.error) { notice(event.error); return; }
  const note = event.cue === "receive" && event.variation === "musical" ? event.note : null;
  const detail = note ? ` · ${note}${event.pitch ? " (transposed)" : ""}` : event.variation === "natural" && event.candidate !== "current" ? ` · take ${event.take + 1}` : "";
  const metadata = event.status === "capped" ? "Silent · five-cue limit" : event.status === "muted" ? "Silent · muted" : `Sounded${detail}`;
  const message = el("div", { className: `message ${event.cue} ${event.status}`, "data-status": event.status, "data-cue": event.cue }, event.text || (event.cue === "send" ? "Your message" : "Incoming message"), el("small", {}, metadata));
  $("#chat-log").append(message);
  $("#chat-log").scrollTop = $("#chat-log").scrollHeight;
  $("#played-count").textContent = `${event.played} sounded`;
  $("#suppressed-count").textContent = `${event.suppressed} capped`;
}

function play(mode, candidate = null) {
  if (!player) return;
  notice(catalog.error || "");
  clearLog();
  const selected = sanitizeSettings(settings);
  if (candidate) {
    selected.send.candidate = candidate;
    selected.receive.candidate = candidate;
  }
  if (mode === "melody") selected.variation = "musical";
  const sequence = mode === "melody" ? MELODY : mode === "conversation" ? CONVERSATION : mode === "burst" ? BURST : [[0, mode, mode === "send" ? "Your message" : "Incoming message"]];
  player.start(sequence, { audition: mode === "send" || mode === "receive", settings: selected });
}

function save() {
  settings = sanitizeSettings(settings);
  player?.setSettings(settings);
  try { localStorage.setItem(storageKey, JSON.stringify(settings)); }
  catch { notice("Settings work for this session; this browser could not save them."); }
  syncControls();
}

function choosePair(candidate) {
  settings.send.candidate = candidate;
  settings.receive.candidate = candidate;
  save();
}

function waveform(candidate) {
  const samples = synthesize(candidate, "receive", 0, "balanced");
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 240 44");
  svg.setAttribute("class", "wave");
  svg.setAttribute("aria-hidden", "true");
  const shape = document.createElementNS(svg.namespaceURI, "path");
  let data = "";
  for (let x = 0; x < 120; x++) {
    const from = Math.floor(x / 120 * samples.length);
    const to = Math.floor((x + 1) / 120 * samples.length);
    let peak = 0;
    for (let index = from; index < to; index++) peak = Math.max(peak, Math.abs(samples[index]));
    const amplitude = Math.max(.5, peak / .16 * 21);
    data += `M${x * 2 + 1},${22 - amplitude}v${amplitude * 2} `;
  }
  shape.setAttribute("d", data);
  shape.setAttribute("stroke", "currentColor");
  shape.setAttribute("stroke-width", "1");
  svg.append(shape);
  return svg;
}

function melodyFor(candidateId, melodyId = settings.receive.melody) {
  const candidate = CANDIDATES.find(item => item.id === candidateId);
  return candidate?.melodies.find(item => item.id === melodyId) ?? candidate?.melodies[0] ?? null;
}

function melodyText(melody) {
  return `${melody.label}: ${melody.notes.join(" · ")}`;
}

function renderCandidates() {
  const families = [...new Set(CANDIDATES.map(candidate => candidate.pack))];
  for (const candidate of catalog.candidates) {
    const actions = el("div", { className: "button-row" });
    for (const mode of ["send", "receive", "melody"]) {
      const action = button(mode[0].toUpperCase() + mode.slice(1), () => play(mode, candidate.id));
      action.setAttribute("aria-label", `${candidate.label}: ${mode}`);
      action.dataset.audition = mode;
      action.disabled = !candidate.available;
      actions.append(action);
    }
    const choose = button("Use this pair", () => choosePair(candidate.id));
    choose.className = "choose";
    choose.disabled = !candidate.available;
    const card = el("article", { className: "candidate", "data-candidate": candidate.id, "data-family": families.indexOf(candidate.pack) },
      el("p", { className: "pack" }, candidate.pack), el("h3", {}, candidate.label), el("p", { className: "description" }, candidate.description), waveform(candidate.id),
      el("p", { className: "melody-label", "data-melody-label": "" }, candidate.melodies[0].label),
      el("p", { className: "melody-notes", "data-melody-notes": "" }, candidate.melodies[0].notes.join(" · ")),
      el("p", { className: "melody-degrees", "data-melody-degrees": "" }, `Major scale: ${candidate.melodies[0].degrees.join(" → ")}`), actions, choose,
      el("span", { className: "chosen-label" }, candidate.available ? "" : "Generate studies to listen"));
    $("#candidates").append(card);
  }
  $("#candidates").setAttribute("aria-busy", "false");
}

function renderCueControls() {
  for (const cue of ["send", "receive"]) {
    const fieldset = el("fieldset", { className: "cue-fieldset" }, el("legend", {}, cue === "send" ? "Sending" : "Receiving"));
    const enabled = el("input", { id: `${cue}-enabled`, type: "checkbox" });
    enabled.addEventListener("change", () => { settings[cue].enabled = enabled.checked; save(); });
    fieldset.append(el("label", { className: "enable", for: enabled.id }, enabled, "Enabled"));
    const select = el("select", { id: `${cue}-candidate`, "aria-label": `${cue} sound` });
    for (const candidate of catalog.candidates) {
      const option = el("option", { value: candidate.id }, candidate.label);
      option.disabled = !candidate.available;
      select.append(option);
    }
    const reference = el("option", { value: "current" }, "Current sound");
    reference.disabled = !catalog.referenceAvailable;
    select.append(reference);
    select.addEventListener("change", () => { settings[cue].candidate = select.value; save(); });
    fieldset.append(select);
    if (cue === "receive") {
      const melody = el("select", { id: "receive-melody", "aria-label": "Receive melody" });
      melody.addEventListener("change", () => { settings.receive.melody = melody.value; save(); });
      fieldset.append(el("label", { for: melody.id }, "Melody"), melody, el("p", { className: "tone-note", id: "receive-melody-note", hidden: "" }, "Choose Musical variation to use a receive phrase."));
    }
    const volume = el("input", { type: "range", id: `${cue}-volume`, min: 0, max: 100, step: 1 });
    volume.addEventListener("input", () => { settings[cue].volume = Number(volume.value); save(); });
    fieldset.append(el("label", { className: "slider-label", for: volume.id }, "Volume", el("output", { id: `${cue}-volume-output`, for: volume.id })), volume);
    const details = el("details", {}, el("summary", {}, "Fine-tune"));
    const pitch = el("input", { type: "range", id: `${cue}-pitch`, min: -4, max: 4, step: 1 });
    pitch.addEventListener("input", () => { settings[cue].pitch = Number(pitch.value); save(); });
    details.append(el("label", { className: "slider-label", for: pitch.id }, "Pitch", el("output", { id: `${cue}-pitch-output`, for: pitch.id })), pitch);
    const tone = el("select", { id: `${cue}-tone` }, ...["warm", "balanced", "bright"].map(value => el("option", { value }, value[0].toUpperCase() + value.slice(1))));
    tone.addEventListener("change", () => { settings[cue].tone = tone.value; save(); });
    details.append(el("label", { for: tone.id }, "Tone"), tone, el("p", { className: "tone-note", id: `${cue}-tone-note`, hidden: "" }, "The current sound keeps its original tone and single take."));
    fieldset.append(details);
    $("#cue-controls").append(fieldset);
  }
}

function syncControls() {
  $("#master").value = settings.master;
  $("#master-output").textContent = `${settings.master}%`;
  $("#variation").value = settings.variation;
  $("#variation-description").textContent = {
    natural: "Five related takes, shuffled without immediate repeats. Their volume stays consistent.",
    fixed: "One familiar cue for every message. No variation in pitch or texture.",
    musical: "Five-note major-key phrases start and resolve on the home note. Each incoming message advances one note; a two-second pause restarts the phrase.",
  }[settings.variation];
  for (const cue of ["send", "receive"]) {
    if (!$(`#${cue}-enabled`)) continue;
    $(`#${cue}-enabled`).checked = settings[cue].enabled;
    for (const property of ["candidate", "volume", "pitch", "tone"]) $(`#${cue}-${property}`).value = settings[cue][property];
    $(`#${cue}-volume-output`).textContent = `${settings[cue].volume}%`;
    $(`#${cue}-pitch-output`).textContent = `${settings[cue].pitch > 0 ? "+" : ""}${settings[cue].pitch} st`;
    $(`#${cue}-tone`).disabled = settings[cue].candidate === "current";
    $(`#${cue}-tone-note`).hidden = settings[cue].candidate !== "current";
  }
  const melodySelect = $("#receive-melody");
  const melodyCandidate = CANDIDATES.find(candidate => candidate.id === settings.receive.candidate);
  melodySelect.replaceChildren();
  if (melodyCandidate) {
    for (const melody of melodyCandidate.melodies) {
      melodySelect.append(el("option", { value: melody.id }, `${melody.label} · ${melody.notes.join(" ")}`));
    }
    melodySelect.value = settings.receive.melody;
  }
  melodySelect.disabled = !melodyCandidate || settings.variation !== "musical";
  $("#receive-melody-note").hidden = settings.variation === "musical";
  for (const card of document.querySelectorAll(".candidate")) {
    const chosen = ["send", "receive"].filter(cue => settings[cue].candidate === card.dataset.candidate);
    card.classList.toggle("selected", chosen.length > 0);
    if (catalog.candidates.find(candidate => candidate.id === card.dataset.candidate).available) card.querySelector(".chosen-label").textContent = chosen.length ? `Selected for ${chosen.join(" + ")}` : "";
    const melody = melodyFor(card.dataset.candidate);
    card.querySelector("[data-melody-label]").textContent = melody.label;
    card.querySelector("[data-melody-notes]").textContent = melodyText(melody);
    card.querySelector("[data-melody-degrees]").textContent = `Major scale: ${melody.degrees.join(" → ")}`;
  }
  $("#settings-copy").hidden = true;
}

$("#master").addEventListener("input", event => { settings.master = Number(event.target.value); save(); });
$("#variation").addEventListener("change", event => { settings.variation = event.target.value; save(); });
$("#stop").addEventListener("click", () => player?.stop());
$("#reset").addEventListener("click", () => { settings = sanitizeSettings(null); save(); clearLog(); });
$("#copy-settings").addEventListener("click", async () => {
  const text = JSON.stringify({ ...settings, receiveBurst: { maximum: 5, resetAfterMs: 2000 } }, null, 2);
  try { await navigator.clipboard.writeText(text); notice("Settings copied. Paste them with your feedback."); }
  catch {
    $("#settings-copy").value = text;
    $("#settings-copy").hidden = false;
    $("#settings-copy").focus();
    $("#settings-copy").select();
    notice("Copy the selected settings below.");
  }
});
for (const action of document.querySelectorAll("[data-play]")) action.addEventListener("click", () => play(action.dataset.play));
for (const action of document.querySelectorAll("[data-reference]")) action.addEventListener("click", () => play(action.dataset.reference, "current"));
$("#use-reference").addEventListener("click", () => choosePair("current"));
document.addEventListener("visibilitychange", () => { if (document.hidden) player?.stop(); });
window.addEventListener("pagehide", () => player?.stop());

try {
  const response = await fetch("/api/sounds");
  if (!response.ok) throw new Error(`Could not load sound studies (${response.status}). Refresh to try again.`);
  catalog = await response.json();
  player = new StudyPlayer(catalog, { onEvent, onState: state => {
    $("#playback-state").textContent = state.loading ? "Loading sounds…" : state.running ? "Playing" : "Ready";
    $("#playback-state").dataset.running = String(state.running);
  } });
  player.setSettings(settings);
  renderCandidates();
  renderCueControls();
  syncControls();
  for (const action of document.querySelectorAll("[data-play]")) action.disabled = !catalog.assets.length;
  for (const action of document.querySelectorAll("[data-reference], #use-reference")) action.disabled = !catalog.referenceAvailable;
  notice(catalog.error || (catalog.candidates.some(candidate => !candidate.available) ? "Some studies are missing. Regenerate the studies and refresh to hear the full collection." : ""));
} catch (error) {
  $("#candidates").setAttribute("aria-busy", "false");
  notice(error.message);
}
