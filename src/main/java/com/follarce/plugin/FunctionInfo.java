package com.follarce.plugin;

/**
 * Function information descriptor
 * Used for documentation generation and IDE support
 */
public class FunctionInfo {
    private final String name;
    private final String description;
    private final String[] params;
    private final String returnType;
    private final String provider;
    
    public FunctionInfo(String name, String description, String[] params, String returnType, String provider) {
        this.name = name;
        this.description = description;
        this.params = params != null ? params : new String[0];
        this.returnType = returnType != null ? returnType : "any";
        this.provider = provider;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String[] getParams() {
        return params;
    }
    
    public String getReturnType() {
        return returnType;
    }
    
    public String getProvider() {
        return provider;
    }
    
    /**
     * Generate Markdown format documentation
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(name).append(" | ");
        
        // Parameters
        if (params.length == 0) {
            sb.append("None");
        } else {
            sb.append(String.join(", ", params));
        }
        sb.append(" | ");
        
        // Return value
        sb.append(returnType).append(" | ");

        // Description
        sb.append(description).append(" |");
        
        return sb.toString();
    }
}
