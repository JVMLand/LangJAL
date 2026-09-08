package tokyo.peya.langjal.compiler.instructions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import tokyo.peya.langjal.compiler.CompileSettings;
import tokyo.peya.langjal.compiler.JALFileCompiler;
import tokyo.peya.langjal.compiler.instructions.utils.TestCompileReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructorInitializationExecutionTest {
    @ParameterizedTest
    @ValueSource(ints = {CompileSettings.REQUIRED_ONLY, CompileSettings.FULL})
    void storesOwnFieldAfterSuperConstructor(int settings) throws Exception {
        Class<?> clazz = compileAndLoad("""
                public class OwnFieldConstructor {
                    public value:I

                    public <init>(I)V {
                        aload_0
                        invokespecial java/lang/Object-><init>()V
                        aload_0
                        iload_1
                        putfield OwnFieldConstructor->value:I
                        return
                    }
                }
                """, settings);

        Object instance = clazz.getConstructor(int.class).newInstance(42);
        assertEquals(42, clazz.getField("value").getInt(instance));
    }

    @ParameterizedTest
    @ValueSource(ints = {CompileSettings.REQUIRED_ONLY, CompileSettings.FULL})
    void preservesNewObjectTypeAfterConstructor(int settings) throws Exception {
        Class<?> clazz = compileAndLoad("""
                public class NewObjectConstructor {
                    public static create()Ljava/lang/StringBuilder; {
                        new java/lang/StringBuilder
                        dup
                        invokespecial java/lang/StringBuilder-><init>()V
                        areturn
                    }
                }
                """, settings);

        Object instance = clazz.getMethod("create").invoke(null);
        assertEquals(StringBuilder.class, instance.getClass());
        assertEquals("", instance.toString());
    }

    private static Class<?> compileAndLoad(String source, int settings) throws Exception {
        var clazz = JALFileCompiler.compileOnly(source, new TestCompileReporter(), settings).getCompiledClass();
        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(ConstructorInitializationExecutionTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
    }
}
