const elements = {
  grid: document.querySelector("#sticker-grid"), notice: document.querySelector("#notice"), dialog: document.querySelector("#lightbox"),
  dialogImage: document.querySelector("#lightbox-image"), dialogTitle: document.querySelector("#lightbox-title"), dialogActions: document.querySelector("#lightbox-actions"),
  close: document.querySelector("#close"), print: document.querySelector("#print-sheet"),
};
function link(label, href) { const anchor = document.createElement("a"); anchor.className = "download"; anchor.href = href; anchor.download = ""; anchor.textContent = label; return anchor; }
function open(sticker) {
  elements.dialogImage.src = sticker.artworkUrl; elements.dialogImage.alt = `motd sticker: ${sticker.title}`; elements.dialogTitle.textContent = sticker.title;
  elements.dialogActions.replaceChildren(link("SVG", sticker.svgUrl), link("PNG", sticker.pngUrl)); elements.dialog.showModal();
}
function card(sticker) {
  const article = document.createElement("article"); article.className = `card shape-${sticker.shape ?? "rectangle"}`;
  if (sticker.available) {
    const button = document.createElement("button"); button.type = "button"; button.className = "art-button"; button.setAttribute("aria-label", `View ${sticker.title} at full size`);
    const image = document.createElement("img"); image.loading = "lazy"; image.src = sticker.artworkUrl; image.alt = `motd sticker: ${sticker.title}`;
    button.append(image); button.addEventListener("click", () => open(sticker)); article.append(button);
  } else { const pending = document.createElement("div"); pending.className = "pending-art"; pending.textContent = "ARTWORK\nINCOMING"; article.append(pending); }
  const copy = document.createElement("div"); copy.className = "card-copy";
  const heading = document.createElement("div"); const title = document.createElement("h2"); title.textContent = sticker.title; const status = document.createElement("p"); status.className = "status"; status.textContent = sticker.status; heading.append(title, status);
  if (sticker.paletteName) { const palette = document.createElement("p"); palette.className = "palette"; palette.append(`${sticker.paletteName} · `); const swatches = document.createElement("span"); swatches.className = "swatches"; for (const color of sticker.palette ?? []) { const swatch = document.createElement("i"); swatch.className = "swatch"; swatch.style.backgroundColor = color; swatches.append(swatch); } palette.append(swatches); heading.append(palette); }
  const actions = document.createElement("div"); actions.className = "actions"; if (sticker.available) actions.append(link("SVG", sticker.svgUrl), link("PNG", sticker.pngUrl)); else { const waiting = document.createElement("span"); waiting.className = "disabled"; waiting.textContent = "downloads unlock after QR build"; actions.append(waiting); }
  copy.append(heading, actions); article.append(copy); return article;
}
async function editionModel() {
  const requested = new URLSearchParams(location.search).get("edition");
  const eligible = requested === "v2" ? ["v2", "v1"] : ["v3", "v2", "v1"];
  let edition = "v1";
  let promptResponse;
  for (const candidate of eligible) { const response = await fetch(`../prompts${candidate === "v1" ? "" : `-${candidate}`}.json`, { cache: "no-store" }); if (response.ok) { edition = candidate; promptResponse = response; break; } }
  if (!promptResponse) throw new Error("no sticker metadata was found");
  if (!promptResponse.ok) throw new Error(`prompts returned ${promptResponse.status}`);
  const prompts = await promptResponse.json();
  const suffix = edition === "v1" ? "" : `-${edition}`;
  const exportsResponse = await fetch(`../exports${suffix}/manifest.json`, { cache: "no-store" });
  const exports = exportsResponse.ok ? await exportsResponse.json() : { assets: [] };
  const complete = new Map(exports.assets.filter(asset => asset.available).map(asset => [asset.slug, asset]));
  return { edition, stickers: prompts.map(prompt => { const asset = complete.get(prompt.slug); const prefix = `../exports${suffix}`; return { slug: prompt.slug, title: prompt.title, shape: prompt.shape, paletteName: prompt.paletteName, palette: prompt.palette, available: Boolean(asset), status: asset ? "QR integrated" : "Awaiting generated artwork", artworkUrl: asset ? `${prefix}/${asset.slug}.png` : `../${prompt.artwork}`, svgUrl: asset ? `${prefix}/${asset.slug}.svg` : null, pngUrl: asset ? `${prefix}/${asset.slug}.png` : null }; }) };
}
async function load() { const { edition, stickers } = await editionModel(); for (const link of document.querySelectorAll(".edition-link")) { if (link.dataset.edition === edition) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current"); } elements.grid.replaceChildren(...stickers.map(card)); const ready = stickers.filter(sticker => sticker.available).length; elements.notice.textContent = `${ready}/${stickers.length} proof${ready === 1 ? "" : "s"} have a real QR code for motd releases.`; }
elements.close.addEventListener("click", () => elements.dialog.close()); elements.dialog.addEventListener("click", event => { if (event.target === elements.dialog) elements.dialog.close(); }); elements.print.addEventListener("click", () => window.print()); load().catch(error => { elements.notice.textContent = `Could not load sticker proofs: ${error.message}`; });
