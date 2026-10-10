/*
 * Extension relay. Runs in the host WebView page and owns exactly one sandboxed
 * extension frame. Messages from the app arrive on the injected `adosHost` channel;
 * messages from the frame are accepted only when they come from that frame's window.
 *
 * App -> relay:   {kind:"mount", html} | {kind:"envelope", envelope} | {kind:"unmount"}
 * Relay -> app:   {kind:"ready"} | {kind:"loaded"} | {kind:"envelope", envelope} | {kind:"rejected", reason}
 */
(function (root) {
  "use strict";

  function createRelay(win, doc, host) {
    var frame = null;
    var loaded = false;
    var pending = [];

    function send(msg) {
      host.postMessage(JSON.stringify(msg));
    }

    function postToFrame(envelope) {
      if (!frame || !frame.contentWindow) return;
      if (!loaded) {
        pending.push(envelope);
        return;
      }
      // The frame has an opaque origin, so "*" is the only usable target.
      frame.contentWindow.postMessage(envelope, "*");
    }

    function unmount() {
      if (frame && frame.parentNode) frame.parentNode.removeChild(frame);
      frame = null;
      loaded = false;
      pending = [];
    }

    function mount(html) {
      unmount();
      var f = doc.createElement("iframe");
      f.setAttribute("sandbox", "allow-scripts");
      f.setAttribute("referrerpolicy", "no-referrer");
      f.setAttribute("allow", "");
      f.setAttribute("title", "extension");
      f.style.border = "0";
      f.style.width = "100%";
      f.style.height = "100%";
      f.style.background = "transparent";
      f.addEventListener("load", function () {
        if (frame !== f) return;
        loaded = true;
        var queued = pending;
        pending = [];
        for (var i = 0; i < queued.length; i++) postToFrame(queued[i]);
        send({ kind: "loaded" });
      });
      f.srcdoc = html;
      frame = f;
      doc.body.appendChild(f);
    }

    /** Message from any window posted to this page. */
    function onWindowMessage(ev) {
      if (!frame || ev.source !== frame.contentWindow) {
        send({ kind: "rejected", reason: "foreign_source" });
        return;
      }
      var data = ev.data;
      if (!data || typeof data !== "object") {
        send({ kind: "rejected", reason: "not_object" });
        return;
      }
      send({ kind: "envelope", envelope: data });
    }

    /** Message from the app on the host channel. */
    function onHostMessage(ev) {
      var msg;
      try {
        msg = typeof ev.data === "string" ? JSON.parse(ev.data) : ev.data;
      } catch (e) {
        return;
      }
      if (!msg || typeof msg !== "object") return;
      if (msg.kind === "mount" && typeof msg.html === "string") mount(msg.html);
      else if (msg.kind === "envelope" && msg.envelope) postToFrame(msg.envelope);
      else if (msg.kind === "unmount") unmount();
    }

    win.addEventListener("message", onWindowMessage);
    host.onmessage = onHostMessage;
    send({ kind: "ready" });

    return {
      onWindowMessage: onWindowMessage,
      onHostMessage: onHostMessage,
      frame: function () { return frame; },
    };
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { createRelay: createRelay };
  } else if (root && root.adosHost) {
    createRelay(root, root.document, root.adosHost);
  }
})(typeof window !== "undefined" ? window : undefined);
