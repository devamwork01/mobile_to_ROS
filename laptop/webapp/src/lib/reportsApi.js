// Test-run / recording reports (server-computed on full-rate data).
export async function listReports() {
  const r = await fetch("/api/reports");
  if (!r.ok) throw new Error(`reports: HTTP ${r.status}`);
  return (await r.json()).items;
}

export async function getReport(id) {
  const r = await fetch(`/api/reports/${encodeURIComponent(id)}`);
  if (!r.ok) throw new Error(`report: HTTP ${r.status}`);
  return r.json();
}
