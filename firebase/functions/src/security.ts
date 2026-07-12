import {createHash, randomBytes, timingSafeEqual} from "node:crypto";

export const MAX_PUSH_BYTES = 3000;
export const MAX_PUSHES_PER_MINUTE = 120;
export const MAX_REGISTRATIONS_PER_HOUR = 20;

export function opaqueSecret(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}

export function secretHash(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

export function secretMatches(value: string, expectedHex: string): boolean {
  const actual = Buffer.from(secretHash(value), "hex");
  const expected = Buffer.from(expectedHex, "hex");
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function validToken(value: unknown): value is string {
  return typeof value === "string" && value.length >= 32 && value.length <= 4096;
}

export function validInstance(value: unknown): value is string {
  return typeof value === "string" && /^[1-9][0-9]{0,18}$/.test(value);
}
