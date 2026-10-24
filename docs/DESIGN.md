# Pure Metal: design notes

Pure Metal adds a native Apple Metal backend to Minecraft 26.3 (Fabric). Render Scale and Terrain LOD trade
distant detail or rendering resolution for performance. Terrain LOD defaults on at RD16+ with an 80% full-detail
radius; it can be disabled.

## How it plugs in

Minecraft 26.3 splits rendering into a shared frontend (`com.mojang.renderpearl.frontend`) and swappable backends
(`backend.opengl`, `backend.vulkan`). The frontend validates every call, builds pipelines and compiles GLSL to
SPIR-V. A backend only implements `GpuBackend` + `GpuDeviceBackend` and the objects they create.

Pure Metal is a third backend modelled on the vanilla Vulkan one:

- **Entry point:** a mixin on `PreferredGraphicsApi.getBackendsToTry` puts Metal first. If Metal fails to start, the
  next backend in the list (vanilla) is used. Further mixins hook the vanilla or Sodium section mesher for terrain LOD,
  the video and Sodium settings screens (Render Scale, Terrain LOD, Metal 4), startup recovery, the F3 screen and the
  benchmark. None of them alter entities.
- **Shaders:** the frontend's SPIR-V is translated to MSL with SPIRV-Cross (LWJGL `spvc`, already shipped with the
  game), vertex Y flipped so render targets have the same memory layout as Vulkan. The final present flips back,
  like `VulkanGpuSurface.blitFromTexture`.
- **Native bridge:** a small Objective-C library (`src/main/native/puremetal.m`) called through Java's FFM API.
  Metal enums are mapped by name in C, never by hard-coded numbers in Java.
- **Memory:** Apple Silicon has unified memory, so every buffer is `MTLStorageModeShared`. Transient allocations
  use Mojang's `TransientBlockAllocator`, rotated per submit with 2 submits in flight (same as Vulkan).
- **Ordering:** all copies/clears are encoded into the command buffer in call order (blit encoders), so a write
  issued between two draws is seen only by the later draw, matching Vulkan semantics.

## Feature flags we report (and why)

| Feature | Value | Reason |
|---|---|---|
| multiDrawDirectSeparate | true | Sodium's non-Vulkan terrain path (`GLDrawBatch`) uses it. Ciderlight lacks it, so it must block Sodium. |
| multiDrawDirectInterleaved | true | Looped in native code, one FFM call per batch. |
| drawIndirect / multiDrawIndirect | true | Metal `drawIndexedPrimitives:indirectBuffer:`; Vulkan and Metal indirect structs have the same layout. |
| persistentMapping | true | Shared memory is always mapped. |
| nonZeroFirstInstance / shaderDrawParameters | true | Metal supports base instance and base vertex. |
| wireframeFillMode | true | `MTLTriangleFillModeLines`. |
| isExplicitDepthRequired hint | true | Same as vanilla Vulkan on Apple Silicon (invariant depth). |

## Risks we expect, and the plan for each

| Risk | Where others hit it | Plan |
|---|---|---|
| Mods that call OpenGL directly (no GL context exists) | JourneyMap native crash on Metallum | Detect GL calls with no context, show which mod, fall back to OpenGL for that instance (later milestone). |
| Mods with their own renderer setting | Better Clouds needed OPENGL→METAL by hand | Small optional compat mixins (`require = 0`) for known mods. |
| Core-shader resource packs that fail to translate | Metallum #3 | Shader failures are logged with the pack/shader name; the frontend's fallback rules decide, no hard crash. |
| Using a resource after it is closed / freed too early | Essential "Color texture 0 is closed" | Command buffers retain resources; destruction is deferred two submits like Vulkan. |
| Writes racing the GPU | Visual artifacts reports | Never memcpy into a buffer the GPU may be reading; ordered blit from staging. |
| TRIANGLE_FAN (no Metal equivalent) | – | Converted to an indexed triangle list. |
| 3-component texture formats (RGB8, RGB16, RGB32) | – | No Metal pixel format; reported clearly if a mod asks for one. Vertex formats are fine. |
| Timestamp queries / F3 profiler | Metallum F3+1 crash | Query pool returns "no value" instead of crashing until real GPU timestamps are implemented. |
| Device loss / GPU error | – | Command buffer errors are captured and surfaced as `GpuDeviceLossException`, not a JVM crash. |
| Intel Macs / old macOS | – | Requires Apple Silicon (Apple7+ GPU family) and macOS 14; otherwise Pure Metal stays off. |

## Privacy and safety

- No network access, no telemetry, no analytics.
- The native library is extracted to the game's own directory under `.puremetal/`, named by content hash.
- Nothing is published or pushed anywhere without the owner's say-so.

## Testing rules (owner's)

- Clients are launched only when a batch of patches is ready to test, never ad hoc.
- At most one client at a time in practice (never more than two); the previous one must be closed first.
