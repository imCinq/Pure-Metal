package io.github.imcinq.puremetal.perf;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Caches immutable scope bindings while leaving the original Runnable factories and Carrier.run calls intact. */
public final class FabricFluidCallbacks {
    private static final String TARGET = "net/fabricmc/fabric/impl/client/rendering/fluid/FluidRenderingImpl";
    private static final String PREFIX = "puremetal$fluidCallback";
    private static final String CARRIER = "Ljava/lang/ScopedValue$Carrier;";
    private static final String WHERE = "(Ljava/lang/ScopedValue;Ljava/lang/Object;)" + CARRIER;
    private static final String VANILLA_CARRIER = PREFIX + "VanillaCarrier";
    private record Site(MethodNode method, MethodInsnNode where, AbstractInsnNode key, AbstractInsnNode value, boolean vanilla) {}

    private FabricFluidCallbacks() {}

    public static boolean supportsVersion(String version) {
        return version.equals("6.0.6") || version.startsWith("6.0.6+");
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static boolean keyField(AbstractInsnNode insn, String name) {
        return insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
            && field.owner.equals(TARGET) && field.name.equals(name) && field.desc.equals("Ljava/lang/ScopedValue;");
    }

    private static MethodNode initializer(ClassNode node) {
        MethodNode init = node.methods.stream().filter(m -> m.name.equals("<clinit>") && m.desc.equals("()V")).findFirst().orElse(null);
        if (init == null) return null;
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn : init.instructions) if (insn.getOpcode() >= 0) code.add(insn);
        if (code.size() != 5 || code.get(4).getOpcode() != Opcodes.RETURN) return null;
        String[] names = {"CURRENT_DEFAULT_RENDERER", "IS_RENDERING_VANILLA_DEFAULT"};
        for (int i = 0; i < 2; i++) {
            if (!(code.get(i * 2) instanceof MethodInsnNode create) || create.getOpcode() != Opcodes.INVOKESTATIC
                || !create.owner.equals("java/lang/ScopedValue") || !create.name.equals("newInstance")
                || !create.desc.equals("()Ljava/lang/ScopedValue;")) return null;
            if (!(code.get(i * 2 + 1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                || !field.owner.equals(TARGET) || !field.name.equals(names[i]) || !field.desc.equals("Ljava/lang/ScopedValue;")) return null;
        }
        return init;
    }

    private static List<Site> sites(ClassNode node) {
        if (!TARGET.equals(node.name) || initializer(node) == null
            || node.fields.stream().anyMatch(f -> f.name.startsWith(PREFIX))
            || node.methods.stream().anyMatch(m -> m.name.startsWith(PREFIX))) return List.of();
        for (String name : List.of("CURRENT_DEFAULT_RENDERER", "IS_RENDERING_VANILLA_DEFAULT")) {
            if (node.fields.stream().noneMatch(f -> f.name.equals(name) && f.desc.equals("Ljava/lang/ScopedValue;")
                && (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))) return List.of();
        }
        List<Site> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            int captures = method.name.equals("render") ? 7 : method.name.equals("renderVanillaDefault") ? 6 : 0;
            if (captures == 0 || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            List<MethodInsnNode> bindings = new ArrayList<>();
            int factories = 0;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/ScopedValue") && call.name.equals("where") && call.desc.equals(WHERE)) bindings.add(call);
                if (!(insn instanceof InvokeDynamicInsnNode factory)
                    || !factory.name.equals("run") || !Type.getReturnType(factory.desc).equals(Type.getType(Runnable.class))
                    || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    || !factory.bsm.getName().equals("metafactory") || factory.bsmArgs.length != 3
                    || !factory.bsmArgs[0].equals(Type.getMethodType("()V"))
                    || !factory.bsmArgs[2].equals(Type.getMethodType("()V"))
                    || !(factory.bsmArgs[1] instanceof Handle impl) || impl.getTag() != Opcodes.H_INVOKESTATIC
                    || !impl.getOwner().equals(TARGET) || impl.isInterface()) continue;
                Type[] args = Type.getArgumentTypes(factory.desc);
                if (args.length != captures || !impl.getDesc().equals(Type.getMethodDescriptor(Type.VOID_TYPE, args))
                    || java.util.Arrays.stream(args).anyMatch(t -> t.getSort() != Type.OBJECT)) continue;
                AbstractInsnNode next = factory.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof MethodInsnNode consumer && consumer.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && consumer.owner.equals("java/lang/ScopedValue$Carrier") && consumer.name.equals("run")
                    && consumer.desc.equals("(Ljava/lang/Runnable;)V")) factories++;
            }
            if (bindings.size() != 1 || factories != 1) return List.of();
            MethodInsnNode where = bindings.getFirst();
            AbstractInsnNode value = previousCode(where), key = value == null ? null : previousCode(value);
            boolean vanilla = captures == 6;
            if (!keyField(key, vanilla ? "IS_RENDERING_VANILLA_DEFAULT" : "CURRENT_DEFAULT_RENDERER")) return List.of();
            if (vanilla ? value.getOpcode() != Opcodes.ACONST_NULL
                : !(value instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD && var.var == 7)) return List.of();
            found.add(new Site(method, where, key, value, vanilla));
        }
        return found.size() == 2 && found.get(0).vanilla != found.get(1).vanilla ? found : List.of();
    }

    public static boolean supported(ClassNode node) { return !sites(node).isEmpty(); }

    public static boolean supportsOwner(ClassNode node) {
        return node.name.equals("net/caffeinemc/mods/sodium/fabric/render/FluidRendererImpl$DefaultRenderContext")
            && node.interfaces.contains("net/fabricmc/fabric/api/client/render/fluid/v1/FluidRendering$DefaultRenderer")
            && node.fields.stream().noneMatch(f -> f.name.startsWith("puremetal$fluid"))
            && node.methods.stream().noneMatch(m -> m.name.startsWith("puremetal$fluid"));
    }

    public static boolean apply(ClassNode node) {
        List<Site> sites = sites(node);
        if (sites.isEmpty()) return false;
        MethodNode init = initializer(node);
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            VANILLA_CARRIER, CARRIER, null, null));
        InsnList create = new InsnList();
        create.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, "IS_RENDERING_VANILLA_DEFAULT", "Ljava/lang/ScopedValue;"));
        create.add(new InsnNode(Opcodes.ACONST_NULL));
        create.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/ScopedValue", "where", WHERE, false));
        create.add(new FieldInsnNode(Opcodes.PUTSTATIC, TARGET, VANILLA_CARRIER, CARRIER));
        AbstractInsnNode end = init.instructions.getLast();
        while (end.getOpcode() < 0) end = end.getPrevious();
        init.instructions.insertBefore(end, create);
        init.maxStack = Math.max(init.maxStack, 2);
        for (Site site : sites) {
            if (site.vanilla) {
                site.method.instructions.remove(site.key);
                site.method.instructions.remove(site.value);
                site.method.instructions.set(site.where, new FieldInsnNode(Opcodes.GETSTATIC, TARGET, VANILLA_CARRIER, CARRIER));
            } else {
                site.method.instructions.set(site.where, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "io/github/imcinq/puremetal/perf/ScopedFluidCallbacks", "where", WHERE, false));
            }
        }
        return true;
    }
}
