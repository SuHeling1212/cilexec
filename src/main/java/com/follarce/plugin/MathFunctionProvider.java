package com.follarce.plugin;

import com.follarce.basicUtil.Logger;

import java.util.*;

/**
 * Math function provider - comprehensive mathematical operations
 * Provides arithmetic, trigonometric, logarithmic, and statistical functions
 */
public class MathFunctionProvider implements FunctionProvider {

    private static final Random random = new Random();

    @Override
    public Object call(String name, Object[] args, FunctionContext context) {
        switch (name) {
            // Basic arithmetic
            case "math.abs":
                return handleAbs(args);
            case "math.max":
                return handleMax(args);
            case "math.min":
                return handleMin(args);
            case "math.pow":
                return handlePow(args);
            case "math.sqrt":
                return handleSqrt(args);
            case "math.cbrt":
                return handleCbrt(args);
            case "math.round":
                return handleRound(args);
            case "math.floor":
                return handleFloor(args);
            case "math.ceil":
                return handleCeil(args);
            case "math.mod":
                return handleMod(args);
            case "math.sign":
                return handleSign(args);
            case "math.clamp":
                return handleClamp(args);
            case "math.lerp":
                return handleLerp(args);

            // Trigonometric functions
            case "math.sin":
                return handleSin(args);
            case "math.cos":
                return handleCos(args);
            case "math.tan":
                return handleTan(args);
            case "math.asin":
                return handleAsin(args);
            case "math.acos":
                return handleAcos(args);
            case "math.atan":
                return handleAtan(args);
            case "math.atan2":
                return handleAtan2(args);
            case "math.sinh":
                return handleSinh(args);
            case "math.cosh":
                return handleCosh(args);
            case "math.tanh":
                return handleTanh(args);
            case "math.deg":
                return handleDeg(args);
            case "math.rad":
                return handleRad(args);

            // Logarithmic and exponential
            case "math.log":
                return handleLog(args);
            case "math.log10":
                return handleLog10(args);
            case "math.log2":
                return handleLog2(args);
            case "math.ln":
                return handleLn(args);
            case "math.exp":
                return handleExp(args);

            // Random numbers
            case "math.random":
                return handleRandom(args);
            case "math.randint":
                return handleRandInt(args);
            case "math.randfloat":
                return handleRandFloat(args);
            case "math.randchoice":
                return handleRandChoice(args);
            case "math.shuffle":
                return handleShuffle(args);

            // Statistical functions
            case "math.sum":
                return handleSum(args);
            case "math.avg":
            case "math.mean":
                return handleMean(args);
            case "math.median":
                return handleMedian(args);
            case "math.mode":
                return handleMode(args);
            case "math.var":
                return handleVariance(args);
            case "math.std":
                return handleStdDev(args);
            case "math.minarr":
                return handleMinArr(args);
            case "math.maxarr":
                return handleMaxArr(args);
            case "math.range":
                return handleRange(args);

            // Number theory
            case "math.gcd":
                return handleGcd(args);
            case "math.lcm":
                return handleLcm(args);
            case "math.prime":
                return handleIsPrime(args);
            case "math.factors":
                return handleFactors(args);
            case "math.fib":
                return handleFibonacci(args);
            case "math.factorial":
                return handleFactorial(args);

            // Constants
            case "math.pi":
                return Math.PI;
            case "math.e":
                return Math.E;
            case "math.tau":
                return 2 * Math.PI;
            case "math.inf":
                return Double.POSITIVE_INFINITY;
            case "math.nan":
                return Double.NaN;

            // Geometric functions
            case "math.hypot":
                return handleHypot(args);
            case "math.dist":
                return handleDistance(args);
            case "math.area.circle":
                return handleCircleArea(args);
            case "math.area.rect":
                return handleRectArea(args);
            case "math.vol.sphere":
                return handleSphereVolume(args);

            // Bitwise operations
            case "math.bit.and":
                return handleBitAnd(args);
            case "math.bit.or":
                return handleBitOr(args);
            case "math.bit.xor":
                return handleBitXor(args);
            case "math.bit.not":
                return handleBitNot(args);
            case "math.bit.shiftl":
                return handleBitShiftLeft(args);
            case "math.bit.shiftr":
                return handleBitShiftRight(args);

            // Advanced functions
            case "math.map":
                return handleMap(args);
            case "math.norm":
                return handleNormalize(args);
            case "math.perlin":
                return handlePerlin(args);
            case "math.noise":
                return handleNoise(args);

            default:
                return null;
        }
    }

    // ==================== Basic Arithmetic ====================

    private Object handleAbs(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.abs(((Number) args[0]).doubleValue());
    }

    private Object handleMax(Object[] args) {
        if (args.length < 2) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double max = Double.NEGATIVE_INFINITY;
        for (Object arg : args) {
            if (arg instanceof Number) {
                max = Math.max(max, ((Number) arg).doubleValue());
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        max = Math.max(max, ((Number) item).doubleValue());
                    }
                }
            }
        }
        return max;
    }

    private Object handleMin(Object[] args) {
        if (args.length < 2) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double min = Double.POSITIVE_INFINITY;
        for (Object arg : args) {
            if (arg instanceof Number) {
                min = Math.min(min, ((Number) arg).doubleValue());
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        min = Math.min(min, ((Number) item).doubleValue());
                    }
                }
            }
        }
        return min;
    }

    private Object handlePow(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.pow(((Number) args[0]).doubleValue(), ((Number) args[1]).doubleValue());
    }

    private Object handleSqrt(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double val = ((Number) args[0]).doubleValue();
        if (val < 0) return new String[]{"ERROR", "NEGATIVE_SQRT"};
        return Math.sqrt(val);
    }

    private Object handleCbrt(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.cbrt(((Number) args[0]).doubleValue());
    }

    private Object handleRound(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        if (args.length >= 2 && args[1] instanceof Number) {
            int decimals = ((Number) args[1]).intValue();
            double factor = Math.pow(10, decimals);
            return Math.round(((Number) args[0]).doubleValue() * factor) / factor;
        }
        return Math.round(((Number) args[0]).doubleValue());
    }

    private Object handleFloor(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.floor(((Number) args[0]).doubleValue());
    }

    private Object handleCeil(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.ceil(((Number) args[0]).doubleValue());
    }

    private Object handleMod(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double divisor = ((Number) args[1]).doubleValue();
        if (divisor == 0) return new String[]{"ERROR", "DIVISION_BY_ZERO"};
        return ((Number) args[0]).doubleValue() % divisor;
    }

    private Object handleSign(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.signum(((Number) args[0]).doubleValue());
    }

    private Object handleClamp(Object[] args) {
        if (args.length < 3 || !(args[0] instanceof Number) || !(args[1] instanceof Number) || !(args[2] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double val = ((Number) args[0]).doubleValue();
        double min = ((Number) args[1]).doubleValue();
        double max = ((Number) args[2]).doubleValue();
        return Math.max(min, Math.min(max, val));
    }

    private Object handleLerp(Object[] args) {
        if (args.length < 3 || !(args[0] instanceof Number) || !(args[1] instanceof Number) || !(args[2] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double start = ((Number) args[0]).doubleValue();
        double end = ((Number) args[1]).doubleValue();
        double t = ((Number) args[2]).doubleValue();
        return start + (end - start) * t;
    }

    // ==================== Trigonometric Functions ====================

    private Object handleSin(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.sin(((Number) args[0]).doubleValue());
    }

    private Object handleCos(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.cos(((Number) args[0]).doubleValue());
    }

    private Object handleTan(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.tan(((Number) args[0]).doubleValue());
    }

    private Object handleAsin(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.asin(((Number) args[0]).doubleValue());
    }

    private Object handleAcos(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.acos(((Number) args[0]).doubleValue());
    }

    private Object handleAtan(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.atan(((Number) args[0]).doubleValue());
    }

    private Object handleAtan2(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.atan2(((Number) args[0]).doubleValue(), ((Number) args[1]).doubleValue());
    }

    private Object handleSinh(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.sinh(((Number) args[0]).doubleValue());
    }

    private Object handleCosh(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.cosh(((Number) args[0]).doubleValue());
    }

    private Object handleTanh(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.tanh(((Number) args[0]).doubleValue());
    }

    private Object handleDeg(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.toDegrees(((Number) args[0]).doubleValue());
    }

    private Object handleRad(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.toRadians(((Number) args[0]).doubleValue());
    }

    // ==================== Logarithmic and Exponential ====================

    private Object handleLog(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double base = ((Number) args[0]).doubleValue();
        double val = ((Number) args[1]).doubleValue();
        if (base <= 0 || base == 1 || val <= 0) return new String[]{"ERROR", "INVALID_LOG"};
        return Math.log(val) / Math.log(base);
    }

    private Object handleLog10(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double val = ((Number) args[0]).doubleValue();
        if (val <= 0) return new String[]{"ERROR", "INVALID_LOG"};
        return Math.log10(val);
    }

    private Object handleLog2(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double val = ((Number) args[0]).doubleValue();
        if (val <= 0) return new String[]{"ERROR", "INVALID_LOG"};
        return Math.log(val) / Math.log(2);
    }

    private Object handleLn(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double val = ((Number) args[0]).doubleValue();
        if (val <= 0) return new String[]{"ERROR", "INVALID_LOG"};
        return Math.log(val);
    }

    private Object handleExp(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.exp(((Number) args[0]).doubleValue());
    }

    // ==================== Random Numbers ====================

    private Object handleRandom(Object[] args) {
        if (args.length == 0) {
            return random.nextDouble();
        }
        if (args.length == 1 && args[0] instanceof Number) {
            return random.nextInt(((Number) args[0]).intValue());
        }
        if (args.length >= 2 && args[0] instanceof Number && args[1] instanceof Number) {
            int min = ((Number) args[0]).intValue();
            int max = ((Number) args[1]).intValue();
            return random.nextInt(max - min) + min;
        }
        return random.nextDouble();
    }

    private Object handleRandInt(Object[] args) {
        if (args.length == 0) {
            return random.nextInt();
        }
        if (args.length == 1 && args[0] instanceof Number) {
            return random.nextInt(((Number) args[0]).intValue());
        }
        if (args.length >= 2 && args[0] instanceof Number && args[1] instanceof Number) {
            int min = ((Number) args[0]).intValue();
            int max = ((Number) args[1]).intValue();
            return random.nextInt(max - min) + min;
        }
        return new String[]{"ERROR", "INVALID_ARGUMENTS"};
    }

    private Object handleRandFloat(Object[] args) {
        if (args.length == 0) {
            return random.nextDouble();
        }
        if (args.length >= 2 && args[0] instanceof Number && args[1] instanceof Number) {
            double min = ((Number) args[0]).doubleValue();
            double max = ((Number) args[1]).doubleValue();
            return min + (max - min) * random.nextDouble();
        }
        return random.nextDouble();
    }

    private Object handleRandChoice(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof List)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<?> list = (List<?>) args[0];
        if (list.isEmpty()) return new String[]{"ERROR", "EMPTY_LIST"};
        return list.get(random.nextInt(list.size()));
    }

    private Object handleShuffle(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof List)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<?> list = new ArrayList<>((List<?>) args[0]);
        Collections.shuffle(list, random);
        return list;
    }

    // ==================== Statistical Functions ====================

    private Object handleSum(Object[] args) {
        if (args.length < 1) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double sum = 0;
        for (Object arg : args) {
            if (arg instanceof Number) {
                sum += ((Number) arg).doubleValue();
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        sum += ((Number) item).doubleValue();
                    }
                }
            }
        }
        return sum;
    }

    private Object handleMean(Object[] args) {
        if (args.length < 1) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double sum = 0;
        int count = 0;
        for (Object arg : args) {
            if (arg instanceof Number) {
                sum += ((Number) arg).doubleValue();
                count++;
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        sum += ((Number) item).doubleValue();
                        count++;
                    }
                }
            }
        }
        if (count == 0) return new String[]{"ERROR", "NO_NUMBERS"};
        return sum / count;
    }

    private Object handleMedian(Object[] args) {
        if (args.length < 1) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<Double> numbers = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Number) {
                numbers.add(((Number) arg).doubleValue());
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        numbers.add(((Number) item).doubleValue());
                    }
                }
            }
        }
        if (numbers.isEmpty()) return new String[]{"ERROR", "NO_NUMBERS"};
        Collections.sort(numbers);
        int size = numbers.size();
        if (size % 2 == 0) {
            return (numbers.get(size / 2 - 1) + numbers.get(size / 2)) / 2;
        } else {
            return numbers.get(size / 2);
        }
    }

    private Object handleMode(Object[] args) {
        if (args.length < 1) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        Map<Double, Integer> frequency = new HashMap<>();
        for (Object arg : args) {
            if (arg instanceof Number) {
                double val = ((Number) arg).doubleValue();
                frequency.put(val, frequency.getOrDefault(val, 0) + 1);
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        double val = ((Number) item).doubleValue();
                        frequency.put(val, frequency.getOrDefault(val, 0) + 1);
                    }
                }
            }
        }
        if (frequency.isEmpty()) return new String[]{"ERROR", "NO_NUMBERS"};
        double mode = frequency.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .get().getKey();
        return mode;
    }

    private Object handleVariance(Object[] args) {
        if (args.length < 1) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<Double> numbers = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof Number) {
                numbers.add(((Number) arg).doubleValue());
            } else if (arg instanceof List) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Number) {
                        numbers.add(((Number) item).doubleValue());
                    }
                }
            }
        }
        if (numbers.size() < 2) return new String[]{"ERROR", "INSUFFICIENT_DATA"};
        double mean = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = numbers.stream()
                .mapToDouble(n -> Math.pow(n - mean, 2))
                .average().orElse(0);
        return variance;
    }

    private Object handleStdDev(Object[] args) {
        Object variance = handleVariance(args);
        if (variance instanceof String[]) return variance;
        return Math.sqrt((Double) variance);
    }

    private Object handleMinArr(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof List)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<?> list = (List<?>) args[0];
        if (list.isEmpty()) return new String[]{"ERROR", "EMPTY_LIST"};
        double min = Double.POSITIVE_INFINITY;
        for (Object item : list) {
            if (item instanceof Number) {
                min = Math.min(min, ((Number) item).doubleValue());
            }
        }
        return min;
    }

    private Object handleMaxArr(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof List)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        List<?> list = (List<?>) args[0];
        if (list.isEmpty()) return new String[]{"ERROR", "EMPTY_LIST"};
        double max = Double.NEGATIVE_INFINITY;
        for (Object item : list) {
            if (item instanceof Number) {
                max = Math.max(max, ((Number) item).doubleValue());
            }
        }
        return max;
    }

    private Object handleRange(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int start = ((Number) args[0]).intValue();
        int end = ((Number) args[1]).intValue();
        int step = (args.length >= 3 && args[2] instanceof Number) ? ((Number) args[2]).intValue() : 1;
        List<Integer> range = new ArrayList<>();
        for (int i = start; i < end; i += step) {
            range.add(i);
        }
        return range;
    }

    // ==================== Number Theory ====================

    private Object handleGcd(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int a = Math.abs(((Number) args[0]).intValue());
        int b = Math.abs(((Number) args[1]).intValue());
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    private Object handleLcm(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int a = ((Number) args[0]).intValue();
        int b = ((Number) args[1]).intValue();
        Object gcd = handleGcd(args);
        if (gcd instanceof String[]) return gcd;
        return Math.abs(a * b) / (Integer) gcd;
    }

    private Object handleIsPrime(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int n = ((Number) args[0]).intValue();
        if (n < 2) return false;
        if (n == 2) return true;
        if (n % 2 == 0) return false;
        for (int i = 3; i * i <= n; i += 2) {
            if (n % i == 0) return false;
        }
        return true;
    }

    private Object handleFactors(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int n = Math.abs(((Number) args[0]).intValue());
        List<Integer> factors = new ArrayList<>();
        for (int i = 1; i * i <= n; i++) {
            if (n % i == 0) {
                factors.add(i);
                if (i != n / i) {
                    factors.add(n / i);
                }
            }
        }
        Collections.sort(factors);
        return factors;
    }

    private Object handleFibonacci(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int n = ((Number) args[0]).intValue();
        if (n < 0) return new String[]{"ERROR", "NEGATIVE_INDEX"};
        if (n == 0) return 0;
        if (n == 1) return 1;
        long a = 0, b = 1;
        for (int i = 2; i <= n; i++) {
            long temp = a + b;
            a = b;
            b = temp;
        }
        return b;
    }

    private Object handleFactorial(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        int n = ((Number) args[0]).intValue();
        if (n < 0) return new String[]{"ERROR", "NEGATIVE_FACTORIAL"};
        if (n > 20) return new String[]{"ERROR", "OVERFLOW"};
        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    // ==================== Geometric Functions ====================

    private Object handleHypot(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return Math.hypot(((Number) args[0]).doubleValue(), ((Number) args[1]).doubleValue());
    }

    private Object handleDistance(Object[] args) {
        if (args.length < 4 || !(args[0] instanceof Number) || !(args[1] instanceof Number)
                || !(args[2] instanceof Number) || !(args[3] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double x1 = ((Number) args[0]).doubleValue();
        double y1 = ((Number) args[1]).doubleValue();
        double x2 = ((Number) args[2]).doubleValue();
        double y2 = ((Number) args[3]).doubleValue();
        return Math.hypot(x2 - x1, y2 - y1);
    }

    private Object handleCircleArea(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double r = ((Number) args[0]).doubleValue();
        return Math.PI * r * r;
    }

    private Object handleRectArea(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double w = ((Number) args[0]).doubleValue();
        double h = ((Number) args[1]).doubleValue();
        return w * h;
    }

    private Object handleSphereVolume(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double r = ((Number) args[0]).doubleValue();
        return (4.0 / 3.0) * Math.PI * r * r * r;
    }

    // ==================== Bitwise Operations ====================

    private Object handleBitAnd(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ((Number) args[0]).intValue() & ((Number) args[1]).intValue();
    }

    private Object handleBitOr(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ((Number) args[0]).intValue() | ((Number) args[1]).intValue();
    }

    private Object handleBitXor(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ((Number) args[0]).intValue() ^ ((Number) args[1]).intValue();
    }

    private Object handleBitNot(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ~((Number) args[0]).intValue();
    }

    private Object handleBitShiftLeft(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ((Number) args[0]).intValue() << ((Number) args[1]).intValue();
    }

    private Object handleBitShiftRight(Object[] args) {
        if (args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        return ((Number) args[0]).intValue() >> ((Number) args[1]).intValue();
    }

    // ==================== Advanced Functions ====================

    private Object handleMap(Object[] args) {
        if (args.length < 5 || !(args[0] instanceof Number) || !(args[1] instanceof Number)
                || !(args[2] instanceof Number) || !(args[3] instanceof Number) || !(args[4] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double value = ((Number) args[0]).doubleValue();
        double inMin = ((Number) args[1]).doubleValue();
        double inMax = ((Number) args[2]).doubleValue();
        double outMin = ((Number) args[3]).doubleValue();
        double outMax = ((Number) args[4]).doubleValue();
        return outMin + (outMax - outMin) * ((value - inMin) / (inMax - inMin));
    }

    private Object handleNormalize(Object[] args) {
        if (args.length < 3 || !(args[0] instanceof Number) || !(args[1] instanceof Number) || !(args[2] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double value = ((Number) args[0]).doubleValue();
        double min = ((Number) args[1]).doubleValue();
        double max = ((Number) args[2]).doubleValue();
        if (max == min) return new String[]{"ERROR", "DIVISION_BY_ZERO"};
        return (value - min) / (max - min);
    }

    private Object handlePerlin(Object[] args) {
        // Simplified Perlin-like noise
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double x = ((Number) args[0]).doubleValue();
        double y = (args.length >= 2 && args[1] instanceof Number) ? ((Number) args[1]).doubleValue() : 0;
        return (Math.sin(x * 12.9898 + y * 78.233) * 43758.5453) % 1;
    }

    private Object handleNoise(Object[] args) {
        // Simple noise function
        if (args.length < 1 || !(args[0] instanceof Number)) {
            return new String[]{"ERROR", "INVALID_ARGUMENTS"};
        }
        double x = ((Number) args[0]).doubleValue();
        return Math.sin(x) * Math.cos(x * 1.5) * 0.5 + 0.5;
    }

    @Override
    public FunctionInfo[] getFunctions() {
        return new FunctionInfo[]{
                // Basic arithmetic
                new FunctionInfo("math.abs", "Absolute value", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.max", "Maximum of numbers", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.min", "Minimum of numbers", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.pow", "Power", new String[]{"base: number", "exp: number"}, "number", "Math"),
                new FunctionInfo("math.sqrt", "Square root", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.cbrt", "Cube root", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.round", "Round to nearest integer", new String[]{"n: number", "decimals: int (optional)"}, "number", "Math"),
                new FunctionInfo("math.floor", "Floor (round down)", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.ceil", "Ceiling (round up)", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.mod", "Modulo", new String[]{"a: number", "b: number"}, "number", "Math"),
                new FunctionInfo("math.sign", "Sign of number", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.clamp", "Clamp value to range", new String[]{"n: number", "min: number", "max: number"}, "number", "Math"),
                new FunctionInfo("math.lerp", "Linear interpolation", new String[]{"start: number", "end: number", "t: number"}, "number", "Math"),

                // Trigonometric
                new FunctionInfo("math.sin", "Sine", new String[]{"rad: number"}, "number", "Math"),
                new FunctionInfo("math.cos", "Cosine", new String[]{"rad: number"}, "number", "Math"),
                new FunctionInfo("math.tan", "Tangent", new String[]{"rad: number"}, "number", "Math"),
                new FunctionInfo("math.asin", "Arc sine", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.acos", "Arc cosine", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.atan", "Arc tangent", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.atan2", "Arc tangent 2", new String[]{"y: number", "x: number"}, "number", "Math"),
                new FunctionInfo("math.sinh", "Hyperbolic sine", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.cosh", "Hyperbolic cosine", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.tanh", "Hyperbolic tangent", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.deg", "Radians to degrees", new String[]{"rad: number"}, "number", "Math"),
                new FunctionInfo("math.rad", "Degrees to radians", new String[]{"deg: number"}, "number", "Math"),

                // Logarithmic
                new FunctionInfo("math.log", "Logarithm with base", new String[]{"base: number", "n: number"}, "number", "Math"),
                new FunctionInfo("math.log10", "Base-10 logarithm", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.log2", "Base-2 logarithm", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.ln", "Natural logarithm", new String[]{"n: number"}, "number", "Math"),
                new FunctionInfo("math.exp", "Exponential (e^x)", new String[]{"n: number"}, "number", "Math"),

                // Random
                new FunctionInfo("math.random", "Random number", new String[]{"min: int (optional)", "max: int (optional)"}, "number", "Math"),
                new FunctionInfo("math.randint", "Random integer", new String[]{"min: int (optional)", "max: int (optional)"}, "int", "Math"),
                new FunctionInfo("math.randfloat", "Random float", new String[]{"min: number (optional)", "max: number (optional)"}, "number", "Math"),
                new FunctionInfo("math.randchoice", "Random choice from list", new String[]{"list: array"}, "any", "Math"),
                new FunctionInfo("math.shuffle", "Shuffle list", new String[]{"list: array"}, "array", "Math"),

                // Statistical
                new FunctionInfo("math.sum", "Sum of numbers", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.avg", "Average (mean)", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.mean", "Average (mean)", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.median", "Median value", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.mode", "Most frequent value", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.var", "Variance", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.std", "Standard deviation", new String[]{"...numbers: number"}, "number", "Math"),
                new FunctionInfo("math.minarr", "Minimum in array", new String[]{"arr: array"}, "number", "Math"),
                new FunctionInfo("math.maxarr", "Maximum in array", new String[]{"arr: array"}, "number", "Math"),
                new FunctionInfo("math.range", "Generate range", new String[]{"start: int", "end: int", "step: int (optional)"}, "array", "Math"),

                // Number theory
                new FunctionInfo("math.gcd", "Greatest common divisor", new String[]{"a: int", "b: int"}, "int", "Math"),
                new FunctionInfo("math.lcm", "Least common multiple", new String[]{"a: int", "b: int"}, "int", "Math"),
                new FunctionInfo("math.prime", "Check if prime", new String[]{"n: int"}, "boolean", "Math"),
                new FunctionInfo("math.factors", "Get factors", new String[]{"n: int"}, "array", "Math"),
                new FunctionInfo("math.fib", "Fibonacci number", new String[]{"n: int"}, "int", "Math"),
                new FunctionInfo("math.factorial", "Factorial", new String[]{"n: int"}, "int", "Math"),

                // Constants
                new FunctionInfo("math.pi", "Pi constant", new String[]{}, "number", "Math"),
                new FunctionInfo("math.e", "Euler's number", new String[]{}, "number", "Math"),
                new FunctionInfo("math.tau", "Tau (2*pi)", new String[]{}, "number", "Math"),
                new FunctionInfo("math.inf", "Infinity", new String[]{}, "number", "Math"),
                new FunctionInfo("math.nan", "Not a number", new String[]{}, "number", "Math"),

                // Geometric
                new FunctionInfo("math.hypot", "Hypotenuse", new String[]{"a: number", "b: number"}, "number", "Math"),
                new FunctionInfo("math.dist", "Distance between points", new String[]{"x1: number", "y1: number", "x2: number", "y2: number"}, "number", "Math"),
                new FunctionInfo("math.area.circle", "Circle area", new String[]{"radius: number"}, "number", "Math"),
                new FunctionInfo("math.area.rect", "Rectangle area", new String[]{"width: number", "height: number"}, "number", "Math"),
                new FunctionInfo("math.vol.sphere", "Sphere volume", new String[]{"radius: number"}, "number", "Math"),

                // Bitwise
                new FunctionInfo("math.bit.and", "Bitwise AND", new String[]{"a: int", "b: int"}, "int", "Math"),
                new FunctionInfo("math.bit.or", "Bitwise OR", new String[]{"a: int", "b: int"}, "int", "Math"),
                new FunctionInfo("math.bit.xor", "Bitwise XOR", new String[]{"a: int", "b: int"}, "int", "Math"),
                new FunctionInfo("math.bit.not", "Bitwise NOT", new String[]{"a: int"}, "int", "Math"),
                new FunctionInfo("math.bit.shiftl", "Bit shift left", new String[]{"a: int", "bits: int"}, "int", "Math"),
                new FunctionInfo("math.bit.shiftr", "Bit shift right", new String[]{"a: int", "bits: int"}, "int", "Math"),

                // Advanced
                new FunctionInfo("math.map", "Map value to new range", new String[]{"value: number", "inMin: number", "inMax: number", "outMin: number", "outMax: number"}, "number", "Math"),
                new FunctionInfo("math.norm", "Normalize to 0-1", new String[]{"value: number", "min: number", "max: number"}, "number", "Math"),
                new FunctionInfo("math.perlin", "Perlin-like noise", new String[]{"x: number", "y: number (optional)"}, "number", "Math"),
                new FunctionInfo("math.noise", "Simple noise", new String[]{"x: number"}, "number", "Math")
        };
    }

    @Override
    public String getProviderName() {
        return "MathFunctionProvider";
    }
}
