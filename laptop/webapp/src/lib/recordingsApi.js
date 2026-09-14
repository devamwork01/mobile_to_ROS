// Thin client for the server-side recordings API (ADR-001). The browser never
// downloads raw recordings — it asks the server for metadata and LOD buckets.

export async function listRecordings() {
  const r = await fetch("/api/recordings");
  if (!r.ok) throw new Error(`recordings list: HTTP ${r.status}`);
  return r.json();
}

// Aggregated buckets for one signal. `buckets` should be ~the chart's pixel width so
// the server returns roughly one bucket per pixel (min/max preserved per bucket).
export async function querySignal(id, handle, { start, end, buckets } = {}) {
  const q = new URLSearchParams();
  if (start != null) q.set("start", Math.round(start));
  if (end != null) q.set("end", Math.round(end));
  if (buckets != null) q.set("buckets", Math.round(buckets));
  const r = await fetch(`/api/recordings/${encodeURIComponent(id)}/signals/${handle}?${q}`);
  if (!r.ok) throw new Error(`signal query: HTTP ${r.status}`);
  return r.json();
}
