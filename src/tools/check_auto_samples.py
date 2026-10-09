#!/usr/bin/env python3
"""Quick check of the two normalized frames produced by the automatic-crop geometry."""
from pathlib import Path
import cv2
import bench_templates as bench

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    "auto_geometry_136_90_61.png": (136, 90, 61),
    "auto_geometry_111_75_56.png": (111, 75, 56),
}

ok = True
for name, expected in CASES.items():
    image = cv2.imread(str(ROOT / "samples" / name))
    got = bench.read_screen(image)
    print(f"{name}: got={got}, expected={expected}")
    ok &= got == expected
raise SystemExit(0 if ok else 1)
