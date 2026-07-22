package com.follarce.fcl;

import java.util.List;
import java.util.Objects;

/** Flat instructions produced by {@link FclCompiler}. */
public sealed interface FclInstruction permits FclInstruction.Assignment,
        FclInstruction.Evaluation, FclInstruction.Conditional, FclInstruction.Loop,
        FclInstruction.Break, FclInstruction.Continue, FclInstruction.Return,
        FclInstruction.FunctionDeclaration, FclInstruction.Import,
        FclInstruction.Include, FclInstruction.Jump {

    int line();

    record Assignment(int line, String variable, List<FclExpression> indices,
                      FclExpression value) implements FclInstruction {
        public Assignment {
            Objects.requireNonNull(variable, "variable");
            indices = List.copyOf(indices);
            Objects.requireNonNull(value, "value");
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

    record Return(int line, FclExpression value, boolean implicit) implements FclInstruction {}

    record FunctionDeclaration(int line, String name, List<String> parameters,
                               int bodyTarget, int endTarget) implements FclInstruction {
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

    /** Compiler-only control edge. The runtime folds jumps around one semantic step. */
    record Jump(int line, int target) implements FclInstruction {}
}
