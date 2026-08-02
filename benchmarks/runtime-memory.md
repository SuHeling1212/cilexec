# Runtime memory benchmark

`runtime-memory.sh` starts an isolated disposable Compose project, samples the idle shared JVM,
creates the requested number of users whose terminal processes run
`while(true){ util.sleep(250) }`, and removes all benchmark containers and networks on exit. It
does not use the persistent CilExec database volume.

Run it against an already-built image:

```bash
bash benchmarks/runtime-memory.sh
```

Select another image tag with `CILEXEC_BENCHMARK_IMAGE_TAG`. The CSV output reports the Java PID 1
RSS in KiB and the whole Runtime container's cgroup memory. Compare medians because class loading,
JIT compilation, PostgreSQL client buffers, and garbage collection make any single sample noisy.

Set `CILEXEC_BENCHMARK_USERS` to choose the target number of sleeping users/processes:

```bash
CILEXEC_BENCHMARK_USERS=100 bash benchmarks/runtime-memory.sh
```

## 2026-08-01 result

The test used one shared JVM with 10 scheduler workers and 6 effect workers. Each phase had seven
samples after a 10-second settling period.

| Phase | Median Java RSS | Median Runtime cgroup memory | Increase from baseline |
|---|---:|---:|---:|
| Idle Runtime | 120.24 MiB | 99.70 MiB | — |
| 1 user / 1 sleeping process | 134.85 MiB | 114.60 MiB | about 14.7 MiB |
| 10 users / 10 sleeping processes | 160.71 MiB | 140.60 MiB | about 40.9 MiB |

The additional nine users after the first added about 25.9 MiB RSS in total, or about 2.9 MiB per
user/process. The first-user delta includes one-time class loading and JIT work, so it must not be
treated as a linear per-user cost. This benchmark measures sleeping terminal processes, not active
CPU-bound programs or large FCL variables.

## 100-process result

The test was repeated from a fresh isolated database with 10 scheduler workers, 6 effect workers,
and `CILEXEC_BENCHMARK_USERS=100`. Creation was back-pressured and each process was confirmed in
PostgreSQL before the next user was submitted. Seven samples were collected per phase.

| Phase | Median Java RSS | Median Runtime cgroup memory | Increase from baseline |
|---|---:|---:|---:|
| Idle Runtime | 110.89 MiB | 90.39 MiB | — |
| 1 user / 1 sleeping process | 129.67 MiB | 109.30 MiB | about 18.9 MiB |
| 100 users / 100 sleeping processes | 193.57 MiB | 173.90 MiB | about 83.5 MiB |

The 99 processes after the first added about 63.90 MiB of Java RSS in this run, or about 0.65 MiB
per additional persisted sleeping process if merely averaged. This average is not a stable linear
per-process allocation: RSS includes committed-but-free JVM heap, JIT data, database buffers, and
allocation churn from roughly 400 timer wake-ups per second. A larger step test plus post-GC live
heap/native-memory measurements is required before extrapolating to thousands of processes.
