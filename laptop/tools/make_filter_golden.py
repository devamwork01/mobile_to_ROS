"""Write golden filter vectors from sensorstream/filters.py for the Android port's parity test.

    python tools/make_filter_golden.py   # from laptop/  -> android/app/src/test/resources/filter_golden.json
"""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from sensorstream.filters import FilterChain  # noqa: E402

FS = 100.0
N = 400
rng = np.random.default_rng(1234)
inputs = {
    "noise": rng.normal(0, 1, N).tolist(),
    "step": ([0.0] * 50 + [9.8] * (N - 50)),
    "sine": np.sin(2 * np.pi * 8.0 * np.arange(N) / FS).tolist(),
}
configs = {
    "lp2": {"lowpass": {"hz": 5.0, "order": 2}, "notches": []},
    "lp4_notch": {"lowpass": {"hz": 20.0, "order": 4}, "notches": [{"hz": 8.0, "q": 10.0}]},
    "notches": {"lowpass": None, "notches": [{"hz": 3.0, "q": 5.0}, {"hz": 12.0, "q": 20.0}]},
}
cases = []
for cname, cfg in configs.items():
    for iname, x in inputs.items():
        y = FilterChain(cfg, FS, 1).process_block(np.array(x)[:, None])[:, 0].tolist()
        cases.append({"config": cfg, "input": iname, "x": x, "y": y})
out = os.path.join(os.path.dirname(__file__), "..", "..", "android", "app", "src", "test", "resources", "filter_golden.json")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "w", encoding="utf-8") as fh:
    json.dump({"fs": FS, "cases": cases}, fh)
print("wrote", os.path.normpath(out), len(cases), "cases")
