# Architecture Design

## "Zero Memory State" Design Principle

CilExec's core design principle is **all system state is persisted to the file system, with no business state kept in memory**. This brings the following advantages:

- **Extreme fault tolerance**: Power outages or kill -9 won't lose state
- **Constant memory footprint**: Independent of process count or data volume
- **Completely transparent state**: View files directly to understand system state
- **Recoverability**: Restart and resume execution from files

## Architecture Limitations

### Fully Memory-Less Modules ✅

| Module | Implementation | State Storage Location |
|--------|----------------|----------------------|
| Virtual File System (FileUtil) | All file operations directly read/write disk | `/system/files/` |
| Process Management (ProcessFunc) | Process state saved as JSON | `/system/process/*.json` |
| Swap Pool (SwapUtil) | Variable data persisted to files | `/system/swap/*.json` |
| User System (UserUtil) | User info stored in config file | `/system/config/users.json` |

### Modules Limited by Technical Constraints ⚠️

**Socket Network Functionality (SocketUtil)**

**Reason**:
1. Java Socket objects cannot be serialized (`java.net.Socket` does not implement `Serializable`)
2. Socket connections are real OS kernel resources, not pure Java objects
3. Real OS kernels do not persist TCP connection state; all sockets are forcibly closed when a process terminates
4. The TCP protocol itself is connection-oriented with state; after disconnection, the three-way handshake must be re-established

**Impact**:
- All socket connections are lost after system restart
- Socket ID generator is in memory, may produce duplicate IDs after restart

**Mitigation**:
- Socket metadata (ID, configuration) still saved to `/system/sockets/*.json`
- Actual connection objects must be maintained in memory
- Automatically clean up all sockets on process exit

**Educational Value**:
This serves as an excellent teaching case for understanding the distinction between "persistable state" and "temporary runtime resources." Some resources (network connections, file handles, threads) are inherently temporary and cannot be persisted.
