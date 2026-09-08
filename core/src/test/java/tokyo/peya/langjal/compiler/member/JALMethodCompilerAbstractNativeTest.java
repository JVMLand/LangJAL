package tokyo.peya.langjal.compiler.member;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import tokyo.peya.langjal.compiler.CompileSettings;
import tokyo.peya.langjal.compiler.JALClassCompiler;
import tokyo.peya.langjal.compiler.JALFileCompiler;
import tokyo.peya.langjal.compiler.exceptions.IllegalValueException;
import tokyo.peya.langjal.compiler.instructions.utils.TestCompileReporter;
import tokyo.peya.langjal.compiler.jvm.EOpcodes;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JALMethodCompilerAbstractNativeTest {
    private static Stream<Arguments> declarations() {
        return Stream.of("abstract", "native", "static native").flatMap(modifier ->
                Stream.of("V", "I", "J", "Ljava/lang/String;").flatMap(returnType ->
                        Stream.of(CompileSettings.NONE, CompileSettings.REQUIRED_ONLY, CompileSettings.FULL)
                                .map(settings -> Arguments.of(modifier, returnType, settings))));
    }

    private static JALClassCompiler compile(String modifier, String returnType, String body, int settings) {
        return JALFileCompiler.compileOnly("""
                public abstract class Test {
                    public %s demo(JLjava/lang/String;)%s {
                        %s
                    }
                }
                """.formatted(modifier, returnType, body), new TestCompileReporter(), settings);
    }

    @ParameterizedTest
    @MethodSource("declarations")
    void emptyDeclarationsLoadWithoutCode(String modifier, String returnType, int settings) throws Exception {
        JALClassCompiler compiler = compile(modifier, returnType, "// No implementation", settings);
        ClassNode clazz = compiler.getCompiledClass();
        assertEquals(1, clazz.methods.size());
        MethodNode method = clazz.methods.getFirst();
        for (int i = 0; i < 2; i++) {
            var analysis = compiler.getMethodCompilers().getFirst().analyseMethod();
            assertSame(method, analysis.node());
            assertEquals(0, analysis.propagations().length);
            assertEquals(0, analysis.instructionAnalysisResults().length);
            assertEquals(0, analysis.maxStack());
            assertEquals(0, analysis.maxLocals());
        }
        assertEquals(0, method.instructions.size());
        assertTrue(method.tryCatchBlocks == null || method.tryCatchBlocks.isEmpty());
        assertTrue(method.localVariables == null || method.localVariables.isEmpty());

        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        byte[] bytecode = writer.toByteArray();
        Class<?> loaded = new ClassLoader() {
            Class<?> load() {
                return defineClass(null, bytecode, 0, bytecode.length);
            }
        }.load();

        var reflected = loaded.getDeclaredMethod("demo", long.class, String.class);
        int expectedAccess = EOpcodes.ACC_PUBLIC
                | (modifier.equals("abstract") ? EOpcodes.ACC_ABSTRACT : EOpcodes.ACC_NATIVE)
                | (modifier.startsWith("static") ? EOpcodes.ACC_STATIC : 0);
        assertEquals(expectedAccess, reflected.getModifiers());
        assertEquals(switch (returnType) {
            case "V" -> void.class;
            case "I" -> int.class;
            case "J" -> long.class;
            default -> String.class;
        }, reflected.getReturnType());
    }

    @ParameterizedTest
    @MethodSource("declarations")
    void rejectsActualBodies(String modifier, String returnType, int settings) {
        for (String body : new String[]{"nop", "return", "iconst_1\nireturn",
                "Start: [~End, java/lang/Exception: Handler]\nnop\nEnd: return\nHandler: athrow"}) {
            IllegalValueException error = assertThrows(IllegalValueException.class,
                    () -> compile(modifier, returnType, body, settings));
            assertEquals("Abstract or native method must have an empty body.", error.getDetailedMessage());
            assertTrue(error.getLine() > 0);
        }
    }
}
