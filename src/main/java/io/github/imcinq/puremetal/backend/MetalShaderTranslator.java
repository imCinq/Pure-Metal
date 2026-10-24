package io.github.imcinq.puremetal.backend;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/** SPIR-V (from the frontend's GLSL compiler) to Metal Shading Language, via SPIRV-Cross. */
final class MetalShaderTranslator {
    private static final int MSL_VERSION_3_0 = 30000;
    private static final int EXECUTION_MODEL_VERTEX = 0;
    private static final int EXECUTION_MODEL_FRAGMENT = 4;

    record Result(String source, String entryPoint) {
    }

    private MetalShaderTranslator() {
    }

    static Result translate(final BackendRenderPipeline.CreateInfo.Shader shader, final MetalPipeline.Bindings bindings, final boolean points) {
        int model = shader.module().type() == ShaderType.VERTEX ? EXECUTION_MODEL_VERTEX : EXECUTION_MODEL_FRAGMENT;
        IntBuffer spirv = shader.module().spv().asIntBuffer();
        long context = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ptr = stack.callocPointer(1);
            check(Spvc.spvc_context_create(ptr), "create context");
            context = ptr.get(0);
            check(Spvc.spvc_context_parse_spirv(context, spirv, spirv.remaining(), ptr), "parse SPIR-V");
            long ir = ptr.get(0);
            check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, ptr), "create compiler");
            long compiler = ptr.get(0);

            check(Spvc.spvc_compiler_create_compiler_options(compiler, ptr), "create options");
            long options = ptr.get(0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_0);
            Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
            // Metal rejects a [[point_size]] output on non-point pipelines.
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_BUILTIN, points);
            Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_POINT_SIZE_DEFAULT, points);
            if (model == EXECUTION_MODEL_VERTEX) {
                // Metal's NDC Y points the other way from Vulkan's. Flipping keeps render targets laid out exactly
                // like the Vulkan backend's; the present pass flips back, as VulkanGpuSurface does.
                Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
            }
            check(Spvc.spvc_compiler_install_compiler_options(compiler, options), "install options");

            SpvcMslResourceBinding binding = SpvcMslResourceBinding.calloc(stack);
            for (int i = 0; i < bindings.count(); i++) {
                Spvc.spvc_msl_resource_binding_init(binding);
                binding.stage(model).desc_set(0).binding(i)
                    .msl_buffer(bindings.bufferIndex(i))
                    .msl_texture(bindings.textureIndex(i))
                    .msl_sampler(bindings.samplerIndex(i));
                check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add resource binding");
            }
            Spvc.spvc_msl_resource_binding_init(binding);
            binding.stage(model)
                .desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                .binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING)
                .msl_buffer(MetalPipeline.PUSH_CONSTANT_INDEX);
            check(Spvc.spvc_compiler_msl_add_resource_binding(compiler, binding), "add push constant binding");

            check(Spvc.spvc_compiler_set_entry_point(compiler, shader.entryPoint(), model), "set entry point");
            check(Spvc.spvc_compiler_compile(compiler, ptr), "compile to MSL");
            String source = MemoryUtil.memUTF8(ptr.get(0));
            String entry = Spvc.spvc_compiler_get_cleansed_entry_point_name(compiler, shader.entryPoint(), model);
            return new Result(source, entry);
        } finally {
            if (context != 0L) {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static void check(final int result, final String what) {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross failed to " + what + " (error " + result + ")");
        }
    }
}
