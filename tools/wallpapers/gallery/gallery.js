const elements = {
  grid: document.querySelector("#theme-grid"),
  notice: document.querySelector("#notice"),
  selection: document.querySelector("#selection-detail"),
  inspectorHeading: document.querySelector("#inspector-heading"),
  imageMetadata: document.querySelector("#image-metadata"),
  original: document.querySelector("#original-art"),
  originalStage: document.querySelector("#original-stage"),
  originalEmpty: document.querySelector("#original-empty"),
  repeat: document.querySelector("#repeat-art"),
  repeatStage: document.querySelector("#repeat-stage"),
  repeatEmpty: document.querySelector("#repeat-empty"),
  chat: document.querySelector("#chat-wallpaper"),
  phone: document.querySelector("#phone-frame"),
  intensity: document.querySelector("#intensity"),
  intensityValue: document.querySelector("#intensity-value"),
  variant: document.querySelector("#variant"),
};

const state = { gallery: null, themeId: "motd", asset: null, tab: "original", zoom: "fit" };

function setMask(element, url) {
  const value = url ? `url("${url}")` : "none";
  element.style.maskImage = value;
  element.style.webkitMaskImage = value;
}

// Percent-sized raster masks can land on half a CSS pixel at narrow viewport widths.
// Snap one tile to a whole CSS pixel so repeated mask edges share an exact raster column.
function snapRepeatTileSize() {
  const tileSize = Math.max(1, Math.floor(elements.repeatStage.clientWidth / 2));
  elements.repeatStage.style.setProperty("--repeat-tile-size", `${tileSize}px`);
}

function selectedTheme() {
  return state.gallery?.themes.find(theme => theme.id === state.themeId) ?? state.gallery?.themes[0];
}

function chooseAsset(theme) {
  return theme?.assets.find(asset => asset.status === "Final") ?? theme?.assets[0] ?? null;
}

function renderGrid() {
  const orderedThemes = [...state.gallery.themes].sort((left, right) => Number(Boolean(chooseAsset(right))) - Number(Boolean(chooseAsset(left))));
  elements.grid.replaceChildren(...orderedThemes.map(theme => {
    const asset = chooseAsset(theme);
    const card = document.createElement("button");
    card.type = "button";
    card.className = `theme-card${asset ? " has-art" : ""}`;
    card.setAttribute("aria-pressed", String(theme.id === state.themeId));
    card.innerHTML = `<span class="card-art" aria-hidden="true"></span><span class="card-copy"><span class="card-name"></span><span class="card-description"></span><span class="status"></span></span>`;
    card.querySelector(".card-name").textContent = theme.label;
    card.querySelector(".card-description").textContent = theme.description;
    card.querySelector(".status").textContent = asset ? asset.status : "Awaiting artwork";
    if (asset) setMask(card.querySelector(".card-art"), asset.url);
    card.addEventListener("click", () => { state.themeId = theme.id; state.asset = chooseAsset(theme); render(); });
    return card;
  }));
}

async function imageDimensions(url) {
  const image = new Image();
  image.src = url;
  try {
    await image.decode();
    return { width: image.naturalWidth, height: image.naturalHeight };
  } catch {
    return null;
  }
}

async function renderArtwork() {
  const theme = selectedTheme();
  const asset = state.asset ?? chooseAsset(theme);
  state.asset = asset;
  const hasAsset = Boolean(asset);
  elements.variant.replaceChildren(...(theme?.assets.length ? theme.assets : [{ id: "", label: "No artwork available" }]).map(candidate => {
    const option = document.createElement("option");
    option.value = candidate.id;
    option.textContent = candidate.label;
    option.selected = candidate.id === asset?.id;
    return option;
  }));
  elements.variant.disabled = !hasAsset;
  elements.inspectorHeading.textContent = theme.label;
  elements.selection.textContent = hasAsset ? asset.label : "Awaiting artwork";
  elements.imageMetadata.hidden = true;
  elements.original.hidden = !hasAsset;
  elements.originalEmpty.hidden = hasAsset;
  elements.originalStage.classList.toggle("has-art", hasAsset);
  if (hasAsset) {
    elements.original.src = asset.url;
    elements.original.alt = `${theme.label} original artwork`;
  } else {
    elements.original.removeAttribute("src");
    elements.original.alt = "";
  }
  for (const element of [elements.repeat, elements.chat]) {
    element.hidden = !hasAsset;
    setMask(element, asset?.url);
  }
  elements.repeatStage.classList.toggle("has-art", hasAsset);
  elements.repeatEmpty.hidden = hasAsset;
  if (!asset) return;
  const currentUrl = asset.url;
  const dimensions = await imageDimensions(currentUrl);
  if (state.asset?.url !== currentUrl) return;
  if (!dimensions) {
    elements.original.hidden = true;
    elements.originalEmpty.hidden = false;
    elements.originalEmpty.textContent = "This artwork could not be decoded.";
    elements.repeatEmpty.hidden = false;
    elements.repeatEmpty.textContent = "This artwork could not be decoded.";
    for (const element of [elements.repeat, elements.chat]) {
      element.hidden = true;
      setMask(element, null);
    }
    return;
  }
  elements.originalEmpty.textContent = "Artwork is awaiting its first study.";
  elements.repeatEmpty.textContent = "Artwork is awaiting its first study.";
  elements.originalStage.style.setProperty("--art-width", `${dimensions.width}px`);
  elements.originalStage.style.setProperty("--art-height", `${dimensions.height}px`);
  elements.imageMetadata.hidden = false;
  elements.imageMetadata.textContent = `Original raster: ${dimensions.width} × ${dimensions.height} px`;
}

function render() {
  renderGrid();
  renderArtwork();
  document.querySelectorAll("[data-tab]").forEach(button => {
    const selected = button.dataset.tab === state.tab;
    button.setAttribute("aria-selected", String(selected));
    button.tabIndex = selected ? 0 : -1;
    document.querySelector(`#panel-${button.dataset.tab}`).hidden = !selected;
  });
  document.querySelectorAll(".zoom").forEach(button => button.classList.toggle("active", button.dataset.zoom === state.zoom));
  elements.originalStage.classList.toggle("zoom-1", state.zoom === "1");
  elements.originalStage.classList.toggle("zoom-2", state.zoom === "2");
}

async function refresh() {
  const previousAssetId = state.asset?.id;
  const response = await fetch(`/api/gallery?refresh=${Date.now()}`, { cache: "no-store" });
  if (!response.ok) throw new Error(`Gallery request failed (${response.status})`);
  state.gallery = await response.json();
  const selected = selectedTheme();
  state.asset = selected?.assets.find(asset => asset.id === previousAssetId) ?? chooseAsset(selected);
  elements.notice.hidden = !state.gallery.manifestError;
  elements.notice.textContent = state.gallery.manifestError ?? "";
  render();
}

document.querySelector("#refresh").addEventListener("click", () => refresh().catch(error => {
  elements.notice.hidden = false;
  elements.notice.textContent = `Could not refresh artwork: ${error.message}`;
}));
const tabs = [...document.querySelectorAll("[data-tab]")];
tabs.forEach((button, index) => {
  button.addEventListener("click", () => { state.tab = button.dataset.tab; render(); });
  button.addEventListener("keydown", event => {
    let nextIndex;
    if (event.key === "ArrowRight") nextIndex = (index + 1) % tabs.length;
    else if (event.key === "ArrowLeft") nextIndex = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === "Home") nextIndex = 0;
    else if (event.key === "End") nextIndex = tabs.length - 1;
    else return;
    event.preventDefault();
    state.tab = tabs[nextIndex].dataset.tab;
    render();
    tabs[nextIndex].focus();
  });
});
document.querySelectorAll(".zoom").forEach(button => button.addEventListener("click", () => { state.zoom = button.dataset.zoom; render(); }));
document.querySelector("#seam-guides").addEventListener("change", event => elements.repeatStage.classList.toggle("show-seams", event.target.checked));
new ResizeObserver(snapRepeatTileSize).observe(elements.repeatStage);
document.querySelectorAll("button[data-appearance]").forEach(button => button.addEventListener("click", () => {
  elements.phone.dataset.appearance = button.dataset.appearance;
  document.querySelectorAll("button[data-appearance]").forEach(control => control.setAttribute("aria-pressed", String(control === button)));
}));
elements.variant.addEventListener("change", () => {
  state.asset = selectedTheme()?.assets.find(asset => asset.id === elements.variant.value) ?? null;
  render();
});
elements.intensity.addEventListener("input", () => {
  elements.phone.style.setProperty("--intensity", elements.intensity.value);
  elements.intensityValue.value = `${elements.intensity.value}%`;
  elements.intensityValue.textContent = `${elements.intensity.value}%`;
});

refresh().catch(error => {
  elements.notice.hidden = false;
  elements.notice.textContent = `Could not load the gallery: ${error.message}`;
});
