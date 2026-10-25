// Builds assets/baritone-26.3.jar from upstream Baritone v1.20.0 (LGPL-3.0, https://github.com/cabaletta/baritone).
// Input: baritone-api-fabric-1.20.0.jar, sha256 49adfc063cfbfd0b6f08e9d814359807baa2d1768c0d39d6c5968268547cbca6
// Run (JDK 24+): java -cp "<minecraft 26.3 jar>:<input jar>" scripts/patch-baritone.java <input jar> assets/baritone-26.3.jar
//
// Baritone 1.20.0 x Essential: Baritone's MixinRenderType adds an instance method
// createRenderType(String, RenderSetup) -> RenderType to RenderType. Essential picks the first
// RenderType method with that exact signature via reflection and invokes it statically -> NPE.
// Fix: widen the first parameter to Object so the added method no longer matches.
import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class PatchBaritone {
    static final ClassDesc RT = ClassDesc.of("net.minecraft.client.renderer.rendertype.RenderType");
    static final ClassDesc SETUP = ClassDesc.of("net.minecraft.client.renderer.rendertype.RenderSetup");
    static final MethodTypeDesc OLD = MethodTypeDesc.of(RT, ConstantDescs.CD_String, SETUP);
    static final MethodTypeDesc NEW = MethodTypeDesc.of(RT, ConstantDescs.CD_Object, SETUP);
    static final String NAME = "createRenderType";

    public static void main(String[] a) throws Exception {
        var in = Path.of(a[0]);
        var out = Path.of(a[1]);
        var cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofClassLoading(PatchBaritone.class.getClassLoader()))));
        int patched = 0;
        try (var zin = new ZipInputStream(Files.newInputStream(in)); var zout = new ZipOutputStream(Files.newOutputStream(out))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                byte[] b = zin.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    byte[] p = patch(cf, b);
                    if (p != b) { patched++; System.out.println("patched " + e.getName()); }
                    b = p;
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(b);
                zout.closeEntry();
            }
        }
        if (patched != 3) throw new IllegalStateException("expected 3 patched classes, got " + patched);
    }

    static boolean isTarget(MethodModel m) {
        return m.methodName().equalsString(NAME) && m.methodTypeSymbol().equals(OLD);
    }

    static boolean isCall(CodeElement e) {
        return e instanceof InvokeInstruction i && i.name().equalsString(NAME) && i.typeSymbol().equals(OLD);
    }

    static byte[] patch(ClassFile cf, byte[] bytes) {
        ClassModel cm = cf.parse(bytes);
        boolean declares = cm.methods().stream().anyMatch(PatchBaritone::isTarget);
        boolean calls = cm.methods().stream().anyMatch(m -> m.code().map(c -> c.elementStream().anyMatch(PatchBaritone::isCall)).orElse(false));
        if (!declares && !calls) return bytes;
        return cf.transformClass(cm, (cb, ce) -> {
            if (ce instanceof MethodModel m && isTarget(m)) {
                // Same method with a widened first parameter; the body casts it back to String.
                cb.withMethod(NAME, NEW, m.flags().flagsMask(), mb -> {
                    for (var me : m) {
                        if (me instanceof CodeModel code) {
                            mb.withCode(c -> {
                                boolean cast = false;
                                for (var el : code) {
                                    c.with(el);
                                    if (!cast && el instanceof Instruction ins && ins.opcode() == Opcode.ALOAD_1) {
                                        c.checkcast(ConstantDescs.CD_String);
                                        cast = true;
                                    }
                                }
                                if (!cast) throw new IllegalStateException("no aload_1 in " + NAME);
                            });
                        } else mb.with(me);
                    }
                });
            } else if (ce instanceof MethodModel m && m.code().isPresent()
                    && m.code().get().elementStream().anyMatch(PatchBaritone::isCall)) {
                cb.transformMethod(m, MethodTransform.transformingCode((c, el) -> {
                    if (isCall(el)) {
                        var i = (InvokeInstruction) el;
                        c.invoke(i.opcode(), i.owner().asSymbol(), NAME, NEW, i.isInterface());
                    } else c.with(el);
                }));
            } else cb.with(ce);
        });
    }
}
