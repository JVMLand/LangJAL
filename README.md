<div align="center">
  <h1>JAL</h1>

<a href="https://javadoc.io/doc/tokyo.peya/langjal"><img src="https://javadoc.io/badge2/tokyo.peya/langjal/javadoc.svg?style=flat-square"></a>
</div>

**JAL (JVM Assembly Language)**  is a custom-designed assembly language for the Java Virtual Machine.  
It makes exploring the internals of the JVM not just possible, but genuinely fun.

---

## 🚀 What is JAL?

JAL is a fresh take on Java assembly — a spiritual successor to projects like **Jasmin**, but with modern features and
design.

Unlike Jasmin, which stopped development around 2022 and only supports outdated Java versions, **JAL supports modern JVM
features**, including automatic generation of **StackMapFrames**, which are mandatory from Java 1.6 and up. Jasmin users
may remember `VerifyError`s haunting their dreams — JAL eliminates them.

### Why JAL is cool:

- **Named local variables**  
  Instead of remembering slot numbers, you can name your variables:
  ```
  istore 0 [->example]  
  iload example
  ```
- **Structured exception handling with labels**  
  Try-catch-finally blocks are declared with jump labels:
  ```
  tryStart: [~tryEnd, java/lang/Exception: catchStart, java/lang/Error: catchStart2 ->finallyStart]  
  tryEnd:  
    
  catchStart:  
  catchStart2:  
  finallyStart:
   ```

- **Readable member references**  
  Method and field calls separate class and member names for clarity:
  ```
  invokevirtual java/io/PrintStream->println(Ljava/lang/String;)V
  ```

### Sample: HelloWorld

```java
public class HelloWorld {
  public static main([Ljava/lang/String;)V {
    // Print "Hello, World!"
    getstatic java/lang/System->out:Ljava/io/PrintStream;
    ldc "Hello, World!"
    invokevirtual java/io/PrintStream->println(Ljava/lang/String;)V
    
    // Return from main
    return
  }
}
```

---

## 🧠 StackMapFrame? Automatically Done.

One of the biggest pain points in JVM bytecode writing is StackMapFrame management.  
JAL's compiler **calculates and inserts StackMapFrames automatically** — no more manual frame declaration, no more
hair-pulling `VerifyError`s.

---

## 📦 Installation

JAL is available as
a [Javasm IntelliJ plugin](https://plugins.jetbrains.com/plugin/27944-javasm), [jal-gradle-plugin](https://github.com/JVMLand/jal-gradle-plugin)
or [JAL CLI Compiler](https://github.com/JVMLand/LangJAL/releases)

To install the JAL CLI Compiler, download the latest release from
the [releases page](https://github.com/JVMLand/LangJAL/releases)
and decompress it to a directory of your choice.
To use the JAL CLI Compiler, add the `bin` directory to your system's PATH environment variable.

---

## 🛠️ JAL CLI Compiler Usage

To compile JAL files, use the JAL CLI compiler:

```bash
$ jalc MyProgram.jal
```

Or specify the jal files directly:

```bash
$ jalc MyJALFiles
```

### Making .jar file

To create a `.jar` file from your compiled JAL files, use the `jar` command:

```bash
$ jalc MyProgram.jal --output /path/to/MyProgram.jar
$ jalc MyJALFiles --output /path/to/MyJALFiles.jar
```

#### Customizing output directory

You can specify the output directory for the compiled classes using the `--output` option:

```bash
$ jalc MyProgram.jal --output /path/to/output/directory
$ jalc MyJALFiles --output /path/to/output/directory
```

---

## 🔌 Javasm IntelliJ Plugin Features

Javasm supercharges your JAL development inside IntelliJ IDEA
with [Javasm](https://plugins.jetbrains.com/plugin/27944-javasm) plugin:

- ✅ **Instruction name completion**  
  Start typing and get autocompletion for all instructions.

- 📄 **Hover documentation**  
  Hover over instructions to see inline documentation.

- 🌀 **Label navigation**  
  Ctrl+Click a label to jump to its declaration.

- 🐞 **Debugger integration**  
  Full integration with IntelliJ's standard JVM debugger (JDWP).  
  Breakpoints, step-over, step-into — all supported.

- 📊 **Frame and Stack Viewer**   
  A custom tool window that shows:
    - Stack state at the selected instruction
    - Local variable states
    - Visualises the stack during live debugging sessions

---

## 📄 License

MIT License. See the [LICENCE](./LICENCE) file for details.

---

❤️ A project by someone who enjoys poking around the JVM bytecode guts.
