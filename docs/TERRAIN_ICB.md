# Terrain indirect-command experiment

Status (2026-10-25): offscreen GPU-encoded ICB prototype passes its pixel checks,
but does not establish a performance benefit. No production integration or game
launch was made. Keep the existing terrain submission path.

## Scope and source findings

Sodium already reaches `MetalRenderPass.multiDrawIndexed` as a batch, with one
native call per batch. `pm_pass_multi_draw_indexed_separate` then encodes the
individual Metal draws on the CPU. The existing indirect-argument path also loops
over draws; an actual `MTLIndirectCommandBuffer` is needed to remove that loop.

`tools/bench_terrain_submission.m` now compares the native loop, freshly CPU-encoded
ICBs, reused ICBs (an optimistic bound), and freshly GPU-encoded ICBs. The GPU path
copies prepared draw records into shared memory and dispatches a compute kernel
that resets and writes ICB slots before rendering. It does not change visibility
or construct chunk meshes. Index offsets, distinct base vertices, and empty draw
records affect the reference image.

## Metal 3 result

Apple M2, Metal 3 explicitly selected with `pm_device_create(0, 0)`. Three unvalidated
timing runs, 240 interleaved samples per mode in each run, four warmup rounds;
mode order reverses each round. Each sample has 1,000 records, 857 nonempty.
Times below are medians across the three per-run means, in milliseconds.

| Path | CPU encoding | GPU command-buffer duration |
| --- | ---: | ---: |
| Current native loop | 0.039 | 0.081 |
| Fresh CPU ICB | 0.072 | 0.150 |
| Reused ICB, optimistic bound | 0.007 | 0.139 |
| Fresh GPU ICB | 0.011 | 0.153 |

The GPU prototype saves about 0.028 ms of CPU encoding but adds about 0.072 ms
of GPU duration in this synthetic workload. CPU and GPU execution overlap in a
real game, so these differences must not be added to predict frame time or FPS.
GPU timing varied between runs; the prototype's GPU time exceeded the native
loop in all three runs. This does not prove that every ICB design is slower.

All four paths produced byte-identical, nonempty output. A separate run with
Metal API validation enabled also passed, with no validation errors reported;
its timings are excluded. This is a tiny offscreen shader, not Minecraft terrain,
an in-game visual check, or a general proof of buffer-binding/lifetime correctness.

Existing Metal 3 + Sodium logs in
`build/stutter-041-20261025/off-game.log` report about 0.09 ms/frame of terrain
encoding for 1,573 terrain draws in 58 batches. Those are earlier captures, not a
fresh baseline for this experiment. They suggest a small CPU budget to recover.

## Integration gate and next step

The backend starts a render encoder in `MetalCommandEncoder.createRenderPass`;
terrain draw records arrive later. GPU command generation would require preparing
batches before that encoder opens, or splitting the pass. Per-region splits risk
extra attachment stores/loads. The current prototype is favorable in this respect:
one compute dispatch before one render pass, without splitting an existing pass.

The performance gate is not met: there is no demonstrated total-frame improvement
to justify this renderer restructuring. No experimental game flag, Metal 4 path,
or vanilla integration was added. No in-game before/after is available because
there is no integrated after variant. Runtime options/configuration were untouched.

Reconsider only if a fresh game profile identifies substantial CPU draw-encoding
cost and GPU headroom. First solve pre-pass batch preparation and test realistic
region/binding counts; then validate an opaque-only Sodium path with frames in
flight, buffer churn, and whole-batch fallback before a matched in-game A/B.

## Reproduce and artifacts

```
clang -fobjc-arc -O2 -Wall -Werror -framework Metal -framework MetalFX \
  -framework QuartzCore -framework Foundation -framework AppKit \
  tools/bench_terrain_submission.m -o /tmp/pm-terrain-bench
/tmp/pm-terrain-bench
MTL_DEBUG_LAYER=1 MTL_DEBUG_LAYER_ERROR_MODE=nslog /tmp/pm-terrain-bench
```

GPU access is required. Timing and validation must be separate.
Raw logs are under `build/terrain-icb/gpu-icb-metal3-repeat{1,2,3}.log` and
`build/terrain-icb/gpu-icb-metal3-validation.log`. Earlier logs in that folder used
the initial CPU-reset variant and are superseded by the repeat logs. The original
dirty production files were hash-checked unchanged after the experiment.
