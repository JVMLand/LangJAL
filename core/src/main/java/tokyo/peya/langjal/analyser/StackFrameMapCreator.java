package tokyo.peya.langjal.analyser;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import tokyo.peya.langjal.analyser.stack.LocalStackElement;
import tokyo.peya.langjal.analyser.stack.StackElement;
import tokyo.peya.langjal.compiler.FileEvaluatingReporter;
import tokyo.peya.langjal.compiler.member.LabelInfo;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates a map of stack frames for a given method based on frame propagations.
 * This class is responsible for merging frames, computing the next frame, and
 * generating the final stack frame map entries.
 * <p>
 * The stack frame map is used to optimize the bytecode by providing information
 * about how the stack and local variables change at each instruction.
 */
public class StackFrameMapCreator {
    private final FileEvaluatingReporter context;
    private final MethodNode method;

    private final Map<LabelInfo, InstructionSetFrame> frames;

    /**
     * Constructor for StackFrameMapCreator.
     * Initializes the map of stack frames for a given method.
     *
     * @param context the context for reporting evaluation messages
     * @param method  the method for which the stack frame map is created
     */
    public StackFrameMapCreator(@NotNull FileEvaluatingReporter context, @NotNull MethodNode method) {
        this.context = context;
        this.method = method;

        this.frames = new HashMap<>();
    }

    private static boolean isSameStack(@NotNull StackElement[] stack1, @NotNull StackElement[] stack2, int max) {
        // 先頭 max 要素まで比較。比較対象が足りなければ false。
        if (stack1.length < max || stack2.length < max)
            return false;

        for (int i = 0; i < max; i++)
            if (!stack1[i].equals(stack2[i]))
                return false;

        return true;
    }

    private static boolean isSameStack(@NotNull StackElement[] stack1, @NotNull StackElement[] stack2) {
        if (stack1.length != stack2.length)
            return false;
        return isSameStack(stack1, stack2, stack1.length);
    }

    private static InstructionSetFrame mergeFrames(@NotNull InstructionSetFrame frame1,
                                                   @NotNull InstructionSetFrame frame2) {
        StackElement[] mergedStack = StackElementUtils.mergeStack(
                frame1.label(),
                frame1.stack(),
                frame2.stack()
        );
        LocalStackElement[] mergedLocals = StackElementUtils.mergeLocals(frame1.locals(), frame2.locals());
        StackElementUtils.cleanUpLocals(mergedLocals);

        return new InstructionSetFrame(frame1.label(), mergedStack, mergedLocals);
    }

    /**
     * Updates the stack frames based on the provided frame propagations.
     * This method processes each propagation and updates the corresponding
     * stack frame in the map, merging frames when necessary.
     *
     * @param propagations the array of frame propagations containing the stack and local variables
     *                     for each instruction in the method
     */
    public void updateFrames(@NotNull FramePropagation[] propagations) {
        this.context.postInfo("Updating stack frames for method: " + this.method.name);
        for (FramePropagation propagation : propagations)
            this.updateFrame(propagation);
        this.context.postInfo("Finished updating stack frames for method: " + this.method.name);
    }

    /**
     * Updates a single stack frame based on the provided propagation.
     * This method creates a new frame and merges it with the existing frame if necessary.
     * If the frame does not exist, it is added to the map.
     *
     * @param propagation the frame propagation containing the stack and local variables for a specific instruction
     *                    in the method
     *                    <p>
     *                    This method is used internally by {@link #updateFrames(FramePropagation[])} to process each propagation.
     *                    </p>
     */
    public void updateFrame(@NotNull FramePropagation propagation) {
        LabelInfo label = propagation.receiver();  // StacKFrameMap はジャンプ先に貼っつけるため。

        InstructionSetFrame newFrame = new InstructionSetFrame(
                label,
                propagation.stack(),
                propagation.locals()
        );

        // 同じものがなかったら何もせずに，マップに入れる。
        if (!this.frames.containsKey(label)) {
            this.printFrame(newFrame);
            this.frames.put(label, newFrame);
            return;
        }

        this.context.postDebug("Merging frame at " + label.name() + " with existing frame.");

        // 既に同じものがあったら，スタックとローカル変数をマージする。
        InstructionSetFrame existingFrame = this.frames.get(label);
        this.context.postDebug("Existing frame: " + this.formatFrame(existingFrame));
        this.context.postDebug("Incoming frame: " + this.formatFrame(newFrame));
        InstructionSetFrame mergedFrame = mergeFrames(existingFrame, newFrame);

        this.context.postDebug("Merged frame at " + label.name() + ":");
        this.printFrame(mergedFrame);

        this.frames.put(label, mergedFrame);
    }

    /**
     * Creates a stack frame map for the method.
     * This method processes all the frames collected and computes the next frame
     * based on the changes in stack and local variables.
     * <p>
     * The resulting stack frame map is an array of {@link StackFrameMapEntry} that describes
     * how the stack and local variables change from one instruction to the next.
     * </p>
     *
     * @return an array of {@link StackFrameMapEntry} representing the stack frame map
     * for the method, or an empty array if there are no frames to process.
     * <p>
     * The initial frame is implicit unless bytecode offset zero is a branch target.
     */
    public StackFrameMapEntry[] createStackFrameMap() {
        this.context.postInfo("Creating stack frame map for method: " + this.method.name);
        // フレームをラベルのインデックス順にする
        List<InstructionSetFrame> frames =
                this.frames.values().stream()
                        .sorted(Comparator.comparingInt(frame -> frame.label().instructionIndex()))
                        .toList();
        this.printFrames(frames);
        boolean emitInitialFrame = !frames.isEmpty() && this.isEntryBranchTarget();
        if (frames.size() < 2 && !emitInitialFrame) {
            this.context.postInfo("No need to compute frames, StackFrameMap will be empty.");
            return new StackFrameMapEntry[0];
        }

        int initialFrameCount = emitInitialFrame ? 1 : 0;
        StackFrameMapEntry[] stackFrameMap = new StackFrameMapEntry[frames.size() - 1 + initialFrameCount];
        if (emitInitialFrame) {
            InstructionSetFrame first = frames.getFirst();
            // Merged entry locals may differ from the descriptor's implicit frame.
            stackFrameMap[0] = StackFrameMapEntry.full(first, first, first.stack(), first.locals());
        }
        // 各フレームの次のフレームを計算する
        for (int i = 0; i < frames.size() - 1; i++) {
            InstructionSetFrame previous = frames.get(i);
            InstructionSetFrame next = frames.get(i + 1);
            StackFrameMapEntry nextFrame = computeNextFrame(previous, next);
            this.context.postDebug("Computed StackMap frame transition " + previous.label().name() +
                    " -> " + next.label().name() + ": " + nextFrame);
            stackFrameMap[i + initialFrameCount] = nextFrame;
        }

        this.context.postInfo("Stack frame map created with " + stackFrameMap.length + " entries.");
        return stackFrameMap;
    }

    private boolean isEntryBranchTarget() {
        Set<LabelNode> entryLabels = new HashSet<>();
        for (AbstractInsnNode instruction : this.method.instructions) {
            if (instruction.getOpcode() >= 0)
                break;
            if (instruction instanceof LabelNode label)
                entryLabels.add(label);
        }

        // Inspect bytecode: a single-block self-loop can share its propagation
        // with method entry, so propagation senders alone cannot identify it.
        for (AbstractInsnNode instruction : this.method.instructions) {
            if (instruction instanceof JumpInsnNode jump && entryLabels.contains(jump.label))
                return true;
            if (instruction instanceof TableSwitchInsnNode table &&
                    (entryLabels.contains(table.dflt) || table.labels.stream().anyMatch(entryLabels::contains)))
                return true;
            if (instruction instanceof LookupSwitchInsnNode lookup &&
                    (entryLabels.contains(lookup.dflt) || lookup.labels.stream().anyMatch(entryLabels::contains)))
                return true;
        }
        return false;
    }

    private StackFrameMapEntry computeNextFrame(@NotNull InstructionSetFrame previous,
                                                @NotNull InstructionSetFrame next) {
        StackElement[] previousStack = previous.stack();
        StackElement[] nextStack = next.stack();

        LocalStackElement[] previousLocals = previous.locals();
        LocalStackElement[] nextLocals = next.locals();

        // スタックとローカル変数が同じならば、同じエントリを返す。
        boolean isStackSame = isSameStack(previousStack, nextStack);
        boolean isLocalsSame = isSameStack(previousLocals, nextLocals);
        if (isStackSame && isLocalsSame)
            return StackFrameMapEntry.same(previous, next);

        if (isLocalsSame)  // スタックのみ変わった場合
        {
            // same_locals_1_stack_item かどうか？（スタックに１つのアイテムのみが存在するか）
            if (nextStack.length == 1) {
                StackElement stackItem = nextStack[0];
                return StackFrameMapEntry.sameLocals1StackItem(previous, next, stackItem);
            }
        }

        if (nextStack.length != 0) {
            // スタックが空ではない＆何らかの変更がある -> FULL フレーム
            return StackFrameMapEntry.full(previous, next, nextStack, nextLocals);
        }

        // スタックが空の場合は, ローカル変数について CHOP/APPEND が使える
        int chopCount = previousLocals.length - nextLocals.length;  // 末尾に TOP がないことは確認済み
        if (chopCount > 0) {
            // CHOP フレーム
            if (chopCount <= 3 && isSameStack(previousLocals, nextLocals, nextLocals.length))  // 最大で 3 個まで CHOP できる
            {
                LocalStackElement[] choppedLocals = new LocalStackElement[chopCount];
                System.arraycopy(
                        previousLocals,
                        previousLocals.length - chopCount,
                        choppedLocals,
                        0,
                        chopCount
                );
                return StackFrameMapEntry.chop(previous, next, choppedLocals);
            }
        } else if (chopCount < 0)  // APPEND フレーム
        {
            int appendCount = -chopCount;  // 負の値を正に変換
            if (appendCount <= 3 && isSameStack(previousLocals, nextLocals, previousLocals.length))  // 最大で 3 個まで APPEND できる
            {
                LocalStackElement[] appendedLocals = new LocalStackElement[appendCount];
                System.arraycopy(
                        nextLocals,
                        nextLocals.length - appendCount,
                        appendedLocals,
                        0,
                        appendCount
                );
                return StackFrameMapEntry.append(previous, next, appendedLocals);
            }
        }

        // どうしようもない場合は FULL フレーム
        return StackFrameMapEntry.full(previous, next, nextStack, nextLocals);
    }

    private void printFrames(List<InstructionSetFrame> frames) {
        this.context.postDebug("----- Stack Frames of " + this.method.name + " -----");
        if (this.frames.isEmpty()) {
            this.context.postDebug("No stack frames found.");
            return;
        }
        for (InstructionSetFrame frame : frames)
            this.printFrame(frame);
    }

    private void printFrame(@NotNull InstructionSetFrame frame) {
        this.context.postDebug(this.formatFrame(frame));
    }

    private @NotNull String formatFrame(@NotNull InstructionSetFrame frame) {
        return "--- Frame at " + this.method.name + ":" + frame.label().name() + " ---" +
                "Stack: " + StackElementUtils.stackToString(frame.stack())
                + ", Locals: " + StackElementUtils.stackToString(frame.locals());
    }
}
