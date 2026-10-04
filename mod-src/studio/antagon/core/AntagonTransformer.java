package studio.antagon.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class AntagonTransformer implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null) return bytes;
        if ("net.minecraft.client.renderer.entity.RendererLivingEntity".equals(transformedName))
            return patch(bytes, "func_177092_a", AntagonTransformer::hitColor, "Hit Color");
        if ("net.minecraft.client.renderer.entity.RenderEntityItem".equals(transformedName))
            return patch(bytes, "func_177077_a", AntagonTransformer::itemPhysics, "Item physics");
        if ("net.minecraft.client.renderer.ItemRenderer".equals(transformedName)) {
            bytes = patch(bytes, "func_78440_a", AntagonTransformer::itemSize, "Item Size");
            bytes =
                    patch(
                            bytes,
                            "func_78440_a",
                            AntagonTransformer::animationFrame,
                            "Animation frame");
            return patch(bytes, "func_178096_b", AntagonTransformer::legacySwing, "1.7 Animations");
        }
        if ("net.minecraft.client.gui.GuiPlayerTabOverlay".equals(transformedName))
            return patch(bytes, "func_175245_a", AntagonTransformer::tabIcon, "Tab icon");
        if ("net.minecraft.client.gui.GuiIngame".equals(transformedName))
            return patch(bytes, "func_180475_a", AntagonTransformer::scoreboard, "Scoreboard");
        if ("net.minecraft.client.entity.AbstractClientPlayer".equals(transformedName))
            return patch(bytes, "func_110303_q", AntagonTransformer::cape, "Cape");
        return bytes;
    }

    private interface Patch {
        boolean apply(MethodNode method);
    }

    private static byte[] patch(byte[] bytes, String method, Patch patch, String label) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (Object o : node.methods) {
                MethodNode m = (MethodNode) o;
                if (m.name.equals(method) && patch.apply(m)) {
                    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    System.out.println("[ANTAGON] " + label + " patch applied");
                    return writer.toByteArray();
                }
            }
            System.out.println("[ANTAGON] " + label + " patch: pattern not found, left vanilla");
        } catch (Throwable e) {
            System.out.println("[ANTAGON] " + label + " patch failed: " + e);
        }
        return bytes;
    }

    private static boolean hitColor(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null;
                insn = insn.getNext()) {
            if (!(insn instanceof LdcInsnNode)
                    || !Float.valueOf(.3f).equals(((LdcInsnNode) insn).cst)) continue;
            AbstractInsnNode[] loads = new AbstractInsnNode[4];
            loads[3] = insn;
            int found = 3;
            for (AbstractInsnNode p = insn.getPrevious();
                    p != null && found > 0;
                    p = p.getPrevious())
                if (p.getOpcode() == Opcodes.FCONST_0 || p.getOpcode() == Opcodes.FCONST_1)
                    loads[--found] = p;
            if (found != 0
                    || loads[0].getOpcode() != Opcodes.FCONST_1
                    || loads[1].getOpcode() != Opcodes.FCONST_0
                    || loads[2].getOpcode() != Opcodes.FCONST_0) return false;
            String[] getters = {"r", "g", "b", "a"};
            for (int i = 0; i < 4; i++)
                m.instructions.set(
                        loads[i],
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "studio/antagon/HitColor",
                                getters[i],
                                "()F",
                                false));
            return true;
        }
        return false;
    }

    private static boolean itemPhysics(MethodNode m) {
        LabelNode vanilla = new LabelNode();
        InsnList head = new InsnList();
        head.add(new FieldInsnNode(Opcodes.GETSTATIC, "studio/antagon/Hooks", "itemPhysics", "Z"));
        head.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new VarInsnNode(Opcodes.DLOAD, 2));
        head.add(new VarInsnNode(Opcodes.DLOAD, 4));
        head.add(new VarInsnNode(Opcodes.DLOAD, 6));
        head.add(new VarInsnNode(Opcodes.FLOAD, 8));
        head.add(new VarInsnNode(Opcodes.ALOAD, 9));
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "studio/antagon/Hooks",
                        "itemTransform",
                        "(Ljava/lang/Object;DDDFLjava/lang/Object;Ljava/lang/Object;)I",
                        false));
        head.add(new InsnNode(Opcodes.IRETURN));
        head.add(vanilla);
        head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(head);
        return true;
    }

    private static boolean itemSize(MethodNode m) {
        boolean changed = false;
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null;
                insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            // The model draw is separate from maps and the empty arm. Apply after the
            // equip/swing/eating/bow/block transforms; the existing pop restores the matrix.
            if (!call.owner.equals("net/minecraft/client/renderer/ItemRenderer")
                    || !call.name.equals("func_178099_a")) continue;
            m.instructions.insertBefore(
                    insn,
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "studio/antagon/Hooks",
                            "scaleHeldItem",
                            "()V",
                            false));
            changed = true;
        }
        return changed;
    }

    private static boolean animationFrame(MethodNode m) {
        if (!m.desc.equals("(F)V")) return false;
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.FLOAD, 1));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "studio/antagon/LegacyAnimations",
                        "frame",
                        "(F)V",
                        false));
        m.instructions.insert(hook);
        return true;
    }

    private static boolean legacySwing(MethodNode m) {
        if (!m.desc.equals("(FF)V")) return false;
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.FLOAD, 2));
        hook.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "studio/antagon/LegacyAnimations",
                        "swing",
                        "(F)F",
                        false));
        hook.add(new VarInsnNode(Opcodes.FSTORE, 2));
        m.instructions.insert(hook);
        return true;
    }

    private static boolean tabIcon(MethodNode m) {
        boolean patched = false;
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null;
                insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ILOAD, 1));
            call.add(new VarInsnNode(Opcodes.ILOAD, 2));
            call.add(new VarInsnNode(Opcodes.ILOAD, 3));
            call.add(new VarInsnNode(Opcodes.ALOAD, 4));
            call.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "studio/antagon/Hooks",
                            "tabIcon",
                            "(IIILjava/lang/Object;)V",
                            false));
            m.instructions.insertBefore(insn, call);
            patched = true;
        }
        return patched;
    }

    private static boolean scoreboard(MethodNode m) {
        LabelNode vanilla = new LabelNode();
        InsnList head = new InsnList();
        head.add(
                new FieldInsnNode(
                        Opcodes.GETSTATIC, "studio/antagon/Hooks", "hideScoreboard", "Z"));
        head.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        head.add(new InsnNode(Opcodes.RETURN));
        head.add(vanilla);
        head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        m.instructions.insert(head);
        return true;
    }

    private static boolean cape(MethodNode m) {
        boolean changed = false;
        for (AbstractInsnNode insn = m.instructions.getFirst();
                insn != null;
                insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "studio/antagon/Hooks",
                            "cape",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            false));
            hook.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/util/ResourceLocation"));
            m.instructions.insertBefore(insn, hook);
            changed = true;
        }
        return changed;
    }
}
