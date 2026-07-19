package com.follarce.process;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites imported FCL functions into a process-local namespace. */
final class FclNamespaceRewriter {
    private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern FUNCTION_DEFINITION = Pattern.compile(
            "^(\\s*func\\s+)([A-Za-z_][A-Za-z0-9_]*)(?=\\s*\\()");

    private FclNamespaceRewriter() {}

    static Set<String> declaredFunctions(String source) {
        LinkedHashSet<String> functions = new LinkedHashSet<>();
        if (source == null) return functions;
        for (String line : source.split("\\R", -1)) {
            Matcher matcher = FUNCTION_DEFINITION.matcher(line);
            if (matcher.find()) functions.add(matcher.group(2));
        }
        return functions;
    }

    static String rewrite(String source,
                          String namespace,
                          Set<String> ownFunctions,
                          Map<String, String> dependencyNamespaces,
                          Map<String, Set<String>> dependencyFunctions) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid FCL import namespace: " + namespace);
        }
        StringBuilder output = new StringBuilder();
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) output.append('\n');
            output.append(rewriteLine(lines[i], namespace, ownFunctions,
                    dependencyNamespaces, dependencyFunctions));
        }
        return output.toString();
    }

    private static String rewriteLine(String line,
                                      String namespace,
                                      Set<String> ownFunctions,
                                      Map<String, String> dependencyNamespaces,
                                      Map<String, Set<String>> dependencyFunctions) {
        Matcher definition = FUNCTION_DEFINITION.matcher(line);
        int definitionStart = definition.find() ? definition.start(2) : -1;
        StringBuilder output = new StringBuilder(line.length() + 32);
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < line.length();) {
            char current = line.charAt(i);
            if (inString) {
                output.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                i++;
                continue;
            }
            if (current == '"') {
                inString = true;
                output.append(current);
                i++;
                continue;
            }
            if (current == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                output.append(line, i, line.length());
                break;
            }
            if (!isIdentifierStart(current)) {
                output.append(current);
                i++;
                continue;
            }

            int start = i;
            i++;
            while (i < line.length() && isIdentifierPart(line.charAt(i))) i++;
            String identifier = line.substring(start, i);
            if (start == definitionStart) {
                output.append(namespace).append('.').append(identifier);
                continue;
            }

            int next = i;
            while (next < line.length() && Character.isWhitespace(line.charAt(next))) next++;
            if (next < line.length() && line.charAt(next) == '(') {
                output.append(rewriteCall(identifier, namespace, ownFunctions,
                        dependencyNamespaces, dependencyFunctions));
            } else {
                output.append(identifier);
            }
        }
        return output.toString();
    }

    private static String rewriteCall(String identifier,
                                      String namespace,
                                      Set<String> ownFunctions,
                                      Map<String, String> dependencyNamespaces,
                                      Map<String, Set<String>> dependencyFunctions) {
        int dot = identifier.indexOf('.');
        if (dot > 0) {
            String binding = identifier.substring(0, dot);
            String function = identifier.substring(dot + 1);
            String dependencyNamespace = dependencyNamespaces.get(binding);
            if (dependencyNamespace == null) return identifier;
            Set<String> available = dependencyFunctions.getOrDefault(binding, Set.of());
            if (!available.contains(function)) {
                throw new IllegalArgumentException("Dependency '" + binding
                        + "' does not define function '" + function + "'");
            }
            return dependencyNamespace + "." + function;
        }

        if (ownFunctions.contains(identifier)) return namespace + "." + identifier;

        String selectedNamespace = null;
        for (Map.Entry<String, Set<String>> dependency : dependencyFunctions.entrySet()) {
            if (!dependency.getValue().contains(identifier)) continue;
            String candidate = dependencyNamespaces.get(dependency.getKey());
            if (candidate == null) continue;
            if (selectedNamespace != null && !selectedNamespace.equals(candidate)) {
                throw new IllegalArgumentException("Ambiguous dependency function '" + identifier
                        + "'; call it as <dependency>." + identifier + "()");
            }
            selectedNamespace = candidate;
        }
        return selectedNamespace == null ? identifier : selectedNamespace + "." + identifier;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '.';
    }
}
