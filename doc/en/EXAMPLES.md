# Usage Examples

## Example 1: Basic File Operations

```fcl
// Create file and write content
createFile("/user/local/app/", "test.txt")
write("/user/local/app/test.txt", "Hello World")

// Read file
result = read("/user/local/app/test.txt")
if result[0] == "SUCCESS" {
    content = result[1]
}
```

## Example 2: Process Creation

```fcl
pid = fork()
if pid == 0 {
    // Child process
    exec("/user/local/app/child.txt", [])
} else {
    // Parent process
    waitPID(pid)
}
```

## Example 3: Swap Pool Usage

```fcl
// Create swap pool
swapPool.create("shared")

// Add variable (permanent)
swapPool.add("counter:0", "shared", ["always"])

// Add variable (limit to 3 reads)
swapPool.add("token:abc123", "shared", ["times(3)"])

// Get variable
value = swapPool.get("counter", "shared")
```

## Example 4: Network Download

```fcl
// Download image to specified directory (filename auto-extracted)
result = webget("https://example.com/image.png", "/user/local/downloads/")
if result[0] == "SUCCESS" {
    filename = result[1]  // "image.png"
}

// Download file with 30-second timeout
result = webget("https://example.com/archive.zip", "/user/local/downloads/", 30000)
```

## Example 5: Socket TCP Communication

```fcl
# TCP Server (using default save directory)
# Local user default: /user/local/sockets/
# Regular user alice default: /user/alice/sockets/
result = socket.createServer("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    serverId = int(result[1])
    
    # Accept client connection
    clientResult = socket.accept(serverId)
    if clientResult[0] == "SUCCESS" {
        clientId = int(clientResult[1])
        
        # Receive data (auto-saved to default directory)
        recvResult = socket.receive(clientId)
        if recvResult[0] == "SUCCESS" {
            filename = recvResult[1]  # e.g., "socket_2_20260321_201145_123.dat"
        }
        
        # Send response
        socket.send(clientId, "Hello Client!")
        socket.close(clientId)
    }
    socket.close(serverId)
}

# TCP Client (using default save directory)
result = socket.connect("127.0.0.1", 8080)
if result[0] == "SUCCESS" {
    socketId = int(result[1])
    socket.send(socketId, "Hello Server!")
    recvResult = socket.receive(socketId)
    socket.close(socketId)
}

# Regular user attempting to save to system directory (will fail)
result = socket.createServer("127.0.0.1", 8081, "/system/data/")
# Returns: ["ERROR", "INSUFFICIENT_PERMISSION"]
```

## Example 6: Socket UDP Communication

```fcl
# Create UDP socket (port 0 for auto-assign, using default save directory)
result = socket.createUdp("0.0.0.0", 0)
if result[0] == "SUCCESS" {
    udpSocket = int(result[1])
    
    # Send UDP packet to specified address
    socket.sendTo(udpSocket, "127.0.0.1", 9090, "Hello UDP!")
    
    # Receive data (auto-saved to default directory)
    recvResult = socket.receive(udpSocket)
    if recvResult[0] == "SUCCESS" {
        filename = recvResult[1]
    }
    
    socket.close(udpSocket)
}
```

## Example 7: Mathematical Functions

```fcl
# Basic arithmetic
x = math.abs(-10)           # 10
y = math.pow(2, 8)          # 256
z = math.sqrt(16)           # 4
w = math.clamp(x, 0, 100)   # Clamp to 0-100

# Trigonometry
angle = math.rad(90)        # π/2
s = math.sin(angle)         # 1.0

# Random numbers
r = math.random()           # Random number 0-1
dice = math.randint(1, 6)   # Random integer 1-6

# Statistics
data = [1, 2, 3, 4, 5]
sum = math.sum(data)        # 15
avg = math.avg(data)        # 3.0
std = math.std(data)        # Standard deviation

# Number theory
p = math.prime(17)          # true
f = math.factors(12)        # [1, 2, 3, 4, 6, 12]
fib10 = math.fib(10)        # 55

# Geometry
area = math.area.circle(5)  # 78.54...
dist = math.dist(0, 0, 3, 4) # 5.0

# Bitwise operations
result = math.bit.and(5, 3) # 1
```
