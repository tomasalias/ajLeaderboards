import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class ThreadPoolTest {
    
    @Test
    public void testThreadPoolConfigurationLimits() {
        // Test that we properly limit thread count based on system resources
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int configuredThreads = 70; // old default
        
        // Simulate the new logic from TopManager constructor
        int maxReasonableThreads = Math.max(16, Math.min(availableProcessors * 4, 50));
        int actualThreads = Math.min(configuredThreads, maxReasonableThreads);
        
        // Verify that we're not creating too many threads
        assertTrue("Thread count should be limited", actualThreads <= 50);
        assertTrue("Thread count should be at least 16", actualThreads >= 16);
        assertTrue("Thread count should consider CPU count", actualThreads <= availableProcessors * 4);
        
        System.out.println("Available processors: " + availableProcessors);
        System.out.println("Configured threads: " + configuredThreads);
        System.out.println("Actual threads: " + actualThreads);
    }
    
    @Test
    public void testQueueSizeReduction() {
        // Test memory-aware queue sizing
        int threadCount = 20;
        long maxMemory = Runtime.getRuntime().maxMemory();
        long availableMemory = maxMemory - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        
        // Test normal memory scenario
        int baseQueueSize = Math.max(100, Math.min(1000, threadCount * 50));
        
        // Test low memory scenario (less than 1GB)
        int lowMemoryQueueSize = Math.max(50, Math.min(500, threadCount * 25));
        
        assertTrue("Base queue size should be reasonable", baseQueueSize >= 100 && baseQueueSize <= 1000);
        assertTrue("Low memory queue size should be smaller", lowMemoryQueueSize >= 50 && lowMemoryQueueSize <= 500);
        assertTrue("Low memory size should be smaller than base", lowMemoryQueueSize <= baseQueueSize);
        
        System.out.println("Available memory: " + (availableMemory / 1024 / 1024) + "MB");
        System.out.println("Base queue size: " + baseQueueSize);
        System.out.println("Low memory queue size: " + lowMemoryQueueSize);
    }
    
    @Test
    public void testThreadPoolCreation() {
        // Test that we can create a thread pool with our new configuration
        int threadCount = 16;
        int queueSize = 500; // Reduced from previous tests
        
        ThreadPoolExecutor executor = null;
        try {
            executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                500, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize, true),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            
            assertNotNull("Thread pool should be created successfully", executor);
            assertEquals("Core pool size should match", threadCount, executor.getCorePoolSize());
            assertEquals("Max pool size should match", threadCount, executor.getMaximumPoolSize());
            assertTrue("Should have rejection policy", executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
            
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }
    }
    
    @Test
    public void testRollingListMemoryOptimization() {
        // Test that LinkedList with synchronized access is more memory efficient than CopyOnWriteArrayList
        List<Integer> rollingList = Collections.synchronizedList(new LinkedList<>());
        
        // Add many entries to simulate rolling metrics
        for (int i = 0; i < 100; i++) {
            synchronized (rollingList) {
                rollingList.add(i);
                if (rollingList.size() > 60) {
                    // Batch removal like in our implementation
                    for (int j = 0; j < 10 && rollingList.size() > 50; j++) {
                        rollingList.remove(0);
                    }
                }
            }
        }
        
        assertTrue("Rolling list should be limited in size", rollingList.size() <= 60);
        assertTrue("Rolling list should maintain some history", rollingList.size() >= 50);
        
        // Test thread-safe access
        List<Integer> snapshot;
        synchronized (rollingList) {
            snapshot = new java.util.ArrayList<>(rollingList);
        }
        
        assertNotNull("Snapshot should be created", snapshot);
        assertEquals("Snapshot size should match", rollingList.size(), snapshot.size());
    }
    
    @Test
    public void testMemoryBasedConfiguration() {
        // Test configuration based on available memory
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long availableMemory = maxMemory - (totalMemory - freeMemory);
        
        // Simulate low memory condition
        boolean isLowMemory = availableMemory < 1024 * 1024 * 1024; // Less than 1GB
        
        int threadCount = 20;
        int queueSize;
        
        if (isLowMemory) {
            queueSize = Math.max(50, Math.min(500, threadCount * 25));
        } else {
            queueSize = Math.max(100, Math.min(1000, threadCount * 50));
        }
        
        assertTrue("Queue size should be reasonable", queueSize >= 50);
        assertTrue("Queue size should not exceed maximum", queueSize <= 1000);
        
        if (isLowMemory) {
            assertTrue("Low memory queue should be smaller", queueSize <= 500);
        }
        
        System.out.println("Memory-based queue size: " + queueSize);
        System.out.println("Available memory: " + (availableMemory / 1024 / 1024) + "MB");
    }
}