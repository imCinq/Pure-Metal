package io.github.imcinq.puremetal.lod;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

/** Atlas-aware texture repetition for eligible merged terrain faces. */
public final class LodTextureRepeat {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("puremetal.experimentalLodTextures", "true"));
    /** Bit-identical RGSS early-outs (see rgss_early_out_*.glsl); -Dpuremetal.rgssEarlyOut=false removes them. */
    public static final boolean EARLY_OUT = Boolean.parseBoolean(System.getProperty("puremetal.rgssEarlyOut", "true"));
    /** Specialization constant id of PM_RGSS_EARLY_OUT in the injected shader text. */
    public static final int EARLY_OUT_CONSTANT_ID = 77;
    private static final String VANILLA_SAMPLING_INCLUDE = "ffd52a2ed71dc43f83295c71ba7648e20b5b81d3ae43b8d64b027b1881c55c7b";
    public static final int SODIUM_FLAG = 128; // Pinned Sodium 0.9.2 uses material bits 0..2.
    private static final Identifier VANILLA = Identifier.fromNamespaceAndPath("minecraft", "core/terrain");
    private static final Identifier SODIUM = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");
    private static volatile Source current;

    private LodTextureRepeat() {}

    public static synchronized ShaderSource wrap(ShaderSource original) {
        if (!ENABLED && !EARLY_OUT) return original;
        Source old = current;
        if (old != null && old.original == original) return old;
        Source next = new Source(original);
        current = next;
        return next;
    }

    public static boolean ready(boolean sodium) {
        Source source = current;
        return ENABLED && source != null && (sodium ? source.sodium() : source.vanilla);
    }

    /** Only our known position-preserving patch may be unwrapped for diagnostic shader validation. */
    public static ShaderSource originalSource(ShaderSource source) {
        return source instanceof Source wrapped ? wrapped.original : source;
    }

    /** Reject skewed/non-affine/sub-sprite UVs; rotated and mirrored full rectangles are supported. */
    public static boolean eligible(BakedQuad q) {
        var sprite = q.materialInfo().sprite();
        if (sprite == null) return false;
        return rectangle(q, sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
    }

    public static boolean rectangle(BakedQuad q, float u0, float v0, float u1, float v1) {
        int axis = q.direction().getAxis().ordinal();
        int t1 = axis == 0 ? 1 : 0, t2 = axis == 2 ? 1 : 2;
        long c00 = 0, c01 = 0, c10 = 0, c11 = 0;
        int seen = 0;
        for (int i = 0; i < 4; i++) {
            float a = q.position(i).get(t1), b = q.position(i).get(t2);
            if ((a != 0 && a != 1) || (b != 0 && b != 1)) return false;
            int corner = (a == 1 ? 2 : 0) | (b == 1 ? 1 : 0);
            if ((seen & (1 << corner)) != 0) return false;
            seen |= 1 << corner;
            long uv = q.packedUV(i);
            if (!Float.isFinite(UVPair.unpackU(uv)) || !Float.isFinite(UVPair.unpackV(uv))) return false;
            switch (corner) { case 0 -> c00 = uv; case 1 -> c01 = uv; case 2 -> c10 = uv; case 3 -> c11 = uv; }
        }
        if (seen != 15 || !(u1 > u0 && v1 > v0) || u0 < 0 || v0 < 0 || u1 > 1 || v1 > 1) return false;
        float u00=UVPair.unpackU(c00),v00=UVPair.unpackV(c00),u01=UVPair.unpackU(c01),v01=UVPair.unpackV(c01);
        float u10=UVPair.unpackU(c10),v10=UVPair.unpackV(c10),u11=UVPair.unpackU(c11),v11=UVPair.unpackV(c11);
        float eu = (u1 - u0) * 0.02f, ev = (v1 - v0) * 0.02f;
        for (int i = 0; i < 4; i++) {
            float u=UVPair.unpackU(q.packedUV(i)),v=UVPair.unpackV(q.packedUV(i));
            if (Math.min(Math.abs(u-u0),Math.abs(u-u1)) > eu
                || Math.min(Math.abs(v-v0),Math.abs(v-v1)) > ev) return false;
        }
        float au = u10-u00, av = v10-v00, bu = u01-u00, bv = v01-v00;
        if (Math.abs(u11-u10-u01+u00) > eu * 0.01f
            || Math.abs(v11-v10-v01+v00) > ev * 0.01f) return false;
        return (Math.abs(au) > eu && Math.abs(av) < ev && Math.abs(bu) < eu && Math.abs(bv) > ev)
            || (Math.abs(au) < eu && Math.abs(av) > ev && Math.abs(bu) > eu && Math.abs(bv) < ev);
    }

    /** Vanilla UV0 is RG32_FLOAT. Values in [2,3] identify only our marked atlas UVs. */
    public static long mark(long uv) {
        return ((long)Float.floatToRawIntBits(UVPair.unpackU(uv) + 2.0f) << 32) | (uv & 0xffffffffL);
    }

    private static boolean stock(String source, String hash) {
        if (source == null) return false;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8))).equals(hash);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static String helper() {
        return resource("lod_repeat.glsl");
    }

    private static String resource(String name) {
        try (var in = LodTextureRepeat.class.getResourceAsStream("/assets/puremetal/shaders/" + name)) {
            if (in == null) throw new IllegalStateException("Missing shader resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** True when the stock vanilla texture_sampling.glsl include (which our early-out copy replaces) is unmodified. */
    private static boolean stockSamplingInclude(ShaderSource source) {
        try {
            var include = source.getInclude(Identifier.parse("minecraft:texture_sampling.glsl"));
            if (include == null || include.includeResultPtr() == 0) return false;
            var result = ShadercIncludeResult.create(include.includeResultPtr());
            long length = result.content_length();
            long address = MemoryUtil.memGetAddress(result.address() + ShadercIncludeResult.CONTENT);
            if (address == 0 || length <= 0 || length > 1048576) return false;
            var content = MemoryUtil.memByteBuffer(address, (int) length);
            return stock(StandardCharsets.UTF_8.decode(content).toString(), VANILLA_SAMPLING_INCLUDE);
        } catch (RuntimeException unsupported) { return false; }
    }

    /** lod: 2x2 face repetition; earlyOut: bit-identical RGSS early-outs. The caller has proven the source stock. */
    public static String patch(String source, boolean sodium, ShaderType type, boolean lod, boolean earlyOut) {
        int location = sodium ? 4 : 5;
        String qualifiers = type == ShaderType.VERTEX ? "out" : "in";
        String varyings = "\nlayout(location = " + location + ") " + qualifiers + " vec3 pmLocalPos;\n"
            + "layout(location = " + (location+1) + ") flat " + qualifiers + " uint pmRepeatFace;\n";
        if (type == ShaderType.VERTEX) {
            if (!lod) return source;
            String init = sodium ? "pmLocalPos = _vert_position; pmRepeatFace = (_material_params & 128u) != 0u ? 1u : 0u;"
                : "pmLocalPos = Position; pmRepeatFace = UV0.x >= 2.0 && UV0.x <= 3.0 ? 1u : 0u;";
            source = source.replace("void main() {", varyings + "\nvoid main() {");
            source = sodium ? source.replace("_vert_init();", "_vert_init();\n" + init)
                : source.replace("texCoord0 = UV0;", init + "\ntexCoord0 = UV0 - vec2(pmRepeatFace != 0u ? 2.0 : 0.0, 0.0);");
        } else {
            String rgss = earlyOut ? resource(sodium ? "rgss_early_out_sodium.glsl" : "rgss_early_out_vanilla.glsl")
                : "const bool PM_RGSS_EARLY_OUT = false;\nvec4 pmSampleRGSS(sampler2D s, vec2 uv, vec2 p) { return sampleRGSS(s, uv, p); }\n";
            String old = sodium ? "u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize)"
                : "(UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize))";
            String replacement;
            if (lod) {
                source = source.replace("void main() {", varyings + rgss + helper() + "\nvoid main() {");
                replacement = sodium ? "pmSampleLod(u_BlockTex, v_TexCoord, u_TexelSize, u_UseRGSS, pmLocalPos, pmRepeatFace)"
                    : "pmSampleLod(Sampler0, texCoord0, 1.0f / TextureSize, UseRgss == 1, pmLocalPos, pmRepeatFace)";
            } else {
                source = source.replace("void main() {", rgss + "\nvoid main() {");
                replacement = old.replace("sampleRGSS(", "pmSampleRGSS(");
            }
            if (!source.contains(old)) throw new IllegalArgumentException("Unsupported LOD shader sample expression");
            source = source.replace(old, replacement);
        }
        return source;
    }

    private static final class Source implements ShaderSource {
        final ShaderSource original;
        final boolean vanilla, vanillaEarlyOut;
        private Boolean sodiumStock;
        Source(ShaderSource original) {
            this.original = original;
            vanilla = stock(original.getShader(VANILLA, ShaderType.VERTEX), "001120e03dd7b9d690d3c98a1563334497c465f79ce14dc557d3d4caa02299a0")
                && stock(original.getShader(VANILLA, ShaderType.FRAGMENT), "e440d13f0c3c3aa0ae584a7e006410e4318922c5bc3c94a7a1be28b89b221bf9");
            vanillaEarlyOut = EARLY_OUT && vanilla && stockSamplingInclude(original);
        }
        /** Lazy: vanilla's early preload source cannot see Sodium's shader resources yet. */
        synchronized boolean sodium() {
            if (sodiumStock == null) sodiumStock = stock(original.getShader(SODIUM, ShaderType.VERTEX), "eb663f22c1be14a3a1e391601e466a77b420f8e087f2edf2b06c0c9e64342ce8")
                && stock(original.getShader(SODIUM, ShaderType.FRAGMENT), "380c49c7463bed6382cb993e39a55e4a07e4c00462f8980fc020b66e29bd27a1");
            return sodiumStock;
        }
        @Override public String getShader(Identifier id, ShaderType type) {
            String source = original.getShader(id, type);
            boolean isSodium = id.equals(SODIUM);
            boolean stock = (vanilla && id.equals(VANILLA)) || (isSodium && sodium());
            if (!stock) return source;
            boolean lod = ENABLED, earlyOut = EARLY_OUT && (isSodium || vanillaEarlyOut);
            return lod || earlyOut ? patch(source, isSodium, type, lod, earlyOut) : source;
        }
        @Override public CachedIncludeSource getInclude(Identifier id) { return original.getInclude(id); }
        @Override public void close() {} // Borrowed source is owned and closed by ShaderManager.
    }
}
