package tokyo.peya.langjal.analyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import tokyo.peya.langjal.compiler.CompileSettings;
import tokyo.peya.langjal.compiler.JALFileCompiler;
import tokyo.peya.langjal.compiler.instructions.utils.TestCompileReporter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InitialLoopStackMapVerificationTest {
    @Test
    void countdownAtOffsetZeroVerifiesAndRuns() throws Exception {
        ClassNode compiled = compile("""
                Loop:
                    iload_0
                    ifle Done
                    getstatic java/lang/System->out:Ljava/io/PrintStream;
                    iload_0
                    invokevirtual java/io/PrintStream->println(I)V
                    iinc 0 -1
                    goto Loop
                Done:
                    return
                """);
        Class<?> clazz = define(compiled);
        var countdown = clazz.getDeclaredMethod("countdown", int.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            countdown.invoke(null, 3);
            countdown.invoke(null, 0);
            countdown.invoke(null, -1);
        } finally {
            System.setOut(previous);
        }
        assertEquals("3\n2\n1\n", output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
        assertEquals(1, entryFrameCount(compiled));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Loop: goto Loop",
            "Loop: iload_0 ifgt Loop return",
            "Loop: aconst_null astore_0 goto Loop"
    })
    void selfLoopAtOffsetZeroVerifies(String body) throws Exception {
        ClassNode compiled = compile(body);
        // Resolving methods forces JVM verification without executing an infinite loop.
        assertNotNull(define(compiled).getDeclaredMethod("countdown", int.class));
        assertEquals(1, entryFrameCount(compiled));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "return",
            "iload_0 ifle Done iinc 0 -1 Done: return"
    })
    void untargetedEntryKeepsImplicitFrame(String body) throws Exception {
        ClassNode compiled = compile(body);
        define(compiled).getDeclaredMethod("countdown", int.class).invoke(null, 3);
        assertEquals(0, entryFrameCount(compiled));
    }

    private static ClassNode compile(String body) throws Exception {
        return JALFileCompiler.compileOnly(
                "public class InitialLoop { public static countdown(I)V { " + body + " } }",
                new TestCompileReporter(),
                CompileSettings.REQUIRED_ONLY
        ).getCompiledClass();
    }

    private static Class<?> define(ClassNode compiled) {
        // Preserve the compiler's frames and max sizes; ASM must not repair them.
        ClassWriter writer = new ClassWriter(0);
        compiled.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(InitialLoopStackMapVerificationTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
    }

    private static int entryFrameCount(ClassNode compiled) {
        MethodNode method = compiled.methods.getFirst();
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() >= 0)
                break;
            if (instruction instanceof FrameNode)
                count++;
        }
        return count;
    }
}
