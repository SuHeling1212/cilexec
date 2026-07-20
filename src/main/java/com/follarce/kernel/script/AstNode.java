package com.follarce.kernel.script;

import java.util.List;

/**
 * A node in the abstract syntax tree.
 */
public class AstNode {
    public final NodeType type;
    public final Object value;
    public final String name;
    public final String operator;
    public final AstNode left;
    public final AstNode right;
    public final AstNode operand;
    public final AstNode index;
    public final List<AstNode> args;
    public final List<AstNode> keys;
    public final List<AstNode> values;

    // Private constructor – use static factories.
    // For INDEX_ACCESS, the target expression is stored in 'left'.
    private AstNode(NodeType type, Object value, String name, String operator,
                    AstNode left, AstNode right, AstNode operand, AstNode index,
                    List<AstNode> args, List<AstNode> keys, List<AstNode> values) {
        this.type = type;
        this.value = value;
        this.name = name;
        this.operator = operator;
        this.left = left;
        this.right = right;
        this.operand = operand;
        this.index = index;
        this.args = args;
        this.keys = keys;
        this.values = values;
    }

    // Constructor specifically for INDEX_ACCESS where target needs to be set.
    private AstNode(NodeType type, AstNode target, AstNode index) {
        this.type = type;
        this.value = null;
        this.name = null;
        this.operator = null;
        this.left = target;
        this.right = null;
        this.operand = null;
        this.index = index;
        this.args = null;
        this.keys = null;
        this.values = null;
    }

    static AstNode numberLiteral(Object value) {
        return new AstNode(NodeType.NUMBER_LITERAL, value, null, null, null, null, null, null, null, null, null);
    }

    static AstNode stringLiteral(Object value) {
        return new AstNode(NodeType.STRING_LITERAL, value, null, null, null, null, null, null, null, null, null);
    }

    static AstNode booleanLiteral(Object value) {
        return new AstNode(NodeType.BOOLEAN_LITERAL, value, null, null, null, null, null, null, null, null, null);
    }

    static AstNode identifier(String name) {
        return new AstNode(NodeType.IDENTIFIER, null, name, null, null, null, null, null, null, null, null);
    }

    static AstNode unaryOp(String operator, AstNode operand) {
        return new AstNode(NodeType.UNARY_OP, null, null, operator, null, null, operand, null, null, null, null);
    }

    static AstNode binaryOp(String operator, AstNode left, AstNode right) {
        return new AstNode(NodeType.BINARY_OP, null, null, operator, left, right, null, null, null, null, null);
    }

    static AstNode indexAccess(AstNode target, AstNode index) {
        return new AstNode(NodeType.INDEX_ACCESS, target, index);
    }

    static AstNode functionCall(String name, List<AstNode> args) {
        return new AstNode(NodeType.FUNCTION_CALL, null, name, null, null, null, null, null, args, null, null);
    }

    static AstNode arrayLiteral(List<AstNode> elements) {
        return new AstNode(NodeType.ARRAY_LITERAL, null, null, null, null, null, null, null, elements, null, null);
    }

    static AstNode mapLiteral(List<AstNode> keys, List<AstNode> values) {
        return new AstNode(NodeType.MAP_LITERAL, null, null, null, null, null, null, null, null, keys, values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    private void toString(StringBuilder sb, int indent) {
        String pad = "  ".repeat(indent);
        switch (type) {
            case NUMBER_LITERAL:   sb.append(pad).append("NUMBER(").append(value).append(")\n"); break;
            case STRING_LITERAL:   sb.append(pad).append("STRING(\"").append(value).append("\")\n"); break;
            case BOOLEAN_LITERAL:  sb.append(pad).append("BOOLEAN(").append(value).append(")\n"); break;
            case IDENTIFIER:       sb.append(pad).append("IDENTIFIER(").append(name).append(")\n"); break;
            case UNARY_OP:         sb.append(pad).append("UNARY(").append(operator).append(")\n"); operand.toString(sb, indent + 1); break;
            case BINARY_OP:        sb.append(pad).append("BINARY(").append(operator).append(")\n"); left.toString(sb, indent + 1); right.toString(sb, indent + 1); break;
            case INDEX_ACCESS:     sb.append(pad).append("INDEX\n"); left.toString(sb, indent + 1); index.toString(sb, indent + 1); break;
            case FUNCTION_CALL:    sb.append(pad).append("CALL(").append(name).append(")\n"); for (AstNode a : args) a.toString(sb, indent + 1); break;
            case ARRAY_LITERAL:    sb.append(pad).append("ARRAY\n"); for (AstNode a : args) a.toString(sb, indent + 1); break;
            case MAP_LITERAL:      sb.append(pad).append("MAP\n"); for (int i = 0; i < keys.size(); i++) { sb.append(pad).append("  KEY:\n"); keys.get(i).toString(sb, indent + 2); sb.append(pad).append("  VAL:\n"); values.get(i).toString(sb, indent + 2); } break;
        }
    }
}
