// Behaviour tests for the extension relay page script. Run with `node --test`.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { createRelay } = require(path.join(__dirname, "../../main/assets/extension-host/relay.js"));

function fakeEnv() {
  const listeners = {};
  const win = {
    addEventListener(type, fn) { listeners[type] = fn; },
  };
  const appended = [];
  const doc = {
    body: {
      appendChild(el) { el.parentNode = doc.body; appended.push(el); },
      removeChild(el) { el.parentNode = null; },
    },
    createElement() {
      const handlers = {};
      const posted = [];
      return {
        attrs: {},
        style: {},
        parentNode: null,
        posted,
        contentWindow: { postMessage(data, origin) { posted.push({ data, origin }); } },
        setAttribute(k, v) { this.attrs[k] = v; },
        addEventListener(type, fn) { handlers[type] = fn; },
        fire(type) { handlers[type](); },
      };
    },
  };
  const sent = [];
  const host = { postMessage(s) { sent.push(JSON.parse(s)); }, onmessage: null };
  const relay = createRelay(win, doc, host);
  return { relay, host, sent, appended, listeners };
}
test("the relay announces itself so the app can reply", () => {
  const env = fakeEnv();
  assert.deepEqual(env.sent[0], { kind: "ready" });
});


test("mount creates a sandboxed srcdoc frame", () => {
  const env = fakeEnv();
  env.host.onmessage({ data: JSON.stringify({ kind: "mount", html: "<p>x</p>" }) });
  const frame = env.relay.frame();
  assert.equal(frame.attrs.sandbox, "allow-scripts");
  assert.equal(frame.srcdoc, "<p>x</p>");
});

test("messages from the frame window are forwarded to the app", () => {
  const env = fakeEnv();
  env.host.onmessage({ data: JSON.stringify({ kind: "mount", html: "" }) });
  const frame = env.relay.frame();
  const envelope = { id: "1", type: "request", method: "ping", capability: "", args: null, version: 1 };
  env.listeners.message({ source: frame.contentWindow, data: envelope });
  assert.deepEqual(env.sent.at(-1), { kind: "envelope", envelope });
});

test("messages from any other source are rejected, never forwarded", () => {
  const env = fakeEnv();
  env.host.onmessage({ data: JSON.stringify({ kind: "mount", html: "" }) });
  const other = { postMessage() {} };
  env.listeners.message({ source: other, data: { id: "1", type: "request", method: "ping", version: 1 } });
  env.listeners.message({ source: null, data: { id: "2" } });
  assert.equal(env.sent.filter((m) => m.kind === "envelope").length, 0);
  assert.deepEqual(env.sent.at(-1), { kind: "rejected", reason: "foreign_source" });
});

test("a message after unmount is rejected even from the old frame window", () => {
  const env = fakeEnv();
  env.host.onmessage({ data: JSON.stringify({ kind: "mount", html: "" }) });
  const old = env.relay.frame();
  env.host.onmessage({ data: JSON.stringify({ kind: "unmount" }) });
  env.listeners.message({ source: old.contentWindow, data: { id: "1" } });
  assert.equal(env.sent.filter((m) => m.kind === "envelope").length, 0);
});

test("host envelopes are held until the frame loads, then delivered in order", () => {
  const env = fakeEnv();
  env.host.onmessage({ data: JSON.stringify({ kind: "mount", html: "" }) });
  const frame = env.relay.frame();
  env.host.onmessage({ data: JSON.stringify({ kind: "envelope", envelope: { id: "a" } }) });
  env.host.onmessage({ data: JSON.stringify({ kind: "envelope", envelope: { id: "b" } }) });
  assert.equal(frame.posted.length, 0);
  frame.fire("load");
  assert.deepEqual(frame.posted.map((p) => p.data.id), ["a", "b"]);
  assert.deepEqual(env.sent.at(-1), { kind: "loaded" });
});
