package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.List;

/**
 * Pure Metal's own codes for the frontend's enums. Formats are matched by name, so a format added or reordered in a
 * future Minecraft version shows up as "unsupported" instead of silently mapping to the wrong Metal format.
 */
final class MetalConst {
    /** Order must match PIXEL_FORMATS / VERTEX_FORMATS in puremetal.m. */
    static final List<String> FORMAT_NAMES = List.of(
        "R8_UNORM", "R8_SNORM", "RG8_UNORM", "RG8_SNORM", "RGB8_UNORM", "RGB8_SNORM", "RGBA8_UNORM", "RGBA8_SNORM",
        "R16_UNORM", "R16_SNORM", "RG16_UNORM", "RG16_SNORM", "RGB16_UNORM", "RGB16_SNORM", "RGBA16_UNORM", "RGBA16_SNORM",
        "R8_UINT", "R8_SINT", "RG8_UINT", "RG8_SINT", "RGB8_UINT", "RGB8_SINT", "RGBA8_UINT", "RGBA8_SINT",
        "R16_UINT", "R16_SINT", "RG16_UINT", "RG16_SINT", "RGB16_UINT", "RGB16_SINT", "RGBA16_UINT", "RGBA16_SINT",
        "R32_UINT", "R32_SINT", "RG32_UINT", "RG32_SINT", "RGB32_UINT", "RGB32_SINT", "RGBA32_UINT", "RGBA32_SINT",
        "R16_FLOAT", "RG16_FLOAT", "RGB16_FLOAT", "RGBA16_FLOAT", "R32_FLOAT", "RG32_FLOAT", "RGB32_FLOAT", "RGBA32_FLOAT",
        "RGB10A2_UNORM", "RGB10A2_UINT", "RG11B10_FLOAT",
        "D32_FLOAT", "D32_FLOAT_S8_UINT", "D24_UNORM_S8_UINT", "D16_UNORM", "S8_UINT"
    );
    private static final int[] FORMAT_CODES = new int[GpuFormat.values().length];

    static {
        for (GpuFormat format : GpuFormat.values()) {
            FORMAT_CODES[format.ordinal()] = FORMAT_NAMES.indexOf(format.name());
        }
    }

    // Primitive codes understood by puremetal.m.
    static final int PRIM_TRIANGLES = 0;
    static final int PRIM_TRIANGLE_STRIP = 1;
    static final int PRIM_LINES = 2;
    static final int PRIM_LINE_STRIP = 3;
    static final int PRIM_POINTS = 4;

    private MetalConst() {
    }

    /** Pure Metal format code, or -1 if this Minecraft format is unknown to us. */
    static int format(final GpuFormat format) {
        return FORMAT_CODES[format.ordinal()];
    }

    /** Checks the native tables agree with {@link #FORMAT_NAMES}; a mismatch means a broken build. */
    static void verifyNativeTables() {
        int count = MetalNative.formatCount();
        if (count != FORMAT_NAMES.size()) {
            throw new IllegalStateException("Pure Metal format table mismatch: native " + count + ", Java " + FORMAT_NAMES.size());
        }
    }

    static boolean isPixelFormatSupported(final GpuFormat format) {
        int code = format(format);
        return code >= 0 && MetalNative.pixelFormatSupported(code) != 0;
    }

    /**
     * Metal primitive for a topology. QUADS and LINES are drawn as indexed triangles by the frontend (it supplies the
     * quad index buffer), exactly like the Vulkan backend. TRIANGLE_FAN is rewritten by the render pass.
     */
    static int primitive(final PrimitiveTopology topology) {
        return switch (topology) {
            case LINES, QUADS, TRIANGLES, TRIANGLE_FAN -> PRIM_TRIANGLES;
            case TRIANGLE_STRIP -> PRIM_TRIANGLE_STRIP;
            case DEBUG_LINES -> PRIM_LINES;
            case DEBUG_LINE_STRIP -> PRIM_LINE_STRIP;
            case POINTS -> PRIM_POINTS;
        };
    }

    static int compare(final CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> 0;
            case LESS_THAN -> 1;
            case LESS_THAN_OR_EQUAL -> 2;
            case EQUAL -> 3;
            case NOT_EQUAL -> 4;
            case GREATER_THAN_OR_EQUAL -> 5;
            case GREATER_THAN -> 6;
            case NEVER_PASS -> 7;
        };
    }

    static int blendFactor(final BlendFactor factor) {
        return switch (factor) {
            case CONSTANT_ALPHA -> 0;
            case CONSTANT_COLOR -> 1;
            case DST_ALPHA -> 2;
            case DST_COLOR -> 3;
            case ONE -> 4;
            case ONE_MINUS_CONSTANT_ALPHA -> 5;
            case ONE_MINUS_CONSTANT_COLOR -> 6;
            case ONE_MINUS_DST_ALPHA -> 7;
            case ONE_MINUS_DST_COLOR -> 8;
            case ONE_MINUS_SRC_ALPHA -> 9;
            case ONE_MINUS_SRC_COLOR -> 10;
            case SRC_ALPHA -> 11;
            case SRC_ALPHA_SATURATE -> 12;
            case SRC_COLOR -> 13;
            case ZERO -> 14;
        };
    }

    static int blendOp(final BlendOp op) {
        return switch (op) {
            case ADD -> 0;
            case SUBTRACT -> 1;
            case REVERSE_SUBTRACT -> 2;
            case MIN -> 3;
            case MAX -> 4;
        };
    }
}
