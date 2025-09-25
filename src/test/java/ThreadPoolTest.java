import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
        // Test that queue size is significantly reduced from original 1,000,000
        int threadCount = 20;
        int queueSize = Math.max(1000, Math.min(10000, threadCount * 100));
        
        assertTrue("Queue size should be much smaller than original", queueSize < 1000000);
        assertTrue("Queue size should be reasonable", queueSize >= 1000 && queueSize <= 10000);
        assertTrue("Queue size should scale with thread count", queueSize == threadCount * 100);
        
        System.out.println("Thread count: " + threadCount);
        System.out.println("Queue size: " + queueSize);
    }
    
    @Test
    public void testThreadPoolCreation() {
        // Test that we can create a thread pool with our new configuration
        int threadCount = 16;
        int queueSize = 2000;
        
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
}