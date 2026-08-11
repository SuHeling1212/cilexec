# CilExec for Windows

This package runs CilExec through Docker Desktop Linux containers and does not require WSL,
Git Bash, Java, Maven, PostgreSQL, or OpenSSL on the Windows host.

Requirements:

- Windows 10 or 11
- Windows PowerShell 5.1 or PowerShell 7
- Docker Desktop running in Linux-container mode

Verify the downloaded outer checksum before extracting the package. After extraction, open
PowerShell in this directory and run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Cilexec.ps1 install
```

The execution-policy override applies only to this verified invocation; it does not change the
machine or user policy.

Other commands:

```powershell
.\Cilexec.ps1 terminal
.\Cilexec.ps1 headless -Source 'counter = 1'
.\Cilexec.ps1 host-move -HostFile .\editor.db -VfsPath /editor.db -Username alice
.\Cilexec.ps1 shell -Target program
.\Cilexec.ps1 shell -Target data
.\Cilexec.ps1 uninstall
.\Cilexec.ps1 uninstall -Force
```

The ZIP contains both amd64 and arm64 Linux-container images. The installer checks Docker
Desktop's architecture, verifies the matching embedded archive, and loads only that image.

`uninstall` permanently removes this installation's containers, PostgreSQL volume, generated
credentials, TLS identity, and default exports. It does not remove other CilExec installations,
shared images, or Docker's global build cache.
