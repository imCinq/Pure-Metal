# Benchmarking

`tools/bench.py` runs one dev client per graphics API, one at a time, and prints the average FPS and the 98th and 99.5th percentile frame times for each. `default` is Metal; the others are `vulkan` and `opengl`.

```
python3 -I tools/bench.py <tag> default opengl
```

## Setup

- Same toolchain as the build (see the README).
- A world named "New World" in `run/`.
- The game window must stay focused and in the world while it runs. Frames only count with no screen open.

## Options

Environment variables:

| Variable | Meaning |
|---|---|
| `BENCH_SECONDS`, `BENCH_WARMUP` | Measure and warm-up length in seconds (default 25 and 10) |
| `BENCH_REPEAT` | Runs per API; the median is printed |
| `BENCH_WALK` | Fly forward during the run |
| `BENCH_COMMAND` | Command to run once on join, for example `gamemode creative` |
| `NO_SODIUM` | Run without Sodium |
| `BENCH_ARGS` | Extra Gradle arguments |

Your own vsync, fullscreen, pause-on-focus-loss and graphics API options in `run/options.txt` are restored afterwards.

## Terrain LOD

Terrain LOD is on by default at render distance 16 and above, with an 80% full-detail radius. With default settings Pure Metal therefore draws less distant terrain than OpenGL does, so the runs are not the same rendering workload. For a like-for-like comparison, turn Terrain LOD off in Pure Metal's settings, or state the setting next to any numbers you publish.

Results also depend heavily on the Mac, the world and the render distance, so compare runs on the same machine only.
