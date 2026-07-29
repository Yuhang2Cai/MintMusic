# Performance baseline protocol

Use one fixed Android 14+ physical device as the primary result and record model, build, battery level and thermal state. Emulator numbers are supplementary.

- Warm up 3 times, measure 10 times; archive benchmark JSON and Perfetto traces.
- Seed Room with 10,000 and 30,000 tracks. Scan SAF directories containing 1,000 and 3,000 files.
- Measure cold startup, first-library render, scroll frame timing, unchanged second scan, artwork memory peak, and playback checkpoint writes.
- Run each Toxiproxy profile 20 times and record recovery time, position drift and success rate.
- Targets: unchanged scan 80% faster; checkpoint writes at least 90% lower; slow frames under 8%; weak-network recovery at least 95%; cold-start P90 regression no more than 10%.

Tag the untouched-build results `perf-baseline-v1` before comparing the optimized build. Never compare builds using different devices, datasets or thermal conditions.
