#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(import.meta.dirname, "../..");
const outDir = path.join(root, "app/src/main/assets/chat-wallpapers");

const motifs = {
  bubble: ["M8 12h48a7 7 0 0 1 7 7v25a7 7 0 0 1-7 7H34L20 61V51H8a7 7 0 0 1-7-7V19a7 7 0 0 1 7-7z", "M14 27h35M14 37h24"],
  reply: ["M27 12 8 30l19 18M9 30h25c15 0 22 8 22 22"],
  typing: ["M10 32h44", "M18 32h.1M32 32h.1M46 32h.1"],
  hash: ["M22 8 14 56M46 8l-8 48M8 24h48M6 42h48"],
  mention: ["M51 47c-10 11-34 7-38-10C8 21 18 8 34 8c15 0 25 11 23 26-1 10-14 15-18 7V21m0 20c-12 8-23-5-15-16 7-9 20-2 15 8"],
  prompt: ["M11 14 31 32 11 50M34 50h22"],
  terminal: ["M5 8h54v48H5zM5 19h54M12 14h.1M20 14h.1M28 14h.1M13 30l9 8-9 8M27 46h16"],
  server: ["M8 9h48v18H8zM8 37h48v18H8zM16 18h.1M16 46h.1M24 18h24M24 46h24"],
  shield: ["M32 5c9 7 18 8 25 8v18c0 15-10 24-25 29C17 55 7 46 7 31V13c8 0 17-1 25-8zM20 32l8 8 17-19"],
  nodes: ["M11 17a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM53 39a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM15 53a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM17 12l29 18M47 36 21 48M12 46l-1-29"],
  signal: ["M8 46a34 34 0 0 1 48 0M17 37a21 21 0 0 1 30 0M27 28a8 8 0 0 1 11 0M32 51h.1"],
  clock: ["M32 5a27 27 0 1 0 0 54 27 27 0 0 0 0-54zM32 16v18l12 8"],
  link: ["M24 42H16a12 12 0 0 1 0-24h14a12 12 0 0 1 9 4M40 22h8a12 12 0 0 1 0 24H34a12 12 0 0 1-9-4M20 32h24"],
  pixel: ["M8 8h16v8H16v8H8zm32 0h16v16h-8v-8h-8zM8 40h8v8h8v8H8zm32 8h8v-8h8v16H40zM24 28h16v8H24z"],
};

const p = (name, x, y, scale = 1, rotation = 0, opacity = .66) => ({ name, x, y, scale, rotation, opacity });
const edge = (name, x, y, scale = 1, rotation = 0, opacity = .66) => [
  p(name, x, y, scale, rotation, opacity),
  p(name, x + (x < 0 ? 512 : -512), y, scale, rotation, opacity),
];

const presets = {
  chatter: [p("bubble",35,34,.9,-6),p("reply",180,28,.75,8,.5),p("typing",330,38,.8,-4),...edge("mention",-22,145,.9,8),p("bubble",125,145,.72,4,.52),p("typing",275,155,.64,8),p("reply",410,150,.82,-7),p("hash",55,295,.66,-5,.52),p("bubble",195,278,.92,7),p("mention",355,292,.72,-8,.52),...edge("reply",-18,425,.78,-4),p("typing",145,425,.75,4),p("bubble",300,410,.78,-6),p("hash",430,412,.62,7,.5)],
  channels: [p("hash",38,28,.8,-4),p("mention",180,35,.74,6),p("nodes",325,30,.72,-5,.5),...edge("hash",-25,160,.72,5),p("bubble",118,153,.72,-4,.52),p("mention",268,150,.8,-5),p("reply",412,160,.68,7,.5),p("nodes",50,290,.75,5),p("hash",195,288,.7,-5,.52),p("bubble",338,280,.82,6),...edge("mention",-20,421,.72,-7,.52),p("reply",132,410,.72,-5),p("nodes",275,420,.78,6),p("hash",425,405,.68,4)],
  terminal: [p("terminal",30,22,.82,-4),p("prompt",185,35,.7,5),p("typing",330,38,.7,-5,.5),...edge("prompt",-20,155,.78,4),p("terminal",120,145,.72,5),p("prompt",280,160,.8,-5),p("terminal",405,145,.68,-4,.52),p("typing",45,300,.72,6),p("terminal",180,275,.85,-5),p("prompt",345,292,.72,5),...edge("terminal",-22,414,.72,-4),p("prompt",130,425,.74,5,.52),p("typing",280,420,.72,-5),p("terminal",405,405,.7,5)],
  relay: [p("server",32,28,.8,-4),p("link",180,35,.72,5),p("shield",330,26,.78,-4),...edge("nodes",-22,155,.72,5),p("server",120,145,.72,4,.52),p("link",268,158,.78,-5),p("shield",412,146,.68,6),p("nodes",45,285,.78,-5),p("server",190,280,.76,5),p("link",340,290,.7,-4,.52),...edge("shield",-20,415,.72,-5),p("server",135,412,.72,4),p("nodes",278,410,.76,-5),p("link",420,415,.68,6)],
  signals: [p("signal",32,25,.8,-5),p("clock",185,30,.72,5,.52),p("nodes",330,28,.75,-4),...edge("signal",-24,155,.72,5),p("clock",120,148,.78,-5),p("signal",275,155,.72,4,.52),p("nodes",415,150,.7,-5),p("clock",50,285,.76,5),p("signal",195,285,.78,-4),p("clock",350,290,.7,5,.52),...edge("nodes",-20,418,.72,-5),p("signal",132,410,.75,5),p("clock",280,415,.72,-5),p("nodes",422,405,.7,4)],
  pixels: [p("pixel",35,28,.9,0),p("terminal",182,30,.65,0,.5),p("pixel",340,28,.8,0),...edge("pixel",-24,158,.82,0),p("prompt",128,155,.68,0,.52),p("pixel",275,150,.9,0),p("terminal",414,150,.6,0,.5),p("pixel",48,288,.86,0),p("terminal",190,278,.66,0,.5),p("pixel",350,290,.8,0),...edge("pixel",-22,420,.82,0),p("prompt",130,420,.7,0,.52),p("pixel",280,410,.88,0),p("terminal",420,408,.62,0,.5)],
};

function render(name, placements) {
  const paths = placements.flatMap(({name: motif, x, y, scale, rotation, opacity}) =>
    motifs[motif].map(d => `  <path d="${d}" transform="translate(${x} ${y}) rotate(${rotation} 32 32) scale(${scale})" fill="none" stroke="#000000" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" stroke-opacity="${opacity}"/>`));
  return `<?xml version="1.0" encoding="UTF-8"?>\n<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">\n${paths.join("\n")}\n</svg>\n`;
}

fs.mkdirSync(outDir, {recursive:true});
const check = process.argv.includes("--check");
let stale = false;
for (const [name, placements] of Object.entries(presets)) {
  const target = path.join(outDir, `${name}.svg`);
  const content = render(name, placements);
  if (check) {
    if (!fs.existsSync(target) || fs.readFileSync(target, "utf8") !== content) {
      console.error(`stale generated wallpaper: ${path.relative(root, target)}`);
      stale = true;
    }
  } else {
    fs.writeFileSync(target, content);
  }
}
if (stale) process.exit(1);
if (!check) console.log(`generated ${Object.keys(presets).length} wallpapers in ${path.relative(root, outDir)}`);
