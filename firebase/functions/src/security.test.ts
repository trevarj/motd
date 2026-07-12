import assert from "node:assert/strict";
import test from "node:test";
import {opaqueSecret, secretHash, secretMatches, validInstance, validToken} from "./security.js";

test("management secrets are opaque and verifiable", () => {
  const secret = opaqueSecret();
  assert.equal(secret.length, 43);
  assert.equal(secretMatches(secret, secretHash(secret)), true);
  assert.equal(secretMatches(`${secret}x`, secretHash(secret)), false);
});

test("subscription inputs are bounded", () => {
  assert.equal(validInstance("42"), true);
  assert.equal(validInstance("0"), false);
  assert.equal(validInstance("../42"), false);
  assert.equal(validToken("x".repeat(32)), true);
  assert.equal(validToken("short"), false);
});
