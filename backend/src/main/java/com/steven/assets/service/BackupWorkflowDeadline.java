package com.steven.assets.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** A monotonic deadline shared by every child process in one backup workflow. */
final class BackupWorkflowDeadline {
    private static final long CLEANUP_RESERVE_NANOS = Duration.ofSeconds(30).toNanos();
    private static final ThreadLocal<Deque<Long>> DEADLINES = ThreadLocal.withInitial(ArrayDeque::new);

    private BackupWorkflowDeadline() {}

    static <T> T within(Duration duration, Supplier<T> work) {
        Deque<Long> stack = DEADLINES.get();
        long now = System.nanoTime();
        long own = now + duration.toNanos();
        long effective = stack.isEmpty() ? own : Math.min(own, stack.peek());
        stack.push(effective);
        try {
            return work.get();
        } finally {
            stack.pop();
            if (stack.isEmpty()) DEADLINES.remove();
        }
    }

    static long commandSeconds(long commandMaximumSeconds) {
        Deque<Long> stack = DEADLINES.get();
        if (stack.isEmpty()) return commandMaximumSeconds;
        long usable = workNanos();
        // Process APIs accept whole seconds; rounding up can run beyond the reserved cleanup window.
        long seconds = usable / 1_000_000_000L;
        if (seconds < 1) throw new IllegalStateException("備份流程期限不足，未啟動子行程");
        return Math.min(seconds, commandMaximumSeconds);
    }

    static void requireSeconds(long seconds) {
        Deque<Long> stack = DEADLINES.get();
        if (!stack.isEmpty() && stack.peek() - System.nanoTime() < Duration.ofSeconds(seconds).toNanos()) {
            throw new IllegalStateException("備份流程期限不足，未啟動還原程序");
        }
    }

    static long remainingSeconds() {
        Deque<Long> stack = DEADLINES.get();
        return stack.isEmpty() ? Long.MAX_VALUE : Math.max(0, (stack.peek() - System.nanoTime()) / 1_000_000_000L);
    }

    static boolean expired() {
        Deque<Long> stack = DEADLINES.get();
        return !stack.isEmpty() && System.nanoTime() >= stack.peek();
    }

    static long workMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(workNanos()));
    }

    static void requireWorkBudget() {
        if (workNanos() <= 0) throw new IllegalStateException("備份流程期限不足，未提交資料庫變更");
    }

    static void lockWithinDeadline(ReentrantLock lock) {
        long waitNanos = workNanos();
        if (waitNanos <= 0) throw new IllegalStateException("備份流程期限不足，未取得排他鎖");
        try {
            if (!lock.tryLock(waitNanos, TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("備份流程期限不足，未取得排他鎖");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("備份流程等待排他鎖時被中斷");
        }
    }

    private static long workNanos() {
        Deque<Long> stack = DEADLINES.get();
        if (stack.isEmpty()) return Duration.ofSeconds(300).toNanos();
        return stack.peek() - System.nanoTime() - CLEANUP_RESERVE_NANOS;
    }
}
