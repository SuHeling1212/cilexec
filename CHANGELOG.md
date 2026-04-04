# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-04-04

### Added

#### Testing Framework
- ✅ Integrated JUnit 5.10.2 testing framework
- ✅ Added 131 comprehensive unit tests
  - FileUtilTest: 33 tests
  - JsonUtilTest: 33 tests
  - UserUtilTest: 25 tests
  - ConstantsTest: 40 tests
- ✅ Created test directory structure (src/test/java)

#### Exception Handling System
- ✅ Created exception handling package (process/exception/)
  - ExceptionContext.java: Encapsulates exception context information
  - ProcessException.java: Base exception class
  - RecoverableException.java: Recoverable exception types
  - UnrecoverableException.java: Unrecoverable exception types
- ✅ Distinguished between recoverable and unrecoverable exceptions
- ✅ Added detailed exception logging with full stack traces

#### Security Enhancements
- ✅ Added path whitelist validation in FileUtil
  - Only allows letters, numbers, underscores, hyphens, and dots
  - Prevents path traversal attacks
- ✅ Enhanced permission checking in UserUtil
  - Added PermissionResult class for detailed error information
  - Added validatePermission() method with context
  - Added validateProcessPermission() method
- ✅ Fixed resource leaks in SocketUtil
  - Used try-with-resources for all Socket operations
  - Fixed 4 resource leak locations

#### Code Quality Improvements
- ✅ Added 50+ null pointer checks
  - ProcessFunc: 25+ null checks for JSON parsing
  - FileUtil: null checks for file operations
  - ProcessRunner: null checks for critical variables
  - JsonUtil: null checks for JSON parsing
- ✅ Extracted magic numbers into Constants.java
  - Process constants: PROCESS_TICK_MS, TIME_DIVISOR
  - Socket constants: DEFAULT_TIMEOUT, BUFFER_SIZE, etc.
  - File size constants: SIZE_UNIT_KB, SIZE_UNIT_MB, SIZE_UNIT_GB
- ✅ Reduced code duplication in FileUtil
  - Extracted checkAndValidatePermission() method
  - Extracted checkAndValidateLock() method
  - Extracted updateMetadata() method
  - Eliminated 100+ lines of duplicate code

### Changed

#### Configuration
- ✅ Improved .gitignore
  - Added log files (*.log)
  - Added VFS root directory (cilexec_root/)
  - Added compiled files (*.class)
  - Added IDE configs (.idea/, *.iml, .vscode/)
  - Added OS files (.DS_Store, Thumbs.db)

#### Documentation
- ✅ Updated README.md
  - Added version information (1.0.0)
  - Added testing instructions
  - Updated build and run instructions
  - Added project structure overview
- ✅ Updated 读我.md (Chinese README)
  - Added version information (1.0.0)
  - Added testing instructions
  - Updated build and run instructions
  - Added project structure overview
- ✅ Updated PROJECT_STRUCTURE.md (both EN and CN)
  - Added exception package
  - Added test directory structure
  - Added v1.0.0 improvements section
- ✅ Updated ERROR_CODES.md (both EN and CN)
  - Added PATH_TRAVERSAL_DETECTED error code
  - Added detailed descriptions and solutions

### Fixed

- ✅ Fixed resource leaks in SocketUtil
  - ServerSocket resources
  - Socket resources
  - DatagramSocket resources
- ✅ Fixed potential NullPointerExceptions
  - JSON parsing results
  - File operation results
  - Process data structures

### Security

- ✅ Enhanced path validation to prevent path traversal attacks
- ✅ Improved permission checking with detailed error context
- ✅ Fixed resource management to prevent leaks

## Version History

- **1.0.0** (2026-04-04): First stable release with comprehensive improvements
- **1.0.4-SNAPSHOT**: Development version with initial improvements

---

## Statistics

### Code Changes
- Files modified: 15+
- Lines added: 3000+
- Lines removed: 100+
- New files created: 8

### Test Coverage
- Total tests: 131
- Test classes: 4
- Pass rate: 100%

### Quality Metrics
- Null checks added: 50+
- Constants extracted: 11
- Duplicate code reduced: 100+ lines
- Resource leaks fixed: 4

---

For more details about the improvements, see:
- [Project Structure](doc/zh-CN/项目结构.md)
- [Error Codes](doc/zh-CN/错误码.md)
