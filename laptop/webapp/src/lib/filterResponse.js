// Magnitude response of a filter config, in dB - the same RBJ biquads as sensorstream/filters.py
// (Butterworth low-pass as cascaded sections + notches), so the curve drawn on the spectrum is
// exactly what the server applies.
const BUTTER_Q = { 2: [0.7071067811865476], 4: [0.541196100146197, 1.3065629648763766] };

function section(kind, hz, q, fs) {
  const w0 = (2 * Math.PI * hz) / fs;
  const c = Math.cos(w0);
  const alpha = Math.sin(w0) / (2 * q);
  const [b0, b1, b2] = kind === "lowpass" ? [(1 - c) / 2, 1 - c, (1 - c) / 2] : [1, -2 * c, 1];
  const a0 = 1 + alpha;
  return [b0 / a0, b1 / a0, b2 / a0, (-2 * c) / a0, (1 - alpha) / a0];
}

export function sections(cfg, fs) {
  const out = [];
  if (cfg?.lowpass) for (const q of BUTTER_Q[cfg.lowpass.order] || []) out.push(section("lowpass", cfg.lowpass.hz, q, fs));
  for (const n of cfg?.notches || []) out.push(section("notch", n.hz, n.q, fs));
  return out;
}

// |H(f)| in dB for each frequency (evaluated on the unit circle, z = e^{jw}).
export function responseDb(cfg, fs, freqs) {
  const secs = sections(cfg, fs);
  return freqs.map((f) => {
    const w = (2 * Math.PI * f) / fs;
    const c1 = Math.cos(w), s1 = -Math.sin(w), c2 = Math.cos(2 * w), s2 = -Math.sin(2 * w);
    let mag2 = 1;
    for (const [b0, b1, b2, a1, a2] of secs) {
      const nr = b0 + b1 * c1 + b2 * c2, ni = b1 * s1 + b2 * s2;
      const dr = 1 + a1 * c1 + a2 * c2, di = a1 * s1 + a2 * s2;
      mag2 *= (nr * nr + ni * ni) / (dr * dr + di * di);
    }
    return 10 * Math.log10(Math.max(mag2, 1e-30));
  });
}

// The response as a curve on the spectrum plot: 0 dB at `top` (the spectrum's peak), floored at
// -60 dB - a stopband hundreds of dB down would stretch the log axis until no ticks fit.
export const RESPONSE_FLOOR_DB = -60;
export function responseCurve(cfg, fs, freqs, top) {
  return responseDb(cfg, fs, freqs).map((db) => top * Math.pow(10, Math.max(db, RESPONSE_FLOOR_DB) / 10));
}
