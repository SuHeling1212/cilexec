cilexec

简介

`  `这是一个单一二进制可执行文件纯磁盘教学操作系统，该操作系统将unix和Linux的万物皆文件贯彻的更加彻底，将内存概念去除，并将磁盘作为唯一的读写设备，所有系统状态和进程都做为文件存储在磁盘中，同时这也使得这个系统不可作为一个可被boot或bios引导的真实造作系统，这个系统的存在也只是为了教学，所以这也是无关紧要的awa

`  `cilexec只有一个可执行文件，这个文件包含包含安装，启动，和之后需要的一切于硬件直接交互的api和系统基本内核

`  `单一可执行文件并不意味着该操作系统无法编写自定义程序，用户可以通过调用cilexec可执行文件的一系列api来操纵硬件，cilexec在用户编程将使用Python，并将Python解释器并集成入内核，这也是使用Java的原因（JNI太好用了），虽然效率会下降，加上磁盘io的超慢速度……但是这只是一个教学系统awa

`  `由于没有内存，所以也没有指针或内存地址，所以我们有最好的空安全！doge\
事实上这是真的，在这套系统中变量，数组，方法，被定义在进程文件内部，所以不用担心访问错“内存”，因为“内存”是在文件逻辑层面被隔开的

`  `由于没有内存，所以也不用担心断电数据丢失，我们有最好的数据安全！doge但快速，小内容的读写可能会导致硬盘损坏，所以我们使用类似于git diff的方法只改变不一样的部分，或当硬盘请求淤积到某一大小时再集体写入，得益于无内存设计，我们甚至可以直接修改“内存“数据，我们可以先保存状态（文件）关闭系统，让后再真实系统上打开对于文件，进行修改，在打开cilexec可执行文件，次时cilexec会自动加载”内存“然后继续工作

API

时间\
**getTime()**

*获取时间*\
参数 无\
返回数组int {yyyy,mm,dd,hh,minmin,secsec,msms}

文件\
**read(path)**

*读取文件全部内容*

参数 string文件完整路径

返回 string数组

1\.Successful文件内容数组

2\.Error insufficientPermission

3\.Error fileDoesNotExist

4\.Error isNotFile

5\.Error directoryDoesNotExist

**write(path,“content”)**

*清空文件内容并写入*

参数 string文件完整路径，string写入内容

返回 string数组

1\.Successful null

2\.Error insufficientPermissions

3\.Error fileDoesNotExist

4\.Error isNotFile

5\.Error fileIsNotLocked

6\.Error fileIsLocked

7\.Error directoryDoesNotExist

**getListOfFileAndDirectory(path)**

*获取目录下的文件和目录列表顺序为从A到Z*

参数 string目录路径(结尾是/)

返回 string数组文件名和目录名(目录名后面有/，连接文件后有=>源目录/文件)

1\.Successful 文件名和目录名数组

2\.Error insufficientPermissions

3\.Error directoryDoesNotExist

4\.Error isNotDirectory

**readFileMetaData(path)**

*阅读文件元信息*

参数 string文件完整路径

返回 string数组

1\.Successful元信息内容（json且一定不为null但可能为{}）

2\. Error insufficientPermissions

3\. Error fileDoesNotExist

4\. Error isNotFile

5\. Error directoryDoesNotExist

**writeFileMetaData(path,“content”)**

*清空文件元信息内容并写入*

参数 string文件完整路径，string写入内容

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error fileDoesNotExist

4\. Error isNotFile

5\. Error fileNotLock

6\. Error fileIsLocked

7\. Error directoryDoesNotExist

**createFile(path,name)**

*创建文件*

参数 string目录路径(结尾是/) string文件名称

返回 string数组

1\.Successful null

2\. Error insufficientPermissions

3\. Error invalidName

4\. Error isNotDirectory

5\. Error fileExist

6\. Error isNotFile

7\. Error directoryDoesNotExist

**removeFile(path)**

*删除文件*

参数 string文件完整路径

返回 string数组

1\.Successful null

2\. Error insufficientPermissions

3\. Error fileDoesNotExist

4\. Error isNotFile

5\. Error fileIsLocked

6\. Error directoryDoesNotExist

**createDirectory(path,name)**

*创建目录*

参数 string目录路径(结尾是/) string目录名称

返回 string数组

1\.Successful null

2\. Error insufficientPermissions

3\. Error directoryDoesNotExist

4\. Error isNotDirectory

5\. Error directoryExist

**removeDirectory (path)**

*删除空目录*

参数 string目录路径(结尾是/)

返回 string数组

1\.Successful null

2\. Error directoryIsNotEmpty

3\. Error insufficientPermissions

4\. Error directoryDoesNotExist

5\. Error isNotDirectory

**readDirectoryMetaData(path)**

*阅读目录元信息文件*

参数 string目录路径(结尾是/)

返回 string数组

1\.Successful元信息内容（json且一定不为null但可能为{}）

2\. Error insufficientPermissions

3\. Error metaDataFileDoesNotExist

4\. Error directoryDoesNotExist

5\. Error isNotDirectory

**writeDirectoryMetaData(path,“content”)**

*清空目录元信息内容并写入*

参数 string目录路径(结尾是/)，string写入内容()

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error metaDataFileDoesNotExist

4\. Error isNotDirectory

5\. Error directoryDoesNotExist

6\. Error fileIsNotLocked

7\. Error fileIsLocked

**createDirectoryMetaData(path)**

*创建目录元信息文件(空)*

参数 string目录路径(结尾是/)

返回 string数组

1\.Successful null

2\. Error insufficientPermissions

3\. Error directoryDoesNotExist

4\. Error isNotDirectory

**Rename(path,newName)**

*重命名文件或目录*

参数 string目录路径(结尾是/)或文件路径 string新文件名称

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error isNotDirectoryOrFile

4\. Error fileExist

5\. Error fileDoesNotExist

6\. Error invalidNewName

7\. Error fileIsLocked

**readJson(String)**

*阅读json*

参数 string文件路径

返回 string数组

1\. Successful文件字典

2\. Error incorrectFormat

**Lock(path)**

*锁定文件*

参数 string文件路径

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error fileDoesNotExist

4\. Error fileIsLocked

5\. Error isNotFile

**Unlock(path)**

*解锁文件*

参数 string文件路径

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error fileDoesNotExist

4\. Error fileIsNotLocked

5\. Error isNotFile

6\. fileIsLocked

**Link(path sourcePath)**

*连接文件*

参数 string连接文件目录路径(结尾是/) string源文件/文件夹路径

返回 string数组

1\.Successful null

2\.Error insufficientPermissions

3\.Error fileExist

4\.Error sourceFileDoesNotExist

5\.Error sourceDirectoryDoesNotExist

进程

**基本**

**fork()**

*复制当前进程*

参数 无

返回 int

1\.子进程pid

**exec(path param[])**

*切换当前进程的程序*

参数 string完整的程序路径 string[]切换到新程序的参数数组

返回 string数组\
1\. Error fileDoesNotExist

2\. Error insufficientPermissions

3\. Error isNotFile

4\. Successful新进程的内容

**kill(pid)**

*杀死指定进程*

参数 int想要杀死的进程的pid

返回 string 数组

1\. Error insufficientPermissions

2\. Error processDoesNotExist

3\. Successful null

**wait()**

*等待任意子进程结束*

参数 无

返回 string数组

1\. Error childProcessDoesNotExist

2\. Successful null

**waitPID(pid)**

*等待指定子进程结束*

参数 int子进程pid

返回 string数组

1\. Error processDoesNotExist

2\. Error pidDoesNotChildProcess

3\. Successful null

**getPID()**

*获取当前进程PID*

参数 无

返回 int

1\.pid

**getPPID()**

*获取父进程PID*

参数 无

返回 int

1\.ppid(由于所有的进程都是由内核(pid=0)启动的，而内核是一个特殊的进程，所以就算是开机自启动的程序他也拥有父进程)

**getListOfChildProcess ()**

*获取当所有子进程程序名称/PID键值对或只有一个对的键值对，若第一个值为-1，其键为错误码*

参数 无

返回 string:int键值对

1\.子进程程序名称/PID键值对

2\.childProcessDoesNotExist

**getListOfProcess ()**

*获取当所有进程程序名称/PID键值对或只有一个对的键值对，若第一个值为-1，其键为错误码，需要local权限*

参数 无

返回 string:int键值对

1\.进程程序名称/PID键值对

2\.insufficientPermissions

**Pause(pid)**

*暂停指定进程*

参数 int pid

返回 string数组

1\. Successful null

2\. Error insufficientPermissions

3\. Error processDoesNotExist

4\. Error processIsPaused

**Continue (pid)**

*回复指定进程*

参数 int pid

返回 string

1\. Successful null

2\. Error insufficientPermissions

3\. Error processDoesNotExist

4\. Error processIsRunning

**通信**

**基本概念**

交换池:cilexec中唯一的进程交换数据手段，其本质是一个json文件，创建交换池就是创建文件，交换池的数据有不同的类型比如Single只能阅读一次阅读之后就销毁，白名单黑名单，还有同步，同步指的是当一个进程，把它的一个变量或者可变类型放到交换池中时，只要任何一个地方，这个变量发生了变化，那么所有使用这个变量的地方的变量的值变化这很好的方便了数据同步。

**参数**

**必须**

*times(int)*

只能读取int次，读取后自动删除

*always*

一直存在，直到放入进程终结

*sync*

当添加入这个变量的进程(以下简称加入者)修改这个变量时所有使用这个变量的进程内部的这个变量都会被修改，也就是每次使用这个变量时都会进行查询。这个变量在非加入者进程的内部不可以修改

**非必须**

*whitelist{}*

白名单，内部填充进程pid用逗号分隔，只有符合的进程才可以读取

*blacklist{}*

黑名单，内部填充进程pid用逗号分隔，只有不符合的进程才可以读取\
//如果不添加两个参数，默认所有进程都可以访问

**createSwapPool(name)**

*创建交换池*

参数 无

返回 string数组

1\. Successful null

2\. Error invalidName

3\. Error swapPoolExist

**removeSwapPool(name)**

*删除交换池*

参数 无

返回 string数组

1\. Successful null

2\. Error swapPoolDoesNotExist

3\. Error insufficientPermissions

4\. Error someVarIsLocked

**swapPoolAdd(varName:var plloName parm[])**

*将变量加入交换池*

参数 string变量名:值 string交换池名 string参数数组

返回 string数组

1\. Successful null

2\. Error swapPoolIsNotExist

3\. Error insufficientPermissions

4\. Error variableExist

5\. Error invalidParameter

**swapPoolRemove(varName poolName)**

*将变量从交换池中删除*

参数 string变量名:值 string交换池名

返回 string数组

1\. Successful null

2\. Error swapPoolDoesNotExist

3\. Error insufficientPermissions

4\. Error variableDoesNotExist

5\. Error varIsLocked

**swapPoolLock(varName poolName)**

*锁定变量以修改*

参数 string变量名 string交换池名

返回 string数组

1\. Successful null

2\. Error swapPoolDoesNotExist

3\. Error insufficientPermissions

4\. Error variableDoesNotExist

5\. Error varIsLocked

**swapPoolUnlock(varName poolName)**

*解锁变量*

参数 string变量名 string交换池名

返回 string数组

1\. Successful null

2\. Error swapPoolDoesNotExist

3\. Error insufficientPermissions

4\. Error variableDoesNotExist

5\. Error varIsNotLocked

6\. Error varIsLocked

**改变**

**获取所有内容**
