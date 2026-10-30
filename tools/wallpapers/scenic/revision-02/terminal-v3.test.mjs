import assert from "node:assert/strict";
import test from "node:test";
import { buildTerminal } from "./build-pixels.mjs";

test("terminal v3 uses fixed miniature rows and a rare status replacement that restores rest", () => {
  const built=buildTerminal();
  const {event}=built;
  assert.equal(event.id,"terminal-status");
  assert.deepEqual(event.frames.map(frame=>frame.durationMs),[180,720,160,140]);
  assert.deepEqual(event.frames.at(-1).light,[]);
  assert.deepEqual(event.frames.at(-1).dark,[]);
  assert.ok(built.resting.length>50,"resting terminal screen needs complete miniature content");
  for(const frame of event.frames.slice(0,-1)) for(const mode of ["light","dark"]) {
    const positions=frame[mode].map(([x,y])=>x+","+y);
    assert.equal(new Set(positions).size,positions.length);
    assert.ok(frame[mode].every(([x,y,color])=>x>=0&&x<256&&y>=0&&y<448&&/^#[0-9a-f]{6}$/i.test(color)));
  }
  assert.deepEqual(built.regions.map(region=>region.id),["window-sky","city","crt-glass","desk-lamp-and-wood"]);
});
