package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import net.fabricmc.loader.api.FabricLoader;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Bindings to {@code libpuremetal.dylib}. Every pointer crosses as a {@code long}; Java passes native memory
 * (LWJGL stack/heap allocations) by address, so no Java heap is ever exposed to native code.
 */
public final class MetalNative {
    private static final String LIBRARY = "libpuremetal.dylib";
    private static SymbolLookup lookup;

    // Formats and device.
    private static MethodHandle H_formatCount, H_pixelFormatSupported;
    private static MethodHandle H_deviceCheck, H_deviceCreate, H_deviceName, H_deviceMaxBufferLength, H_deviceWorkingSet;
    private static MethodHandle H_deviceLastError, H_deviceFailed, H_deviceDestroy, H_release, H_setLabel;
    private static MethodHandle H_traceEnable, H_traceMark, H_traceRead, H_encEnable, H_encTake;
    private static MethodHandle H_takeGpuNs, H_takeGpuFrames, H_takeStat, H_takeUploadGpuNs, H_takeUploadGpuCommands;
    private static MethodHandle H_profileEnable, H_takeProfileStat;
    private static MethodHandle H_probeEnable, H_takeProbeStats, H_passProbeQueries, H_takePassTrace, H_setOption, H_metal4Enable, H_metal4Encoding;
    private static MethodHandle H_submit, H_completed, H_waitSubmit, H_waitIdle;
    // Resources.
    private static MethodHandle H_bufferCreate, H_bufferContents, H_deviceAllocatedSize, H_textureCreate, H_textureView, H_bufferTexture, H_samplerCreate;
    private static MethodHandle H_libraryCreate, H_libraryFunction, H_libraryFunctionBool, H_pipelineCreate, H_depthStateCreate;
    // Copies.
    private static MethodHandle H_copyBuffer, H_copyBufferEarly, H_copyBufferToTexture, H_copyTextureToBuffer, H_copyTextureToTexture, H_clearTexture;
    // Render passes.
    private static MethodHandle H_passBegin, H_passEnd, H_pushDebugGroup, H_popDebugGroup, H_passSetPipeline, H_passSetScissor;
    private static MethodHandle H_passSetVertexBuffer, H_passSetIndexBuffer, H_passBind, H_passSetBytes;
    private static MethodHandle H_passDraw, H_passDrawIndexed, H_passMultiDrawIndexed, H_passMultiDrawIndexedSeparate;
    private static MethodHandle H_passMultiDraw, H_passMultiDrawSeparate, H_passDrawIndexedIndirect, H_passDrawIndirect;
    // Surface.
    private static MethodHandle H_surfaceCreate, H_surfaceConfigure, H_surfaceBlit, H_surfaceDestroy, H_surfaceTakeStats, H_surfaceTakePresentGpu, H_surfaceTextureBusy;
    private static MethodHandle H_upscale, H_upscaleReset, H_temporalSupported, H_temporalPrepare, H_temporalUpscale, H_temporalInfo;

    private MetalNative() {
    }

    /** Extracts the bundled library into {@code <gameDir>/.puremetal/} (named by content hash) and binds it. */
    public static synchronized void load() throws IOException {
        if (lookup != null) {
            return;
        }
        byte[] bytes;
        try (InputStream in = MetalNative.class.getResourceAsStream("/natives/" + LIBRARY)) {
            if (in == null) {
                throw new IOException("Pure Metal's native library is missing from the jar");
            }
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(Redact.error(e), e);
        }
        Path dir = FabricLoader.getInstance().getGameDir().resolve(".puremetal");
        Files.createDirectories(dir);
        Path target = dir.resolve("libpuremetal-" + hash + ".dylib");
        // Rewritten unless it is byte-for-byte this jar's library, so a changed file in the game folder is never loaded.
        if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
            Path tmp = Files.createTempFile(dir, "libpuremetal", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        lookup = SymbolLookup.libraryLookup(target, Arena.global());
        bind();
    }

    private static MethodHandle fn(final String name, final boolean critical, final MemoryLayout ret, final MemoryLayout... args) {
        FunctionDescriptor descriptor = ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args);
        Linker.Option[] options = critical ? new Linker.Option[]{Linker.Option.critical(false)} : new Linker.Option[0];
        return Linker.nativeLinker().downcallHandle(
            lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing native symbol " + name)), descriptor, options
        );
    }

    private static void bind() {
        final MemoryLayout I = JAVA_INT, L = JAVA_LONG, F = JAVA_FLOAT, D = JAVA_DOUBLE;
        H_formatCount = fn("pm_format_count", true, I);
        H_pixelFormatSupported = fn("pm_pixel_format_supported", true, I, I);
        H_deviceCheck = fn("pm_device_check", false, I, L, I);
        H_deviceCreate = fn("pm_device_create", false, L, I, I);
        H_deviceName = fn("pm_device_name", false, null, L, L, I);
        H_deviceMaxBufferLength = fn("pm_device_max_buffer_length", true, L, L);
        H_deviceWorkingSet = fn("pm_device_working_set", true, L, L);
        H_deviceLastError = fn("pm_device_last_error", false, null, L, L, I);
        H_deviceFailed = fn("pm_device_failed", true, I, L);
        H_deviceDestroy = fn("pm_device_destroy", false, null, L);
        H_release = fn("pm_release", false, null, L);
        H_setLabel = fn("pm_set_label", false, null, L, L);
        H_submit = fn("pm_submit", false, null, L, L);
        H_completed = fn("pm_completed", true, L, L);
        H_waitSubmit = fn("pm_wait", false, I, L, L, L);
        H_waitIdle = fn("pm_wait_idle", false, I, L);
        H_traceEnable = fn("pm_trace_enable", false, null);
        H_traceMark = fn("pm_trace_mark", false, null, I, L, L, L);
        H_traceRead = fn("pm_trace_read", false, I, L, I);
        H_encEnable = fn("pm_enc_enable", false, null);
        H_encTake = fn("pm_enc_take", false, null, L);
        H_takeGpuNs = fn("pm_take_gpu_ns", true, L, L);
        H_takeGpuFrames = fn("pm_take_gpu_frames", true, L, L);
        H_takeUploadGpuNs = fn("pm_take_upload_gpu_ns", true, L, L);
        H_takeUploadGpuCommands = fn("pm_take_upload_gpu_commands", true, L, L);
        H_profileEnable = fn("pm_profile_enable", false, I, L);
        H_takeProfileStat = fn("pm_take_profile_stat", true, L, L, I);
        H_probeEnable = fn("pm_probe_enable", false, I, L, L);
        H_takeProbeStats = fn("pm_take_probe_stats", false, null, L, L);
        H_takePassTrace = fn("pm_take_pass_trace", false, I, L, L, I);
        H_passProbeQueries = fn("pm_pass_probe_queries", false, I, L, L, I, I);
        H_takeStat = fn("pm_take_stat", true, L, L, I);
        H_setOption = fn("pm_set_option", false, null, L, I, I);
        H_metal4Enable = fn("pm_metal4_enable", false, I, L, I, L, I);
        H_metal4Encoding = fn("pm_metal4_encoding", false, I, L, L, I);

        H_bufferCreate = fn("pm_buffer_create", false, L, L, L);
        H_bufferContents = fn("pm_buffer_contents", true, L, L);
        H_deviceAllocatedSize = fn("pm_device_allocated_size", true, L, L);
        H_textureCreate = fn("pm_texture_create", false, L, L, I, I, I, I, I, I);
        H_textureView = fn("pm_texture_view", false, L, L, I, I, I);
        H_bufferTexture = fn("pm_buffer_texture", false, L, L, L, L, I, I);
        H_samplerCreate = fn("pm_sampler_create", false, L, L, I, I, I, I, I, I, F);
        H_libraryCreate = fn("pm_library_create", false, L, L, L, L, I, I);
        H_libraryFunction = fn("pm_library_function", false, L, L, L);
        H_libraryFunctionBool = fn("pm_library_function_bool", false, L, L, L, I, I);
        H_pipelineCreate = fn("pm_pipeline_create", false, L, L, L, L, L, L, L, I);
        H_depthStateCreate = fn("pm_depth_state_create", false, L, L, I, I, I);

        H_copyBuffer = fn("pm_copy_buffer", false, null, L, L, L, L, L, L);
        H_copyBufferEarly = fn("pm_copy_buffer_early", false, null, L, L, L, L, L, L);
        H_copyBufferToTexture = fn("pm_copy_buffer_to_texture", false, null, L, L, L, I, I, L, I, I, I, I, I, I);
        H_copyTextureToBuffer = fn("pm_copy_texture_to_buffer", false, null, L, L, I, I, I, I, I, I, L, L, I);
        H_copyTextureToTexture = fn("pm_copy_texture_to_texture", false, null, L, L, L, I, I, I, I, I, I, I);
        H_clearTexture = fn("pm_clear_texture", false, null, L, L, F, F, F, F, D);

        H_passBegin = fn("pm_pass_begin", false, I, L, I, L, L, L, L, I, D, I, I, I, I, I, I, L);
        H_passEnd = fn("pm_pass_end", false, null, L);
        H_pushDebugGroup = fn("pm_push_debug_group", false, null, L, L);
        H_popDebugGroup = fn("pm_pop_debug_group", false, null, L);
        H_passSetPipeline = fn("pm_pass_set_pipeline", false, null, L, L, L, I, I, F, F);
        H_passSetScissor = fn("pm_pass_set_scissor", false, null, L, I, I, I, I);
        H_passSetVertexBuffer = fn("pm_pass_set_vertex_buffer", false, null, L, I, L, L);
        H_passSetIndexBuffer = fn("pm_pass_set_index_buffer", false, null, L, L, I);
        H_passBind = fn("pm_pass_bind", false, null, L, I, L, L, L, L);
        H_passSetBytes = fn("pm_pass_set_bytes", false, null, L, L, I, I);
        H_passDraw = fn("pm_pass_draw", false, null, L, I, I, I, I, I);
        H_passDrawIndexed = fn("pm_pass_draw_indexed", false, null, L, I, I, I, I, I, I);
        H_passMultiDrawIndexed = fn("pm_pass_multi_draw_indexed", false, I, L, I, L, I, I, I);
        H_passMultiDrawIndexedSeparate = fn("pm_pass_multi_draw_indexed_separate", false, I, L, I, L, L, L, I);
        H_passMultiDraw = fn("pm_pass_multi_draw", false, I, L, I, L, I, I, I);
        H_passMultiDrawSeparate = fn("pm_pass_multi_draw_separate", false, I, L, I, L, L, I);
        H_passDrawIndexedIndirect = fn("pm_pass_draw_indexed_indirect", false, null, L, I, L, L, I);
        H_passDrawIndirect = fn("pm_pass_draw_indirect", false, null, L, I, L, L, I);

        H_surfaceCreate = fn("pm_surface_create", false, L, L, L);
        H_surfaceConfigure = fn("pm_surface_configure", false, null, L, I, I, I, I);
        H_surfaceBlit = fn("pm_surface_blit", false, I, L, L, L, L, I, I);
        H_surfaceTextureBusy = fn("pm_surface_texture_busy", false, I, L, L);
        H_surfaceDestroy = fn("pm_surface_destroy", false, null, L);
        H_surfaceTakeStats = fn("pm_surface_take_stats", true, null, L, L);
        H_surfaceTakePresentGpu = fn("pm_surface_take_present_gpu", true, null, L, L);
        H_upscale = fn("pm_upscale", false, I, L, L, L, I, F);
        H_upscaleReset = fn("pm_upscale_reset", false, null, L);
        H_temporalSupported = fn("pm_temporal_supported", false, I, L);
        H_temporalPrepare = fn("pm_temporal_prepare", false, I, L, L, L, L, L);
        H_temporalUpscale = fn("pm_temporal_upscale", false, I, L, L, L, L, F, F, I);
        H_temporalInfo = fn("pm_temporal_info", false, I, L, L, I);
    }

    static int formatCount() {
        try {
            return (int) H_formatCount.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int pixelFormatSupported(final int a0) {
        try {
            return (int) H_pixelFormatSupported.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int deviceCheck(final long a0, final int a1) {
        try {
            return (int) H_deviceCheck.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Creates the device; {@code metal4} asks for the Metal 4 command model (decided here, for the session). */
    static long deviceCreate(final int a0, final int metal4) {
        try {
            return (long) H_deviceCreate.invokeExact(a0, metal4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void deviceName(final long a0, final long a1, final int a2) {
        try {
            H_deviceName.invokeExact(a0, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long deviceMaxBufferLength(final long a0) {
        try {
            return (long) H_deviceMaxBufferLength.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long deviceWorkingSet(final long a0) {
        try {
            return (long) H_deviceWorkingSet.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void deviceLastError(final long a0, final long a1, final int a2) {
        try {
            H_deviceLastError.invokeExact(a0, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int deviceFailed(final long a0) {
        try {
            return (int) H_deviceFailed.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void deviceDestroy(final long a0) {
        try {
            H_deviceDestroy.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void release(final long a0) {
        try {
            H_release.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setLabel(final long a0, final long a1) {
        try {
            H_setLabel.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void submit(final long a0, final long a1) {
        try {
            H_submit.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long completed(final long a0) {
        try {
            return (long) H_completed.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int waitSubmit(final long a0, final long a1, final long a2) {
        try {
            return (int) H_waitSubmit.invokeExact(a0, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int waitIdle(final long device) {
        try {
            return (int) H_waitIdle.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long bufferCreate(final long a0, final long a1) {
        try {
            return (long) H_bufferCreate.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long deviceAllocatedSize(final long a0) {
        try {
            return (long) H_deviceAllocatedSize.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long bufferContents(final long a0) {
        try {
            return (long) H_bufferContents.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long textureCreate(final long a0, final int a1, final int a2, final int a3, final int a4, final int a5, final int a6) {
        try {
            return (long) H_textureCreate.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long textureView(final long a0, final int a1, final int a2, final int a3) {
        try {
            return (long) H_textureView.invokeExact(a0, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long bufferTexture(final long a0, final long a1, final long a2, final int a3, final int a4) {
        try {
            return (long) H_bufferTexture.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long samplerCreate(final long a0, final int a1, final int a2, final int a3, final int a4, final int a5, final int a6, final float a7) {
        try {
            return (long) H_samplerCreate.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long libraryCreate(final long a0, final long a1, final long a2, final int a3, final int a4) {
        try {
            return (long) H_libraryCreate.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long libraryFunction(final long a0, final long a1) {
        try {
            return (long) H_libraryFunction.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long libraryFunctionBool(final long a0, final long a1, final int a2, final int a3) {
        try {
            return (long) H_libraryFunctionBool.invokeExact(a0, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long pipelineCreate(final long a0, final long a1, final long a2, final long a3, final long a4, final long a5, final int a6) {
        try {
            return (long) H_pipelineCreate.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long depthStateCreate(final long a0, final int a1, final int a2, final int a3) {
        try {
            return (long) H_depthStateCreate.invokeExact(a0, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void copyBufferEarly(final long a0, final long a1, final long a2, final long a3, final long a4, final long a5) {
        try {
            H_copyBufferEarly.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void copyBuffer(final long a0, final long a1, final long a2, final long a3, final long a4, final long a5) {
        try {
            H_copyBuffer.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void copyBufferToTexture(final long a0, final long a1, final long a2, final int a3, final int a4, final long a5, final int a6, final int a7, final int a8, final int a9, final int a10, final int a11) {
        try {
            H_copyBufferToTexture.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void copyTextureToBuffer(final long a0, final long a1, final int a2, final int a3, final int a4, final int a5, final int a6, final int a7, final long a8, final long a9, final int a10) {
        try {
            H_copyTextureToBuffer.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void copyTextureToTexture(final long a0, final long a1, final long a2, final int a3, final int a4, final int a5, final int a6, final int a7, final int a8, final int a9) {
        try {
            H_copyTextureToTexture.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void clearTexture(final long a0, final long a1, final float a2, final float a3, final float a4, final float a5, final double a6) {
        try {
            H_clearTexture.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int passBegin(final long a0, final int a1, final long a2, final long a3, final long a4, final long a5, final int a6, final double a7, final int a8, final int a9, final int a10, final int a11, final int a12, final int a13, final long a14) {
        try {
            return (int) H_passBegin.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passEnd(final long a0) {
        try {
            H_passEnd.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void pushDebugGroup(final long a0, final long a1) {
        try {
            H_pushDebugGroup.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void popDebugGroup(final long a0) {
        try {
            H_popDebugGroup.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passSetPipeline(final long a0, final long a1, final long a2, final int a3, final int a4, final float a5, final float a6) {
        try {
            H_passSetPipeline.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passSetScissor(final long a0, final int a1, final int a2, final int a3, final int a4) {
        try {
            H_passSetScissor.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passSetVertexBuffer(final long a0, final int a1, final long a2, final long a3) {
        try {
            H_passSetVertexBuffer.invokeExact(a0, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passSetIndexBuffer(final long a0, final long a1, final int a2) {
        try {
            H_passSetIndexBuffer.invokeExact(a0, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passBind(final long a0, final int a1, final long a2, final long a3, final long a4, final long a5) {
        try {
            H_passBind.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passSetBytes(final long a0, final long a1, final int a2, final int a3) {
        try {
            H_passSetBytes.invokeExact(a0, a1, a2, a3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passDraw(final long a0, final int a1, final int a2, final int a3, final int a4, final int a5) {
        try {
            H_passDraw.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passDrawIndexed(final long a0, final int a1, final int a2, final int a3, final int a4, final int a5, final int a6) {
        try {
            H_passDrawIndexed.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int passMultiDrawIndexed(final long a0, final int a1, final long a2, final int a3, final int a4, final int a5) {
        try {
            return (int)H_passMultiDrawIndexed.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int passMultiDrawIndexedSeparate(final long a0, final int a1, final long a2, final long a3, final long a4, final int a5) {
        try {
            return (int)H_passMultiDrawIndexedSeparate.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int passMultiDraw(final long a0, final int a1, final long a2, final int a3, final int a4, final int a5) {
        try {
            return (int)H_passMultiDraw.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int passMultiDrawSeparate(final long a0, final int a1, final long a2, final long a3, final int a4) {
        try {
            return (int)H_passMultiDrawSeparate.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passDrawIndexedIndirect(final long a0, final int a1, final long a2, final long a3, final int a4) {
        try {
            H_passDrawIndexedIndirect.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void passDrawIndirect(final long a0, final int a1, final long a2, final long a3, final int a4) {
        try {
            H_passDrawIndirect.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long surfaceCreate(final long a0, final long a1) {
        try {
            return (long) H_surfaceCreate.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void surfaceConfigure(final long a0, final int a1, final int a2, final int a3, final int a4) {
        try {
            H_surfaceConfigure.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void traceEnable() {
        try {
            H_traceEnable.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean loaded() {
        return lookup != null;
    }

    static void encEnable() {
        try {
            H_encEnable.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void encTake(final long out) {
        try {
            H_encTake.invokeExact(out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void traceMark(final int type, final long a, final long b, final long c) {
        try {
            H_traceMark.invokeExact(type, a, b, c);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int traceRead(final long out, final int cap) {
        try {
            return (int) H_traceRead.invokeExact(out, cap);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean surfaceTextureBusy(final long surface, final long texture) {
        try {
            return (int) H_surfaceTextureBusy.invokeExact(surface, texture) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns 0 not presented, 1 presented through a staging copy, 2 presented straight from the texture (see pm_surface_blit). */
    static int surfaceBlit(final long a0, final long a1, final long a2, final long a3, final int a4, final int a5) {
        try {
            return (int) H_surfaceBlit.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void upscaleReset(final long device) {
        try {
            H_upscaleReset.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean temporalSupported(final long device) {
        try {
            return (int) H_temporalSupported.invokeExact(device) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int temporalPrepare(final long a0, final long a1, final long a2, final long a3, final long a4) {
        try {
            return (int) H_temporalPrepare.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int temporalInfo(final long a0, final long a1, final int a2) {
        try {
            return (int) H_temporalInfo.invokeExact(a0, a1, a2);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int temporalUpscale(final long a0, final long a1, final long a2, final long a3, final float a4, final float a5, final int a6) {
        try {
            return (int) H_temporalUpscale.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int upscale(final long a0, final long a1, final long a2, final int a3, final float a4) {
        try {
            return (int) H_upscale.invokeExact(a0, a1, a2, a3, a4);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void surfaceTakePresentGpu(final long a0, final long a1) {
        try {
            H_surfaceTakePresentGpu.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void surfaceTakeStats(final long a0, final long a1) {
        try {
            H_surfaceTakeStats.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void surfaceDestroy(final long a0) {
        try {
            H_surfaceDestroy.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Turns the Metal 4 shader compile path on or off; returns 1 if active, with the reason written to reason. */
    static int metal4Enable(final long device, final int enable, final long reason, final int cap) {
        try {
            return (int) H_metal4Enable.invokeExact(device, enable, reason, cap);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns 1 if the device submits through the Metal 4 command model, with the reason it does not written to reason otherwise. */
    static int metal4Encoding(final long device, final long reason, final int cap) {
        try {
            return (int) H_metal4Encoding.invokeExact(device, reason, cap);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void setOption(final long device, final int which, final int value) {
        try {
            H_setOption.invokeExact(device, which, value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long takeStat(final long a0, final int a1) {
        try {
            return (long) H_takeStat.invokeExact(a0, a1);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long takeGpuNs(final long a0) {
        try {
            return (long) H_takeGpuNs.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long takeUploadGpuNs(final long device) {
        try {
            return (long) H_takeUploadGpuNs.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static int profileEnable(final long device) {
        try { return (int) H_profileEnable.invokeExact(device); }
        catch (Throwable t) { throw rethrow(t); }
    }
    static int probeEnable(long device,long source) {
        try {return (int)H_probeEnable.invokeExact(device,source);} catch(Throwable t) {throw rethrow(t);}
    }
    static int takePassTrace(long device, long out, int capacity) {
        try {return (int)H_takePassTrace.invokeExact(device,out,capacity);} catch(Throwable t) {throw rethrow(t);}
    }
    static void takeProbeStats(long device,long out) {
        try {H_takeProbeStats.invokeExact(device,out);} catch(Throwable t) {throw rethrow(t);}
    }
    static int passProbeQueries(long device,long queries,int count,int reverse) {
        try {return (int)H_passProbeQueries.invokeExact(device,queries,count,reverse);} catch(Throwable t) {throw rethrow(t);}
    }

    static long takeProfileStat(final long device, final int which) {
        try { return (long) H_takeProfileStat.invokeExact(device, which); }
        catch (Throwable t) { throw rethrow(t); }
    }

    static long takeUploadGpuCommands(final long device) {
        try {
            return (long) H_takeUploadGpuCommands.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static long takeGpuFrames(final long a0) {
        try {
            return (long) H_takeGpuFrames.invokeExact(a0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Rethrows anything a downcall throws; downcalls themselves never throw checked exceptions. */
    static RuntimeException rethrow(final Throwable t) {
        if (t instanceof RuntimeException r) {
            return r;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
