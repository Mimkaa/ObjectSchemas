import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ClassMethodCloner {

    public static void main(String[] args) {
        String classNameToModify = null;
        String delegateClassName = null;
        String methodName = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classNameToModify" -> {
                    if (i + 1 < args.length) classNameToModify = args[++i];
                }
                case "--delegateclass" -> {
                    if (i + 1 < args.length) delegateClassName = args[++i];
                }
                case "--method" -> {
                    if (i + 1 < args.length) methodName = args[++i];
                }
                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (classNameToModify == null || delegateClassName == null || methodName == null) {
            System.err.println("ERROR: Missing required arguments.");
            printUsage();
            return;
        }

        try {
            new ClassMethodCloner().cloneMethod(classNameToModify, delegateClassName, methodName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("ClassMethodCloner - Copy method bytecode from delegate into base class");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp .;asm-9.8.jar;asm-tree-9.8.jar ClassMethodCloner \\");
        System.out.println("      --classNameToModify <FullyQualifiedClassName> \\");
        System.out.println("      --delegateclass <FullyQualifiedDelegateClassName> \\");
        System.out.println("      --method <methodName>");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -cp .;asm-9.8.jar;asm-tree-9.8.jar ClassMethodCloner \\");
        System.out.println("      --classNameToModify FibonacciCalculator \\");
        System.out.println("      --delegateclass FibonacciCalculatorDelegate \\");
        System.out.println("      --method fib");
    }

    public void cloneMethod(String classNameToModify,
                            String delegateClassName,
                            String methodName) throws IOException {

        // Turn FQN into path: com.example.Foo -> com/example/Foo.class
        Path basePath = Path.of(classNameToModify.replace('.', '/') + ".class");
        Path delegatePath = Path.of(delegateClassName.replace('.', '/') + ".class");

        if (!Files.exists(basePath)) {
            throw new IllegalStateException("Base class file not found: " + basePath);
        }
        if (!Files.exists(delegatePath)) {
            throw new IllegalStateException("Delegate class file not found: " + delegatePath);
        }

        System.out.println(">>> Base class:     " + classNameToModify + "  (" + basePath + ")");
        System.out.println(">>> Delegate class: " + delegateClassName + "  (" + delegatePath + ")");
        System.out.println(">>> Method to copy: " + methodName);

        byte[] baseBytes = Files.readAllBytes(basePath);
        byte[] delegateBytes = Files.readAllBytes(delegatePath);

        // Parse both classes into ClassNode
        ClassNode baseNode = new ClassNode();
        new ClassReader(baseBytes).accept(baseNode, 0);

        ClassNode delegateNode = new ClassNode();
        new ClassReader(delegateBytes).accept(delegateNode, 0);

        // Internal names, e.g. "Base" or "com/example/Base"
        String baseInternalName = baseNode.name;          // ***
        String delegateInternalName = delegateNode.name;  // ***

        // Find method in delegate
        MethodNode sourceMethod = findMethodByName(delegateNode, methodName);
        if (sourceMethod == null) {
            throw new IllegalStateException(
                    "No method named '" + methodName + "' found in delegate: " + delegateClassName);
        }

        System.out.println("  [FOUND] Delegate method: " + formatSig(sourceMethod));

        // *** Create a remapped copy where all delegate-owner references become base-owner
        MethodNode remappedMethod = copyAndRemapOwner(sourceMethod, delegateInternalName, baseInternalName);

        // Look for matching method (name + descriptor) in base
        MethodNode existing = findMethodByNameAndDesc(baseNode, remappedMethod.name, remappedMethod.desc);

        if (existing != null) {
            System.out.println("  [INFO] Base already has method with same name+desc: " +
                    formatSig(existing));
            System.out.println("  [ACTION] Replacing base method body with delegate method body.");
            replaceMethodBody(existing, remappedMethod);
        } else {
            System.out.println("  [ACTION] Adding new method to base: " + formatSig(remappedMethod));
            baseNode.methods.add(remappedMethod);
        }

        // Write backup
        backupOnce(basePath);

        // Write modified class (recompute frames & maxs)
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        baseNode.accept(cw);
        byte[] modifiedBytes = cw.toByteArray();
        Files.write(basePath, modifiedBytes);

        System.out.println("SUCCESS: Updated " + basePath.getFileName() +
                " with method '" + methodName + "'.");
    }

    private static MethodNode findMethodByName(ClassNode node, String name) {
        @SuppressWarnings("unchecked")
        List<MethodNode> methods = node.methods;
        MethodNode candidate = null;

        for (MethodNode m : methods) {
            if (!m.name.equals(name)) continue;
            // Skip synthetic/bridge
            if ((m.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
            if ((m.access & Opcodes.ACC_BRIDGE) != 0) continue;

            if (candidate != null) {
                System.out.println("  [WARN] Multiple methods named '" + name + "' in delegate; " +
                        "using the first one: " + formatSig(candidate));
                return candidate;
            }
            candidate = m;
        }
        return candidate;
    }

    private static MethodNode findMethodByNameAndDesc(ClassNode node, String name, String desc) {
        @SuppressWarnings("unchecked")
        List<MethodNode> methods = node.methods;
        for (MethodNode m : methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }

    private static void replaceMethodBody(MethodNode target, MethodNode source) {
        target.instructions = source.instructions;
        target.tryCatchBlocks = source.tryCatchBlocks;
        target.localVariables = source.localVariables;
        target.maxStack = source.maxStack;
        target.maxLocals = source.maxLocals;
        // access/name/desc/signature/exceptions we leave as they are in the base method
    }

    /**
     * Create a deep copy of the source method and remap any owner references
     * from 'fromInternalName' to 'toInternalName'.
     */
    private static MethodNode copyAndRemapOwner(MethodNode source,
                                                String fromInternalName,
                                                String toInternalName) {

        MethodNode copy = new MethodNode(
                source.access,
                source.name,
                source.desc,
                source.signature,
                source.exceptions == null
                        ? null
                        : source.exceptions.toArray(new String[0])
        );

        // First, create a deep copy via accept
        source.accept(copy);

        // Then walk instructions and remap owners where needed
        for (org.objectweb.asm.tree.AbstractInsnNode insn = copy.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {

            if (insn instanceof FieldInsnNode fieldInsn) {
                if (fieldInsn.owner.equals(fromInternalName)) {
                    fieldInsn.owner = toInternalName;
                }
            } else if (insn instanceof MethodInsnNode methodInsn) {
                if (methodInsn.owner.equals(fromInternalName)) {
                    methodInsn.owner = toInternalName;
                }
            } else if (insn instanceof TypeInsnNode typeInsn) {
                // For NEW, CHECKCAST, INSTANCEOF, ANEWARRAY etc. if they refer to delegate
                if (typeInsn.desc.equals(fromInternalName)) {
                    typeInsn.desc = toInternalName;
                }
            }
        }

        // Optionally, also remap local variable types that refer to the delegate
        if (copy.localVariables != null) {
            for (Object o : copy.localVariables) {
                LocalVariableNode lv = (LocalVariableNode) o;
                if (lv.desc != null && lv.desc.contains(fromInternalName)) {
                    lv.desc = lv.desc.replace(fromInternalName, toInternalName);
                }
                if (lv.signature != null && lv.signature.contains(fromInternalName)) {
                    lv.signature = lv.signature.replace(fromInternalName, toInternalName);
                }
            }
        }

        return copy;
    }

    private static void backupOnce(Path classFile) throws IOException {
        Path backup = classFile.resolveSibling(classFile.getFileName().toString() + ".clone.backup");
        if (!Files.exists(backup)) {
            Files.copy(classFile, backup);
            System.out.println("Created backup: " + backup.getFileName());
        }
    }

    private static String formatSig(MethodNode m) {
        return m.name + " " + m.desc;
    }
}
