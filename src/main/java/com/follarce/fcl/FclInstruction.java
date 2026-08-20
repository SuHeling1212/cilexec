package com.follarce.fcl;

import java.util.List;
import java.util.Objects;

/** Flat instructions produced by {@link FclCompiler}. */
public sealed interface FclInstruction permits FclInstruction.Assignment, FclInstruction.Link,
        FclInstruction.Evaluation, FclInstruction.Conditional, FclInstruction.Loop,
        FclInstruction.Break, FclInstruction.Continue, FclInstruction.Update, FclInstruction.Return,
        FclInstruction.FunctionDeclaration, FclInstruction.Import,
        FclInstruction.Include, FclInstruction.TryStart, FclInstruction.CatchEnter,
        FclInstruction.CatchEnd, FclInstruction.Jump {

    int line();

    record Assignment(int line, String variable, List<FclExpression> indices,
                      FclExpression value) implements FclInstruction {
        public Assignment {
            Objects.requireNonNull(variable, "variable");
            indices = List.copyOf(indices);
            Objects.requireNonNull(value, "value");
        }
    }

    /** Explicitly makes {@code target} follow the name {@code source}. */
    record Link(int line, String target, String source) implements FclInstruction {
        public Link {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
        }
    }

    record Evaluation(int line, FclExpression expression) implements FclInstruction {
        public Evaluation {
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Conditional(int line, FclExpression condition, int falseTarget,
                       int endTarget) implements FclInstruction {
        public Conditional {
            Objects.requireNonNull(condition, "condition");
        }
    }

    record Loop(int line, FclExpression condition, int bodyTarget,
                int endTarget) implements FclInstruction {
        public Loop {
            Objects.requireNonNull(condition, "condition");
        }
    }

    record Break(int line) implements FclInstruction {}

    record Continue(int line) implements FclInstruction {}

    /** Atomic language-level increment/decrement statement. */
    record Update(int line, String variable, List<FclExpression> indices, int delta)
            implements FclInstruction {
        public Update {
            Objects.requireNonNull(variable, "variable");
            indices = List.copyOf(indices);
            if (delta != 1 && delta != -1) throw new IllegalArgumentException("Update delta must be ±1");
        }
    }

    record Return(int line, FclExpression value, boolean implicit) implements FclInstruction {}

    record FunctionDeclaration(int line, String name, List<String> parameters,
                               int bodyTarget, int endTarget,
                               boolean publicBinding) implements FclInstruction {
        public FunctionDeclaration {
            Objects.requireNonNull(name, "name");
            parameters = List.copyOf(parameters);
        }
    }

    record Import(int line, String target, String alias,
                  boolean wildcard) implements FclInstruction {
        public Import {
            Objects.requireNonNull(target, "target");
        }
    }

    record Include(int line, String target) implements FclInstruction {
        public Include {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Enters a durable exception handler region. */
    record TryStart(int line, int catchTarget, int catchEndTarget, String catchVariable)
            implements FclInstruction {
        public TryStart {
            Objects.requireNonNull(catchVariable, "catchVariable");
        }
    }

    /** Compiler marker reached only after an exception has selected its enclosing handler. */
    record CatchEnter(int line) implements FclInstruction {}

    /** Leaves a catch scope and restores any shadowed catch variable binding. */
    record CatchEnd(int line) implements FclInstruction {}

    /** Compiler-only control edge. The runtime folds jumps around one semantic step. */
    record Jump(int line, int target) implements FclInstruction {}
}
