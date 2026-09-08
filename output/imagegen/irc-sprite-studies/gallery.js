const PARTS = ["body", "head", "face", "accessory", "accent"];
const OUTPUT_SIZE = 1024;
const SMALL_SIZES = [16, 20, 24, 32, 48, 88];
const ACCENT_COLORS = ["#78dce8", "#a78bfa", "#f472b6"];

const grid = document.querySelector("#style-grid");
const pending = document.querySelector("#pending");
const template = document.querySelector("#style-template");

const isPixel = (style) => style.id === "pixel";

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`Could not load ${src}`));
    image.src = src;
  });
}

function addOption(select, item, selected) {
  const option = document.createElement("option");
  option.value = item?.id ?? "none";
  option.textContent = item?.label ?? "None";
  option.selected = selected === option.value;
  select.append(option);
}

function chosenPart(style, state, part) {
  if (part === "accessory" && state.accessory === "none") return null;
  return style.components[part].find((item) => item.id === state[part]) ?? null;
}

const alphaBoundsByImage = new WeakMap();

function alphaBounds(image) {
  const cached = alphaBoundsByImage.get(image);
  if (cached) return cached;
  const canvas = document.createElement("canvas");
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.drawImage(image, 0, 0);
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
  let left = canvas.width;
  let top = canvas.height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < canvas.height; y += 1) {
    for (let x = 0; x < canvas.width; x += 1) {
      // Generated PNGs can contain distant 1/255-alpha specks. They must not move a layer's anchors.
      if (pixels[(y * canvas.width + x) * 4 + 3] <= 8) continue;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
    }
  }
  const bounds = right < left ? null : { x: left, y: top, width: right - left + 1, height: bottom - top + 1 };
  alphaBoundsByImage.set(image, bounds);
  return bounds;
}

function drawComponent(context, image, item) {
  const placement = item.trim === true && Array.isArray(item.placement) && item.placement.length === 4
    && item.placement.every(Number.isFinite) ? item.placement : null;
  if (!placement) return context.drawImage(image, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
  const bounds = alphaBounds(image);
  if (!bounds) return undefined;
  const [x, y, width, height] = placement.map((value) => value * OUTPUT_SIZE);
  return context.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, x, y, width, height);
}

function tintAccent(image, item, color) {
  const canvas = document.createElement("canvas");
  canvas.width = OUTPUT_SIZE;
  canvas.height = OUTPUT_SIZE;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  drawComponent(context, image, item);
  // Tint luminance only: retain the generated alpha, including antialiased edges.
  const pixels = context.getImageData(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
  const rgb = [1, 3, 5].map((offset) => parseInt(color.slice(offset, offset + 2), 16));
  for (let i = 0; i < pixels.data.length; i += 4) {
    if (pixels.data[i + 3] === 0) continue;
    const light = (pixels.data[i] * .2126 + pixels.data[i + 1] * .7152 + pixels.data[i + 2] * .0722) / 255;
    for (let channel = 0; channel < 3; channel += 1) pixels.data[i + channel] = rgb[channel] * light;
  }
  context.putImageData(pixels, 0, 0);
  return canvas;
}

function redrawSmall(card) {
  for (const canvas of card.smallCanvases) {
    const size = Number(canvas.dataset.size);
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext("2d");
    context.imageSmoothingEnabled = !isPixel(card.style);
    context.drawImage(card.canvas, 0, 0, size, size);
  }
  if (card.overviewCanvas) {
    const context = card.overviewCanvas.getContext("2d");
    context.clearRect(0, 0, 256, 256);
    context.imageSmoothingEnabled = !isPixel(card.style);
    context.drawImage(card.canvas, 0, 0, 256, 256);
  }
}

function compose(card) {
  const context = card.canvas.getContext("2d");
  context.clearRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
  context.imageSmoothingEnabled = !isPixel(card.style);
  context.save();
  context.beginPath();
  context.arc(OUTPUT_SIZE / 2, OUTPUT_SIZE / 2, OUTPUT_SIZE / 2, 0, Math.PI * 2);
  context.clip();
  for (const part of PARTS) {
    const item = chosenPart(card.style, card.state, part);
    if (!item) continue;
    const image = card.images.get(item.id);
    if (part === "accent") context.drawImage(tintAccent(image, item, card.state.color), 0, 0);
    else drawComponent(context, image, item);
  }
  context.restore();
  redrawSmall(card);
}

function addComponents(node, style) {
  for (const part of PARTS) {
    for (const item of style.components[part]) {
      const figure = document.createElement("figure");
      figure.className = "component";
      const image = document.createElement("img");
      image.src = item.src;
      image.alt = `${item.label} ${part}`;
      image.loading = "lazy";
      const caption = document.createElement("figcaption");
      caption.textContent = `${part}: ${item.label}`;
      figure.append(image, caption);
      node.append(figure);
    }
  }
}

function addControls(card, root) {
  for (const part of PARTS.slice(0, 4)) {
    const select = root.querySelector(`[data-part="${part}"]`);
    if (part === "accessory") addOption(select, null, card.state.accessory);
    styleOptions(card.style.components[part], select, card.state[part]);
    select.addEventListener("change", () => {
      card.state[part] = select.value;
      compose(card);
    });
  }
  const color = root.querySelector('[data-part="color"]');
  color.value = card.state.color;
  color.addEventListener("input", () => {
    card.state.color = color.value;
    compose(card);
  });
  root.querySelector(".download").addEventListener("click", () => {
    const link = document.createElement("a");
    link.download = `${card.style.id}-irc-sprite.png`;
    link.href = card.canvas.toDataURL("image/png");
    link.click();
  });
}

function styleOptions(items, select, selected) {
  for (const item of items) addOption(select, item, selected);
}

function addPresets(card, root) {
  const list = root.querySelector(".preset-list");
  const { body: bodies, head: heads, face: faces, accessory: accessories } = card.style.components;
  const presets = [
    ["Greeter", 0, 0, 0, "none", 0],
    ["Operator", 1, 1, 1, 0, 1],
    ["Trouble", 0, 2, 2, 1, 2],
  ];
  presets.forEach(([label, body, head, face, accessory, color], index) => {
    const button = document.createElement("button");
    button.className = `preset${index === 0 ? " active" : ""}`;
    button.type = "button";
    button.textContent = label;
    button.addEventListener("click", () => {
      Object.assign(card.state, {
        body: bodies[body].id,
        head: heads[head].id,
        face: faces[face].id,
        accessory: accessory === "none" ? "none" : accessories[accessory].id,
        color: ACCENT_COLORS[color],
      });
      for (const input of root.querySelectorAll("select, input")) input.value = card.state[input.dataset.part];
      list.querySelectorAll("button").forEach((item) => item.classList.remove("active"));
      button.classList.add("active");
      compose(card);
    });
    list.append(button);
  });
}

async function renderStyle(style, index) {
  const fragment = template.content.cloneNode(true);
  const root = fragment.querySelector(".style-card");
  root.id = style.id;
  root.querySelector(".style-number").textContent = `Direction ${String(index + 1).padStart(2, "0")}`;
  root.querySelector("h2").textContent = style.name;
  root.querySelector(".style-description").textContent = style.description;
  root.querySelector(".reference").src = style.reference;
  root.querySelector(".reference").alt = `${style.name} generated reference`;
  const components = PARTS.flatMap((part) => style.components[part]);
  const images = new Map(await Promise.all(components.map(async (item) => [item.id, await loadImage(item.src)])));
  const card = {
    style,
    images,
    canvas: root.querySelector(".hero-canvas"),
    smallCanvases: [],
    state: {
      body: style.components.body[0].id,
      head: style.components.head[0].id,
      face: style.components.face[0].id,
      accessory: "none",
      accent: style.components.accent[0].id,
      color: ACCENT_COLORS[0],
    },
  };
  const overview = document.createElement("a");
  overview.href = `#${style.id}`;
  const overviewCanvas = document.createElement("canvas");
  overviewCanvas.width = 256;
  overviewCanvas.height = 256;
  overviewCanvas.setAttribute("aria-label", `${style.name} assembled avatar`);
  if (isPixel(style)) overviewCanvas.style.imageRendering = "pixelated";
  card.overviewCanvas = overviewCanvas;
  const overviewLabel = document.createElement("span");
  overviewLabel.textContent = style.name;
  overview.append(overviewCanvas, overviewLabel);
  document.querySelector("#overview").append(overview);
  addPresets(card, root);
  addControls(card, root);
  const sizeRoot = root.querySelector(".sizes");
  for (const size of SMALL_SIZES) {
    const sample = document.createElement("label");
    sample.className = "size-sample";
    const canvas = document.createElement("canvas");
    canvas.dataset.size = size;
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;
    if (isPixel(style)) canvas.style.imageRendering = "pixelated";
    sample.append(canvas, `${size}px approx.`);
    sizeRoot.append(sample);
    card.smallCanvases.push(canvas);
  }
  addComponents(root.querySelector(".component-grid"), style);
  const sheetLink = document.createElement("a");
  sheetLink.href = `previews/${style.id}-combinations.png`;
  sheetLink.textContent = "View all 54 combinations";
  sheetLink.className = "sheet-link";
  root.append(sheetLink);
  compose(card);
  grid.append(root);
}

function validate(manifest) {
  const expected = ["pixel", "cel", "ink", "gouache", "clay"];
  if (!Array.isArray(manifest.styles) || manifest.styles.length !== expected.length) throw new Error("five style entries required");
  for (const id of expected) {
    const style = manifest.styles.find((item) => item.id === id);
    if (!style || !style.reference || !style.components) throw new Error(`missing ${id} style`);
    for (const [part, count] of [["body", 2], ["head", 3], ["face", 3], ["accessory", 2], ["accent", 1]]) {
      if (!Array.isArray(style.components[part]) || style.components[part].length !== count) throw new Error(`${id} ${part} count is invalid`);
    }
  }
}

function showProblem() {
  grid.replaceChildren();
  document.querySelector("#overview").replaceChildren();
  pending.hidden = false;
  pending.innerHTML = "<h2>Artwork is not ready to inspect</h2><p>The image manifest or its components are missing. Generate the study assets, then refresh this page.</p>";
}

async function start() {
  try {
    const response = await fetch("manifest.json", { cache: "no-store" });
    if (!response.ok) throw new Error(`manifest.json returned ${response.status}`);
    const manifest = await response.json();
    validate(manifest);
    for (const [index, style] of manifest.styles.entries()) await renderStyle(style, index);
  } catch (error) {
    console.warn(error);
    showProblem();
  }
}

document.querySelectorAll("[data-surface-choice]").forEach((button) => {
  button.addEventListener("click", () => {
    document.body.dataset.surface = button.dataset.surfaceChoice;
    document.querySelectorAll("[data-surface-choice]").forEach((item) => item.setAttribute("aria-pressed", String(item === button)));
  });
});

start();
