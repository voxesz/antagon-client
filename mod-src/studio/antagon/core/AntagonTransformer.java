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
        if ("net.minecraft.client.gui.GuiIngame".equals(transformedName))
            return patch(bytes, "func_180475_a", AntagonTransformer::scoreboard, "Scoreboard");
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
}
