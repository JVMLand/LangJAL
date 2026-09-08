package tokyo.peya.langjal.compiler.member;

import lombok.Getter;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.*;
import tokyo.peya.langjal.analyser.MethodAnalyser;
import tokyo.peya.langjal.analyser.MethodAnalysisResult;
import tokyo.peya.langjal.analyser.StackFrameMapCreator;
import tokyo.peya.langjal.analyser.StackFrameMapEntry;
import tokyo.peya.langjal.compiler.CompileSettings;
import tokyo.peya.langjal.compiler.FileEvaluatingReporter;
import tokyo.peya.langjal.compiler.JALParser;
import tokyo.peya.langjal.compiler.exceptions.IllegalValueException;
import tokyo.peya.langjal.compiler.jvm.EOpcodes;
import tokyo.peya.langjal.compiler.jvm.MethodDescriptor;
import tokyo.peya.langjal.compiler.jvm.PrimitiveTypes;
import tokyo.peya.langjal.compiler.jvm.TypeDescriptor;
import tokyo.peya.langjal.compiler.utils.EvaluatorCommons;

/**
 * Compiles a JAL method definition into JVM bytecode using ASM.
 * Handles parsing, instruction evaluation, local variable management, and try-catch directives.
 */
@Getter
public class JALMethodCompiler {
    /**
     * Reporter for compilation messages and errors.
     */
    private final FileEvaluatingReporter context;
    /**
     * The class node representing the owner class.
     */
    private final ClassNode clazz;
    /**
     * Compilation flags controlling the compilation process.
     */
    private final int compileFlags;

    /**
     * The ASM method node being compiled.
     */
    private final MethodNode method;

    /**
     * Holder for instructions in this method.
     */
    private final InstructionsHolder instructions;
    /**
     * Holder for labels in this method.
     */
    private final LabelsHolder labels;
    /**
     * Holder for local variables in this method.
     */
    private final LocalVariablesHolder locals;
    /**
     * Holder for try-catch directives in this method.
     */
    private final TryCatchDirectivesHolder tryCatchDirectives;

    /**
     * Constructs a JALMethodCompiler for the given class and reporter.
     *
     * @param reporter     The reporter for compilation messages.
     * @param cn           The class node.
     * @param compileFlags Compilation flags.
     */
    public JALMethodCompiler(@NotNull FileEvaluatingReporter reporter, @NotNull ClassNode cn,
                             @MagicConstant(valuesFromClass = CompileSettings.class) int compileFlags) {
        this.context = reporter;
        this.clazz = cn;
        this.compileFlags = compileFlags;
        this.method = new MethodNode();

        this.labels = new LabelsHolder();
        this.instructions = new InstructionsHolder(cn, this.method, this.labels);
        this.locals = new LocalVariablesHolder(this.context, this.labels);
        this.tryCatchDirectives = new TryCatchDirectivesHolder(this.context);
    }

    private static boolean shouldAppendReturnOnLast(InstructionInfo instruction) {
        return switch (instruction.opcode()) {
            case EOpcodes.IRETURN, EOpcodes.LRETURN, EOpcodes.FRETURN,
                 EOpcodes.DRETURN, EOpcodes.ARETURN, EOpcodes.RETURN,
                 EOpcodes.ATHROW, EOpcodes.GOTO -> false; // これらの命令はRETURNを追加しない
            default -> true; // 他の命令が最後の場合はRETURNを追加する
        };
    }

    private static int asAccess(JALParser.AccModMethodContext methodNode) {
        int accessor = EvaluatorCommons.asAccessLevel(methodNode.accessLevel());
        for (JALParser.AccAttrMethodContext ctxt : methodNode.accAttrMethod()) {
            if (ctxt.KWD_ACC_ATTR_STATIC() != null)
                accessor |= EOpcodes.ACC_STATIC;
            else if (ctxt.KWD_ACC_ATTR_FINAL() != null)
                accessor |= EOpcodes.ACC_FINAL;
            else if (ctxt.KWD_ACC_ATTR_SYNCHRONIZED() != null)
                accessor |= EOpcodes.ACC_SYNCHRONIZED;
            else if (ctxt.KWD_ACC_ATTR_BRIDGE() != null)
                accessor |= EOpcodes.ACC_BRIDGE;
            else if (ctxt.KWD_ACC_ATTR_VARARGS() != null)
                accessor |= EOpcodes.ACC_VARARGS;
            else if (ctxt.KWD_ACC_ATTR_NATIVE() != null)
                accessor |= EOpcodes.ACC_NATIVE;
            else if (ctxt.KWD_ACC_ATTR_ABSTRACT() != null)
                accessor |= EOpcodes.ACC_ABSTRACT;
            else if (ctxt.KWD_ACC_ATTR_STRICTFP() != null)
                accessor |= EOpcodes.ACC_STRICT;
            else if (ctxt.KWD_ACC_ATTR_SYNTHETIC() != null)
                accessor |= EOpcodes.ACC_SYNTHETIC;
        }

        return accessor;
    }

    /**
     * Evaluates and compiles the given method definition context.
     *
     * @param method The method definition context.
     */
    public void evaluateMethod(@NotNull JALParser.MethodDefinitionContext method) {
        this.clazz.methods.add(this.method);

        this.evaluateMethodMetadata(method);
        if ((this.method.access & (EOpcodes.ACC_ABSTRACT | EOpcodes.ACC_NATIVE)) != 0) {
            if (!method.methodBody().instructionSet().isEmpty())
                throw new IllegalValueException(
                        "Abstract or native method must have an empty body.",
                        method.methodBody()
                );

            this.method.visitEnd();
            return;
        }

        this.evaluateMethodParameters(method);
        this.evaluateMethodBody(method.methodBody());
        if ((this.compileFlags & CompileSettings.COMPUTE_STACK_FRAME_MAP) != 0)
            this.addStackMapTable();
    }

    /**
     * Analyses the method for stack frames and other metadata.
     *
     * @return The method analysis result.
     */
    public MethodAnalysisResult analyseMethod() {
        MethodAnalyser analyser = new MethodAnalyser(
                this.context,
                this.clazz,
                this.method,
                this.instructions,
                this.labels,
                this.locals
        );
        return analyser.analyse();
    }

    private void addStackMapTable() {
        // 各命令セットを解析して，スタックフレームを作成する。
        MethodAnalysisResult analysisResult = this.analyseMethod();

        StackFrameMapCreator mapCreator = new StackFrameMapCreator(
                this.context,
                this.method
        );
        mapCreator.updateFrames(analysisResult.propagations());
        StackFrameMapEntry[] mapEntries = mapCreator.createStackFrameMap();
        // スタックマップテーブルを追加
        this.method.visitMaxs(
                analysisResult.maxStack(),
                analysisResult.maxLocals()
        );

        for (StackFrameMapEntry entry : mapEntries) {
            LabelInfo atLabel = entry.label();
            FrameNode frameNode = entry.toASMFrameNode();
            InstructionInfo instruction = this.instructions.getInstruction(atLabel.instructionIndex());
            if (instruction == null) {
                this.context.postError("No instruction found for label: " + atLabel.name());
                continue;
            }

            AbstractInsnNode node = instruction.insn();
            this.method.instructions.insertBefore(node, frameNode);
        }
    }

    private void evaluateMethodParameters(@NotNull JALParser.MethodDefinitionContext method) {
        JALParser.MethodDescriptorContext desc = method.methodDescriptor();
        MethodDescriptor descriptor = MethodDescriptor.parse(desc.getText());
        TypeDescriptor[] parameters = descriptor.getParameterTypes();
        int accessor = asAccess(method.accModMethod());

        int currentIndex = 0;
        boolean isInstanceMethod = (accessor & EOpcodes.ACC_STATIC) == 0;
        if (isInstanceMethod) {
            // インスタンスメソッドの場合は，this パラメータを追加
            String thisParamName = "this";
            TypeDescriptor thisParamType = TypeDescriptor.className(this.clazz.name);
            // パラメータをローカル変数として登録
            this.locals.registerParameter(thisParamName, thisParamType, currentIndex++);
        }

        for (int i = 0; i < parameters.length; i++) {
            TypeDescriptor paramType = parameters[i];
            String paramName = String.format("arg%05d", i);
            // パラメータをローカル変数として登録
            if (paramType.getBaseType().getCategory() == 2) {
                this.locals.registerParameter(paramName, paramType, currentIndex++);
                currentIndex++; // カテゴリ２は ２スロット使うため，インデックスを進める
            } else
                this.locals.registerParameter(paramName, paramType, currentIndex++);
        }
    }

    private void finaliseMethod() {
        this.instructions.finaliseInstructions(this.compileFlags);
        this.tryCatchDirectives.finaliseTryCatchDirectives(this.method);
        this.labels.finalise(this.method);
        this.locals.evaluateLocals(this.method);
    }

    private void evaluateMethodMetadata(@NotNull JALParser.MethodDefinitionContext method) {
        String desc = method.methodDescriptor().getText();
        String name = method.methodName().getText();
        int access = asAccess(method.accModMethod());

        this.method.name = name;
        this.method.desc = desc;
        this.method.access = access;
    }

    private void evaluateLabels(@NotNull JALParser.MethodBodyContext body) {
        boolean globalStartUpdated = false;
        int instructionCount = 0;
        for (JALParser.InstructionSetContext bodyItem : body.instructionSet()) {
            if (bodyItem.label() != null) {
                // ラベルを登録
                LabelInfo label = this.labels.register(bodyItem.label().labelName(), instructionCount);
                // グローバルスタートになり得る場合は，入れ替える。
                if (instructionCount == 0 && !globalStartUpdated) {
                    this.labels.setGlobalStart(label);
                    globalStartUpdated = true;
                }
            }

            instructionCount += bodyItem.instruction().size();
        }

        this.labels.registerGlobalStart(this.method);
    }

    private void evaluateMethodBody(@NotNull JALParser.MethodBodyContext body) {
        this.context.postInfo("Evaluating method body for " + this.method.name + this.method.desc);

        this.method.visitCode();
        this.evaluateLabels(body);
        this.evaluateTryCatchDirectives(body);
        this.evaluateInstructions(body);
        this.finaliseMethod();
        this.method.visitEnd();
    }

    private void evaluateTryCatchDirectives(JALParser.MethodBodyContext body) {
        for (JALParser.InstructionSetContext bodyItem : body.instructionSet()) {
            if (bodyItem.tryCatchDirective() == null)
                continue;  // トライキャッチディレクティブがない場合はスキップ

            JALParser.LabelNameContext endLabel = bodyItem.tryCatchDirective().labelName();
            if (endLabel == null)
                throw new IllegalArgumentException("Try-catch directive must have an end label.");

            LabelInfo tryStartLabel = this.labels.resolve(bodyItem.label().labelName());
            LabelInfo tryEndLabel = this.labels.resolve(endLabel);

            JALParser.TryCatchDirectiveContext directiveContext = bodyItem.tryCatchDirective();
            for (JALParser.TryCatchDirectiveEntryContext entry : directiveContext.tryCatchDirectiveEntry())
                this.evaluateTryCatchDirective(
                        tryStartLabel,
                        tryEndLabel,
                        entry
                );
        }
    }

    private void evaluateTryCatchDirective(@NotNull LabelInfo tryBlockStartLabel,
                                           @NotNull LabelInfo tryBlockEndLabel,
                                           @NotNull JALParser.TryCatchDirectiveEntryContext entry) {
        JALParser.CatchDirectiveContext catchDirective = entry.catchDirective();
        JALParser.FinallyDirectiveContext finallyDirective = entry.finallyDirective();

        if (catchDirective == null && finallyDirective == null)
            throw new IllegalValueException(
                    "Try-catch directive must have at least one catch or finally block.",
                    entry
            );
        // finally は, catchDirective 内に指定される場合がある
        if (finallyDirective == null)
            finallyDirective = catchDirective.finallyDirective();

        TypeDescriptor exceptionType = null;
        if (catchDirective != null) {
            JALParser.FullQualifiedClassNameContext exceptionTypeName = catchDirective.fullQualifiedClassName();
            if (exceptionTypeName == null)
                throw new IllegalValueException("Catch directive must have an exception type.", entry);
            exceptionType = TypeDescriptor.className(exceptionTypeName.getText());
        }

        // 各ラベルを解決
        JALParser.LabelNameContext catchLabel = catchDirective == null ? null : catchDirective.labelName();
        JALParser.LabelNameContext finallyLabel = finallyDirective == null ? null : finallyDirective.labelName();
        LabelInfo catchBlockLabel = null;
        LabelInfo finallyBlockLabel = null;
        if (catchLabel != null)
            catchBlockLabel = this.labels.resolve(catchLabel);
        if (finallyLabel != null)
            finallyBlockLabel = this.labels.resolve(finallyLabel);

        // トライキャッチディレクティブを登録
        this.tryCatchDirectives.addTryCatchDirective(
                tryBlockStartLabel,
                tryBlockEndLabel,
                catchBlockLabel,
                exceptionType,
                finallyBlockLabel
        );
    }

    private void evaluateInstructions(@NotNull JALParser.MethodBodyContext body) {
        // 各命令を順に評価していく
        // 命令に割り当てるラベル。１命令のみが割り当てられる。
        LabelInfo labelAssignation = this.labels.getGlobalStart();
        for (JALParser.InstructionSetContext bodyItem : body.instructionSet()) {
            if (bodyItem.label() != null)
                this.labels.setCurrentLabel(
                        labelAssignation = this.labels.resolve(bodyItem.label().labelName())
                );

            for (JALParser.InstructionContext instruction : bodyItem.instruction()) {
                // 命令を評価して，必要に応じてラベルを設定
                EvaluatedInstruction evaluated = JALInstructionEvaluator.evaluateInstruction(
                        this,
                        instruction
                );
                if (evaluated == null)
                    continue;

                this.instructions.addInstruction(evaluated, labelAssignation, instruction.start.getLine());
                labelAssignation = null;  // 次の命令セットのためにラベルをクリア
            }
        }

        this.labels.updateGlobalEndInstructionIndex(this.instructions.getSize());
        if (this.instructions.isEmpty() || shouldAppendReturnOnLast(this.instructions.getLastInstruction())) {
            MethodDescriptor descriptor = MethodDescriptor.parse(this.method.desc);
            if (descriptor.getReturnType().getBaseType() != PrimitiveTypes.VOID)
                throw new IllegalValueException(
                        "Non-void method must end with an explicit return instruction.",
                        body
                );

            // 最後にRETURNがない場合は、デフォルトでRETURNを追加
            this.instructions.importInstruction(new InsnNode(EOpcodes.RETURN), labelAssignation, -1);
            this.labels.updateGlobalEndInstructionIndex(this.instructions.getSize());
        }
    }
}
