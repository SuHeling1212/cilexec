package com.follarce.process.interpreter;

import com.follarce.process.exception.UnrecoverableException;
import com.follarce.plugin.FunctionContext;
import com.follarce.plugin.FunctionRegistry;
import java.util.*;

public class ProcessRunnerNodeEvaluator implements NodeEvaluator {

    private Map<String, Object> variables;
    private int pid;
    private int currentLine;
    private FunctionContext functionContext;
    private FunctionInvoker functionInvoker;

    private Map<String, Object> data;
    private Object returnValue;
    private List<String> codeLines;

    public ProcessRunnerNodeEvaluator(Map<String, Object> variables,
            int pid, int currentLine,
            FunctionContext functionContext) {
        this.variables = variables;
        this.pid = pid;
        this.currentLine = currentLine;
        this.functionContext = functionContext;
    }

    @Override
    public void setFunctionInvoker(FunctionInvoker invoker) {
        this.functionInvoker = invoker;
    }

    public void setRuntimeData(Map<String, Object> data, Object returnValue,
            List<String> codeLines) {
        this.data = data;
        this.returnValue = returnValue;
        this.codeLines = codeLines;
    }

    @Override
    public Object evaluate(Parser.ASTNode node, EvaluationContext context) {
        if (context != null) {
            this.variables = context.variables;
            this.pid = context.pid;
            this.currentLine = context.currentLine;
        }

        return evaluateNode(node);
    }

    private Object evaluateNode(Parser.ASTNode node) {
        if (node == null) {
            return null;
        }

        switch (node.type) {
            case NUMBER:
            case STRING:
            case BOOLEAN:
                return node.value;

            case IDENTIFIER:
                return evaluateIdentifier((String) node.value);

            case UNARY:
                return evaluateUnary((String) node.value, node.right);

            case BINARY:
                return evaluateBinary((String) node.value, node.left, node.right);

            case INDEX:
                return evaluateIndex(node.left, node.right);

            case FUNCTION_CALL:
                return evaluateFunctionCall((String) node.value, node.children);

            case ARRAY:
                return evaluateArray(node.children);

            case MAP:
                return evaluateMap(node.children);

            default:
                throw wrapException("Cannot evaluate node type: " + node.type);
        }
    }

    private Object evaluateIdentifier(String name) {
        if (variables != null && variables.containsKey(name)) {
            return variables.get(name);
        }
        throw UnrecoverableException.undefinedVariable(name, pid, currentLine);
    }

    private Object evaluateUnary(String op, Parser.ASTNode operand) {
        Object right = evaluateNode(operand);

        if (op.equals("not")) {
            return isTrue(right) ? false : true;
        } else if (op.equals("-")) {
            if (right instanceof Number) {
                double result = -((Number) right).doubleValue();
                return normalizeNumber(result);
            }
            throw UnrecoverableException.typeError("number",
                    right != null ? right.getClass().getSimpleName() : "null", pid, currentLine);
        }
        throw wrapException("Unknown unary operator: " + op);
    }

    private Object evaluateBinary(String op, Parser.ASTNode leftNode, Parser.ASTNode rightNode) {
        Object leftVal = evaluateNode(leftNode);
        Object rightVal = evaluateNode(rightNode);

        switch (op) {
            case "and":
                return isTrue(leftVal) && isTrue(rightVal);
            case "or":
                return isTrue(leftVal) || isTrue(rightVal);

            case "==":
                return Objects.equals(leftVal, rightVal);
            case "!=":
                return !Objects.equals(leftVal, rightVal);

            case "<":
                ensureNumbers(leftVal, rightVal);
                return ((Number) leftVal).doubleValue() < ((Number) rightVal).doubleValue();
            case ">":
                ensureNumbers(leftVal, rightVal);
                return ((Number) leftVal).doubleValue() > ((Number) rightVal).doubleValue();
            case "<=":
                ensureNumbers(leftVal, rightVal);
                return ((Number) leftVal).doubleValue() <= ((Number) rightVal).doubleValue();
            case ">=":
                ensureNumbers(leftVal, rightVal);
                return ((Number) leftVal).doubleValue() >= ((Number) rightVal).doubleValue();

            case "+":
                return smartAdd(leftVal, rightVal);
            case "-":
                ensureNumbers(leftVal, rightVal);
                double subResult = ((Number) leftVal).doubleValue() - ((Number) rightVal).doubleValue();
                return normalizeNumber(subResult);
            case "*":
                ensureNumbers(leftVal, rightVal);
                double mulResult = ((Number) leftVal).doubleValue() * ((Number) rightVal).doubleValue();
                return normalizeNumber(mulResult);
            case "/":
                ensureNumbers(leftVal, rightVal);
                if (((Number) rightVal).doubleValue() == 0) {
                    throw UnrecoverableException.divisionByZero(pid, currentLine, op);
                }
                double divResult = ((Number) leftVal).doubleValue() / ((Number) rightVal).doubleValue();
                return normalizeNumber(divResult);
            case "%":
                ensureNumbers(leftVal, rightVal);
                if (((Number) rightVal).intValue() == 0) {
                    throw UnrecoverableException.divisionByZero(pid, currentLine, op);
                }
                return ((Number) leftVal).intValue() % ((Number) rightVal).intValue();
        }

        throw wrapException("Unknown binary operator: " + op);
    }

    private Object smartAdd(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            double result = ((Number) left).doubleValue() + ((Number) right).doubleValue();
            return normalizeNumber(result);
        }

        if (left instanceof String || right instanceof String) {
            String leftStr = convertToStringForConcat(left);
            String rightStr = convertToStringForConcat(right);
            return leftStr + rightStr;
        }

        throw UnrecoverableException.typeError(
                "String or Number",
                getTypeName(left) + " and " + getTypeName(right),
                pid,
                currentLine);
    }

    private String convertToStringForConcat(Object obj) {
        if (obj == null)
            return "null";
        if (obj instanceof String)
            return (String) obj;
        if (obj instanceof Number) {
            double d = ((Number) obj).doubleValue();
            if (d == (int) d) {
                return String.valueOf((int) d);
            }
            return String.valueOf(d);
        }
        if (obj instanceof Boolean)
            return String.valueOf(obj);
        if (obj instanceof List || obj instanceof Map || obj instanceof Object[])
            return com.follarce.basicUtil.JsonUtil.toJson(obj);
        return obj.toString();
    }

    private String getTypeName(Object obj) {
        if (obj == null)
            return "null";
        if (obj instanceof String)
            return "String";
        if (obj instanceof Number)
            return "Number";
        if (obj instanceof Boolean)
            return "Boolean";
        if (obj instanceof List)
            return "Array";
        if (obj instanceof Map)
            return "Map";
        if (obj instanceof Object[])
            return "Array";
        return obj.getClass().getSimpleName();
    }

    private Number normalizeNumber(double value) {
        if (value == (int) value) {
            return (int) value;
        }
        return value;
    }

    private void ensureNumbers(Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) {
            String leftType = left != null ? left.getClass().getSimpleName() : "null";
            String rightType = right != null ? right.getClass().getSimpleName() : "null";
            throw UnrecoverableException.typeError("numbers", leftType + " and " + rightType, pid, currentLine);
        }
    }

    private Object evaluateIndex(Parser.ASTNode leftNode, Parser.ASTNode indexNode) {
        Object container = evaluateNode(leftNode);
        Object index = evaluateNode(indexNode);
        return handleIndexAccess(container, index);
    }

    private Object handleIndexAccess(Object container, Object index) {
        if (container instanceof List && index instanceof Number) {
            List<?> list = (List<?>) container;
            int idx = ((Number) index).intValue();
            if (idx < 0)
                idx = list.size() + idx;
            if (idx < 0 || idx >= list.size()) {
                throw UnrecoverableException.arrayIndexOutOfBounds(idx, list.size(), pid, currentLine);
            }
            return list.get(idx);
        } else if (container instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) container;
            return map.get(index);
        }
        throw UnrecoverableException.typeError("array or map",
                container.getClass().getSimpleName(), pid, currentLine);
    }

    private Object evaluateFunctionCall(String funcName, List<Parser.ASTNode> args) {
        List<Object> argValues = new ArrayList<>();
        if (args != null) {
            for (Parser.ASTNode arg : args) {
                argValues.add(evaluateNode(arg));
            }
        }
        Object[] argArray = argValues.toArray();

        if (functionInvoker != null) {
            return functionInvoker.invokeFunction(funcName, argArray);
        }

        String fullName = funcName;
        if (funcName.contains(".")) {
            fullName = funcName;
        }

        Object result = FunctionRegistry.call(fullName, argArray, functionContext);
        if (result != null) {
            return result;
        }

        throw UnrecoverableException.unknownFunction(funcName, pid, currentLine);
    }

    private List<Object> evaluateArray(List<Parser.ASTNode> elements) {
        List<Object> result = new ArrayList<>();
        if (elements != null) {
            for (Parser.ASTNode element : elements) {
                result.add(evaluateNode(element));
            }
        }
        return result;
    }

    private Map<Object, Object> evaluateMap(List<Parser.ASTNode> children) {
        Map<Object, Object> result = new HashMap<>();
        if (children != null) {
            for (int i = 0; i < children.size(); i += 2) {
                Object key = evaluateNode(children.get(i));
                Object value = evaluateNode(children.get(i + 1));
                result.put(key, value);
            }
        }
        return result;
    }

    private boolean isTrue(Object obj) {
        if (obj == null)
            return false;
        if (obj instanceof Boolean)
            return (Boolean) obj;
        if (obj instanceof Number)
            return ((Number) obj).doubleValue() != 0;
        if (obj instanceof String)
            return !((String) obj).isEmpty();
        if (obj instanceof List)
            return !((List<?>) obj).isEmpty();
        if (obj instanceof Map)
            return !((Map<?, ?>) obj).isEmpty();
        return true;
    }

    private RuntimeException wrapException(String message) {
        String currentLineStr = null;
        if (codeLines != null && currentLine >= 0 && currentLine < codeLines.size()) {
            currentLineStr = codeLines.get(currentLine);
        }
        com.follarce.process.exception.ExceptionContext context = new com.follarce.process.exception.ExceptionContext(
                pid, currentLine,
                "/system/process/" + pid + ".json", currentLineStr, "expression_evaluation");
        return new UnrecoverableException(message, context);
    }
}