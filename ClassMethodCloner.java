import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
        System.out.println("  java -cp .;asm-9.7.jar;asm-tree-9.7.jar ClassMethodCloner \\");
        System.out.println("      --classNameToModify <FullyQualifiedClassName> \\");
        System.out.println("      --delegateclass <FullyQualifiedDelegateClassName> \\");
        System.out.println("      --method <methodName>");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -cp .;asm-9.7.jar;asm-tree-9.7.jar ClassMethodCloner \\");
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

        // Find method in delegate
        MethodNode sourceMethod = findMethodByName(delegateNode, methodName);
        if (sourceMethod == null) {
            throw new IllegalStateException(
                    "No method named '" + methodName + "' found in delegate: " + delegateClassName);
        }

        System.out.println("  [FOUND] Delegate method: " + formatSig(sourceMethod));

        // Look for matching method (name + descriptor) in base
        MethodNode existing = findMethodByNameAndDesc(baseNode, sourceMethod.name, sourceMethod.desc);

        if (existing != null) {
            System.out.println("  [INFO] Base already has method with same name+desc: " +
                    formatSig(existing));
            System.out.println("  [ACTION] Replacing base method body with delegate method body.");
            replaceMethodBody(existing, sourceMethod);
        } else {
            System.out.println("  [ACTION] Adding new method to base: " + formatSig(sourceMethod));
            MethodNode cloned = cloneMethodNode(sourceMethod);
            baseNode.methods.add(cloned);
        }

        // Write backup
        backupOnce(basePath);

        // Write modified class
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
                // If there are multiple overloads, this is ambiguous.
                // For a simple case like 'fib(int)', you usually only have one.
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

    private static MethodNode cloneMethodNode(MethodNode source) {
        MethodNode clone = new MethodNode(
                source.access,
                source.name,
                source.desc,
                source.signature,
                source.exceptions == null
                        ? null
                        : source.exceptions.toArray(new String[0])
        );
        // Copy the body via accept
        source.accept(clone);
        return clone;
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
