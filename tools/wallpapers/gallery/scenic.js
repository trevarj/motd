const $ = selector => document.querySelector(selector);
const elements = {
  grid: $("#scene-grid"), notice: $("#notice"), heading: $("#studio-heading"), description: $("#scene-description"),
  version: $("#version"), compareVersion: $("#compare-version"), comparison: $("#comparison"), comparisonArt: $("#comparison-art"),
  comparisonLabel: $("#comparison-label"), comparisonSeason: $("#comparison-season"), pair: $("#preview-pair"), labels: $(".preview-labels"),
  note: $("#preview-note"), lightArt: $("#light-art"), darkArt: $("#dark-art"), lightPhone: $('.phone[data-side="light"]'),
  darkPhone: $('.phone[data-side="dark"]'), chatVisible: $("#chat-visible"), motionToggle: $("#motion-toggle"), motionOff: $("#motion-off"),
  motionStatus: $("#motion-status"), previewMotion: $("#preview-motion"), eventControl: $("#event-control"), eventSelect: $("#event-select"),
  intensity: $("#intensity"), intensityValue: $("#intensity-value"), feedback: $("#feedback"), reviewStatus: $("#review-status"), openLoop: $("#open-loop"),
};
const keys = { reviews: "motd.scenic-wallpaper-reviews.v1", drafts: "motd.scenic-wallpaper-review-drafts.v1" };
const state = { model: null, sceneId: new URL(location.href).searchParams.get("scene"), versionId: null, compareId: "", appearance: "follow", display: "pair", motionOff: false, paused: false, eventDocs: new Map(), images: new Map(), playback: null, timer: 0, scheduleIndex: 0, scheduledKey: "" };
const systemTheme = matchMedia("(prefers-color-scheme: dark)");
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");
const scene = () => state.model?.scenes.find(item => item.id === state.sceneId) ?? state.model?.scenes[0] ?? null;
const preferredVersion = item => item?.versions.find(candidate => candidate.motion === "pixel-events") ?? item?.versions[0] ?? null;
const version = () => scene()?.versions.find(item => item.id === state.versionId) ?? preferredVersion(scene());
const compare = () => scene()?.versions.find(item => item.id === state.compareId) ?? null;
const activeSide = () => state.appearance === "follow" ? (systemTheme.matches ? "dark" : "light") : state.appearance;
const versionKey = () => scene() && version() ? `${scene().id}/${version().id}` : "";
const store = key => { try { const value = JSON.parse(localStorage.getItem(key) ?? "{}"); return value && typeof value === "object" && !Array.isArray(value) ? value : {}; } catch { return {}; } };
const savedReview = () => { const id = versionKey(); return id ? store(keys.reviews)[id] : null; };
const isPixelEvents = (current = version()) => current?.motion === "pixel-events" && Boolean(current.eventsUrl);

function saveDraft() {
  const id = versionKey(); if (!id) return;
  const all = store(keys.drafts);
  if (elements.feedback.value) all[id] = { notes: elements.feedback.value, savedAt: new Date().toISOString() }; else delete all[id];
  localStorage.setItem(keys.drafts, JSON.stringify(all));
}
function loadFeedback() { const id = versionKey(); elements.feedback.value = id ? (store(keys.drafts)[id]?.notes ?? savedReview()?.notes ?? "") : ""; }
function renderReview() { const item = savedReview(); elements.reviewStatus.textContent = item ? `${item.status} · ${new Date(item.savedAt).toLocaleString()}` : "No saved review"; }
function signOff(status) {
  const id = versionKey(); if (!id) return;
  const all = store(keys.reviews); all[id] = { status, notes: elements.feedback.value.trim(), savedAt: new Date().toISOString() }; localStorage.setItem(keys.reviews, JSON.stringify(all));
  const drafts = store(keys.drafts); delete drafts[id]; localStorage.setItem(keys.drafts, JSON.stringify(drafts)); renderReview();
}
function random(seed) {
  let value = 2166136261;
  for (const character of seed) value = Math.imul(value ^ character.charCodeAt(0), 16777619);
  return ((value >>> 0) % 1000000) / 1000000;
}
function interval(range, document, suffix) {
  const randomValue = random(`${document.scheduleSeed}:${state.scheduleIndex++}:${suffix}`);
  return range[0] + Math.floor(randomValue * (range[1] - range[0] + 1));
}
function stopTimer() { clearTimeout(state.timer); state.timer = 0; state.scheduledKey = ""; delete elements.pair.dataset.scheduleDue; }
function updateMotionStatus() { elements.motionStatus.textContent = motionLabel(version()); }
function restoreRest() { state.playback = null; delete elements.pair.dataset.eventId; delete elements.pair.dataset.eventFrame; renderPixelCanvases(); updateMotionStatus(); }
function permitted() { return !state.motionOff && !state.paused && !reduceMotion.matches && !document.hidden; }
async function eventDocument(current = version()) {
  if (!isPixelEvents(current)) return null;
  if (state.eventDocs.has(current.eventsUrl)) return state.eventDocs.get(current.eventsUrl);
  const response = await fetch(current.eventsUrl, { cache: "no-store" });
  if (!response.ok) throw new Error(`Event data request failed (${response.status})`);
  const document = await response.json();
  state.eventDocs.set(current.eventsUrl, document);
  return document;
}
function loadImage(url) {
  if (state.images.has(url)) return state.images.get(url);
  const image = new Image();
  const promise = new Promise((resolve, reject) => { image.onload = () => resolve(image); image.onerror = reject; });
  image.src = url; state.images.set(url, promise); return promise;
}
function canvasFor(wrapper) { return wrapper.querySelector("canvas"); }
function imageFor(wrapper) { return wrapper.querySelector("img"); }
function drawCanvas(wrapper, image, pixels) {
  const canvas = canvasFor(wrapper); const context = canvas.getContext("2d", { alpha: false });
  context.imageSmoothingEnabled = false; context.clearRect(0, 0, 256, 448); context.drawImage(image, 0, 0, 256, 448);
  for (const [x, y, color] of pixels) { context.fillStyle = color; context.fillRect(x, y, 1, 1); }
}
async function renderPixelCanvases() {
  const current = version(); if (!isPixelEvents(current)) return;
  const key = versionKey();
  try {
    const [light, dark] = await Promise.all([loadImage(current.lightUrl), loadImage(current.darkUrl)]);
    if (key !== versionKey() || current !== version()) return;
    const frame = state.playback?.key === versionKey() ? state.playback.frame : null;
    for (const wrapper of [elements.lightArt, elements.darkArt]) {
      const canvas = canvasFor(wrapper); canvas.dataset.eventId = state.playback?.eventId ?? ""; canvas.dataset.eventFrame = state.playback?.frameIndex?.toString() ?? "";
    }
    drawCanvas(elements.lightArt, light, frame?.light ?? []); drawCanvas(elements.darkArt, dark, frame?.dark ?? []);
  } catch (error) { elements.notice.hidden = false; elements.notice.textContent = `Could not draw pixel event: ${error.message}`; }
}
function positionArt(wrapper, side, paired) {
  const image = imageFor(wrapper); if (!image?.naturalWidth || !wrapper.clientHeight) return;
  const fullWidth = image.naturalWidth / image.naturalHeight * wrapper.clientHeight;
  const panelWidth = paired ? fullWidth / 2 : fullWidth; const inset = Math.max(0, (panelWidth - wrapper.clientWidth) / 2);
  image.style.left = `${paired && side === "dark" ? -panelWidth - inset : -inset}px`;
}
function setArt(wrapper, source, side, paired) {
  const image = imageFor(wrapper); if (image.dataset.source !== source) { image.dataset.source = source; image.src = source; }
  image.onload = () => positionArt(wrapper, side, paired); positionArt(wrapper, side, paired);
}
function renderChat() { for (const target of [$("#light-chat"), $("#dark-chat")]) target.replaceChildren($("#chat-template").content.cloneNode(true)); }
function renderArtwork() {
  const current = version(); const currentScene = scene(); if (!current || !currentScene) return;
  const pixelEvents = isPixelEvents(current);
  for (const wrapper of [elements.lightArt, elements.darkArt]) { canvasFor(wrapper).hidden = !pixelEvents; imageFor(wrapper).hidden = pixelEvents; }
  if (pixelEvents) renderPixelCanvases();
  else { setArt(elements.lightArt, current.lightUrl, "light", current.paired); setArt(elements.darkArt, current.darkUrl || current.lightUrl, "dark", current.paired); }
  const active = activeSide(); elements.pair.dataset.activeSide = active; elements.pair.dataset.scene = currentScene.id;
  elements.lightPhone.classList.toggle("active-system", active === "light"); elements.darkPhone.classList.toggle("active-system", active === "dark");
  if (state.display === "single") elements.labels.firstElementChild.textContent = active === "dark" ? "Night" : "Day"; else { elements.labels.firstElementChild.textContent = "Day"; elements.labels.lastElementChild.textContent = "Night"; }
  const viewing = state.appearance === "follow" ? `Following the current system preference: ${active === "dark" ? "night" : "day"}${state.display === "single" ? " is shown." : " is highlighted."}` : `Manual ${active} preview is ${state.display === "single" ? "shown." : "highlighted."}`;
  elements.note.textContent = pixelEvents ? `${viewing} Rare authored-pixel events run after a 20–40 second start, then every 45–90 seconds; use Triggered event preview for immediate inspection.` : viewing;
}
function playEvent(document, event, inspection) {
  stopTimer(); restoreRest();
  let index = 0; const key = versionKey();
  const advance = () => {
    if ((!inspection && !permitted()) || key !== versionKey()) { restoreRest(); return; }
    const frameIndex = index; const frame = event.frames[index++]; state.playback = { key, eventId: event.id, eventLabel: event.label, inspection, frameIndex, frame }; elements.pair.dataset.eventId = event.id; elements.pair.dataset.eventFrame = frameIndex.toString(); renderPixelCanvases(); updateMotionStatus();
    if (index < event.frames.length) state.timer = setTimeout(advance, frame.durationMs);
    else state.timer = setTimeout(() => { restoreRest(); if (permitted()) schedule(document, document.quietIntervalMs, "quiet"); }, frame.durationMs);
  };
  advance();
}
function schedule(document, range, kind) {
  if (!permitted() || !isPixelEvents() || state.scheduledKey === versionKey()) return;
  const scheduledFor = versionKey(); state.scheduledKey = scheduledFor;
  const delay = interval(range, document, kind); elements.pair.dataset.scheduleDue = (Date.now() + delay).toString();
  state.timer = setTimeout(() => {
    state.scheduledKey = "";
    delete elements.pair.dataset.scheduleDue;
    if (!permitted() || versionKey() !== scheduledFor || !isPixelEvents()) return;
    const event = document.events[Math.floor(random(`${document.scheduleSeed}:${state.scheduleIndex}:event`) * document.events.length)];
    playEvent(document, event, false);
  }, delay);
}
async function ensureSchedule() {
  const current = version(); const key = versionKey(); stopTimer(); restoreRest();
  if (!isPixelEvents(current) || !permitted()) return;
  try {
    const document = await eventDocument(current);
    if (key !== versionKey() || current !== version() || !permitted()) return;
    schedule(document, document.initialDelayMs, "initial");
  } catch (error) { elements.notice.hidden = false; elements.notice.textContent = `Could not load events: ${error.message}`; }
}
function motionLabel(current) {
  if (isPixelEvents(current)) {
    if (state.playback?.key === versionKey()) return `${state.playback.inspection ? "Triggered preview" : "Rare event"} · ${state.playback.eventLabel}`;
    if (state.motionOff || state.paused || reduceMotion.matches || document.hidden) return "Still";
    return "Rare authored-pixel events · waiting";
  }
  return current?.loopUrl ? "Archived still · loop available separately" : "Still";
}
function renderControls() {
  const currentScene = scene(); const current = version();
  elements.heading.textContent = currentScene?.title ?? "Select a scene"; elements.description.textContent = currentScene?.description ?? "Artwork will appear here once a study is ready.";
  elements.version.replaceChildren(...(currentScene?.versions.length ? currentScene.versions : [{ id: "", label: "Awaiting artwork" }]).map(item => new Option(item.season ? `${item.label} · ${item.season}` : item.label, item.id, item.id === current?.id, item.id === current?.id))); elements.version.disabled = !current;
  const alternatives = (currentScene?.versions ?? []).filter(item => item.id !== current?.id); elements.compareVersion.replaceChildren(...[{ id: "", label: "No comparison" }, ...alternatives].map(item => new Option(item.label, item.id, item.id === state.compareId, item.id === state.compareId))); elements.compareVersion.disabled = !alternatives.length;
  const reference = compare(); elements.comparison.hidden = !reference; if (reference) { elements.comparisonArt.style.backgroundImage = `url("${reference.lightUrl}")`; elements.comparisonLabel.textContent = reference.label; elements.comparisonSeason.textContent = reference.season || "Day reference"; }
  document.querySelectorAll("button[data-appearance]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.appearance === state.appearance)));
  document.querySelectorAll("button[data-display]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.display === state.display)));
  const pixelEvents = isPixelEvents(current); elements.eventControl.hidden = !pixelEvents; elements.previewMotion.hidden = !pixelEvents;
  elements.motionStatus.textContent = motionLabel(current); elements.motionToggle.textContent = state.paused || state.motionOff ? "Resume events" : "Pause events"; elements.motionToggle.disabled = !pixelEvents;
  elements.motionOff.textContent = state.motionOff ? "Enable events" : "Show still"; elements.motionOff.disabled = !pixelEvents;
  elements.openLoop.hidden = !current?.loopUrl; elements.openLoop.href = current?.loopUrl || "#";
  elements.pair.classList.toggle("chat-hidden", !elements.chatVisible.checked); elements.pair.classList.toggle("single", state.display === "single");
  elements.pair.style.setProperty("--wallpaper-intensity", String(Number(elements.intensity.value) / 100)); elements.intensityValue.textContent = `${elements.intensity.value}%`;
  renderReview(); renderArtwork();
  if (pixelEvents) eventDocument(current).then(document => {
    if (current !== version()) return;
    const eventIds = document.events.map(event => event.id).join("|");
    if (elements.eventSelect.dataset.eventIds !== eventIds) {
      const selected = elements.eventSelect.value;
      elements.eventSelect.replaceChildren(...document.events.map(event => new Option(event.label, event.id, false, event.id === selected)));
      elements.eventSelect.dataset.eventIds = eventIds;
    }
  }).catch(() => {});
}
function renderGrid() {
  const scenes = state.model?.scenes ?? [];
  elements.grid.replaceChildren(...scenes.map(item => {
    const first = preferredVersion(item); const card = document.createElement("button"); card.type = "button"; card.className = "scene-card"; card.setAttribute("aria-pressed", String(item.id === state.sceneId));
    card.innerHTML = '<img class="scene-thumb" alt=""><span class="scene-card-copy"><strong></strong><span></span><em></em></span>';
    card.querySelector(".scene-thumb").src = first.lightUrl; card.querySelector("strong").textContent = item.title; card.querySelector(".scene-card-copy span").textContent = item.description; card.querySelector("em").textContent = `${item.versions.length} ${item.versions.length === 1 ? "version" : "versions"}`;
    card.addEventListener("click", () => { state.sceneId = item.id; state.versionId = first.id; state.compareId = ""; resetMotion(); render(true); ensureSchedule(); }); return card;
  }));
}
function resetMotion() { stopTimer(); state.playback = null; state.scheduleIndex = 0; state.motionOff = false; state.paused = false; }
function render(versionChanged = false) { renderGrid(); if (versionChanged) loadFeedback(); renderControls(); }
async function refresh() {
  const wasLatest = version()?.id === preferredVersion(scene())?.id;
  const oldScene = state.sceneId; const oldVersion = state.versionId; const response = await fetch(`/api/scenic?refresh=${Date.now()}`, { cache: "no-store" }); if (!response.ok) throw new Error(`Scenic gallery request failed (${response.status})`);
  state.model = await response.json(); const currentScene = state.model.scenes.find(item => item.id === oldScene) ?? state.model.scenes[0] ?? null;
  state.sceneId = currentScene?.id ?? null; state.versionId = (wasLatest ? preferredVersion(currentScene)?.id : currentScene?.versions.find(item => item.id === oldVersion)?.id) ?? preferredVersion(currentScene)?.id ?? null; state.compareId = currentScene?.versions.some(item => item.id === state.compareId) ? state.compareId : ""; resetMotion(); render(true); ensureSchedule();
  elements.notice.hidden = !state.model.manifestError; elements.notice.textContent = state.model.manifestError ?? "";
}
$("#refresh").addEventListener("click", () => refresh().catch(error => { elements.notice.hidden = false; elements.notice.textContent = `Could not refresh artwork: ${error.message}`; }));
elements.version.addEventListener("change", () => { state.versionId = elements.version.value; state.compareId = ""; resetMotion(); render(true); ensureSchedule(); });
elements.compareVersion.addEventListener("change", () => { state.compareId = elements.compareVersion.value; render(); });
document.querySelectorAll("button[data-appearance]").forEach(button => button.addEventListener("click", () => { state.appearance = button.dataset.appearance; renderControls(); }));
document.querySelectorAll("button[data-display]").forEach(button => button.addEventListener("click", () => { state.display = button.dataset.display; renderControls(); }));
[elements.chatVisible, elements.intensity].forEach(control => control.addEventListener("input", () => render()));
elements.motionToggle.addEventListener("click", () => { state.paused = !state.paused; restoreRest(); render(); ensureSchedule(); });
elements.motionOff.addEventListener("click", () => { state.motionOff = !state.motionOff; restoreRest(); render(); ensureSchedule(); });
elements.previewMotion.addEventListener("click", async () => { const document = await eventDocument(); const event = document?.events.find(item => item.id === elements.eventSelect.value); if (event) playEvent(document, event, true); });
elements.feedback.addEventListener("input", saveDraft); $("#approve").addEventListener("click", () => signOff("Approved")); $("#request-changes").addEventListener("click", () => signOff("Changes requested"));
$("#export-feedback").addEventListener("click", () => { const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), reviews: store(keys.reviews) }, null, 2)], { type: "application/json" }); const url = URL.createObjectURL(blob); const link = document.createElement("a"); link.href = url; link.download = "motd-scenic-wallpaper-feedback.json"; link.click(); URL.revokeObjectURL(url); });
systemTheme.addEventListener("change", () => { if (state.appearance === "follow") render(); });
reduceMotion.addEventListener("change", () => { restoreRest(); render(); ensureSchedule(); });
document.addEventListener("visibilitychange", () => { restoreRest(); render(); ensureSchedule(); });
new ResizeObserver(() => { positionArt(elements.lightArt, "light", version()?.paired); positionArt(elements.darkArt, "dark", version()?.paired); }).observe(elements.lightArt);
renderChat(); refresh().catch(error => { elements.notice.hidden = false; elements.notice.textContent = `Could not load scenic artwork: ${error.message}`; });
