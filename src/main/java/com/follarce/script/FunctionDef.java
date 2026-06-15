package com.follarce.script;

import java.util.List;

/**
 * 用户定义函数的描述。
 */
public class FunctionDef {
    public final String name;
    public final List<String> params;
    public final List<String> bodyLines;
    public final int bodyStartLine;

    public FunctionDef(String name, List<String> params, List<String> bodyLines, int bodyStartLine) {
        this.name = name;
        this.params = params;
        this.bodyLines = bodyLines;
        this.bodyStartLine = bodyStartLine;
    }

    public int getParamCount() {
        return params != null ? params.size() : 0;
    }
}
