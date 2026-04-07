# Mathematical Functions

CilExec provides comprehensive mathematical calculation functions, including arithmetic, trigonometric functions, logarithms, random numbers, statistics, number theory, and more.

---

## Basic Arithmetic

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.abs(n)` | `n`: number | `number` | Absolute value |
| `math.max(...numbers)` | `...numbers`: list of numbers | `number` | Maximum value |
| `math.min(...numbers)` | `...numbers`: list of numbers | `number` | Minimum value |
| `math.pow(base, exp)` | `base`: base, `exp`: exponent | `number` | Power operation |
| `math.sqrt(n)` | `n`: number | `number` | Square root |
| `math.cbrt(n)` | `n`: number | `number` | Cube root |
| `math.round(n, decimals)` | `n`: number, `decimals`: decimal places (optional) | `number` | Round |
| `math.floor(n)` | `n`: number | `number` | Floor |
| `math.ceil(n)` | `n`: number | `number` | Ceiling |
| `math.mod(a, b)` | `a`: dividend, `b`: divisor | `number` | Modulo |
| `math.sign(n)` | `n`: number | `number` | Sign function (-1, 0, 1) |
| `math.clamp(n, min, max)` | `n`: number, `min`: minimum, `max`: maximum | `number` | Clamp to range |
| `math.lerp(start, end, t)` | `start`: start value, `end`: end value, `t`: interpolation factor (0-1) | `number` | Linear interpolation |

**Examples:**
```fcl
# Basic arithmetic
x = math.abs(-10)           # 10
y = math.pow(2, 8)          # 256
z = math.sqrt(16)           # 4
w = math.clamp(150, 0, 100) # 100 (clamped to 0-100)

# Rounding
a = math.round(3.14159, 2)  # 3.14
b = math.floor(3.9)         # 3
c = math.ceil(3.1)          # 4

# Sign function
s1 = math.sign(-5)          # -1
s2 = math.sign(0)           # 0
s3 = math.sign(10)          # 1

# Linear interpolation
val = math.lerp(0, 100, 0.5) # 50 (interpolate 50% between 0 and 100)
```

---

## Trigonometric Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.sin(rad)` | `rad`: radians | `number` | Sine |
| `math.cos(rad)` | `rad`: radians | `number` | Cosine |
| `math.tan(rad)` | `rad`: radians | `number` | Tangent |
| `math.asin(n)` | `n`: number | `number` | Arc sine |
| `math.acos(n)` | `n`: number | `number` | Arc cosine |
| `math.atan(n)` | `n`: number | `number` | Arc tangent |
| `math.atan2(y, x)` | `y`: Y coordinate, `x`: X coordinate | `number` | Arc tangent 2 |
| `math.sinh(n)` | `n`: number | `number` | Hyperbolic sine |
| `math.cosh(n)` | `n`: number | `number` | Hyperbolic cosine |
| `math.tanh(n)` | `n`: number | `number` | Hyperbolic tangent |
| `math.deg(rad)` | `rad`: radians | `number` | Radians to degrees |
| `math.rad(deg)` | `deg`: degrees | `number` | Degrees to radians |

**Examples:**
```fcl
# Trigonometric functions (parameters in radians)
angle = math.rad(90)        # π/2 (degrees to radians)
s = math.sin(angle)         # 1.0
c = math.cos(angle)         # close to 0
t = math.tan(angle)         # very large number

# Inverse trigonometric functions
asinVal = math.asin(1)      # π/2
acosVal = math.acos(0)      # π/2

# Angle conversion
deg = math.deg(math.pi)     # 180.0
rad = math.rad(180)         # π
```

---

## Logarithms and Exponents

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.log(base, n)` | `base`: base, `n`: number | `number` | Logarithm |
| `math.log10(n)` | `n`: number | `number` | Common logarithm (base 10) |
| `math.log2(n)` | `n`: number | `number` | Binary logarithm (base 2) |
| `math.ln(n)` | `n`: number | `number` | Natural logarithm (base e) |
| `math.exp(n)` | `n`: number | `number` | e to the power of n |

**Examples:**
```fcl
# Logarithms
log10 = math.log10(100)     # 2.0
log2 = math.log2(8)         # 3.0
ln = math.ln(math.e)        # 1.0

# Exponential
e2 = math.exp(2)            # e² ≈ 7.389
```

---

## Random Numbers

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.random(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `number` | Random number (0-1 or specified range) |
| `math.randint(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `int` | Random integer |
| `math.randfloat(min, max)` | `min`: minimum (optional), `max`: maximum (optional) | `number` | Random float |
| `math.randchoice(list)` | `list`: array | `any` | Random choice from array |
| `math.shuffle(list)` | `list`: array | `array` | Shuffle array |

**Examples:**
```fcl
# Random numbers
r = math.random()           # Random number between 0-1
r2 = math.random(10, 20)    # Random number between 10-20

# Random integers
dice = math.randint(1, 6)   # Random integer 1-6 (dice)
coin = math.randint(0, 1)   # 0 or 1 (coin flip)

# Random choice
fruits = ["apple", "banana", "orange"]
choice = math.randchoice(fruits)  # Randomly select a fruit

# Shuffle array
cards = [1, 2, 3, 4, 5]
shuffled = math.shuffle(cards)    # Randomly shuffle order
```

---

## Statistical Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.sum(...numbers)` | `...numbers`: list of numbers | `number` | Sum |
| `math.avg(...numbers)` | `...numbers`: list of numbers | `number` | Average |
| `math.mean(...numbers)` | `...numbers`: list of numbers | `number` | Mean (same as avg) |
| `math.median(...numbers)` | `...numbers`: list of numbers | `number` | Median |
| `math.mode(...numbers)` | `...numbers`: list of numbers | `number` | Mode |
| `math.var(...numbers)` | `...numbers`: list of numbers | `number` | Variance |
| `math.std(...numbers)` | `...numbers`: list of numbers | `number` | Standard deviation |
| `math.minarr(arr)` | `arr`: array | `number` | Minimum in array |
| `math.maxarr(arr)` | `arr`: array | `number` | Maximum in array |
| `math.range(start, end, step)` | `start`: start, `end`: end, `step`: step (optional) | `array` | Generate range array |

**Examples:**
```fcl
# Statistics
data = [1, 2, 3, 4, 5]
sum = math.sum(data)        # 15
avg = math.avg(data)        # 3.0
median = math.median(data)  # 3
std = math.std(data)        # Standard deviation

# Generate range
nums = math.range(1, 10)    # [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
evens = math.range(0, 10, 2) # [0, 2, 4, 6, 8, 10]
```

---

## Number Theory

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.gcd(a, b)` | `a`: integer, `b`: integer | `int` | Greatest common divisor |
| `math.lcm(a, b)` | `a`: integer, `b`: integer | `int` | Least common multiple |
| `math.prime(n)` | `n`: integer | `boolean` | Check if prime |
| `math.factors(n)` | `n`: integer | `array` | Get all factors |
| `math.fib(n)` | `n`: integer | `int` | nth Fibonacci number |
| `math.factorial(n)` | `n`: integer | `int` | Factorial |

**Examples:**
```fcl
# Number theory
gcd = math.gcd(12, 18)      # 6
lcm = math.lcm(4, 6)        # 12

p = math.prime(17)          # true (is prime)
p2 = math.prime(18)         # false

f = math.factors(12)        # [1, 2, 3, 4, 6, 12]
fib10 = math.fib(10)        # 55
fact5 = math.factorial(5)   # 120
```

---

## Mathematical Constants

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.pi` | None | `number` | π (3.14159...) |
| `math.e` | None | `number` | Euler's number e (2.71828...) |
| `math.tau` | None | `number` | 2π (6.28318...) |
| `math.inf` | None | `number` | Positive infinity |
| `math.nan` | None | `number` | Not a number |

**Examples:**
```fcl
# Using constants
circumference = 2 * math.pi * 5  # Circle circumference
area = math.pi * math.pow(5, 2)  # Circle area

# Check special values
if x == math.inf {
    println("x is infinity")
}
```

---

## Geometry

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.hypot(a, b)` | `a`: leg, `b`: leg | `number` | Hypotenuse length |
| `math.dist(x1, y1, x2, y2)` | Two point coordinates | `number` | Distance between two points |
| `math.area.circle(radius)` | `radius`: radius | `number` | Circle area |
| `math.area.rect(width, height)` | `width`: width, `height`: height | `number` | Rectangle area |
| `math.vol.sphere(radius)` | `radius`: radius | `number` | Sphere volume |

**Examples:**
```fcl
# Geometry calculations
hyp = math.hypot(3, 4)      # 5.0 (Pythagorean theorem)
dist = math.dist(0, 0, 3, 4) # 5.0 (distance between two points)

area = math.area.circle(5)  # 78.54...
rect = math.area.rect(4, 5) # 20.0
vol = math.vol.sphere(3)    # Sphere volume
```

---

## Bitwise Operations

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.bit.and(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise AND |
| `math.bit.or(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise OR |
| `math.bit.xor(a, b)` | `a`: integer, `b`: integer | `int` | Bitwise XOR |
| `math.bit.not(a)` | `a`: integer | `int` | Bitwise NOT |
| `math.bit.shiftl(a, bits)` | `a`: integer, `bits`: bits | `int` | Left shift |
| `math.bit.shiftr(a, bits)` | `a`: integer, `bits`: bits | `int` | Right shift |

**Examples:**
```fcl
# Bitwise operations
and = math.bit.and(5, 3)    # 1 (101 & 011 = 001)
or = math.bit.or(5, 3)      # 7 (101 | 011 = 111)
xor = math.bit.xor(5, 3)    # 6 (101 ^ 011 = 110)
not = math.bit.not(5)       # Bitwise NOT

# Shifts
left = math.bit.shiftl(1, 3)  # 8 (1 << 3)
right = math.bit.shiftr(8, 2) # 2 (8 >> 2)
```

---

## Advanced Functions

| Function | Parameters | Return Value | Description |
|----------|------------|--------------|-------------|
| `math.map(value, inMin, inMax, outMin, outMax)` | Input value and ranges | `number` | Map value to new range |
| `math.norm(value, min, max)` | `value`: value, `min`: minimum, `max`: maximum | `number` | Normalize to 0-1 |
| `math.perlin(x, y)` | `x`: X coordinate, `y`: Y coordinate (optional) | `number` | Perlin noise |
| `math.noise(x)` | `x`: coordinate | `number` | Simple noise |

**Examples:**
```fcl
# Map value to new range
# Map 0-100 value to 0-1
normalized = math.map(50, 0, 100, 0, 1)  # 0.5

# Normalize
n = math.norm(50, 0, 100)  # 0.5

# Noise functions
noise1d = math.noise(1.5)
noise2d = math.perlin(1.5, 2.3)
```
