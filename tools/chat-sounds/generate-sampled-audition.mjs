import {execFileSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {join, resolve} from "node:path";
import {synthesizePolished} from "./polished.mjs";
import {loadRecordedFamilies} from "./recorded.mjs";
import {renderSampled, SAMPLED_FAMILIES} from "./sampled.mjs";
import {SAMPLE_RATE} from "./studies.mjs";

const {FLUID_R3_GM: soundfont, VCSL_AUDIO: recordings} = process.env;
if (!soundfont || !recordings) throw new Error("Run this script from nix develop .#sound-authoring");
const directory = mkdtempSync(join(tmpdir(), "motd-sampled-sounds-"));
const output = resolve(import.meta.dirname, "../../build/sound-previews");
const style = readFileSync(join(import.meta.dirname, "gallery/style.css"), "utf8");
const escape = text => text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");

function card(index, category, family, render) {
  const cues = Object.entries(family.cues).map(([role, {label, description}]) => {
    const audio = render(role).toString("base64");
    return `<div class="button-row"><button type="button" aria-label="${escape(family.label)}: ${escape(label)}" data-audio="${audio}">${escape(label)}</button></div><p class="tone-note">${escape(description)}</p>`;
  });
  return `<article class="candidate" id="voice-${family.id}" data-family="${index}" data-voice="${family.id}">
    <p class="pack">${escape(category)}</p>
    <h3>${escape(family.label)}</h3>
    <p class="description">${escape(family.description)}</p>
    ${cues.join("\n")}
  </article>`;
}

try {
  const recorded = loadRecordedFamilies(recordings).map((family, index) =>
    card(index + 1, family.id === "soft-vibes" ? "App default" : "Acoustic app voice", family, cue => family.cues[cue].audio));
  const sampled = SAMPLED_FAMILIES.map((family, index) =>
    card(index + 1, "Sampled instrument", family, cue => renderSampled(family.id, cue, soundfont, directory)));
  const factory = card(0, "Previous app default", {
    id: "soft-glass",
    label: "Soft Glass",
    description: "The original Soft Glass audio still available in sound settings.",
    cues: {
      send: {label: "Send", description: "A5 · 880 Hz"},
      receive: {label: "Receive", description: "D6 · 1,175 Hz"},
    },
  }, cue => readFileSync(resolve(import.meta.dirname, `../../app/src/main/assets/chat-sounds/soft-glass-${cue}-balanced-2.wav`)));
  const reference = card(0, "Synthesized reference", {
    id: "muted-nylon",
    label: "Muted Nylon",
    description: "Generated from oscillators and modeled string decay, without samples.",
    cues: {
      send: {label: "Send", description: "A short, damped pluck."},
      receive: {label: "Receive", description: "A clearer pluck allowed to ring."},
    },
  }, cue => {
    const render = synthesizePolished("muted-nylon", cue);
    const wav = join(directory, "reference.wav");
    execFileSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-y",
      "-f", "f32le", "-ar", String(SAMPLE_RATE), "-ac", "1", "-i", "pipe:0", "-c:a", "pcm_s16le", wav],
      {input: Buffer.from(render.buffer, render.byteOffset, render.byteLength)});
    return readFileSync(wav);
  });
  mkdirSync(output, {recursive: true});
  writeFileSync(join(output, "sampled.html"), `<!doctype html>
<html lang="en">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MOTD · Sampled instrument auditions</title>
<style>${style}
  .collection + .collection { margin-top: 2rem; }
</style>
<main class="shell polished">
  <header class="masthead">
    <div>
      <p class="eyebrow">MOTD / Sound studies <span>04</span></p>
      <h1>Sampled instrument auditions</h1>
      <p class="lede">Compare <a href="#voice-soft-vibes">Soft Vibes</a>, the new chat default, with the other selected recordings and <a href="#voice-soft-glass">Soft Glass</a>, the previous default.</p>
    </div>
  </header>
  <section class="collection" aria-labelledby="audition-title">
    <div class="section-heading"><h2 id="audition-title">Direct recordings</h2><span>Original pitch · no synthesis</span></div>
    <div class="candidate-grid">${recorded.join("\n")}</div>
  </section>
  <section class="collection" aria-labelledby="reference-title">
    <div class="section-heading"><h2 id="reference-title">Reference sounds</h2><span>Factory audio and earlier synth options</span></div>
    <div class="candidate-grid">${factory}\n${sampled.join("\n")}\n${reference}</div>
    <p class="live-state" role="status">Ready</p>
  </section>
  <footer>Send and receive are the same balanced takes shipped in the app. Notification clips are separate auditions; Android notification channels are unchanged.<br>
    Direct recordings: <a href="https://github.com/sgossner/VCSL">Versilian Community Sample Library</a> (CC0). Earlier instrument samples: FluidR3 by Frank Wen (MIT).</footer>
</main>
<audio></audio>
<script>
  const player = document.querySelector('audio');
  const status = document.querySelector('[role="status"]');
  player.addEventListener('ended', () => { status.textContent = 'Ready'; });
  document.querySelectorAll('[data-audio]').forEach(button => {
    button.addEventListener('click', async () => {
      player.src = 'data:audio/wav;base64,' + button.dataset.audio;
      status.textContent = button.closest('article').querySelector('h3').textContent + ' / ' + button.textContent;
      try { await player.play(); }
      catch (error) { if (error.name !== 'AbortError') status.textContent = 'Could not play this clip.'; }
    });
  });
</script>
</html>\n`);
  console.log(join(output, "sampled.html"));
} finally {
  rmSync(directory, {recursive: true, force: true});
}
