import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ClassFieldCloner {

    public static void main(String[] args) {
        String classNameToModify = null;
        String delegateClassName = null;
        String fieldName = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--classNameToModify" -> {
                    if (i + 1 < args.length) classNameToModify = args[++i];
                }
                case "--delegateclass" -> {
                    if (i + 1 < args.length) delegateClassName = args[++i];
                }
                case "--field" -> {
                    if (i + 1 < args.length) fieldName = args[++i];
                }
                default -> {
                    // ignore unknown flags
                }
            }
        }

        if (classNameToModify == null || delegateClassName == null || fieldName == null) {
            System.err.println("ERROR: Missing required arguments.");
            printUsage();
            return;
        }

        try {
            new ClassFieldCloner().cloneField(classNameToModify, delegateClassName, fieldName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("ClassFieldCloner - Copy a field definition from delegate into base class");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp .;asm-9.8.jar;asm-tree-9.8.jar ClassFieldCloner \\");
        System.out.println("      --classNameToModify <FullyQualifiedClassName> \\");
        System.out.println("      --delegateclass <FullyQualifiedDelegateClassName> \\");
        System.out.println("      --field <fieldName>");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -cp .;asm-9.8.jar;asm-tree-9.8.jar ClassFieldCloner \\");
        System.out.println("      --classNameToModify Base \\");
        System.out.println("      --delegateclass BaseDelegate \\");
        System.out.println("      --field list");
    }

    public void cloneField(String classNameToModify,
                           String delegateClassName,
                           String fieldName) throws IOException {

        // com.example.Foo -> com/example/Foo.class
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
        System.out.println(">>> Field to copy:  " + fieldName);

        byte[] baseBytes = Files.readAllBytes(basePath);
        byte[] delegateBytes = Files.readAllBytes(delegatePath);

        // Parse both classes into ClassNode
        ClassNode baseNode = new ClassNode();
        new ClassReader(baseBytes).accept(baseNode, 0);

        ClassNode delegateNode = new ClassNode();
        new ClassReader(delegateBytes).accept(delegateNode, 0);

        // Find field in delegate
        FieldNode sourceField = findFieldByName(delegateNode, fieldName);
        if (sourceField == null) {
            throw new IllegalStateException(
                    "No field named '" + fieldName + "' found in delegate: " + delegateClassName);
        }

        System.out.println("  [FOUND] Delegate field: " + formatFieldSig(sourceField));

        // Check if base already has same field (name + descriptor)
        FieldNode existing = findFieldByNameAndDesc(baseNode, sourceField.name, sourceField.desc);
        if (existing != null) {
            System.out.println("  [INFO] Base already has field with same name+type: "
                    + formatFieldSig(existing));
            System.out.println("  [ACTION] Leaving base field unchanged.");
        } else {
            System.out.println("  [ACTION] Adding new field to base: " + formatFieldSig(sourceField));
            FieldNode cloned = cloneFieldNode(sourceField);
            @SuppressWarnings("unchecked")
            List<FieldNode> fields = (List<FieldNode>) (List<?>) baseNode.fields;
            fields.add(cloned);
        }

        // Write backup once
        backupOnce(basePath);

        // Write modified class
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        baseNode.accept(cw);
        byte[] modifiedBytes = cw.toByteArray();
        Files.write(basePath, modifiedBytes);

        System.out.println("SUCCESS: Updated " + basePath.getFileName() +
                " with field '" + fieldName + "'.");
    }

    private static FieldNode findFieldByName(ClassNode node, String name) {
        @SuppressWarnings("unchecked")
        List<FieldNode> fields = (List<FieldNode>) (List<?>) node.fields;
        FieldNode candidate = null;

        for (FieldNode f : fields) {
            if (!f.name.equals(name)) continue;

            if (candidate != null) {
                System.out.println("  [WARN] Multiple fields named '" + name +
                        "' in delegate; using the first one: " + formatFieldSig(candidate));
                return candidate;
            }
            candidate = f;
        }
        return candidate;
    }

    private static FieldNode findFieldByNameAndDesc(ClassNode node, String name, String desc) {
        @SuppressWarnings("unchecked")
        List<FieldNode> fields = (List<FieldNode>) (List<?>) node.fields;
        for (FieldNode f : fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) {
                return f;
            }
        }
        return null;
    }

    private static FieldNode cloneFieldNode(FieldNode source) {
        // For most cases, copying access, name, desc, signature, and value is enough.
        FieldNode clone = new FieldNode(
                source.access,
                source.name,
                source.desc,
                source.signature,
                source.value
        );
        // If you later need annotations or attributes, you can copy them here as well.
        return clone;
    }

    private static void backupOnce(Path classFile) throws IOException {
        Path backup = classFile.resolveSibling(classFile.getFileName().toString() + ".clone.backup");
        if (!Files.exists(backup)) {
            Files.copy(classFile, backup);
            System.out.println("Created backup: " + backup.getFileName());
        }
    }

    private static String formatFieldSig(FieldNode f) {
        return f.name + " " + f.desc;
    }
}
