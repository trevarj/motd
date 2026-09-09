const treatments = [
  { id: "original", index: "00", name: "Original flat", file: "original-black.png", prompt: false },
  { id: "01-crisp-flat", index: "01", name: "Crisp flat", file: "01-crisp-flat.png" },
  { id: "02-satin-ink", index: "02", name: "Satin ink", file: "02-satin-ink.png" },
  { id: "03-matte-ceramic", index: "03", name: "Matte ceramic", file: "03-matte-ceramic.png" },
  { id: "04-soft-bevel", index: "04", name: "Soft bevel", file: "04-soft-bevel.png" },
  { id: "05-satin-metal", index: "05", name: "Satin metal", file: "05-satin-metal.png" },
];

const wordmark = "wordmark-black.png";

function image(src, className, alt) {
  const element = document.createElement("img");
  element.src = src;
  element.className = className;
  element.alt = alt;
  return element;
}

function lockup(file, treatmentName) {
  const element = document.createElement("div");
  element.className = "lockup";
  element.append(image(file, "lockup-symbol", `${treatmentName} motd symbol`));
  const crop = document.createElement("div");
  crop.className = "wordmark-crop";
  crop.append(image(wordmark, "", "motd wordmark"));
  element.append(crop);
  return element;
}

function sizeProofs(file, treatmentName) {
  const row = document.createElement("div");
  row.className = "sizes";
  for (const size of [24, 48, 96]) {
    const figure = document.createElement("figure");
    figure.style.setProperty("--size", `${size}px`);
    figure.append(image(file, "", `${treatmentName} at ${size} pixels`));
    const caption = document.createElement("figcaption");
    caption.textContent = `${size}px`;
    figure.append(caption);
    row.append(figure);
  }
  return row;
}

function detailProof(file, treatmentName) {
  const figure = document.createElement("figure");
  figure.className = "detail";
  figure.append(image(file, "", `${treatmentName} enlarged symbol detail`));
  const caption = document.createElement("figcaption");
  caption.textContent = "enlarged detail";
  figure.append(caption);
  return figure;
}

function surface(kind, treatment) {
  const panel = document.createElement("section");
  panel.className = `surface surface--${kind}`;
  const label = document.createElement("div");
  label.className = "surface-label";
  label.textContent = kind === "light" ? "Black on white" : "White on black · inverted proof";
  panel.append(label, lockup(treatment.file, treatment.name), detailProof(treatment.file, treatment.name), sizeProofs(treatment.file, treatment.name));
  return panel;
}

function links(treatment) {
  const row = document.createElement("div");
  row.className = "links";
  const source = document.createElement("a");
  source.href = treatment.file;
  source.textContent = "Source PNG";
  source.target = "_blank";
  source.rel = "noreferrer";
  row.append(source);
  if (treatment.prompt !== false) {
    const prompt = document.createElement("a");
    prompt.href = "prompts.json";
    prompt.textContent = "Prompt set";
    prompt.target = "_blank";
    prompt.rel = "noreferrer";
    row.append(prompt);
  }
  return row;
}

function study(treatment) {
  const card = document.createElement("article");
  card.className = `study ${treatment.id === "original" ? "original" : ""}`;
  const header = document.createElement("header");
  header.className = "study-head";
  const title = document.createElement("h3");
  title.textContent = treatment.name;
  const index = document.createElement("span");
  index.className = "study-index";
  index.textContent = treatment.index;
  header.append(title, index);
  const surfaces = document.createElement("div");
  surfaces.className = "surfaces";
  surfaces.append(surface("light", treatment), surface("dark", treatment));
  const footer = document.createElement("footer");
  footer.className = "study-foot";
  const note = document.createElement("span");
  if (treatment.id === "05-satin-metal") {
    note.className = "pending";
    note.textContent = "Transparency cleanup pending";
  } else if (treatment.prompt !== false) {
    note.textContent = "Generated surface study · silhouette review required";
  } else {
    note.textContent = "Authoritative SVG raster reference";
  }
  footer.append(note, links(treatment));
  card.append(header, surfaces, footer);
  return card;
}

function setupThemeToggle() {
  const button = document.querySelector("#theme-toggle");
  button.addEventListener("click", () => {
    const isDark = document.body.dataset.theme !== "dark";
    document.body.dataset.theme = isDark ? "dark" : "light";
    button.textContent = isDark ? "Use light page" : "Use dark page";
    button.setAttribute("aria-pressed", String(isDark));
  });
}

document.querySelector("#studies").append(...treatments.map(study));
setupThemeToggle();
window.motdLogoStudiesReady = true;
