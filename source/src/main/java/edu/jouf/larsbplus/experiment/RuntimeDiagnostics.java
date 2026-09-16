package edu.jouf.larsbplus.experiment;

import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Run-boundary diagnostics used to distinguish foreground work from JIT, GC, allocation,
 * and process scheduling effects. Samples are taken outside the measured interval;
 * unsupported JVM counters are reported as -1 rather than estimated.
 */
record RuntimeDiagnostics(long processCpuNanos,
                          long currentThreadCpuNanos,
                          long currentThreadAllocatedBytes,
                          long gcCollections,
                          long gcTimeMillis,
                          long jitCompilationMillis) {

    static Snapshot snapshot() {
        return new Snapshot(sampleProcessCpuNanos(), sampleCurrentThreadCpuNanos(),
                sampleCurrentThreadAllocatedBytes(), sampleGcCollections(), sampleGcTimeMillis(),
                sampleJitCompilationMillis());
    }

    static RuntimeDiagnostics since(Snapshot before) {
        Snapshot after = snapshot();
        return new RuntimeDiagnostics(
                delta(before.processCpuNanos, after.processCpuNanos),
                delta(before.currentThreadCpuNanos, after.currentThreadCpuNanos),
                delta(before.currentThreadAllocatedBytes, after.currentThreadAllocatedBytes),
                delta(before.gcCollections, after.gcCollections),
                delta(before.gcTimeMillis, after.gcTimeMillis),
                delta(before.jitCompilationMillis, after.jitCompilationMillis));
    }

    private static long sampleProcessCpuNanos() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sun
                ? sun.getProcessCpuTime() : -1L;
    }

    private static long sampleCurrentThreadCpuNanos() {
        var bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : -1L;
    }

    private static long sampleCurrentThreadAllocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean sun)
                || !sun.isThreadAllocatedMemorySupported()
                || !sun.isThreadAllocatedMemoryEnabled()) {
            return -1L;
        }
        return sun.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static long sampleGcCollections() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = bean.getCollectionCount();
            if (value < 0L) return -1L;
            total += value;
        }
        return total;
    }

    private static long sampleGcTimeMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long value = bean.getCollectionTime();
            if (value < 0L) return -1L;
            total += value;
        }
        return total;
    }

    private static long sampleJitCompilationMillis() {
        CompilationMXBean bean = ManagementFactory.getCompilationMXBean();
        return bean != null && bean.isCompilationTimeMonitoringSupported()
                ? bean.getTotalCompilationTime() : -1L;
    }

    private static long delta(long before, long after) {
        return before < 0L || after < before ? -1L : after - before;
    }

    record Snapshot(long processCpuNanos,
                    long currentThreadCpuNanos,
                    long currentThreadAllocatedBytes,
                    long gcCollections,
                    long gcTimeMillis,
                    long jitCompilationMillis) {}
}
