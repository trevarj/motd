import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildArcadeSign } from "./build-arcade-sign.mjs";

const directory = path.dirname(fileURLToPath(import.meta.url));
const spec = JSON.parse(readFileSync(path.join(directory, "pixel-spec.json"), "utf8"));
const { width, height } = spec.canvas;
const rawLength = width * height * 4;
const hex = value => value.match(/\w\w/g).map(component => Number.parseInt(component, 16));
const pointInside = (x, y, polygon) => polygon.reduce((inside, point, index) => {
  const previous = polygon[(index + polygon.length - 1) % polygon.length];
  return ((point[1] > y) !== (previous[1] > y)) && x < (previous[0] - point[0]) * (y - point[1]) / (previous[1] - point[1]) + point[0] ? !inside : inside;
}, false);

function setPixel(pixels, x, y, color) {
  assert(Number.isInteger(x) && Number.isInteger(y) && x >= 0 && x < width && y >= 0 && y < height, `pixel outside canvas: ${x},${y}`);
  const [r, g, b] = hex(color); const index = (y * width + x) * 4;
  pixels[index] = r; pixels[index + 1] = g; pixels[index + 2] = b; pixels[index + 3] = 255;
}
function fillPolygon(pixels, points, color) {
  const xs = points.map(([x]) => x); const ys = points.map(([, y]) => y);
  for (let y = Math.max(0, Math.floor(Math.min(...ys))); y <= Math.min(height - 1, Math.ceil(Math.max(...ys))); y++) {
    for (let x = Math.max(0, Math.floor(Math.min(...xs))); x <= Math.min(width - 1, Math.ceil(Math.max(...xs))); x++) if (pointInside(x + 0.5, y + 0.5, points)) setPixel(pixels, x, y, color);
  }
}
function sourcePixels(source) {
  const raw = execFileSync("magick", [source, "-filter", "box", "-resize", `${width}x${height}!`, "-colors", "192", "rgba:-"], { maxBuffer: rawLength + 1024 });
  assert.equal(raw.length, rawLength, "unexpected source raster dimensions");
  return new Uint8Array(raw);
}
function grade(source, theme) {
  const pixels = new Uint8Array(source);
  for (let index = 0; index < pixels.length; index += 4) {
    const [r, g, b] = pixels.subarray(index, index + 3);
    if (theme === "day") {
      pixels[index] = Math.min(255, Math.round(r * 1.12 + 9));
      pixels[index + 1] = Math.min(255, Math.round(g * 1.11 + 11));
      pixels[index + 2] = Math.min(255, Math.round(b * 1.04 + 7));
    }
    pixels[index + 3] = 255;
  }
  return pixels;
}
function nightPaletteTransfer(source, study) {
  const samples = { sky: new Map(), foreground: new Map() };
  for (let index = 0; index < source.length; index += 4) {
    const pixel = index / 4; const x = pixel % width; const y = Math.floor(pixel / width);
    const region = spec.nightSkyPolygons.some(polygon => pointInside(x + 0.5, y + 0.5, polygon)) ? "sky" : "foreground";
    const key = `${source[index]},${source[index + 1]},${source[index + 2]}`;
    const bucket = samples[region].get(key) ?? []; bucket.push([study[index], study[index + 1], study[index + 2]]); samples[region].set(key, bucket);
  }
  const median = values => values.slice().sort((a, b) => a - b)[Math.floor(values.length / 2)];
  const palette = Object.fromEntries(Object.entries(samples).map(([region, entries]) => [region, new Map([...entries].map(([key, values]) => [key, [median(values.map(value => value[0])), median(values.map(value => value[1])), median(values.map(value => value[2]))]]))]));
  const pixels = new Uint8Array(source);
  for (let index = 0; index < pixels.length; index += 4) {
    const pixel = index / 4; const x = pixel % width; const y = Math.floor(pixel / width); const region = spec.nightSkyPolygons.some(polygon => pointInside(x + 0.5, y + 0.5, polygon)) ? "sky" : "foreground";
    const key = `${source[index]},${source[index + 1]},${source[index + 2]}`; const [r, g, b] = palette[region].get(key) ?? palette.foreground.get(key);
    pixels[index] = r; pixels[index + 1] = g; pixels[index + 2] = b; pixels[index + 3] = 255;
  }
  return pixels;
}
function decorate(pixels, theme, includeReflection = true) {
  const palette = spec.palette[theme];
  for (const facet of spec.waterFacets) fillPolygon(pixels, facet.points, palette[facet.color]);
  for (const [x, y, color] of spec.cleanupPixels[theme]) setPixel(pixels, x, y, color);
  for (const [x, y, color] of spec.staticSprites[theme]) if (y < 260) setPixel(pixels, x, y, color);
  if (includeReflection) for (const [x, y, color] of spec.restingReflection[theme]) setPixel(pixels, x, y, color);
  return pixels;
}
const colorAt = (pixels, x, y) => `#${[pixels[(y * width + x) * 4], pixels[(y * width + x) * 4 + 1], pixels[(y * width + x) * 4 + 2]].map(value => value.toString(16).padStart(2, "0")).join("")}`;
function eventCells(theme, state, base) {
  const palette = spec.palette[theme];
  const all = [[129,273],[130,273],[131,273],[132,273],[130,276],[131,276],[129,283],[130,283],[131,283],[129,286],[130,286],[131,286],[132,286],[129,289],[130,289],[131,289],[132,289],[130,293],[131,293],[132,293]];
  const patterns = {
    shorten: new Set(["130,273","131,273","130,276","131,276","130,283"]),
    separate: new Set(["129,273","132,273","130,276","131,276","129,286","132,286","130,293","131,293"]),
    reform: new Set(["129,273","130,273","131,273","132,273","129,283","130,283","131,283","130,286","131,286"]),
  };
  return all.map(([x, y]) => [x, y, patterns[state].has(`${x},${y}`) ? (state === "separate" ? palette.reflectionDim : palette.reflection) : colorAt(base, x, y)]);
}
export function eventsForSpec(lightBase = new Uint8Array(rawLength), darkBase = new Uint8Array(rawLength)) {
  const frame = (durationMs, state) => ({ durationMs, light: state ? eventCells("day", state, lightBase) : [], dark: state ? eventCells("dark", state, darkBase) : [] });
  return {
    schemaVersion: 1, scheduleSeed: spec.schedule.seed, width, height,
    initialDelayMs: spec.schedule.initialDelayMs, quietIntervalMs: spec.schedule.quietIntervalMs,
    events: [{ id: "bridge-window-reflection", label: "One distant window reflection separates and reforms", frames: [frame(280, "shorten"), frame(420, "separate"), frame(360, "reform"), frame(140, null)] }],
  };
}
export function applyOverrides(base, overrides) { const output = new Uint8Array(base); for (const [x, y, color] of overrides) setPixel(output, x, y, color); return output; }
function encode(file, pixels) {
  const result = spawnSync("magick", ["-size", `${width}x${height}`, "-depth", "8", "rgba:-", "-define", "png:color-type=6", file], { input: pixels });
  assert.equal(result.status, 0, result.stderr?.toString() || "ImageMagick failed");
}
function proofs(light, dark, events, output) {
  const proofDir = path.join(output, "proofs"); mkdirSync(proofDir, { recursive: true });
  const temp = path.join(tmpdir(), `motd-canal-r02-${process.pid}`); mkdirSync(temp, { recursive: true });
  try {
    const restLight = path.join(temp, "rest-light.png"); const restDark = path.join(temp, "rest-dark.png");
    encode(restLight, light); encode(restDark, dark);
    const event = events.events[0];
    const frames = event.frames.slice(0, -1).flatMap((frame, index) => {
      const lightFrame = path.join(temp, `event-${index}-light.png`); const darkFrame = path.join(temp, `event-${index}-dark.png`);
      encode(lightFrame, applyOverrides(light, frame.light)); encode(darkFrame, applyOverrides(dark, frame.dark)); return [lightFrame, darkFrame];
    });
    execFileSync("magick", [restLight, restDark, "+append", "-scale", "1024x896!", path.join(proofDir, "cyberpunk-canal-resting-pair.png")]);
    const rows = [0, 1, 2].map(index => {
      const row = path.join(temp, `event-row-${index}.png`); execFileSync("magick", [frames[index * 2], frames[index * 2 + 1], "+append", row]); return row;
    });
    const contact = path.join(temp, "event-contact.png");
    execFileSync("magick", [...rows, "-append", contact]);
    execFileSync("magick", [contact, "-scale", "1024x2688!", path.join(proofDir, "cyberpunk-canal-event-contact.png")]);
    writeFileSync(path.join(proofDir, "cyberpunk-canal-timed-preview.json"), `${JSON.stringify({ kind: "timing-specification", initialDelayMs: events.initialDelayMs, event: events.events[0], quietIntervalMs: events.quietIntervalMs, browserRecording: "build/scenic-revision02/browser-proofs/cyberpunk-canal-production-120s.webm", note: "This JSON specifies timings; the separate browser recording demonstrates actual production playback." }, null, 2)}\n`);
  } finally { rmSync(temp, { recursive: true, force: true }); }
}
export function build(output = directory) {
  const source = sourcePixels(path.join(directory, spec.source)); const study = sourcePixels(path.join(directory, spec.nightStudy));
  const daylight = grade(source, "day"); const lightBase = decorate(daylight, "day", false); const darkBase = decorate(nightPaletteTransfer(source, study), "dark", false);
  const light = decorate(new Uint8Array(lightBase), "day"); const dark = decorate(new Uint8Array(darkBase), "dark"); const events = eventsForSpec(lightBase, darkBase);
  mkdirSync(output, { recursive: true }); encode(path.join(output, "cyberpunk-canal-light.png"), light); encode(path.join(output, "cyberpunk-canal-dark.png"), dark);
  writeFileSync(path.join(output, "cyberpunk-canal-events.json"), `${JSON.stringify(events, null, 2)}\n`);
  const renderedSprites = Object.fromEntries(Object.entries(spec.staticSprites).map(([theme, sprites]) => [theme, sprites.filter(([, y]) => y < 260)]));
  writeFileSync(path.join(output, "cyberpunk-canal-materials.json"), `${JSON.stringify({ schemaVersion: 1, scene: spec.scene, width, height, palette: spec.palette, materials: spec.materials, waterFacets: spec.waterFacets, nightPaletteTransfer: "same-position median palette mapping from night study, with an authored atmospheric-region map; source geometry retained", nightIlluminationPolygons: spec.nightIlluminationPolygons, nightSkyPolygons: spec.nightSkyPolygons, staticSprites: renderedSprites, restingReflection: spec.restingReflection }, null, 2)}\n`);
  proofs(light, dark, events, output); return { light, dark, events };
}

const box = (x, y, w, h) => [[x,y],[x+w,y],[x+w,y+h],[x,y+h]];
const rgbHex = values => "#" + values.map(v => Math.max(0,Math.min(255,Math.round(v))).toString(16).padStart(2,"0")).join("");
// Studies contribute colors only. Every output pixel retains the canonical source's
// palette identity within explicitly drawn material regions; study geometry is never copied.
export function materialTransfer(source, study, regions) {
  const keys = [], buckets = new Map();
  for (let i=0; i<rawLength; i+=4) {
    const x=(i/4)%width, y=Math.floor(i/4/width);
    const role=regions.find(r=>pointInside(x+.5,y+.5,r.polygon))?.id ?? "surroundings";
    const key=role+":"+[source[i],source[i+1],source[i+2]].join(",");
    keys.push(key);
    const bucket=buckets.get(key) ?? [[],[],[]];
    for(let c=0;c<3;c++) bucket[c].push(study[i+c]);
    buckets.set(key,bucket);
  }
  const palette=Object.fromEntries([...buckets].map(([key,channels])=>[key,channels.map(values=>values.sort((a,b)=>a-b)[Math.floor(values.length/2)])]));
  const pixels=new Uint8Array(source);
  keys.forEach((key,p)=>pixels.set([...palette[key],255],p*4));
  return {pixels,palette};
}
const translated = (anchor, points, color) => points.map(([x,y])=>[x+anchor[0],y+anchor[1],color]);
const draw = (pixels, cells) => { for(const [x,y,color] of cells) setPixel(pixels,x,y,color); };
const emptyFrame = durationMs => ({durationMs,light:[],dark:[]});
function documentFor(id, events) {
  return {schemaVersion:1,scheduleSeed:id+"-r02-authored",width,height,initialDelayMs:[20000,40000],quietIntervalMs:[45000,90000],events};
}
const moon = anchor => translated(anchor,[[0,-2],[1,-2],[-1,-1],[0,-1],[1,-1],[2,-1],[-1,0],[0,0],[1,0],[2,0],[0,1],[1,1]],"#dfdfba");
function starEvent(anchor) {
  return {id:"single-star",label:"One distant star softens and returns",frames:[
    {durationMs:350,light:[],dark:translated(anchor,[[0,0]],"#7898b7")},
    {durationMs:450,light:[],dark:translated(anchor,[[0,0]],"#4f6b8a")},
    {durationMs:300,light:[],dark:translated(anchor,[[0,0]],"#6c8ca8")},
    emptyFrame(100)
  ]};
}
function exportScene(id, light, dark, events, materials) {
  encode(path.join(directory,id+"-light.png"),light);
  encode(path.join(directory,id+"-dark.png"),dark);
  writeFileSync(path.join(directory,id+"-events.json"),JSON.stringify(documentFor(id,events),null,2)+"\n");
  writeFileSync(path.join(directory,id+"-materials.json"),JSON.stringify({schemaVersion:1,scene:id,width,height,...materials},null,2)+"\n");
  const proofDir=path.join(directory,"proofs"); mkdirSync(proofDir,{recursive:true});
  execFileSync("magick",[path.join(directory,id+"-light.png"),path.join(directory,id+"-dark.png"),"+append","-scale","1024x896",path.join(proofDir,id+"-resting-pair.png")]);
}
const glyphs = {
  m:["101","111","111","101","101"],o:["010","101","101","101","010"],t:["111","010","010","010","011"],d:["001","001","011","101","011"],
  i:["010","000","010","010","010"],r:["000","110","101","100","100"],c:["000","011","100","100","011"],l:["100","100","100","100","011"],
  a:["010","101","111","101","101"],g:["011","100","101","101","011"],e:["111","100","110","100","111"],n:["000","110","101","101","101"],
  y:["101","101","011","001","110"],k:["100","101","110","101","101"],"#":["101","111","101","111","101"],":":["000","010","000","010","000"],
  "/":["001","001","010","100","100"],">":["100","010","001","010","100"]," ":["000","000","000","000","000"],".":["000","000","000","000","010"]
};
function textCells(text, x, y, color) {
  return [...text].flatMap((letter,index)=>(glyphs[letter] ?? glyphs[" "]).flatMap((row,dy)=>[...row].flatMap((bit,dx)=>bit==="1"?[[x+index*4+dx,y+dy,color]]:[])));
}
export function buildTerminal() {
  const id="retro-computer-room-v3", source=id+"-source.png";
  const master=sourcePixels(path.join(directory,source));
  const regions=[
    {id:"window-sky",polygon:box(63,15,137,105)},
    {id:"city",polygon:box(63,108,137,101)},
    {id:"crt-glass",polygon:box(69,218,124,75)},
    {id:"desk-lamp-and-wood",polygon:box(0,205,256,142)}
  ];
  const mapped=materialTransfer(master,sourcePixels(path.join(directory,id+"-night-study.png")),regions);
  const light=new Uint8Array(master), dark=mapped.pixels, resting=[];
  for(const [text,x,y] of [["motd",78,229],["#general",78,240],["irc ready",78,251],["> online",78,262]]) {
    const cells=textCells(text,x,y,"#4b956e");
    draw(light,cells); draw(dark,cells); resting.push(...cells);
  }
  const cursor=[[112,273,"#70b88a"],[113,273,"#70b88a"]];
  draw(light,cursor); draw(dark,cursor); resting.push(...cursor);
  draw(dark,moon([142,49]));
  const status=textCells("ok",137,262,"#74bb8e"), dots=textCells("..",137,262,"#4b956e");
  const event={id:"terminal-status",label:"A small terminal status appears, then clears",frames:[
    {durationMs:180,light:dots,dark:dots},
    {durationMs:720,light:status,dark:status},
    {durationMs:160,light:dots,dark:dots},
    emptyFrame(140)
  ]};
  exportScene(id,light,dark,[event],{
    geometrySource:source,
    regions,
    nightPalette:mapped.palette,
    restingSprites:resting,
    eventAnchor:[137,262],
    moonAnchor:[142,49]
  });
  const proofDir=path.join(directory,"proofs");
  const temporary=[
    ["rest",light,dark],
    ["dots",applyOverrides(light,dots),applyOverrides(dark,dots)],
    ["status",applyOverrides(light,status),applyOverrides(dark,status)]
  ];
  for(const [name,day,night] of temporary) {
    encode(path.join(proofDir,id+"-"+name+"-day.png"),day);
    encode(path.join(proofDir,id+"-"+name+"-night.png"),night);
  }
  execFileSync("magick",[
    path.join(proofDir,id+"-rest-day.png"),path.join(proofDir,id+"-dots-day.png"),path.join(proofDir,id+"-status-day.png"),"+append",
    path.join(proofDir,id+"-event-day.png")
  ]);
  execFileSync("magick",[
    path.join(proofDir,id+"-rest-night.png"),path.join(proofDir,id+"-dots-night.png"),path.join(proofDir,id+"-status-night.png"),"+append",
    path.join(proofDir,id+"-event-night.png")
  ]);
  execFileSync("magick",[
    path.join(proofDir,id+"-event-day.png"),path.join(proofDir,id+"-event-night.png"),"-append","-scale","200%",
    path.join(proofDir,id+"-event-frames.png")
  ]);
  return {light,dark,event,resting,regions};
}
function buildCafe() {
  const id="rainy-cafe",source="rainy-cafe-overcast-source.png",master=sourcePixels(path.join(directory,source));
  const regions=[{id:"cafe-interior",polygon:[[90,121],[216,105],[218,258],[90,246]]},{id:"paper-lantern",polygon:box(209,78,31,43)},{id:"wet-paving",polygon:[[0,257],[256,295],[256,448],[0,448]]},{id:"sky",polygon:box(0,0,70,68)}];
  const mapped=materialTransfer(master,sourcePixels(path.join(directory,id+"-night-study.png")),regions);
  const shapes=[[[0,-1],[0,0],[1,0]],[[-2,0],[-1,-1],[1,-1],[2,0],[-1,1],[1,1]],[[-4,0],[-3,-1],[-2,-1],[2,-1],[3,-1],[4,0],[-2,1],[2,1]],[[-5,0],[-4,0],[4,0],[5,0]]];
  const durations=[140,200,240,160],anchor=[128,336];
  const frames=shapes.map((points,i)=>({durationMs:durations[i],light:translated(anchor,points,i===3?"#627e86":"#8eabb1"),dark:translated(anchor,points,i===3?"#456574":"#71919e")}));
  exportScene(id,master,mapped.pixels,[{id:"puddle-splash",label:"One small flattened puddle splash",frames:[...frames,emptyFrame(120)]}],{geometrySource:source,regions,nightPalette:mapped.palette,eventAnchor:anchor,authoredSplashShapes:shapes});
}
function buildArcade() {
  const id="neon-arcade",source=id+"-source.png",master=sourcePixels(path.join(directory,source));
  const regions=[{id:"motd-symbol",polygon:[[62,88],[109,94],[109,138],[62,138]]},{id:"motd-wordmark",polygon:[[118,112],[197,124],[197,154],[118,139]]},{id:"signboard",polygon:[[48,75],[208,100],[208,174],[48,143]]},{id:"interior",polygon:box(57,177,134,159)},{id:"wet-ground",polygon:box(0,336,256,112)},{id:"sky",polygon:box(212,0,44,96)}];
  const mapped=materialTransfer(master,sourcePixels(path.join(directory,id+"-night-study.png")),regions),light=new Uint8Array(master),dark=mapped.pixels;
  // Finished tiny cabinet games; these remain still during the neon event.
  const games=[...translated([105,259],[[0,0],[1,0],[3,0],[4,0],[0,1],[1,1],[3,1],[4,1],[2,8],[3,8]],"#3c9299"),...translated([145,259],[[0,0],[1,0],[3,0],[4,0],[0,1],[1,1],[3,1],[4,1],[2,8],[3,8]],"#a4537d")];
  draw(light,games);draw(dark,games);
  // Four inspected cells on the existing cyan icon edge, never the slash/letter interiors.
  const edge=[[64,110],[64,111],[64,112],[64,113]];
  const dip=(base,amount)=>edge.map(([x,y])=>[x,y,rgbHex(hex(colorAt(base,x,y)).map(c=>c*amount))]);
  const event={id:"motd-neon-dip",label:"One cyan logo segment dips briefly",frames:[{durationMs:180,light:dip(light,.86),dark:dip(dark,.78)},emptyFrame(220)]};
  exportScene(id,light,dark,[event],{geometrySource:source,regions,nightPalette:mapped.palette,restingSprites:games,emissiveEventMask:edge,neonDipMs:180});
}
const countryRegions=[
  {id:"sky",polygon:box(0,0,256,78)},
  {id:"mountains",polygon:[[0,65],[256,54],[256,159],[201,149],[163,133],[114,141],[62,153],[0,148]]},
  {id:"vending-machine",polygon:box(187,190,57,98)},
  {id:"house-and-shop",polygon:[[163,123],[256,118],[256,330],[165,306]]},
  {id:"village-and-rail",polygon:[[50,145],[184,145],[184,293],[50,293]]},
  {id:"left-vegetation",polygon:box(0,96,100,352)},
  {id:"stream",polygon:[[96,283],[159,290],[172,448],[77,448]]},
  {id:"stone-banks",polygon:[[0,273],[96,294],[77,448],[0,448]]}
];
export function buildCountry() {
  const source="japanese-countryside-v3-source.png",master=sourcePixels(path.join(directory,source));
  const machine=countryRegions.find(region=>region.id==="vending-machine").polygon;
  const summerNight=materialTransfer(master,sourcePixels(path.join(directory,"japanese-countryside-v3-night-study.png")),countryRegions);
  const anchor=[128,382],rest=[[-2,0],[-1,0],[0,0],[1,0],[2,0],[3,0],[4,0],[5,0],[-1,2],[0,2],[1,2],[2,2],[3,2],[4,2]];
  const shapes=[
    [[-2,0],[-1,0],[0,0],[1,0],[2,0],[-1,2],[0,2],[1,2],[2,2]],
    [[-3,0],[-2,0],[-1,0],[2,0],[3,0],[0,2],[1,2],[2,2],[4,3],[5,3]],
    [[-2,0],[-1,0],[0,0],[1,0],[2,0],[3,0],[4,0],[5,0],[-1,2],[0,2],[1,2],[2,2],[3,2],[4,2]]
  ];
  const union=[...new Set([...rest,...shapes.flat()].map(point=>point.join(",")))].map(key=>key.split(",").map(Number));
  const copyPolygon=(target,from,polygon)=>{
    const xs=polygon.map(([x])=>x),ys=polygon.map(([,y])=>y);
    for(let y=Math.floor(Math.min(...ys));y<Math.ceil(Math.max(...ys));y++) for(let x=Math.floor(Math.min(...xs));x<Math.ceil(Math.max(...xs));x++) {
      if(pointInside(x+.5,y+.5,polygon)) target.set(from.subarray((y*width+x)*4,(y*width+x+1)*4),(y*width+x)*4);
    }
  };
  const results={};
  for(const season of ["summer","spring","autumn","winter"]) {
    const id="japanese-countryside-"+season+"-v3";
    const transfer=season==="summer"?{pixels:new Uint8Array(master),palette:null}:materialTransfer(master,sourcePixels(path.join(directory,"japanese-countryside-"+season+"-v3-study.png")),countryRegions);
    const light=transfer.pixels, dark=new Uint8Array(master);
    // The generated vending machine is a finished fixed prop, not an authored sprite.
    // Keep its complete source detail stable in every daytime season.
    copyPolygon(light,master,machine);
    // Map seasonal colors onto the canonical night palette at the same coordinates.
    // This retains the one source composition while studies only supply materials.
    for(let i=0;i<rawLength;i+=4) {
      const luminance=values=>.2126*values[0]+.7152*values[1]+.0722*values[2];
      const shade=Math.min(1,(luminance(summerNight.pixels.subarray(i,i+3))+8)/(luminance(master.subarray(i,i+3))+8));
      for(let c=0;c<3;c++) dark[i+c]=Math.max(0,Math.min(255,Math.round(summerNight.pixels[i+c]+(light[i+c]-master[i+c])*shade)));
      dark[i+3]=255;
    }
    // Night seasons share one inspected machine palette and geometry as well.
    copyPolygon(dark,summerNight.pixels,machine);
    const cleanLight=new Uint8Array(light),cleanDark=new Uint8Array(dark);
    draw(light,translated(anchor,rest,"#74b6bc"));draw(dark,translated(anchor,rest,"#5188a0"));
    draw(dark,moon([164,43]));draw(dark,[[190,34,"#5c7b99"]]);
    const frame=(points,index)=>{
      const set=new Set(points.map(point=>point.join(",")));
      const side=(base,colors)=>union.map(([dx,dy])=>[anchor[0]+dx,anchor[1]+dy,set.has([dx,dy].join(","))?colors[index]:colorAt(base,anchor[0]+dx,anchor[1]+dy)]);
      return {durationMs:[260,340,300][index],light:side(cleanLight,["#74b6bc","#5a9ba6","#74b6bc"]),dark:side(cleanDark,["#5188a0","#3d718b","#5188a0"])};
    };
    const event={id:"stream-highlight",label:"One stream highlight separates and reforms",frames:[...shapes.map(frame),emptyFrame(200)]};
    exportScene(id,light,dark,[event,starEvent([190,34])],{geometrySource:source,regions:countryRegions,season,seasonPalette:transfer.palette,nightPalette:summerNight.palette,nightTreatment:"canonical v3 night palette with seasonal source-material differences scaled by local luminance",eventAnchor:anchor,restingHighlight:rest,eventShapes:shapes,machineSourcePolygon:machine,moonAnchor:[164,43],starAnchor:[190,34]});
    const proofDir=path.join(directory,"proofs");
    const temporary=[["rest",light,dark],...event.frames.slice(0,-1).map((frame,index)=>["step-"+(index+1),applyOverrides(light,frame.light),applyOverrides(dark,frame.dark)])];
    for(const [name,day,night] of temporary) { encode(path.join(proofDir,id+"-"+name+"-day.png"),day); encode(path.join(proofDir,id+"-"+name+"-night.png"),night); }
    execFileSync("magick",[...temporary.map(([name])=>path.join(proofDir,id+"-"+name+"-day.png")),"+append",path.join(proofDir,id+"-stream-day.png")]);
    execFileSync("magick",[...temporary.map(([name])=>path.join(proofDir,id+"-"+name+"-night.png")),"+append",path.join(proofDir,id+"-stream-night.png")]);
    execFileSync("magick",[path.join(proofDir,id+"-stream-day.png"),path.join(proofDir,id+"-stream-night.png"),"-append","-scale","200%",path.join(proofDir,id+"-stream-event-frames.png")]);
    results[season]={light,dark,event,regions:countryRegions,machine};
  }
  return results;
}
export function buildCollection() { build(); buildCafe(); buildTerminal(); buildArcadeSign(); buildCountry(); }
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) buildCollection();
