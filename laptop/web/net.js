// WebSocket transport for the dashboard. Auto-reconnects; dispatches decoded
// messages to handlers registered via SensorNet.on(kind, fn).
// The WS port defaults to 8081 but can be overridden with ?ws=PORT.
(function () {
  const params = new URLSearchParams(location.search);
  const wsPort = params.get("ws") || "8081";
  const host = location.hostname || "localhost";
  const url = `ws://${host}:${wsPort}`;

  const handlers = { data: [], stats: [], open: [], close: [] };
  let ws = null;
  let backoff = 500;

  function emit(kind, payload) {
    (handlers[kind] || []).forEach((fn) => {
      try { fn(payload); } catch (e) { console.error(e); }
    });
  }

  function connect() {
    ws = new WebSocket(url);
    ws.onopen = () => { backoff = 500; emit("open"); };
    ws.onclose = () => { emit("close"); setTimeout(connect, backoff); backoff = Math.min(backoff * 2, 5000); };
    ws.onerror = () => { try { ws.close(); } catch (e) {} };
    ws.onmessage = (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      emit(msg.kind, msg);
    };
  }

  window.SensorNet = {
    on(kind, fn) { (handlers[kind] = handlers[kind] || []).push(fn); },
    send(obj) { try { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); } catch (e) {} },
    url,
  };
  connect();
})();
