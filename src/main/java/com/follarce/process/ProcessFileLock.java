package com.follarce.process;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程文件锁 —— 为虚拟线程模式提供每 PID 级别的文件写入互斥。
 * <p>
 * 虚拟线程模式下，多个子进程的虚拟线程可能同时写父进程的 .proc 文件
 * （如 {@code cleanParentChildList()} 和 {@code handleKill()}），
 * 导致写入竞争。本类通过 {@link ReentrantLock} 实现细粒度 PID 级锁定。
 * <p>
 * <strong>锁顺序：</strong> 如需锁多个 PID，先锁小号 PID 再锁大号 PID，
 * 避免循环等待死锁。
 * <p>
 * <strong>虚拟线程安全性：</strong> 使用 {@link ReentrantLock} 而非
 * {@code synchronized}，因为后者在虚拟线程上会钉住载体线程。
 */
public final class ProcessFileLock {

    private static final ConcurrentHashMap<Integer, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private ProcessFileLock() {}

    /**
     * 获取指定 PID 的文件锁（阻塞直到可用）。
     */
    public static void lock(int pid) {
        LOCKS.computeIfAbsent(pid, k -> new ReentrantLock()).lock();
    }

    /**
     * 尝试获取指定 PID 的文件锁，不阻塞。
     *
     * @return true 如果成功获得锁
     */
    public static boolean tryLock(int pid) {
        return LOCKS.computeIfAbsent(pid, k -> new ReentrantLock()).tryLock();
    }

    /**
     * 释放指定 PID 的文件锁。
     */
    public static void unlock(int pid) {
        ReentrantLock lock = LOCKS.get(pid);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 按锁顺序（小→大）锁定两个 PID。
     */
    public static void lockTwo(int pid1, int pid2) {
        if (pid1 == pid2) {
            lock(pid1);
            return;
        }
        int first = Math.min(pid1, pid2);
        int second = Math.max(pid1, pid2);
        lock(first);
        lock(second);
    }

    /**
     * 按锁顺序释放两个 PID。
     */
    public static void unlockTwo(int pid1, int pid2) {
        if (pid1 == pid2) {
            unlock(pid1);
            return;
        }
        // 后进先出（与锁顺序相反）
        unlock(Math.max(pid1, pid2));
        unlock(Math.min(pid1, pid2));
    }
}
