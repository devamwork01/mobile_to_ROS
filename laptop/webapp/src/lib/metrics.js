// Net packet loss = wire loss the backfill did NOT recover.
//
// The live UDP stream drops sequence numbers under bursts (Wi-Fi, send-channel pressure); `d.lost`
// counts those raw gaps and is cumulative-since-connect, so a single early burst stays in the raw
// figure forever. But the phone resends missed records from its on-phone recording, and `s.backfilled`
// counts what was recovered. What the user actually cares about is data that never arrived AND could
// not be resent — i.e. max(0, lost - backfilled). That converges to the permanent loss at steady state
// and momentarily reflects in-flight-but-not-yet-recovered loss during a burst.
export function netLoss(d = {}, s = {}) {
  const lost = d.lost || 0;
  const backfilled = s.backfilled || 0;
  const received = d.received || 0;
  const netLost = Math.max(0, lost - backfilled);
  const total = received + lost;
  const pct = total ? +(100 * netLost / total).toFixed(3) : 0;
  return { netLost, pct, rawLost: lost, backfilled };
}
