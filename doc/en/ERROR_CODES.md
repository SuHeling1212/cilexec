# Error Codes Reference

## Return Value Standards

All functions returning `String[]` follow a unified format:
- Success: `["SUCCESS", null]` or `["SUCCESS", data]`
- Failure: `["ERROR", "ERROR_CODE"]`

## File Operation Error Codes

| Error Code | Description |
|------------|-------------|
| `INVALID_PATH` | Invalid path |
| `FILE_DOES_NOT_EXIST` | File does not exist |
| `DIRECTORY_DOES_NOT_EXIST` | Directory does not exist |
| `FILE_EXIST` | File already exists |
| `DIRECTORY_EXIST` | Directory already exists |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |
| `FILE_IS_LOCKED` | File is locked |
| `DIRECTORY_IS_LOCKED` | Directory is locked |
| `IS_NOT_FILE` | Path is not a file |
| `IS_NOT_DIRECTORY` | Path is not a directory |
| `DIRECTORY_IS_NOT_EMPTY` | Directory is not empty |

## Process Operation Error Codes

| Error Code | Description |
|------------|-------------|
| `PROCESS_DOES_NOT_EXIST` | Process does not exist |
| `CANNOT_KILL_INIT` | Cannot terminate INIT process |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |

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
| `POOL_EXISTS` | Swap pool already exists |
| `POOL_DOES_NOT_EXIST` | Swap pool does not exist |
| `VARIABLE_EXISTS` | Variable already exists |
| `VARIABLE_DOES_NOT_EXIST` | Variable does not exist |
| `VARIABLE_IS_LOCKED` | Variable is locked |
| `INSUFFICIENT_PERMISSION` | Insufficient permission |

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
