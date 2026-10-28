# Fluid callbacks and LOD work reduction

Work for 0.4.1, 2026-10-26. Uncommitted; no publication or in-game performance claim.

## Current implementation: repaired scope-binding cache

The fluid optimization remains enabled for supported versions. The former
ThreadLocal invocation pool described in the historical sections below has been
replaced. No temporary Runnable, output callback, world, block, or position is
stored in a cache.

The new implementation preserves both original Runnable factories, their typed
captures, their implementation methods, and the original `Carrier.run` calls.
It reuses only immutable ScopedValue.Carrier bindings:

- Sodium 0.9.2 and 0.9.3-beta.1 default-render contexts own their cached binding.
  The binding contains the scope key and that existing context instance. Its
  lifetime follows the owner, with no global or ThreadLocal collection retaining it.
- The vanilla-default scope binds a fixed key to null. A single carrier is created
  after that key is initialized. Every call still creates its own active scope
  snapshot, so nested calls and current parent scopes behave as before.
- An unknown renderer uses the original binding-creation path. An unexpected other
  scope key does not overwrite the normal owner binding.

The optional Sodium owner mixin checks the exact target interface, name collisions
and supported version. The Fabric transform additionally checks both scope-creation
sites, the untouched Runnable factories/consumers, and the exact two-key initializer.
Changed shapes, unknown versions and the existing opt-out retain the original path.
Fabric API remains optional; no new runtime dependency was added.

The allocation regression fixture now calls the real Knot-transformed Fabric API
with a real Sodium default context, a simplified handler, and a caller-created
output callback. It does not force callbacks to escape. Both supported and fallback
callers are checked, with optimization enabled and disabled.

| Caller fixture, normal Java optimizer | Original | Repaired carrier cache |
| --- | ---: | ---: |
| Supported Sodium owner, bytes/call | 56 | 24 |
| Unsupported renderer, bytes/call | 56 | 56 |

The supported fixture saves 32 bytes/call relative to the original path. This fixes
the demonstrated escape-analysis regression; it is not a whole-game allocation
estimate or an FPS claim. Nanosecond timings fluctuate across these small fixtures
and do not establish an in-game CPU speedup. The older game comparison below tested
the broken pool and must not be attributed to this replacement.

Final validation passed on stable Sodium and the beta: unchanged factory bytecode,
real scoped handler calls, renderer identity, other-key fallback, nested exceptions,
shared immutable carriers with separate thread parent scopes, successful recursive
vanilla-default calls, optional API absence, API present without Sodium, opt-out,
shape/version rejection and the existing terrain suite. The offline build passed.
Logs: `build/fluid-carrier-fix/stable-checks.log`, `beta-checks.log`, and `build.log`.
Regression fixture: `tools/TestFluidCarrierCost.java`. A subsequent single live
capture verified the repaired cache; visual equivalence remains untested.

### Repaired live capture

`build/repaired-fluid-lod-test-20261026-184225/`: one successful client launch,
same Metal 3/Sodium beta/world/RD16/LOD50% scenario, 20-second warmup followed by
15 seconds stationary and 45 seconds flying. Test-only camera locking preserved
the original route and camera exactly. AFK mode was temporarily set to minimized;
all 59 recorded limiter snapshots reported NONE. No intervals were excluded.

Results: 293.71 average FPS, median 3.01 ms, p99.5 8.07 ms, maximum 74.53 ms;
one interval exceeded 20 ms, attributed by the native trace to late GPU start.
The earlier baseline was 292.10 FPS and p99.5 7.94 ms. The large slowdown did not
reproduce; this historical comparison is not a new controlled A/B or proof of gain.
Both worker contexts logged actual carrier-cache creation. No Carrier or Sodium
fluid-output callback allocations were sampled during recording; total sampled
allocation estimate was 12789.881 MiB. Stationary LOD entry visits were 23508.
Options/config/world were hash-verified restored, source hashes and the existing
tracked diff were unchanged, normal compiled classes were restored, and no test
client remained. One earlier preparation failure launched no client.

## Historical: original invocation-pool implementation

The sections below retain the superseded implementation, measurements and diagnosis.

`FabricFluidCallbacks` rewrites only the two Runnable factories immediately
consumed by `ScopedValue.Carrier.run` in Fabric's `FluidRenderingImpl`. The original
lambda implementation remains the render operation. A generated stateless action
forwards its exact typed arguments; `ScopedFluidCallbacks` supplies temporary
Runnable storage.

Each thread retains at most four invocation slots. Nested calls use distinct
slots; deeper calls allocate temporary slots. Every argument and the action are
cleared in `finally`, including exception paths. The original Carrier and scoped
values remain intact. No renderer, world, output, builder, or block state is cached
between invocations. The pool's empty slots live with their worker thread.

The optional marker mixin is gated to Fabric fluid API 6.0.6, with mandatory
factory/consumer shape checks before and after mixin application. Changed shapes,
unknown versions and an absent fluid API keep their original implementation.
`-Dpuremetal.disableFluidCallbacks=true` disables the integration. Fabric API is
not added as a required dependency.

This targets the Fabric Runnable allocation site from the earlier walking JFR.
It does **not** remove Sodium's separate fluid output callback or the ScopedValue
Carrier/snapshot allocations. Those callbacks carry different ownership and
capture data; reusing them requires separate lifetime proof.

## LOD work

Near sections now validate candidate models before being marked as requiring a
different coarse mesh. Previously a solid block with an ineligible model could
produce a FULL candidate near the camera but a NONE mesh in the outer band,
causing an unnecessary transition rebuild. Exact vanilla SingleVariant models
are rejected once per model set, including during state eligibility checks.
Position-dependent models retain per-position validation; model-set changes
invalidate both caches.

Sodium's transition scheduler skips the periodic traversal when neither the camera
policy nor completed meshes changed. It retains the existing 20-frame phase,
immediate checks on camera/radius changes, 32-request budget and next-frame
continuation after budget exhaustion. Newly completed relevant meshes re-arm the
periodic check. Vanilla's transition scheduling is unchanged.

Vines remain detailed. They are thin, alpha-cutout geometry, not solid cubes.
Visible shapes and coverage are preserved. A future vegetation
merger must preserve face planes, sidedness, UV repetition, lighting and tint;
removing the solid/model eligibility guards would not meet that requirement.

## Verification and limits

Historical verification for the original invocation pool and the LOD changes follows.
The pool has been replaced; its test descriptions and allocation numbers below do
not validate the current carrier cache. See the current implementation section for
the replacement's tests and results.

The earlier `tools/test_terrain_lod.py` suite covered terrain geometry/lighting, rejected near models,
vine exclusion, transition scheduling, vanilla/Sodium mixin loading, optional
fluid API absence, enabled/disabled callback integration and existing regressions.
The earlier `TestFluidCallbacks` executed the Knot-transformed Fabric class, including
nested scopes, argument identity and exception propagation. It also checked bounded
nesting, worker isolation, cleared references, changed-shape rejection and duplicate
application.

The original pool's warmed microcheck reported 4,000,000 fewer allocated bytes
over 100,000 scoped calls:
6,400,000 original versus 2,400,000 with reused invocation storage. Remaining scope
allocations are intentionally retained. This is a headless allocation fixture,
not a measurement of whole-game allocation, FPS, frame-time tails or pixel identity.

Stable and beta suite logs: `build/fluid-lod-checks.log` and
`build/fluid-lod-beta-checks.log`. Release build: `build/fluid-lod-build.log`.
No game launch, runtime settings change, or visual capture was performed during
the initial implementation checks. The subsequent live comparison is recorded below;
manual visual equivalence testing remains outstanding.

## Combined live comparison, 2026-10-26

Historical comparison: the changed variant used the superseded invocation pool,
not the repaired carrier cache. Its fluid results do not measure the current
implementation.

Two completed captures compare all changes in this task together. Earlier dirty
work, including the block callback cache and corner-position changes, remains in
both variants. Metal 3, Sodium 0.9.3-beta.1, 3420x2148, RD16, simulation distance
16, render scale 100%, LOD enabled at 50%, vsync off, and the saved maxFps 260
setting were identical. Each capture had a 20-second stationary warmup, 15 seconds
stationary recording, then 45 seconds of sprint-flight from the same restored
world and initial position/camera. There was a 30-second cooldown between captures.

Both variants used JFR/profile/frame tracing and identical test-only LOD counters.
Complete copied source trees supplied the baseline/current mesher and scheduler;
production source files were never swapped. The fluid opt-out was true only in
the baseline. Compiled classes were checked before launch and after exit to
confirm the variant, scenario, and instrumentation. Test-only classes were then
replaced by a normal production compilation.

The player was inactive during the second capture. JFR shows a 30 FPS plateau
at recording seconds 34 and 35; focus snapshots remained true, so this was not a
recorded focus-loss interval. Seconds [33,37), including transitions, were excluded
from **both** recordings for the table below. Raw results are retained separately.
The remaining comparison contains 56 seconds per capture.

| Metric, inactivity interval excluded | Without task changes | With task changes |
| --- | ---: | ---: |
| Average rendered FPS | 298.05 | 186.60 |
| Median frame, ms | 2.869 | 4.239 |
| p99.5 frame, ms | 7.856 | 13.041 |
| Maximum frame, ms | 63.374 | 104.960 |
| Estimated allocated MiB | 12010.232 | 11537.966 |
| Young collections | 43 | 40 |
| Longest GC pause, ms | 14.849 | 16.116 |

The full-phase counters below include the inactivity interval; there are no
counter snapshots at the exclusion boundaries.

| LOD work | Without | With |
| --- | ---: | ---: |
| Stationary scans | 328 | 11 |
| Stationary entries visited | 859360 | 28732 |
| Total entries visited | 3256095 | 1449357 |
| Transition rebuild requests | 7818 | 7805 |
| Completed LOD-tracked build invocations, all states | 17271 | 17292 |

Stationary entry visits fell 96.7%; total visits fell 55.5%. Rebuild requests were
almost unchanged, and this route did not demonstrate a reduction in total builds.
Estimated allocations fell 3.9% in the filtered window. The targeted Fabric fluid
Runnable class was not sampled in either measured baseline/current window, so
this pair cannot establish its allocation benefit. The unmodified Sodium fluid
output callback was sampled in the changed run, illustrating JIT/sampling/workload
differences; no sampled pooled Invocation allocation was observed.

There is no demonstrated FPS/stutter improvement. Even outside inactivity, the
changed run was slower. The second run's camera changed from pitch 16 to 22.45
degrees and yaw -180 to approximately -179.70, ending about 9.5 blocks sideways
from the baseline route. Stationary performance also differed before the inactivity
plateau. Sequential order, thermal state, background activity and JIT behavior are
uncontrolled; pmset reported no recorded thermal/performance warnings, which does
not establish equal GPU clocks. Neither a code-caused regression nor a speedup
can be concluded from this pair. Do not use it as a release performance claim.

Artifacts: `build/combined-fluid-lod-ab-20261026-175413/`, including `launch.json`,
both JFRs/logs, `comparison-raw.txt`, `comparison-inactivity-excluded.txt` and
`focus-timeline.txt`. Analyzer: `build/AnalyzeCombinedFluidLod.java`; runner:
`build/combined_fluid_lod_ab.py`. Options, config and world were hash-verified
restored after each capture. Production source hashes and the pre-existing tracked
diff were unchanged; production classes were restored and no test client remained.

Launch accounting: one earlier game capture was discarded because an ineffective
Gradle source override left the new LOD code active in its intended baseline.
The corrected pair above used two further game launches, three actual launches
overall. Preparation failures and subsequent variant-compilation checks launched
no game clients. The discarded capture is under
`build/combined-fluid-lod-ab-20261026-174807/`; it is not included in any comparison.

## Historical diagnosis: the original invocation pool defeated escape analysis

**This warning applies to the superseded invocation pool, not the repaired carrier cache.**
A follow-up headless test through the actual Knot-loaded Fabric entrypoint found a concrete regression
for a simplified, synchronous handler with a caller-created output callback.
No additional Minecraft game client was launched for these checks.

| Actual Fabric fixture | Original | Pooled invocation |
| --- | ---: | ---: |
| Normal JVM, allocated bytes/call | 56 | 80 |
| Normal JVM, median ns/call | 7.757 | 19.450 |
| Escape analysis disabled, allocated bytes/call | 120 | 80 |
| Escape analysis disabled, median ns/call | 19.093 | 19.221 |

Each mode warms one million calls, then measures seven rounds of one million calls.
Timing is a small headless microbenchmark, not a prediction of real meshing cost or
FPS. Allocation counts use ThreadMXBean, not JFR sampling. Enabled/disabled runs
assert the actual Fabric transformation before measuring.

The original JVM path can eliminate both temporary callbacks in this fixture.
The pool writes the caller's output callback into its thread-local Invocation
(`ScopedFluidCallbacks.run`, argument `e`), making that object escape and preserving
its allocation. Clearing fields afterward avoids retention but does not reverse
escape. Thus saving the inner Runnable can cost more than it saves after Java's
optimizer has acted. The game JFR is consistent with this risk: Sodium's output
callback has no measured-window allocation samples in the baseline and about
279 MiB of estimated sampled allocation in the filtered changed run. Those samples
alone do not prove exact per-call behavior in the real renderer.

The initial `TestFluidCallbacks.original` explicitly assigns its Runnable to a
volatile sink. That forces escape and does not model the optimizer's opportunities
in the real call chain. Its 40-byte saving is true for that constrained fixture
but cannot establish a production allocation reduction. The replacement's realistic
caller/output fixture is described in the current implementation section above.

This was a reason to remove, disable, or redesign the original invocation pool,
independently of the LOD changes. It does not establish that the pool caused the entire recorded
FPS drop. During the stationary segment, render-thread time outside GPU waits was
about 0.43–0.50 ms in both captures, while GPU durations rose in the changed run.
The unchanged fullscreen presentation command buffer also rose from roughly
1.09 to 1.35 ms on average. Queue-start delays and callback delivery delays were
comparatively small. This supports a GPU throughput/contention component, but
clock, temperature and other-process GPU telemetry were not captured, so its
specific cause remains unknown.

Diagnosis artifacts in the comparison folder: `diagnosis-actual-fluid-*.log`,
`diagnosis-native-timing.txt`, and `diagnosis-gpu-cpu.txt`. Fixture source:
`build/TestActualFluidCost.java`, runner: `build/run_actual_fluid_cost.py`.
Production code was not modified during the diagnosis.
