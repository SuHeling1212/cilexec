# cilexec API 文档

## 简介

### 系统概述

`cilexec` 是一个单一二进制可执行文件的纯磁盘教学操作系统，它将 Unix 和 Linux 的「万物皆文件」理念贯彻得更加彻底：

- **无内存设计**：去除内存概念，将磁盘作为唯一的读写设备
- **文件化状态**：所有系统状态和进程都作为文件存储在磁盘中
- **单一可执行文件**：包含安装、启动和所有与硬件直接交互的 API 及系统基本内核
- **Python 集成**：在用户编程中使用 Python，将 Python 解释器集成入内核
- **数据安全**：由于无内存设计，不用担心断电数据丢失
- **空安全**：由于没有内存，所以也没有指针或内存地址，避免了空指针问题

### 设计理念

- **文件即状态**：系统中的一切（进程、变量、数组、方法）都以文件形式存在
- **磁盘 IO 优化**：使用类似于 git diff 的方法只改变不一样的部分，或当硬盘请求淤积到某一大小时再集体写入
- **可修改性**：可以先保存状态（文件）关闭系统，在真实系统上打开对应文件进行修改，再打开 cilexec 可执行文件，系统会自动加载「内存」并继续工作

## API 参考

### 时间 API

#### `getTime()`

**功能**：获取当前系统时间

**参数**：无

**返回值**：
- 成功：`int[]` 时间数组，格式为 `{yyyy, mm, dd, hh, min, sec, ms}`
- 失败：无

**示例**：
```python
# 调用示例
time_array = getTime()
print(time_array)  # 输出: [2026, 2, 15, 10, 30, 45, 123]
```

### 文件 API

#### `read(path)`

**功能**：读取文件全部内容

**参数**：
- `path` (string)：文件完整路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 文件路径字符串
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
content = read("/path/to/file.txt")
if content[0] == "ERROR_INSUFFICIENT_PERMISSION":
    print("权限不足")
else:
    print(content)  # 输出文件内容数组
```

#### `write(path, content)`

**功能**：清空文件内容并写入新内容

**参数**：
- `path` (string)：文件完整路径
- `content` (string)：写入内容

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`FILE_IS_NOT_LOCKED`：文件未锁定
  - `ERROR`和`FILE_IS_LOCKED`：文件已被其他进程锁定
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
result = write("/path/to/file.txt", "Hello, cilexec!")
if result[0] == "SUCCESS":
    print("写入成功")
else:
    print(f"写入失败: {result[0]}")
```

#### `getListOfFileAndDirectory(path)`

**功能**：获取目录下的文件和目录列表，按字母顺序排序

**参数**：
- `path` (string)：目录路径（结尾是 `/`）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 文件名和目录名数组（目录名后面有 `/`）
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录

**示例**：
```python
# 调用示例
items = getListOfFileAndDirectory("/path/to/directory/")
if items[0] == "ERROR"和"INSUFFICIENT_PERMISSION":
    print("权限不足")
else:
    print(items)  # 输出目录内容列表
```

#### `readFileMetaData(path)`

**功能**：读取文件元信息

**参数**：
- `path` (string)：文件完整路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 元信息内容（JSON 格式，一定不为 null 但可能为 `{}`）
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
meta_data = readFileMetaData("/path/to/file.txt")
if meta_data[0].startswith("ERROR_"):
    print(f"读取失败: {meta_data[0]}")
else:
    print(meta_data)  # 输出元信息
```

#### `writeFileMetaData(path, content)`

**功能**：清空文件元信息内容并写入新内容

**参数**：
- `path` (string)：文件完整路径
- `content` (string)：写入内容（JSON 格式）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`FILE_NOT_LOCK`：文件未锁定
  - `ERROR`和`FILE_IS_LOCKED`：文件已被其他进程锁定
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
result = writeFileMetaData("/path/to/file.txt", '{"author": "user", "created": "2026-02-15"}')
if result[0] == "SUCCESS":
    print("写入成功")
else:
    print(f"写入失败: {result[0]}")
```

#### `createFile(path, name)`

**功能**：创建文件

**参数**：
- `path` (string)：目录路径（结尾是 `/`）
- `name` (string)：文件名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`INVALID_NAME`：文件名无效
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录
  - `ERROR`和`FILE_EXIST`：文件已存在
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
result = createFile("/path/to/directory/", "new_file.txt")
if result[0] == "SUCCESS":
    print("文件创建成功")
else:
    print(f"文件创建失败: {result[0]}")
```

#### `removeFile(path)`

**功能**：删除文件

**参数**：
- `path` (string)：文件完整路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`FILE_IS_LOCKED`：文件已被锁定
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在

**示例**：
```python
# 调用示例
result = removeFile("/path/to/file.txt")
if result[0] == "SUCCESS":
    print("文件删除成功")
else:
    print(f"文件删除失败: {result[0]}")
```

#### `createDirectory(path, name)`

**功能**：创建目录

**参数**：
- `path` (string)：目录路径（结尾是 `/`）
- `name` (string)：目录名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录
  - `ERROR`和`DIRECTORY_EXIST`：目录已存在

**示例**：
```python
# 调用示例
result = createDirectory("/path/to/directory/", "new_directory")
if result[0] == "SUCCESS":
    print("目录创建成功")
else:
    print(f"目录创建失败: {result[0]}")
```

#### `removeDirectory(path)`

**功能**：删除空目录

**参数**：
- `path` (string)：目录路径（结尾是 `/`）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`DIRECTORY_IS_NOT_EMPTY`：目录不为空
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录

**示例**：
```python
# 调用示例
result = removeDirectory("/path/to/directory/")
if result[0] == "SUCCESS":
    print("目录删除成功")
else:
    print(f"目录删除失败: {result[0]}")
```

#### `readDirectoryMetaData(path)`

**功能**：读取目录元信息文件

**参数**：
- `path` (string)：目录路径（结尾是 `/`）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 元信息内容（JSON 格式，一定不为 null 但可能为 `{}`）
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`META_DATA_FILE_DOES_NOT_EXIST`：元信息文件不存在
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录

**示例**：
```python
# 调用示例
meta_data = readDirectoryMetaData("/path/to/directory/")
if meta_data[0].startswith("ERROR_"):
    print(f"读取失败: {meta_data[0]}")
else:
    print(meta_data)  # 输出元信息
```

#### `writeDirectoryMetaData(path, content)`

**功能**：清空目录元信息内容并写入新内容

**参数**：
- `path` (string)：目录路径（结尾是 `/`）
- `content` (string)：写入内容（JSON 格式）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`META_DATA_FILE_DOES_NOT_EXIST`：元信息文件不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`FILE_IS_NOT_LOCKED`：文件未锁定
  - `ERROR`和`FILE_IS_LOCKED`：文件已被其他进程锁定

**示例**：
```python
# 调用示例
result = writeDirectoryMetaData("/path/to/directory/", '{"owner": "user", "created": "2026-02-15"}')
if result[0] == "SUCCESS":
    print("写入成功")
else:
    print(f"写入失败: {result[0]}")
```

#### `createDirectoryMetaData(path)`

**功能**：创建目录元信息文件（空）

**参数**：
- `path` (string)：目录路径（结尾是 `/`）

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`DIRECTORY_DOES_NOT_EXIST`：目录不存在
  - `ERROR`和`IS_NOT_DIRECTORY`：路径指向的不是目录

**示例**：
```python
# 调用示例
result = createDirectoryMetaData("/path/to/directory/")
if result[0] == "SUCCESS":
    print("元信息文件创建成功")
else:
    print(f"元信息文件创建失败: {result[0]}")
```

#### `Rename(path, newName)`

**功能**：重命名文件或目录

**参数**：
- `path` (string)：目录路径（结尾是 `/`）或文件路径
- `newName` (string)：新文件或目录名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`IS_NOT_DIRECTORY_OR_FILE`：路径指向的不是目录或文件
  - `ERROR`和`FILE_EXIST`：目标文件或目录已存在
  - `ERROR`和`FILE_DOES_NOT_EXIST`：源文件或目录不存在
  - `ERROR`和`INVALID_NEW_NAME`：新名称无效
  - `ERROR`和`FILE_IS_LOCKED`：文件已被锁定

**示例**：
```python
# 调用示例 - 重命名文件
result = Rename("/path/to/file.txt", "new_name.txt")
if result[0] == "SUCCESS":
    print("重命名成功")
else:
    print(f"重命名失败: {result[0]}")

# 调用示例 - 重命名目录
result = Rename("/path/to/directory/", "new_directory")
if result[0] == "SUCCESS":
    print("目录重命名成功")
else:
    print(f"目录重命名失败: {result[0]}")
```

#### `readJson(path)`

**功能**：读取 JSON 文件内容

**参数**：
- `path` (string)：文件路径

**返回值**：
- 成功：`dict` 文件内容字典
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INCORRECT_FORMAT`：JSON 格式错误
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足

**示例**：
```python
# 调用示例
content = readJson("/path/to/file.json")
if isinstance(content, list) and content[0].startswith("ERROR_"):
    print(f"读取失败: {content[0]}")
else:
    print(content)  # 输出 JSON 内容字典
```

#### `Lock(path)`

**功能**：锁定文件，防止其他进程修改

**参数**：
- `path` (string)：文件路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`FILE_IS_LOCKED`：文件已被锁定
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件

**示例**：
```python
# 调用示例
result = Lock("/path/to/file.txt")
if result[0] == "SUCCESS":
    print("文件锁定成功")
else:
    print(f"文件锁定失败: {result[0]}")
```

#### `Unlock(path)`

**功能**：解锁文件，允许其他进程修改

**参数**：
- `path` (string)：文件路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`FILE_IS_NOT_LOCKED`：文件未锁定
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件

**示例**：
```python
# 调用示例
result = Unlock("/path/to/file.txt")
if result[0] == "SUCCESS":
    print("文件解锁成功")
else:
    print(f"文件解锁失败: {result[0]}")
```

#### `Link(path, sourcePath)`

**功能**：创建文件或目录的链接

**参数**：
- `path` (string)：链接文件目录路径（结尾是 `/`）
- `sourcePath` (string)：源文件或目录路径

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`FILE_EXIST`：链接文件已存在
  - `ERROR`和`SOURCE_FILE_DOES_NOT_EXIST`：源文件不存在
  - `ERROR`和`SOURCE_DIRECTORY_DOES_NOT_EXIST`：源目录不存在

**示例**：
```python
# 调用示例
result = Link("/path/to/link/directory/", "/path/to/source/file.txt")
if result[0] == "SUCCESS":
    print("链接创建成功")
else:
    print(f"链接创建失败: {result[0]}")
```

### 进程 API

#### 基本操作

##### `fork()`

**功能**：复制当前进程

**参数**：无

**返回值**：
- 成功：`int` 子进程 PID
- 失败：无

**示例**：
```python
# 调用示例
child_pid = fork()
if child_pid == 0:
    # 子进程代码
    print("This is child process")
else:
    # 父进程代码
    print(f"Created child process with PID: {child_pid}")
```

##### `exec(path, param[])`

**功能**：切换当前进程的程序

**参数**：
- `path` (string)：完整的程序路径
- `param[]` (string[])：切换到新程序的参数数组

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 新进程的内容
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`FILE_DOES_NOT_EXIST`：文件不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件

**示例**：
```python
# 调用示例
result = exec("/path/to/program.py", ["arg1", "arg2"])
if result[0].startswith("ERROR_"):
    print(f"执行失败: {result[0]}")
else:
    print("程序执行成功")
```

##### `kill(pid)`

**功能**：杀死指定进程

**参数**：
- `pid` (int)：想要杀死的进程的 PID

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`PROCESS_DOES_NOT_EXIST`：进程不存在

**示例**：
```python
# 调用示例
result = kill(12345)
if result[0] == "SUCCESS":
    print("进程杀死成功")
else:
    print(f"进程杀死失败: {result[0]}")
```

##### `wait()`

**功能**：等待任意子进程结束

**参数**：无

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`CHILD_PROCESS_DOES_NOT_EXIST`：子进程不存在

**示例**：
```python
# 调用示例
result = wait()
if result[0] == "SUCCESS":
    print("子进程已结束")
else:
    print(f"等待失败: {result[0]}")
```

##### `waitPID(pid)`

**功能**：等待指定子进程结束

**参数**：
- `pid` (int)：子进程 PID

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`PROCESS_DOES_NOT_EXIST`：进程不存在
  - `ERROR`和`PID_DOES_NOT_CHILD_PROCESS`：PID 不是子进程

**示例**：
```python
# 调用示例
result = waitPID(12345)
if result[0] == "SUCCESS":
    print("指定子进程已结束")
else:
    print(f"等待失败: {result[0]}")
```

##### `getPID()`

**功能**：获取当前进程 PID

**参数**：无

**返回值**：
- 成功：`int` 当前进程 PID
- 失败：无

**示例**：
```python
# 调用示例
pid = getPID()
print(f"Current process PID: {pid}")
```

##### `getPPID()`

**功能**：获取父进程 PID

**参数**：无

**返回值**：
- 成功：`int` 父进程 PID
- 失败：无

**示例**：
```python
# 调用示例
ppid = getPPID()
print(f"Parent process PID: {ppid}")
```

##### `getListOfChildProcess()`

**功能**：获取所有子进程程序名称/PID 键值对

**参数**：无

**返回值**：
- 成功：`dict<string, int>` 子进程程序名称/PID 键值对
- 失败：`dict<string, int>` 包含错误码，格式为 `{"ERROR_CHILD_PROCESS_DOES_NOT_EXIST": -1}`

**示例**：
```python
# 调用示例
child_processes = getListOfChildProcess()
if "ERROR_CHILD_PROCESS_DOES_NOT_EXIST" in child_processes:
    print("没有子进程")
else:
    print(child_processes)  # 输出子进程列表
```

##### `getListOfProcess()`

**功能**：获取所有进程程序名称/PID 键值对（需要 local 权限）

**参数**：无

**返回值**：
- 成功：`dict<string, int>` 进程程序名称/PID 键值对
- 失败：`dict<string, int>` 包含错误码，格式为 `{"ERROR_INSUFFICIENT_PERMISSION": -1}`

**示例**：
```python
# 调用示例
all_processes = getListOfProcess()
if "ERROR_INSUFFICIENT_PERMISSION" in all_processes:
    print("权限不足")
else:
    print(all_processes)  # 输出所有进程列表
```

##### `Pause(pid)`

**功能**：暂停指定进程

**参数**：
- `pid` (int)：进程 PID

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`PROCESS_DOES_NOT_EXIST`：进程不存在
  - `ERROR`和`PROCESS_IS_PAUSED`：进程已暂停

**示例**：
```python
# 调用示例
result = Pause(12345)
if result[0] == "SUCCESS":
    print("进程暂停成功")
else:
    print(f"进程暂停失败: {result[0]}")
```

##### `Continue(pid)`

**功能**：恢复指定进程

**参数**：
- `pid` (int)：进程 PID

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`PROCESS_DOES_NOT_EXIST`：进程不存在
  - `ERROR`和`PROCESS_IS_RUNNING`：进程已在运行

**示例**：
```python
# 调用示例
result = Continue(12345)
if result[0] == "SUCCESS":
    print("进程恢复成功")
else:
    print(f"进程恢复失败: {result[0]}")
```

#### 进程通信

##### 基本概念

**交换池**：cilexec 中唯一的进程交换数据手段，其本质是一个 JSON 文件。创建交换池就是创建文件。

**数据类型**：
- `times(int)`：只能读取 int 次，读取后自动删除
- `always`：一直存在，直到放入进程终结
- `sync`：当添加入这个变量的进程（以下简称加入者）修改这个变量时，所有使用这个变量的进程内部的这个变量都会被修改。每次使用这个变量时都会进行查询。这个变量在非加入者进程的内部不可以修改

**访问控制**：
- `whitelist{}`：白名单，内部填充进程 PID 用逗号分隔，只有符合的进程才可以读取
- `blacklist{}`：黑名单，内部填充进程 PID 用逗号分隔，只有不符合的进程才可以读取
- *默认*：如果不添加两个参数，默认所有进程都可以访问

##### `createSwapPool(name)`

**功能**：创建交换池

**参数**：
- `name` (string)：交换池名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INVALID_NAME`：名称无效
  - `ERROR`和`SWAP_POOL_EXIST`：交换池已存在

**示例**：
```python
# 调用示例
result = createSwapPool("my_swap_pool")
if result[0] == "SUCCESS":
    print("交换池创建成功")
else:
    print(f"交换池创建失败: {result[0]}")
```

##### `removeSwapPool(name)`

**功能**：删除交换池

**参数**：
- `name` (string)：交换池名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`SOME_VAR_IS_LOCKED`：某些变量已锁定

**示例**：
```python
# 调用示例
result = removeSwapPool("my_swap_pool")
if result[0] == "SUCCESS":
    print("交换池删除成功")
else:
    print(f"交换池删除失败: {result[0]}")
```

##### `swapPoolAdd(varName:var, poolName, parm[])`

**功能**：将变量加入交换池

**参数**：
- `varName:var` (string)：变量名和值，格式为 `"variable_name:value"`
- `poolName` (string)：交换池名称
- `parm[]` (string[])：参数数组，包含数据类型和访问控制

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_IS_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`VARIABLE_EXIST`：变量已存在
  - `ERROR`和`INVALID_PARAMETER`：参数无效

**示例**：
```python
# 调用示例
result = swapPoolAdd("counter:0", "my_swap_pool", ["always"])
if result[0] == "SUCCESS":
    print("变量添加成功")
else:
    print(f"变量添加失败: {result[0]}")
```

##### `swapPoolRemove(varName, poolName)`

**功能**：将变量从交换池中删除

**参数**：
- `varName` (string)：变量名
- `poolName` (string)：交换池名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`VARIABLE_DOES_NOT_EXIST`：变量不存在
  - `ERROR`和`VAR_IS_LOCKED`：变量已锁定

**示例**：
```python
# 调用示例
result = swapPoolRemove("counter", "my_swap_pool")
if result[0] == "SUCCESS":
    print("变量删除成功")
else:
    print(f"变量删除失败: {result[0]}")
```

##### `swapPoolLock(varName, poolName)`

**功能**：锁定变量以修改

**参数**：
- `varName` (string)：变量名
- `poolName` (string)：交换池名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`VARIABLE_DOES_NOT_EXIST`：变量不存在
  - `ERROR`和`VAR_IS_LOCKED`：变量已锁定

**示例**：
```python
# 调用示例
result = swapPoolLock("counter", "my_swap_pool")
if result[0] == "SUCCESS":
    print("变量锁定成功")
else:
    print(f"变量锁定失败: {result[0]}")
```

##### `swapPoolUnlock(varName, poolName)`

**功能**：解锁变量

**参数**：
- `varName` (string)：变量名
- `poolName` (string)：交换池名称

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`VARIABLE_DOES_NOT_EXIST`：变量不存在
  - `ERROR`和`VAR_IS_NOT_LOCKED`：变量未锁定

**示例**：
```python
# 调用示例
result = swapPoolUnlock("counter", "my_swap_pool")
if result[0] == "SUCCESS":
    print("变量解锁成功")
else:
    print(f"变量解锁失败: {result[0]}")
```

##### `swapPoolUpdate(varName, poolName, newValue)`

**功能**：修改交换池内部某个变量的内容（必须是该变量的拥有者才可以修改）

**参数**：
- `varName` (string)：变量名
- `poolName` (string)：交换池名称
- `newValue` (string)：新的变量值

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足（非变量拥有者）
  - `ERROR`和`VARIABLE_DOES_NOT_EXIST`：变量不存在
  - `ERROR`和`VAR_IS_NOT_LOCKED`：变量未锁定

**示例**：
```python
# 调用示例
result = swapPoolUpdate("counter", "my_swap_pool", "10")
if result[0] == "SUCCESS":
    print("变量更新成功")
else:
    print(f"变量更新失败: {result[0]}")
```

##### `swapPoolGetAll(poolName)`

**功能**：获取交换池中的所有内容（必须是这个交换池的拥有者才可以操作）

**参数**：
- `poolName` (string)：交换池名称

**返回值**：
- 成功：`dict<string, string>` 变量名和值的键值对
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`SWAP_POOL_DOES_NOT_EXIST`：交换池不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足（非交换池拥有者）

**示例**：
```python
# 调用示例
content = swapPoolGetAll("my_swap_pool")
if isinstance(content, list) and content[0].startswith("ERROR_"):
    print(f"获取失败: {content[0]}")
else:
    print(content)  # 输出交换池中的所有变量和值
```

## 通用规范

### 错误处理

所有 API 调用返回的错误信息都遵循以下格式：
- 错误码：使用大写字母和下划线组成的常量，如 `ERROR_INSUFFICIENT_PERMISSION`
- 错误信息数组：第一个元素为错误码，后续元素为详细错误信息（如果有）

### 权限系统

cilexec 实现了基本的权限系统，用于控制对文件和进程的访问：
- `local` 权限：允许访问系统级资源，如查看所有进程
- `file` 权限：允许对文件进行读写操作
- `process` 权限：允许对进程进行管理操作

### 速率限制

为了保护系统资源，cilexec 对 API 调用实施了速率限制：
- 文件操作：每秒最多 100 次调用
- 进程操作：每秒最多 50 次调用
- 交换池操作：每秒最多 30 次调用

### 数据验证

所有 API 调用都会进行基本的数据验证：
- 路径格式验证：确保路径格式正确
- 文件名验证：确保文件名符合系统规范
- 参数类型验证：确保参数类型正确
- JSON 格式验证：确保 JSON 内容格式正确

### 最佳实践

1. **文件操作**：
   - 对于频繁的小文件操作，建议批量处理以减少磁盘 IO
   - 对于大文件操作，建议使用适当的缓冲区大小

2. **进程管理**：
   - 及时清理不再需要的子进程
   - 合理使用进程间通信机制

3. **交换池使用**：
   - 对于频繁访问的数据，使用 `sync` 类型
   - 对于一次性数据，使用 `times(1)` 类型

4. **错误处理**：
   - 始终检查 API 调用的返回值
   - 对于关键操作，实现重试机制

## 终端 API

### 基本概念

cilexec 提供了基于 JavaFX 技术的终端组件，用于提供图形化的命令交互界面。终端组件作为系统的一个重要组成部分，允许用户通过图形界面执行命令、查看输出和管理系统。

### 终端 API

#### `terminalExec(command, terminalId)`

**功能**：执行终端命令

**参数**：
- `command` (string)：要执行的命令字符串
- `terminalId` (int, optional)：终端 ID，默认为当前终端

**返回值**：
- 成功：`string[]` 命令执行结果输出
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`COMMAND_NOT_FOUND`：命令不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`COMMAND_EXECUTION_FAILED`：命令执行失败
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例 - 使用当前终端
result = terminalExec("ls -la")
if isinstance(result, list) and result[0].startswith("ERROR_"):
    print(f"命令执行失败: {result[0]}")
else:
    print(result)  # 输出命令执行结果

# 调用示例 - 指定终端
result = terminalExec("ls -la", 1)
if isinstance(result, list) and result[0].startswith("ERROR_"):
    print(f"命令执行失败: {result[0]}")
else:
    print(result)  # 输出命令执行结果
```

#### `terminalRedirectOutput(command, outputPath, terminalId)`

**功能**：执行命令并将输出重定向到指定文件

**参数**：
- `command` (string)：要执行的命令字符串
- `outputPath` (string)：输出文件路径
- `terminalId` (int, optional)：终端 ID，默认为当前终端

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`COMMAND_NOT_FOUND`：命令不存在
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`COMMAND_EXECUTION_FAILED`：命令执行失败
  - `ERROR`和`FILE_DOES_NOT_EXIST`：输出文件不存在
  - `ERROR`和`IS_NOT_FILE`：路径指向的不是文件
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例
result = terminalRedirectOutput("ls -la", "/path/to/output.txt")
if result[0] == "SUCCESS":
    print("命令执行并输出重定向成功")
else:
    print(f"操作失败: {result[0]}")
```

#### `terminalGetHistory(terminalId)`

**功能**：获取终端历史命令

**参数**：
- `terminalId` (int, optional)：终端 ID，默认为当前终端

**返回值**：
- 成功：`string[]` 历史命令数组
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例
history = terminalGetHistory()
if isinstance(history, list) and history[0].startswith("ERROR_"):
    print(f"获取历史命令失败: {history[0]}")
else:
    print(history)  # 输出历史命令列表
```

#### `terminalCreateNew()`

**功能**：创建新的终端窗口

**参数**：无

**返回值**：
- 成功：`int` 新终端窗口的 ID
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`TERMINAL_LIMIT_REACHED`：终端数量达到上限

**示例**：
```python
# 调用示例
terminal_id = terminalCreateNew()
if isinstance(terminal_id, list) and terminal_id[0].startswith("ERROR_"):
    print(f"创建终端失败: {terminal_id[0]}")
else:
    print(f"创建新终端成功，ID: {terminal_id}")
```

#### `terminalClose(terminalId)`

**功能**：关闭指定的终端窗口

**参数**：
- `terminalId` (int)：要关闭的终端 ID

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例
result = terminalClose(1)
if result[0] == "SUCCESS":
    print("终端关闭成功")
else:
    print(f"终端关闭失败: {result[0]}")
```

#### `terminalGetList()`

**功能**：获取所有打开的终端列表

**参数**：无

**返回值**：
- 成功：`dict<int, string>` 终端 ID 和状态的键值对
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足

**示例**：
```python
# 调用示例
terminals = terminalGetList()
if isinstance(terminals, list) and terminals[0].startswith("ERROR_"):
    print(f"获取终端列表失败: {terminals[0]}")
else:
    print(terminals)  # 输出终端列表
```

#### `print(text, terminalId)`

**功能**：在终端中打印文本内容

**参数**：
- `text` (string)：要打印的文本内容
- `terminalId` (int, optional)：终端 ID，默认为当前终端

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例
result = print("Hello, cilexec!")
if result[0] == "SUCCESS":
    print("打印成功")
else:
    print(f"打印失败: {result[0]}")
```

#### `println(text, terminalId)`

**功能**：在终端中打印文本内容并换行

**参数**：
- `text` (string)：要打印的文本内容
- `terminalId` (int, optional)：终端 ID，默认为当前终端

**返回值**：
- 成功：`string[]` 包含 `"SUCCESS"` 和 `null`
- 失败：`string[]` 错误信息数组，包含以下错误码之一：
  - `ERROR`和`INSUFFICIENT_PERMISSION`：权限不足
  - `ERROR`和`TERMINAL_DOES_NOT_EXIST`：终端不存在
  - `ERROR`和`NO_TERMINAL_OPEN`：没有打开的终端

**示例**：
```python
# 调用示例
result = println("Hello, cilexec!")
if result[0] == "SUCCESS":
    println("打印成功并换行")
else:
    println(f"打印失败: {result[0]}")
```

## 架构理念

cilexec 的核心架构理念是「万物皆文件」，这体现在以下几个方面：

1. **无内存设计**：所有数据都存储在磁盘上，没有传统意义上的内存
2. **文件化状态**：系统状态、进程、变量等都以文件形式存在
3. **单一可执行文件**：系统核心和所有 API 都集成在一个可执行文件中
4. **Python 集成**：用户程序使用 Python，解释器集成在内核中
5. **磁盘 IO 优化**：使用类似 git diff 的方法减少磁盘写入操作

这种设计使得 cilexec 成为一个理想的教学操作系统，它简化了系统架构，使学生能够更直观地理解操作系统的工作原理，同时也展示了「万物皆文件」理念的极致应用。