import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parse-only Java syntax check; Android SDK symbols are intentionally not resolved. */
public final class JavaSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler module is unavailable");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            List<Path> paths = new ArrayList<>();
            for (String arg : args) paths.add(Path.of(arg));
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(paths);
            JavacTask task = (JavacTask) compiler.getTask(
                    null, manager, diagnostics, List.of("-proc:none"), null, units);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            int count = 0;
            for (CompilationUnitTree ignored : parsed) count++;

            boolean failed = false;
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    failed = true;
                    System.err.printf("%s:%d:%d: %s%n",
                            diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().getName(),
                            diagnostic.getLineNumber(),
                            diagnostic.getColumnNumber(),
                            diagnostic.getMessage(null));
                }
            }
            if (failed) System.exit(1);
            System.out.println("PASS: parsed " + count + " Java source files");
        }
    }
}
