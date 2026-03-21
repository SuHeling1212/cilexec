package com.follarce.plugin;

/**
 * Random number function provider
 * Provides random number generation functions
 */
public class RandomFunctionProvider implements FunctionProvider {

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            case "random":
                return handleRandom(args);
            default:
                return null;
        }
    }

    /**
     * Generate random number
     * Usage: random() - returns 0-99
     *        random(max) - returns 0 to max-1
     *        random(min, max) - returns min to max-1
     */
    private Object handleRandom(Object[] args) {
        int min = 0;
        int max = 100;

        if (args.length >= 1 && args[0] instanceof Number) {
            max = ((Number) args[0]).intValue();
        }

        if (args.length >= 2 && args[1] instanceof Number) {
            min = ((Number) args[0]).intValue();
            max = ((Number) args[1]).intValue();
        }

        if (min >= max) {
            return new String[]{"ERROR", "INVALID_RANGE"};
        }

        return (int) (Math.random() * (max - min) + min);
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
            new FunctionInfo(
                "random",
                "Generate random number",
                new String[]{"min: int (optional)", "max: int (optional)"},
                "int",
                "Random"
            )
        };
    }

    @Override
    public String getProviderName() {
        return "RandomFunctionProvider";
    }
}
