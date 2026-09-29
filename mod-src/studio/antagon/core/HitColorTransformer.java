package studio.antagon.core;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public class HitColorTransformer implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes == null
                || !"net.minecraft.client.renderer.entity.RendererLivingEntity"
                        .equals(transformedName)) return bytes;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            for (Object o : node.methods) {
                MethodNode m = (MethodNode) o;
                if (!m.name.equals("func_177092_a") && !m.name.equals("setBrightness")) continue;
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
                            || loads[2].getOpcode() != Opcodes.FCONST_0) break;
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
                    ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(w);
                    System.out.println("[ANTAGON] Hit Color patch applied");
                    return w.toByteArray();
                }
            }
            System.out.println("[ANTAGON] Hit Color patch: pattern not found, left vanilla");
        } catch (Throwable e) {
            System.out.println("[ANTAGON] Hit Color patch failed: " + e);
        }
        return bytes;
    }
}
