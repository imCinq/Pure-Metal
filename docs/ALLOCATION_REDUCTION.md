# Sodium block callback allocation reduction

Implemented and validated on 2026-10-25; not committed or published.

The supported Sodium 0.9.2 and 0.9.3-beta.1 BlockRenderer creates two receiver-only method references in renderModel. Pure Metal now moves those exact factories to lazy instance getters, retaining one callback of each type per renderer. Calls, receiver binding, virtual dispatch, model arguments and culling operations are preserved. No constructor, mesh, lighting snapshot, visibility tree, fluid callback scope or worker ownership is replaced.

The plugin enables the marker mixin only for Sodium 0.9.2 and 0.9.3-beta.1 (including build suffixes) and only when both expected factories match. Post-apply checks the shape again before changing either site. Unknown versions, changed factory shapes and missing Sodium retain the original path. -Dpuremetal.disableSodiumBlockCallbacks=true disables it. Names are checked for collisions and duplicate application is rejected.

Tests: the complete headless terrain/palette/mixin harness passes, including vanilla and Sodium paths. Dedicated checks cover the real Knot-loaded transformed class, opt-out, changed captures, duplicate application, renderer isolation, current state, virtual dispatch, argument forwarding and allocation. In 100,000 callback pairs, warmed cached getters allocated 0 bytes versus 3,262,552 bytes for fresh references. Gradle build and diff whitespace checks pass.

A single 20-second Metal 3 + Sodium validation repeated the earlier walking scene, 15-second warmup, same options/world, tracing and JFR. The cache activation was confirmed in the live log. Results:

| Metric | Earlier baseline | Cache enabled |
| --- | ---: | ---: |
| Estimated total allocations, MiB | 6221.776 | 5939.639 |
| Estimated BlockRenderer callback allocations, MiB | 281.080 | 0 sampled |
| Young collections | 20 | 19 |
| Longest young GC pause, ms | 12.340 | 11.650 |
| Average FPS | 229.22 | 226.61 |
| p99.5 frame, ms | 8.48 | 7.87 |
| Maximum game sample, ms | 16.06 | 14.84 |
| Native intervals above 20 ms | 1 | 0 |

The targeted sampled allocation site disappears, and total allocation estimates decline approximately 4.5%. JFR weights are statistical estimates; no sampled callbacks does not mean the two first-use callbacks were never allocated. This is one sequential comparison, not a balanced performance trial. FPS was approximately 1.1% lower while tail metrics improved; neither change establishes a causal performance effect. Do not advertise a proven FPS gain or a stutter fix. Profiling adds overhead and Helium remained running.

Tradeoffs: two retained callback objects and two reference fields per renderer, lazy-cache checks, and a narrowly gated Sodium bytecode integration. Compatibility with arbitrary third-party transforms is not proven. Native timing, heap settings, graphics quality and world settings were not changed by this optimization. Metal 4 was not live-tested; no-Sodium skipping was headless-tested. In-game pixel equivalence was not measured; callback behavior was checked in headless tests.

Before artifacts: build/stutter-repro-metal3-20261025-210649/. After artifacts: build/stutter-repro-metal3-20261025-212123/, including comparison.txt, capture.jfr, game.log and launch.json. Each run restored options, all runtime configuration and the test world byte-for-byte. The validation client exited. Pre-existing source changes were preserved.


## Sodium 0.9.3 beta support and two-run check (2026-10-26)

The current published 26.3 Fabric beta is 0.9.3-beta.1. The official jar SHA-512 is 884a1ef0ce0e69d565d41a1359070a8d4f8589a086b565540defdb4741985053077dfd0f2a56898ce60510720a9f95dd8ba220a9a6340b6e0cf5cb12352e2419. BlockRenderer, AbstractBlockRenderContext and PlatformModelEmitter are byte-identical to the inspected 0.9.2 jar. That exact beta is now allowlisted; subsequent betas and the final 0.9.3 remain unverified and skip this optimization. Version-boundary tests reject beta.10, alpha builds and unknown releases. The bytecode shape check and runtime opt-out remain in force.

The development dependency defaults to stable 0.9.2. Use -PsodiumVersion=mc26.3-0.9.3-beta.1-fabric to test the beta; the headless harness accepts the same version via SODIUM_VERSION. The complete headless suite passed on the beta, including callback enabled/disabled and vanilla fallback; warmed callback allocation remained zero versus about 3.26 MB per 100,000 fresh callback pairs.

Exactly two new client launches were performed, off then on. Both used the same beta jar and compiled callback/native binaries, Metal 3, Sodium, 3420x2148, RD16, vsync off, maxFps 260, 15-second warmup and 20-second recording, walking the same restored world from (-272,95,112), yaw 180, pitch 16. Both used the same tracing/JFR instrumentation. A cooldown was allowed between runs. The optimization flag was the only intended configuration difference. Both exited successfully and restored options, configs and world byte-for-byte. No client remained running.

| Metric | Cache off | Cache on |
| --- | ---: | ---: |
| Average FPS | 234.08 | 232.56 |
| Median frame, ms | 4.07 | 4.12 |
| p98 frame, ms | 6.39 | 6.43 |
| p99.5 frame, ms | 8.04 | 8.43 |
| Maximum game sample, ms | 14.48 | 17.42 |
| Estimated total allocation, MiB | 6010.803 | 5723.593 |
| Estimated targeted callback allocation, MiB | 431.078 | 0 sampled |
| Young collections | 19 | 18 |
| Longest young GC pause, ms | 11.861 | 13.320 |
| Native intervals above 20 ms | 0 | 0 |

Allocation estimates declined 4.78%; average FPS was 0.65% lower with the cache. Tail and maximum GC timings were worse in this pair, despite one fewer collection. Two sequential runs cannot establish causality or a stable performance difference, and sample weights are not exact allocation accounting. The supported conclusion is removal of the targeted repeated allocation, not an FPS or stutter improvement.

Artifacts: build/callback-ab-beta-off-20261026-165625/ and build/callback-ab-beta-on-20261026-165856/; aggregate allocation comparison in build/callback-beta-comparison.txt; beta headless checks in build/sodium-beta-headless.log. The runner is build/repro_callback_beta.py. Earlier measurements above remain historical and must not be substituted for this pair.
