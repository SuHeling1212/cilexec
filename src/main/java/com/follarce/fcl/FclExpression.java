package com.follarce.fcl;

import java.util.List;
import java.util.Objects;

/** Immutable expression tree. Node ids make suspended function calls resumable. */
public sealed interface FclExpression permits FclExpression.Literal, FclExpression.Variable,
        FclExpression.ArrayLiteral, FclExpression.MapLiteral, FclExpression.Unary,
        FclExpression.Binary, FclExpression.Index, FclExpression.Call,
        FclExpression.Member, FclExpression.Update, FclExpression.DestroyTarget, FclExpression.NewObject,
        FclExpression.SuperConstructor {

    long id();

    record Literal(long id, Object value) implements FclExpression {}

    record Variable(long id, String name) implements FclExpression {
        public Variable {
            Objects.requireNonNull(name, "name");
        }
    }

    record ArrayLiteral(long id, List<FclExpression> elements) implements FclExpression {
        public ArrayLiteral {
            elements = List.copyOf(elements);
        }
    }

    record MapEntry(FclExpression key, FclExpression value) {
        public MapEntry {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    record MapLiteral(long id, List<MapEntry> entries) implements FclExpression {
        public MapLiteral {
            entries = List.copyOf(entries);
        }
    }

    record Unary(long id, String operator, FclExpression operand) implements FclExpression {
        public Unary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    record Binary(long id, String operator, FclExpression left,
                  FclExpression right) implements FclExpression {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Index(long id, FclExpression target, FclExpression index) implements FclExpression {
        public Index {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(index, "index");
        }
    }

    /** Member access after a non-identifier expression, for example {@code e.stack[0].line}. */
    record Member(long id, FclExpression target, String name) implements FclExpression {
        public Member {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(name, "name");
        }
    }

    /** Postfix update expression; it evaluates to the updated target value. */
    record Update(long id, String variable, List<FclExpression> indices, int delta)
            implements FclExpression {
        public Update {
            Objects.requireNonNull(variable, "variable");
            indices = List.copyOf(indices);
            if (delta != 1 && delta != -1) throw new IllegalArgumentException("Update delta must be ±1");
        }
    }

    record Call(long id, String name, List<FclExpression> arguments) implements FclExpression {
        public Call {
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * A language-level {@code memory.destroy} target: the symbol name and its optional
     * index path are captured at compile time so the runtime can delete the real
     * variable/function binding or the real container element instead of a deep copy.
     */
    record DestroyTarget(long id, String functionName, String rootName,
                         List<FclExpression> indices) implements FclExpression {
        public DestroyTarget {
            Objects.requireNonNull(functionName, "functionName");
            Objects.requireNonNull(rootName, "rootName");
            indices = List.copyOf(indices);
        }
    }

    record NewObject(long id, String className, List<FclExpression> arguments)
            implements FclExpression {
        public NewObject {
            Objects.requireNonNull(className, "className");
            arguments = List.copyOf(arguments);
        }
    }

    record SuperConstructor(long id, List<FclExpression> arguments) implements FclExpression {
        public SuperConstructor { arguments = List.copyOf(arguments); }
    }
}
