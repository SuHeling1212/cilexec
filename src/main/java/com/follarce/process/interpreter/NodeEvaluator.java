package com.follarce.process.interpreter;

public interface NodeEvaluator {

    interface FunctionInvoker {
        Object invokeFunction(String funcName, Object[] args);
    }

    void setFunctionInvoker(FunctionInvoker invoker);

    Object evaluate(Parser.ASTNode node, EvaluationContext context);

    class EvaluationContext {
        public final java.util.Map<String, Object> variables;
        public final java.util.Map<String, java.util.List<String>> functions;
        public final int pid;
        public final int currentLine;

        public EvaluationContext(java.util.Map<String, Object> variables,
                                 java.util.Map<String, java.util.List<String>> functions,
                                 int pid, int currentLine) {
            this.variables = variables;
            this.functions = functions;
            this.pid = pid;
            this.currentLine = currentLine;
        }
    }
}