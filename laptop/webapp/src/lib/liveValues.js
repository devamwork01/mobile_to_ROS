// Live values in a panel header. Their slot width only grows (to the widest text seen), so a value
// flipping sign or gaining a digit never changes the header's width - which, beside the filter pane,
// made the header wrap and unwrap several times a second (the whole row flickered).
export const liveText = (v, decimals) => (v != null && Number.isFinite(v) ? v.toFixed(decimals) : "—");

export const slotChars = (prev, texts) => Math.max(prev, ...texts.map((t) => t.length));
