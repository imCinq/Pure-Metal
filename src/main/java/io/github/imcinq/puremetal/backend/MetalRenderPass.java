package io.github.imcinq.puremetal.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import io.github.imcinq.puremetal.bench.Bench;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * One Metal render command encoder. Uniform bindings are collected and flushed in a single native call right before
 * a draw, mirroring the Vulkan backend's push descriptors.
 */
final class MetalRenderPass implements RenderPassBackend {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BINDINGS = 64;
    private static boolean warnedIndexedFan;

    private final MetalCommandEncoder encoder;
    private final long device;
    private final RenderPass.RenderArea renderArea;
    private final int width;
    private final int height;
    private final int depthFormat;
    private @Nullable MetalPipeline pipeline;
    private final List<@Nullable Object> uniforms = new ArrayList<>();
    private boolean bindingsDirty;
    private @Nullable MetalBuffer indexBuffer;
    private IndexType indexType = IndexType.SHORT;
    private int debugGroups;
    private float probeRegionX,probeRegionY,probeRegionZ;
    private boolean probeRegionKnown;
    private static boolean probeStateLogged;
    /** Buffer identity matters: another allocation may later reuse a closed buffer's native address. */
    private record TexelViewKey(MetalBuffer buffer, long offset, long length, int format) {
    }

    /** Created on first use; views are released at pass end (the command buffer keeps them alive). */
    private @Nullable Map<TexelViewKey, Long> texelViews;
    // Native scratch for binding flushes, reused for the life of the pass.
    private final long scratch = MemoryUtil.nmemAlloc(MAX_BINDINGS * (4 + 4 + 8 + 8));
    private final long kinds = this.scratch;
    private final long indices = this.kinds + MAX_BINDINGS * 4L;
    private final long objects = this.indices + MAX_BINDINGS * 4L;
    private final long offsets = this.objects + MAX_BINDINGS * 8L;

    MetalRenderPass(final MetalCommandEncoder encoder, final RenderPass.RenderArea renderArea, final int width, final int height, final int depthFormat) {
        this.encoder = encoder;
        this.device = encoder.handle();
        this.renderArea = renderArea;
        this.width = width;
        this.height = height;
        this.depthFormat = depthFormat;
    }

    void end() {
        while (this.debugGroups > 0) {
            this.popDebugGroup();
        }
        if (this.texelViews != null) {
            for (long view : this.texelViews.values()) {
                MetalNative.release(view);
            }
            this.texelViews.clear();
        }
        MemoryUtil.nmemFree(this.scratch);
    }

    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        this.debugGroups++;
        if (this.encoder.device().debugLabels()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                MetalNative.pushDebugGroup(this.device, MemoryUtil.memAddress(stack.UTF8Safe(label.get())));
            }
        }
    }

    @Override
    public void popDebugGroup() {
        if (this.debugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        this.debugGroups--;
        if (this.encoder.device().debugLabels()) {
            MetalNative.popDebugGroup(this.device);
        }
    }

    @Override
    public void setPipeline(final BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof MetalPipeline metalPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a Pure Metal pipeline");
        }
        long state = metalPipeline.state(this.depthFormat);
        if (state == 0L) {
            // Logged once by the pipeline; skip its draws rather than crash.
            this.pipeline = null;
            return;
        }
        this.pipeline = metalPipeline;
        this.probeRegionKnown = false;
        this.uniforms.clear();
        for (int i = 0; i < metalPipeline.uniforms().size(); i++) {
            this.uniforms.add(null);
        }
        this.bindingsDirty = true;
        MetalNative.passSetPipeline(
            this.device, state, metalPipeline.depthState(), metalPipeline.cull ? 1 : 0, metalPipeline.wireframe ? 1 : 0,
            metalPipeline.depthBiasConstant, metalPipeline.depthBiasSlope
        );
    }

    @Override
    public void setUniform(final int index, final @Nullable Object value) {
        if (this.pipeline != null) {
            this.uniforms.set(index, value);
            this.bindingsDirty = true;
        }
    }

    @Override
    public void pushConstants(final ByteBuffer value) {
        if (this.pipeline != null) {
            if (io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED && value.remaining()>=12) {
                var copy=value.duplicate().order(java.nio.ByteOrder.nativeOrder());int start=copy.position();
                this.probeRegionX=copy.getFloat(start);this.probeRegionY=copy.getFloat(start+4);this.probeRegionZ=copy.getFloat(start+8);
                this.probeRegionKnown=true;
            }
            MetalNative.passSetBytes(this.device, MemoryUtil.memAddress(value), value.remaining(), MetalPipeline.PUSH_CONSTANT_INDEX);
        }
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        // Metal requires the scissor to stay inside the attachment.
        int x0 = Math.clamp(x, 0, this.width);
        int y0 = Math.clamp(y, 0, this.height);
        int x1 = Math.clamp((long)x + width, x0, this.width);
        int y1 = Math.clamp((long)y + height, y0, this.height);
        MetalNative.passSetScissor(this.device, x0, y0, x1 - x0, y1 - y0);
    }

    @Override
    public void disableScissor() {
        this.enableScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
    }

    @Override
    public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            MetalNative.passSetVertexBuffer(this.device, slot, ((MetalBuffer)vertexBuffer.buffer()).use().handle(), vertexBuffer.offset());
        }
    }

    @Override
    public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
        this.indexBuffer = ((MetalBuffer)indexBuffer).use();
        this.indexType = indexType;
        MetalNative.passSetIndexBuffer(this.device, this.indexBuffer.handle(), indexType == IndexType.INT ? 1 : 0);
    }

    /** Flushes pending uniform bindings. Returns false if this draw must be skipped. */
    private boolean prepareDraw() {
        MetalPipeline p = this.pipeline;
        if (p == null) {
            Debug.skippedDraws++;
            return false;
        }
        Debug.draws++;
        Debug.metalDraws++;
        if (!this.bindingsDirty) {
            return true;
        }
        MetalPipeline.Bindings b = p.bindings();
        int count = 0;
        for (int i = 0; i < b.count(); i++) {
            Object value = this.uniforms.get(i);
            if (value == null) {
                throw new IllegalStateException("Missing uniform " + p.uniforms().get(i).name() + " for pipeline " + p.name());
            }
            UniformType type = b.types()[i];
            switch (type) {
                case UNIFORM_BUFFER -> {
                    GpuBufferSlice slice = (GpuBufferSlice)value;
                    MetalBuffer buffer = (MetalBuffer)slice.buffer();
                    long bytes = (slice.length() + 15L) & ~15L;
                    if (buffer.shadow() != 0L && bytes <= 4096L && slice.offset() + bytes <= buffer.shadowBytes()) {
                        // Small uniform: setBytes copies the CPU shadow now, so the MTLBuffer is not referenced by this draw.
                        MetalNative.passSetBytes(this.device, buffer.shadow() + slice.offset(), (int)bytes, b.bufferIndex(i));
                        io.github.imcinq.puremetal.bench.Bench.bytesBinds++;
                    } else {
                        count = this.put(count, 0, b.bufferIndex(i), buffer.use().handle(), slice.offset());
                    }
                }
                case COMBINED_IMAGE_SAMPLER -> {
                    TextureViewAndSampler ts = (TextureViewAndSampler)value;
                    count = this.put(count, 1, b.textureIndex(i), ((MetalTextureView)ts.view()).handle(), 0L);
                    count = this.put(count, 2, b.samplerIndex(i), ((MetalSampler)ts.sampler()).handle(), 0L);
                }
                case TEXEL_BUFFER -> {
                    GpuBufferSlice slice = (GpuBufferSlice)value;
                    var format = p.uniforms().get(i).gpuFormat();
                    MetalBuffer buffer = ((MetalBuffer)slice.buffer()).use();
                    TexelViewKey key = new TexelViewKey(buffer, slice.offset(), slice.length(), MetalConst.format(format));
                    Map<TexelViewKey, Long> views = this.texelViews;
                    if (views == null) {
                        views = new HashMap<>();
                        this.texelViews = views;
                    }
                    Long cached = views.get(key);
                    long view;
                    if (cached != null) {
                        view = cached;
                    } else {
                        view = MetalNative.bufferTexture(buffer.handle(), key.offset(), key.length(), key.format(), format.blockSize());
                        if (view == 0L) {
                            throw new IllegalStateException("Could not create a texel buffer view (" + format + ") for pipeline " + p.name());
                        }
                        views.put(key, view);
                    }
                    count = this.put(count, 1, b.textureIndex(i), view, 0L);
                }
            }
        }
        MetalNative.passBind(this.device, count, this.kinds, this.indices, this.objects, this.offsets);
        this.bindingsDirty = false;
        return true;
    }

    private int put(final int at, final int kind, final int index, final long object, final long offset) {
        MemoryUtil.memPutInt(this.kinds + at * 4L, kind);
        MemoryUtil.memPutInt(this.indices + at * 4L, index);
        MemoryUtil.memPutLong(this.objects + at * 8L, object);
        MemoryUtil.memPutLong(this.offsets + at * 8L, offset);
        return at + 1;
    }

    private int primitive() {
        return this.pipeline.primitive;
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        this.probeTerrain(1);
        if (!this.prepareDraw()) {
            return;
        }
        if (this.pipeline.triangleFan) {
            this.drawIndexedFan(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
            return;
        }
        MetalNative.passDrawIndexed(this.device, this.primitive(), indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        this.probeTerrain(drawCount);
        if (this.prepareDraw() && drawCount > 0) {
            Debug.metalDraws += -1 + MetalNative.passMultiDrawIndexed(this.device, this.primitive(), MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
        this.probeTerrain(drawCount);
        // Sodium's terrain path on non-Vulkan backends (glMultiDrawElementsBaseVertex style).
        long prepareStart = Bench.SECONDS > 0 ? System.nanoTime() : 0;
        if (this.prepareDraw() && drawCount > 0) {
            long encodeStart = Bench.SECONDS > 0 ? System.nanoTime() : 0;
            int issued = MetalNative.passMultiDrawIndexedSeparate(
                this.device, this.primitive(), MemoryUtil.memAddress(firstIndexOffsets), MemoryUtil.memAddress(indexCounts),
                MemoryUtil.memAddress(vertexOffsets), drawCount
            );
            if (Bench.SECONDS > 0) {
                Bench.terrainPrepareNs += encodeStart - prepareStart;
                Bench.terrainEncodeNs += System.nanoTime() - encodeStart;
                Bench.terrainBatches++;
                Bench.terrainDraws += issued;
            }
            Debug.metalDraws += -1 + issued;
        }
    }

    @Override
    public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
        this.probeTerrain(drawCount);
        if (this.prepareDraw() && drawCount > 0) {
            MetalNative.passDrawIndexedIndirect(this.device, this.primitive(), ((MetalBuffer)commands.buffer()).use().handle(), commands.offset(), drawCount);
        }
    }

    /** Copies only CPU-known uniform bytes; never waits for or reads back GPU buffers. */
    private long probeUniform(String name,int bytes) {
        for(int i=0;i<this.pipeline.uniforms().size();i++) {
            if(!this.pipeline.uniforms().get(i).name().equals(name) || !(this.uniforms.get(i) instanceof GpuBufferSlice slice)
                || !(slice.buffer() instanceof MetalBuffer buffer) || buffer.isClosed() || slice.offset()<0 || slice.length()<bytes
                || slice.offset()>buffer.size()-bytes) continue;
            if(buffer.shadow()!=0 && slice.offset()<=buffer.shadowBytes()-bytes) return buffer.shadow()+slice.offset();
            if(!buffer.gpuWritten) return buffer.contents()+slice.offset();
        }
        return 0;
    }
    private static float[] probeMatrix(long address) {
        float[] matrix=new float[16];for(int i=0;i<16;i++) matrix[i]=MemoryUtil.memGetFloat(address+i*4L);return matrix;
    }
    private void probeTerrain(int count) {
        if(!io.github.imcinq.puremetal.perf.TerrainProbeScope.ENABLED || count<=0) return;
        var scope=io.github.imcinq.puremetal.perf.TerrainProbeScope.current();if(scope==null) return;
        var ranges=scope.consume(count);
        if(!probeStateLogged && !ranges.isEmpty() && this.pipeline!=null) {
            probeStateLogged=true;
            LOGGER.info("Pure Metal: terrain probe first draw pipeline={} transform={} sodium={} push={} area={}x{} target={}x{} uniforms={}",
                this.pipeline.name(),this.pipeline.terrainProbeTransform,scope.sodium,this.probeRegionKnown,
                this.renderArea.width(),this.renderArea.height(),this.width,this.height,this.pipeline.uniforms());
        }
        if(ranges.isEmpty() || this.pipeline==null || this.pipeline.terrainProbeTransform!=(scope.sodium?2:1)
            || this.renderArea.x()!=0 || this.renderArea.y()!=0 || this.renderArea.width()!=this.width || this.renderArea.height()!=this.height) return;
        long projection,model,globals=0;
        if(scope.sodium) {
            if(!this.probeRegionKnown) return;
            projection=this.probeUniform("u_Globals",128);if(projection==0) return;model=projection+64;
        } else {
            projection=this.probeUniform("Projection",64);model=this.probeUniform("TerrainUniform",64);globals=this.probeUniform("Globals",32);
            if(projection==0 || model==0 || globals==0) return;
        }
        float[] view=probeMatrix(model),clip=probeMatrix(projection);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            // Native code copies these bytes before returning. Cap stack scratch independently of metadata size.
            ByteBuffer queries=stack.malloc(Math.min(1024,ranges.size())*24).order(java.nio.ByteOrder.nativeOrder());int written=0;
            for(var range:ranges) {
                float x,y,z,magnitude;
                if(scope.sodium) {
                    x=(float)(range.x()-scope.regionX)+this.probeRegionX;
                    y=(float)(range.y()-scope.regionY)+this.probeRegionY;
                    z=(float)(range.z()-scope.regionZ)+this.probeRegionZ;
                    magnitude=Math.max(Math.abs(this.probeRegionX)+Math.abs((float)(range.x()-scope.regionX)),
                        Math.max(Math.abs(this.probeRegionY)+Math.abs((float)(range.y()-scope.regionY)),Math.abs(this.probeRegionZ)+Math.abs((float)(range.z()-scope.regionZ))));
                } else {
                    x=(float)(range.x()-MemoryUtil.memGetInt(globals))+MemoryUtil.memGetFloat(globals+16);
                    y=(float)(range.y()-MemoryUtil.memGetInt(globals+4))+MemoryUtil.memGetFloat(globals+20);
                    z=(float)(range.z()-MemoryUtil.memGetInt(globals+8))+MemoryUtil.memGetFloat(globals+24);
                    magnitude=Math.max(Math.abs((float)(range.x()-MemoryUtil.memGetInt(globals)))+Math.abs(MemoryUtil.memGetFloat(globals+16)),
                        Math.max(Math.abs((float)(range.y()-MemoryUtil.memGetInt(globals+4)))+Math.abs(MemoryUtil.memGetFloat(globals+20)),
                            Math.abs((float)(range.z()-MemoryUtil.memGetInt(globals+8)))+Math.abs(MemoryUtil.memGetFloat(globals+24))));
                }
                var b=range.bounds();
                float extent=Math.max(Math.max(Math.abs(b.minX()),Math.abs(b.maxX())),
                    Math.max(Math.max(Math.abs(b.minY()),Math.abs(b.maxY())),Math.max(Math.abs(b.minZ()),Math.abs(b.maxZ()))));
                // The shader and CPU associate additions differently; preserve an error bound on operands before cancellation.
                float roundoff=8*Math.ulp(magnitude+extent);
                var q=io.github.imcinq.puremetal.perf.BoundsProjection.project(b,view,clip,x,y,z,this.width,this.height,roundoff,this.pipeline.terrainProbeReverse);
                if(q==null) continue;
                queries.putInt(q.x0()).putInt(q.y0()).putInt(q.x1()).putInt(q.y1()).putFloat(q.nearest()).putInt(0);
                if(++written==1024) break;
            }
            if(written>0) MetalNative.passProbeQueries(this.device,MemoryUtil.memAddress(queries.flip()),written,this.pipeline.terrainProbeReverse?1:0);
        }
    }

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        if (!this.prepareDraw()) {
            return;
        }
        if (this.pipeline.triangleFan) {
            this.drawFan(vertexCount, instanceCount, firstVertex, firstInstance);
            return;
        }
        MetalNative.passDraw(this.device, this.primitive(), vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        if (this.prepareDraw() && drawCount > 0) {
            Debug.metalDraws += -1 + MetalNative.passMultiDraw(this.device, this.primitive(), MemoryUtil.memAddress(drawParameters), drawCount, instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
        if (this.prepareDraw() && drawCount > 0) {
            Debug.metalDraws += -1 + MetalNative.passMultiDrawSeparate(this.device, this.primitive(), MemoryUtil.memAddress(firstVertices), MemoryUtil.memAddress(vertexCounts), drawCount);
        }
    }

    @Override
    public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
        if (this.prepareDraw() && drawCount > 0) {
            MetalNative.passDrawIndirect(this.device, this.primitive(), ((MetalBuffer)commands.buffer()).use().handle(), commands.offset(), drawCount);
        }
    }

    // Metal has no triangle fans: expand to a triangle list (vanilla's sky disc uses a fan).

    private void drawFan(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        int triangles = vertexCount - 2;
        if (triangles <= 0) {
            return;
        }
        GpuBufferSlice.MappedView out = this.encoder.transientMemoryImpl().allocateGpuMapped(triangles * 12L, 4L, GpuBuffer.USAGE_INDEX);
        IntBuffer idx = out.data().asIntBuffer();
        for (int t = 0; t < triangles; t++) {
            idx.put(0).put(t + 1).put(t + 2);
        }
        this.drawExpanded(out.slice(), triangles * 3, instanceCount, firstVertex, firstInstance);
    }

    private void drawIndexedFan(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        int triangles = indexCount - 2;
        if (triangles <= 0 || this.indexBuffer == null) {
            return;
        }
        if (this.indexBuffer.gpuWritten && !warnedIndexedFan) {
            warnedIndexedFan = true;
            LOGGER.warn("Pure Metal: a triangle fan reads an index buffer written by the GPU; it may draw incorrectly");
        }
        long src = this.indexBuffer.contents();
        boolean wide = this.indexType == IndexType.INT;
        GpuBufferSlice.MappedView out = this.encoder.transientMemoryImpl().allocateGpuMapped(triangles * 12L, 4L, GpuBuffer.USAGE_INDEX);
        IntBuffer idx = out.data().asIntBuffer();
        int first = this.readIndex(src, wide, firstIndex);
        for (int t = 0; t < triangles; t++) {
            idx.put(first).put(this.readIndex(src, wide, firstIndex + t + 1)).put(this.readIndex(src, wide, firstIndex + t + 2));
        }
        this.drawExpanded(out.slice(), triangles * 3, instanceCount, vertexOffset, firstInstance);
    }

    private int readIndex(final long base, final boolean wide, final int i) {
        return wide ? MemoryUtil.memGetInt(base + 4L * i) : Short.toUnsignedInt(MemoryUtil.memGetShort(base + 2L * i));
    }

    private void drawExpanded(final GpuBufferSlice indices, final int count, final int instances, final int baseVertex, final int firstInstance) {
        MetalBuffer buffer = ((MetalBuffer)indices.buffer()).use();
        MetalNative.passSetIndexBuffer(this.device, buffer.handle(), 1);
        MetalNative.passDrawIndexed(this.device, MetalConst.PRIM_TRIANGLES, count, instances, (int)(indices.offset() / 4L), baseVertex, firstInstance);
        if (this.indexBuffer != null) {
            MetalNative.passSetIndexBuffer(this.device, this.indexBuffer.handle(), this.indexType == IndexType.INT ? 1 : 0);
        }
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
    }
}
