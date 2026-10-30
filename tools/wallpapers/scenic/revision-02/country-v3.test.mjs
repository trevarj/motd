import assert from "node:assert/strict";
import test from "node:test";
import { buildCountry } from "./build-pixels.mjs";

const width=256;
const pixel=(pixels,x,y)=>Array.from(pixels.subarray((y*width+x)*4,(y*width+x+1)*4));

test("country v3 keeps the generated vending machine fixed and restores one authored stream glint",()=>{
  const variants=buildCountry();
  const seasons=["summer","spring","autumn","winter"];
  assert.deepEqual(Object.keys(variants),seasons);
  const summer=variants.summer;
  for(const season of seasons) {
    const variant=variants[season];
    assert.deepEqual(variant.event.frames.map(frame=>frame.durationMs),[260,340,300,200]);
    assert.deepEqual(variant.event.frames.at(-1).light,[]);
    assert.deepEqual(variant.event.frames.at(-1).dark,[]);
    for(const frame of variant.event.frames.slice(0,-1)) {
      const points=new Set(frame.light.map(([x,y])=>`${x},${y}`));
      assert.equal(points.size,frame.light.length,"stream event does not duplicate cells");
      for(const [x,y] of frame.light) assert.ok(x>=0&&x<256&&y>=0&&y<448,"stream cell is in bounds");
      for(const [x,y] of frame.light) assert.ok(!(x>=187&&x<244&&y>=190&&y<288),"stream event never touches vending machine");
    }
    for(let y=190;y<288;y++) for(let x=187;x<244;x++) {
      assert.deepEqual(pixel(variant.light,x,y),pixel(summer.light,x,y),`${season} day machine is stable at ${x},${y}`);
      assert.deepEqual(pixel(variant.dark,x,y),pixel(summer.dark,x,y),`${season} night machine is stable at ${x},${y}`);
    }
  }
});
