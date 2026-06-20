# Logging System

## Log File Location

Log file `app.log` is located in the program's run directory (same directory as the JAR file).

## Log Format

```
[2024-01-15 10:30:25] [INFO] Operation success
[2024-01-15 10:30:26] [ERROR] Error: file not found
```

## Startup and Shutdown Markers

Each program run automatically adds separator markers:

```
============================================================
[2024-01-15 10:30:25] [STARTUP] Application started
============================================================
[2024-01-15 10:30:25] [INFO] Registered 5 function providers
...
============================================================
[2024-01-15 10:30:30] [SHUTDOWN] Application ended
============================================================
```

## Log Levels

- `DEBUG` - Debugging information
- `INFO` - General information (default level)
- `WARN` - Warning information
- `ERROR` - Error information

## Usage

```java
import com.follarce.basicUtil.Logger;

Logger.debug("Debug message");
Logger.info("Info message");
Logger.warn("Warning message");
Logger.error("Error message");
Logger.error("Error with exception", throwable);
```
