package com.altnautica.gcs.core.extensions.bridge

/**
 * The document an extension's iframe loads. A policy declared inside the frame can only
 * tighten the parent's, so the frame pins itself to no network (`connect-src 'none'`):
 * every capability an extension holds is a bridge method the host performs. A guard script
 * runs before the bundle, deletes the WebRTC interfaces (CSP does not govern them) and
 * removes nested frames, which would hand the extension a fresh global.
 */
object FrameShell {

    val CSP: String = listOf(
        "default-src 'none'",
        "script-src 'unsafe-inline'",
        "style-src 'unsafe-inline'",
        "img-src data: blob:",
        "connect-src 'none'",
        "webrtc 'block'",
        "form-action 'none'",
        "base-uri 'none'",
    ).joinToString("; ")

    val CSP_META: String = "<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">"

    val GUARD_SCRIPT: String = """(() => {
"use strict";
const w = window;
for (const n of Object.getOwnPropertyNames(w)) {
  if (/^(webkit)?RTC/.test(n)) { try { delete w[n]; } catch (e) {} }
}
const FRAMES = "iframe,frame,object,embed,fencedframe,portal";
const roots = [document];
const observer = new MutationObserver(() => purge());
const purge = () => {
  if (!(w.length > 0)) return;
  for (const r of roots) for (const f of r.querySelectorAll(FRAMES)) f.remove();
  if (w.length > 0) {
    document.documentElement.remove();
    throw new Error("nested frames are not permitted in an extension frame");
  }
};
const attach = Element.prototype.attachShadow;
Element.prototype.attachShadow = function (init) {
  const root = attach.call(this, init);
  roots.push(root);
  observer.observe(root, { childList: true, subtree: true });
  return root;
};
const wrap = (proto, name) => {
  const d = Object.getOwnPropertyDescriptor(proto, name);
  if (!d) return;
  if (typeof d.value === "function") {
    const f = d.value;
    d.value = function (...a) { const r = f.apply(this, a); purge(); return r; };
  } else if (d.set) {
    const s = d.set;
    d.set = function (v) { s.call(this, v); purge(); };
  } else return;
  Object.defineProperty(proto, name, d);
};
const SINKS = [
  [Node.prototype, ["appendChild", "insertBefore", "replaceChild"]],
  [Element.prototype, ["append", "prepend", "before", "after", "replaceWith", "replaceChildren", "moveBefore", "insertAdjacentElement", "insertAdjacentHTML", "setHTMLUnsafe", "innerHTML", "outerHTML"]],
  [CharacterData.prototype, ["before", "after", "replaceWith"]],
  [DocumentFragment.prototype, ["append", "prepend", "replaceChildren", "moveBefore"]],
  [ShadowRoot.prototype, ["innerHTML", "setHTMLUnsafe"]],
  [Document.prototype, ["append", "prepend", "replaceChildren", "moveBefore", "write", "writeln", "execCommand", "body"]],
  [Range.prototype, ["insertNode", "surroundContents"]],
];
for (const [proto, names] of SINKS) for (const n of names) wrap(proto, n);
observer.observe(document, { childList: true, subtree: true });
})();"""

    val HEAD: String = "$CSP_META<script>$GUARD_SCRIPT</script>"

    private val SCRIPT_CLOSE = Regex("</(script)", RegexOption.IGNORE_CASE)

    /** Wrap an ESM bundle in the frame document; `</script` inside the bundle is escaped. */
    fun build(bundleJs: String): String {
        val safe = SCRIPT_CLOSE.replace(bundleJs) { "<\\/" + it.groupValues[1] }
        return buildString {
            append("<!doctype html>")
            append("<html lang=\"en\">")
            append("<head>")
            append(HEAD)
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"color-scheme\" content=\"dark light\">")
            append("<style>html,body{margin:0;padding:0;height:100%;background:transparent;overflow:hidden}</style>")
            append("</head>")
            append("<body>")
            append("<script type=\"module\">\n").append(safe).append("\n</script>")
            append("</body>")
            append("</html>")
        }
    }
}
