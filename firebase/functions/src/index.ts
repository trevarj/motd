import {initializeApp} from "firebase-admin/app";
import {FieldValue, Timestamp, getFirestore} from "firebase-admin/firestore";
import {getMessaging} from "firebase-admin/messaging";
import {setGlobalOptions} from "firebase-functions/v2";
import {onRequest, type Request} from "firebase-functions/v2/https";
import type {Response} from "express";
import {
  MAX_PUSH_BYTES,
  MAX_PUSHES_PER_MINUTE,
  MAX_REGISTRATIONS_PER_HOUR,
  opaqueSecret,
  secretHash,
  secretMatches,
  validInstance,
  validToken,
} from "./security.js";

initializeApp();
setGlobalOptions({region: "us-central1", maxInstances: 20});

const db = getFirestore();
const subscriptions = db.collection("subscriptions");

type Subscription = {
  token: string;
  instance: string;
  managementHash: string;
  expiresAt: Timestamp;
  rateBucket?: number;
  rateCount?: number;
};

function json(res: Response, status: number, value: unknown): void {
  res.status(status).set("Cache-Control", "no-store").json(value);
}

function bearer(req: Request): string | null {
  const value = req.header("authorization");
  return value?.startsWith("Bearer ") ? value.slice(7) : null;
}

async function authorize(id: string, secret: string | null): Promise<Subscription | null> {
  if (!secret) return null;
  const snapshot = await subscriptions.doc(id).get();
  if (!snapshot.exists) return null;
  const data = snapshot.data() as Subscription;
  return secretMatches(secret, data.managementHash) ? data : null;
}

async function consumeRegistrationQuota(ip: string): Promise<boolean> {
  const hour = Math.floor(Date.now() / 3_600_000);
  const id = secretHash(ip || "unknown");
  return db.runTransaction(async transaction => {
    const ref = db.collection("registrationQuotas").doc(id);
    const snapshot = await transaction.get(ref);
    const data = snapshot.data() as {bucket?: number; count?: number} | undefined;
    const count = data?.bucket === hour ? (data.count ?? 0) + 1 : 1;
    if (count > MAX_REGISTRATIONS_PER_HOUR) return false;
    transaction.set(ref, {
      bucket: hour,
      count,
      expiresAt: Timestamp.fromMillis(Date.now() + 2 * 3_600_000),
    });
    return true;
  });
}

async function consumePushQuota(id: string): Promise<Subscription | null> {
  const minute = Math.floor(Date.now() / 60_000);
  return db.runTransaction(async transaction => {
    const ref = subscriptions.doc(id);
    const snapshot = await transaction.get(ref);
    if (!snapshot.exists) return null;
    const data = snapshot.data() as Subscription;
    if (data.expiresAt.toMillis() <= Date.now()) return null;
    const count = data.rateBucket === minute ? (data.rateCount ?? 0) + 1 : 1;
    if (count > MAX_PUSHES_PER_MINUTE) return null;
    transaction.update(ref, {rateBucket: minute, rateCount: count, lastPushAt: FieldValue.serverTimestamp()});
    return data;
  });
}

export const relay = onRequest({cors: false}, async (req, res) => {
  try {
    const parts = req.path.split("/").filter(Boolean);
    if (req.method === "POST" && req.path === "/v1/subscriptions") {
      if (!await consumeRegistrationQuota(req.ip ?? "unknown")) return json(res, 429, {error: "quota exceeded"});
      const {token, instance} = req.body ?? {};
      if (!validToken(token) || !validInstance(instance)) return json(res, 400, {error: "invalid subscription"});
      const publicBaseUrl = process.env.PUBLIC_BASE_URL?.replace(/\/$/, "");
      if (!publicBaseUrl) return json(res, 503, {error: "relay is not configured"});
      const subscriptionId = opaqueSecret();
      const managementSecret = opaqueSecret();
      await subscriptions.doc(subscriptionId).create({
        token,
        instance,
        managementHash: secretHash(managementSecret),
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
        expiresAt: Timestamp.fromMillis(Date.now() + 90 * 24 * 3_600_000),
      });
      return json(res, 201, {
        subscriptionId,
        managementSecret,
        endpoint: `${publicBaseUrl}/push/${subscriptionId}`,
      });
    }

    if (parts[0] === "v1" && parts[1] === "subscriptions" && parts.length === 3) {
      const id = parts[2]!;
      const subscription = await authorize(id, bearer(req));
      if (!subscription) return json(res, 404, {error: "not found"});
      if (req.method === "PUT") {
        const token = req.body?.token;
        if (!validToken(token)) return json(res, 400, {error: "invalid token"});
        await subscriptions.doc(id).update({
          token,
          updatedAt: FieldValue.serverTimestamp(),
          expiresAt: Timestamp.fromMillis(Date.now() + 90 * 24 * 3_600_000),
        });
        return json(res, 200, {ok: true});
      }
      if (req.method === "DELETE") {
        await subscriptions.doc(id).delete();
        res.status(204).send();
        return;
      }
    }

    if (req.method === "POST" && parts[0] === "push" && parts.length === 2) {
      const body = req.rawBody;
      if (!body || body.length === 0 || body.length > MAX_PUSH_BYTES) {
        return json(res, 413, {error: "invalid payload size"});
      }
      const id = parts[1]!;
      const subscription = await consumePushQuota(id);
      if (!subscription) return json(res, 404, {error: "not found"});
      try {
        await getMessaging().send({
          token: subscription.token,
          data: {instance: subscription.instance, payload: body.toString("base64")},
          android: {priority: "high", ttl: 24 * 60 * 60 * 1000},
        });
      } catch (error) {
        const code = (error as {code?: string}).code ?? "";
        if (code.endsWith("registration-token-not-registered") || code.endsWith("invalid-registration-token")) {
          await subscriptions.doc(id).delete();
        }
        throw error;
      }
      res.status(204).send();
      return;
    }

    return json(res, 404, {error: "not found"});
  } catch (error) {
    console.error(error);
    return json(res, 500, {error: "internal error"});
  }
});
