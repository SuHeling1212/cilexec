# Error Codes Reference

## Return Value Standards

All functions returning `String[]` follow a unified format:
- Success: `["SUCCESS", null]` or `["SUCCESS", data]`
- Failure: `["ERROR", "ERROR_CODE"]`

## File Operation Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_PATH` | Invalid path |
| `INVALID_SOURCE_PATH` | Invalid source path |
| `INVALID_NAME` | Invalid name |
| `INVALID_NEW_NAME` | Invalid new name |
| `PATH_TRAVERSAL_DETECTED` | Path traversal attack detected |
| `FILE_DOES_NOT_EXIST` | File does not exist |
| `SOURCE_FILE_DOES_NOT_EXIST` | Source file does not exist |
| `DIRECTORY_DOES_NOT_EXIST` | Directory does not exist |
| `FILE_EXIST` | File already exists |
| `DIRECTORY_EXIST` | Directory already exists |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |
| `FILE_IS_LOCKED` | File is locked |
| `FILE_IS_NOT_LOCKED` | File is not locked |
| `DIRECTORY_IS_LOCKED` | Directory is locked |
| `IS_NOT_FILE` | Path is not a file |
| `IS_NOT_DIRECTORY` | Path is not a directory |
| `DIRECTORY_IS_NOT_EMPTY` | Directory is not empty |
| `CREATE_LINK_FAILED` | Failed to create symbolic link |
| `RENAME_FAILED` | Rename failed |
| `CHECK_LOCK_FAILED` | Failed to check lock status |
| `LOCK_FAILED` | Failed to lock |
| `UNLOCK_FAILED` | Failed to unlock |
| `META_DATA_FILE_DOES_NOT_EXIST` | Metadata file does not exist |
| `INVALID_META_FORMAT` | Invalid metadata format |
| `META_NOT_CLOSED` | Metadata is not closed |
| `NO_META` | No metadata |
| `APPEND_FAILED` | Append failed |

## Process Operation Error Codes

| Error Code | Description |
|------------|-------------|
| `PROCESS_NOT_FOUND` | Process not found |
| `PROCESS_DOES_NOT_EXIST` | Process does not exist |
| `PROCESS_IS_PAUSED` | Process is paused |
| `PROCESS_IS_RUNNING` | Process is running |
| `CHILD_PROCESS_DOES_NOT_EXIST` | Child process does not exist |
| `PID_DOES_NOT_CHILD_PROCESS` | PID is not a child process |
| `CANNOT_KILL_INIT` | Cannot terminate INIT process |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |
| `INTERRUPTED` | Operation interrupted |

## User Management Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_USERNAME` | Invalid username |
| `INVALID_PASSWORD` | Invalid password |
| `USER_EXISTS` | User already exists |
| `USER_NOT_EXISTS` | User does not exist |
| `CANNOT_REMOVE_LOCAL` | Cannot remove local user |
| `SAVE_FAILED` | Save failed |
| `READ_FAILED` | Read failed |
| `INVALID_USER_DATA` | Invalid user data |
| `USERNAME_MUST_BE_STRING` | Username must be string |
| `PASSWORD_MUST_BE_STRING` | Password must be string |
| `ISLOCAL_MUST_BE_BOOLEAN` | isLocal must be boolean |
| `TOO_MANY_ARGUMENTS` | Too many arguments |
| `UNKNOWN_FUNCTION` | Unknown function |

## Swap Pool Error Codes

| Error Code | Description |
|------------|-------------|
| `SWAP_POOL_EXIST` | Swap pool already exists |
| `SWAP_POOL_DOES_NOT_EXIST` | Swap pool does not exist |
| `INVALID_SWAP_POOL` | Invalid swap pool |
| `VARIABLE_EXISTS` | Variable already exists |
| `VARIABLE_DOES_NOT_EXIST` | Variable does not exist |
| `VARIABLE_IS_LOCKED` | Variable is locked |
| `SOME_VAR_IS_LOCKED` | Some variables are locked |
| `VARIABLE_EXPIRED` | Variable has expired |
| `INVALID_PARAMETER` | Invalid parameter |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |

## Environment Variable Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_ENV_NAME` | Invalid environment variable name |
| `INVALID_ENV_VALUE` | Invalid environment variable value |
| `ENV_VAR_NOT_FOUND` | Environment variable not found |
| `SET_ENV_FAILED` | Failed to set environment variable |
| `GET_ENV_FAILED` | Failed to get environment variable |
| `LIST_ENV_FAILED` | Failed to list environment variables |
| `DELETE_ENV_FAILED` | Failed to delete environment variable |
| `PROCESS_NOT_FOUND` | Process not found |

## Network Download Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_URL` | URL is empty or malformed |
| `INVALID_SAVE_DIR` | Save directory is empty |
| `SAVE_DIR_MUST_BE_STRING` | Save directory must be string |
| `CANNOT_EXTRACT_FILENAME` | Cannot extract filename from URL |
| `TOO_MANY_REDIRECTS` | Too many redirects |
| `RESOURCE_NOT_FOUND` | HTTP 404 |
| `ACCESS_FORBIDDEN` | HTTP 403 |
| `UNAUTHORIZED` | HTTP 401 |
| `SERVER_ERROR` | HTTP 5xx error |
| `CONNECTION_TIMEOUT` | Connection timeout |
| `UNKNOWN_HOST` | Cannot resolve host |
| `CONNECTION_REFUSED` | Connection refused |
| `IO_ERROR` | I/O error |
| `DOWNLOAD_FAILED` | Download failed |

## Socket Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_HOST` | Invalid host address |
| `INVALID_PORT` | Invalid port number (1-65535) |
| `INVALID_SAVE_DIR` | Invalid save directory |
| `INVALID_DATA` | Invalid data |
| `INVALID_TIMEOUT` | Invalid timeout |
| `SOCKET_ID_MUST_BE_NUMBER` | Socket ID must be number |
| `HOST_MUST_BE_STRING` | Host must be string |
| `PORT_MUST_BE_NUMBER` | Port must be number |
| `DATA_MUST_BE_STRING` | Data must be string |
| `SAVE_DIR_MUST_BE_STRING` | Save directory must be string |
| `SOCKET_DOES_NOT_EXIST` | Socket does not exist |
| `SOCKET_CLOSED` | Socket is closed |
| `NOT_SERVER_SOCKET` | Not a server socket |
| `NOT_UDP_SOCKET` | Not a UDP socket |
| `INVALID_SOCKET_TYPE` | Invalid socket type |
| `PORT_IN_USE` | Port already in use |
| `CREATE_SOCKET_FAILED` | Failed to create socket |
| `CONNECT_FAILED` | Failed to connect |
| `ACCEPT_FAILED` | Failed to accept connection |
| `ACCEPT_TIMEOUT` | Accept timeout |
| `SEND_FAILED` | Failed to send |
| `RECEIVE_FAILED` | Failed to receive |
| `RECEIVE_TIMEOUT` | Receive timeout |
| `NO_DATA_RECEIVED` | No data received |
| `CONNECTION_REFUSED` | Connection refused |

## General Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_ARGUMENTS` | Invalid arguments |
| `INVALID_JSON` | Invalid JSON format |
| `CREATE_FAILED` | Create failed |
| `DELETE_FAILED` | Delete failed |
| `WRITE_FAILED` | Write failed |
| `READ_FAILED` | Read failed |
| `RENAME_FAILED` | Rename failed |

## Detailed Error Code Descriptions

### Path Validation Error Codes

#### `PATH_TRAVERSAL_DETECTED`

**Description**: Path traversal attack detected, access denied by the system.

**Causes**:
- Path contains directory traversal characters like `..`, attempting to access files outside the Virtual File System (VFS) root directory
- Normalized path exceeds the VFS root directory scope
- Path contains special characters like `~`

**Solutions**:
1. Check if the path contains special characters like `..` or `~`
2. Ensure the path is within the VFS root directory scope
3. When using relative paths, verify the base path is correct
4. Avoid constructing file paths directly from user input

**Examples**:
```
Invalid: /user/../../../etc/passwd
Valid:   /user/local/app/data.txt
```

#### `INVALID_PATH`

**Description**: Path format is invalid or empty.

**Causes**:
- Path is an empty string or null
- Path format does not conform to specifications
- Path contains illegal characters (non-alphanumeric, underscore, hyphen, or dot)

**Solutions**:
1. Ensure the path is not empty
2. Check if the path format is correct (should start with `/`)
3. Ensure path components only contain allowed characters: letters, digits, `_`, `-`, `.`

**Examples**:
```
Invalid: user/data (missing leading /)
Invalid: /user/data@file (contains illegal character @)
Valid:   /user/local/app/data.txt
```

#### `INVALID_NAME`

**Description**: File name or directory name is invalid.

**Causes**:
- Name is empty or contains only whitespace
- Name starts with `.` (hidden file)
- Name contains illegal characters (non-alphanumeric, underscore, hyphen, or dot)
- Name contains path separators `/` or `\`

**Solutions**:
1. Ensure the name is not empty
2. Name cannot start with `.`
3. Name can only contain letters, digits, `_`, `-`, `.`
4. Do not include path separators in the name

**Examples**:
```
Invalid: .hidden (starts with dot)
Invalid: file@name (contains illegal character @)
Invalid: dir/file (contains path separator)
Valid:   data_file-2024.txt
```

#### `INVALID_SOURCE_PATH`

**Description**: Source path is invalid.

**Causes**:
- Source path is empty or has incorrect format
- Source path contains illegal characters

**Solutions**:
1. Check the source path format
2. Ensure the source path conforms to path naming conventions
3. Refer to the solutions for `INVALID_PATH`

#### `INVALID_NEW_NAME`

**Description**: New name is invalid.

**Causes**:
- New name does not conform to naming conventions
- New name contains illegal characters
- New name starts with `.`

**Solutions**:
1. Refer to the solutions for `INVALID_NAME`
2. Ensure the new name conforms to naming conventions
