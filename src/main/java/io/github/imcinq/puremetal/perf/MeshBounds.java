package io.github.imcinq.puremetal.perf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

/** Immutable bounds of decoded position attributes; shaders that displace vertices still require fallback. */
public record MeshBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public static @Nullable MeshBounds scan(ByteBuffer source, int count, int stride, int offset, boolean packedSodium) {
        int bytes = packedSodium ? 8 : 12;
        if (count <= 0 || stride < bytes || offset < 0 || offset > stride - bytes
                || (long) count * stride > source.remaining()) return null;
        ByteBuffer data = source.duplicate().order(ByteOrder.nativeOrder());
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < count; i++) {
            int position = data.position() + i * stride + offset;
            float x, y, z;
            if (packedSodium) {
                int hi = data.getInt(position), lo = data.getInt(position + 4);
                x = decode(hi, lo, 0); y = decode(hi, lo, 10); z = decode(hi, lo, 20);
            } else { x = data.getFloat(position); y = data.getFloat(position + 4); z = data.getFloat(position + 8); }
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return null;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        minX = Math.nextDown(minX); minY = Math.nextDown(minY); minZ = Math.nextDown(minZ);
        maxX = Math.nextUp(maxX); maxY = Math.nextUp(maxY); maxZ = Math.nextUp(maxZ);
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)) return null;
        return new MeshBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }
    public static float decode(int hi, int lo, int shift) {
        int coordinate = ((hi >>> shift & 1023) << 10) | (lo >>> shift & 1023);
        return coordinate * (32.0f / 1048576.0f) - 8.0f;
    }
}
