package us.ajg0702.leaderboards.boards;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalCause;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import us.ajg0702.leaderboards.Debug;
import us.ajg0702.leaderboards.LeaderboardPlugin;
import us.ajg0702.leaderboards.boards.keys.BoardType;
import us.ajg0702.leaderboards.boards.keys.ExtraKey;
import us.ajg0702.leaderboards.boards.keys.PlayerBoardType;
import us.ajg0702.leaderboards.boards.keys.PositionBoardType;
import us.ajg0702.leaderboards.cache.BlockingFetch;
import us.ajg0702.leaderboards.cache.CacheMethod;
import us.ajg0702.leaderboards.cache.methods.MysqlMethod;
import us.ajg0702.leaderboards.nms.legacy.ThreadFactoryProxy;
import us.ajg0702.leaderboards.utils.Cached;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TopManager {

    private final ThreadPoolExecutor fetchService;
    //private final ThreadPoolExecutor fetchService = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    private final AtomicInteger fetching = new AtomicInteger(0);

    public void shutdown() {
        try {
            // Log current state before shutdown
            plugin.getLogger().info("Shutting down TopManager - Active: " + getActiveFetchers() + 
                    ", Queued: " + getQueuedTasks() + ", Fetching: " + getFetching());
                    
            fetchService.shutdown();
            
            // Give threads a chance to finish gracefully
            if (!fetchService.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("TopManager threads didn't finish within 5 seconds, forcing shutdown");
                fetchService.shutdownNow();
                
                // Wait a bit more for forced shutdown
                if (!fetchService.awaitTermination(2, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("TopManager threads could not be terminated");
                }
            }
        } catch (InterruptedException e) {
            plugin.getLogger().warning("TopManager shutdown interrupted");
            fetchService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final String OUT_OF_THREADS_MESSAGE = "unable to create native thread: possibly out of memory or process/resource limits reached";


    private final LeaderboardPlugin plugin;
    public TopManager(LeaderboardPlugin pl, List<String> initialBoards) {
        plugin = pl;
        CacheMethod method = plugin.getCache().getMethod();
        int configuredThreads = method instanceof MysqlMethod ? Math.max(10, method.getMaxConnections()) : plugin.getAConfig().getInt("max-fetching-threads");
        
        // Cap the thread count to reasonable limits to prevent memory issues
        // Consider available processors and system resources
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int maxReasonableThreads = Math.max(16, Math.min(availableProcessors * 4, 50));
        int t = Math.min(configuredThreads, maxReasonableThreads);
        
        int keepAlive = plugin.getAConfig().getInt("fetching-thread-pool-keep-alive");
        
        // Reduce queue size significantly to prevent excessive memory usage
        // A large queue can cause OOM issues when tasks accumulate
        // Consider available memory when setting queue size
        long maxMemory = Runtime.getRuntime().maxMemory();
        long availableMemory = maxMemory - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        
        // Conservative queue sizing based on available memory and thread count
        // Each queued task uses roughly 1KB of memory (conservative estimate)
        int baseQueueSize = Math.max(100, Math.min(1000, t * 50)); // Reduced from t * 100
        
        // Further reduce queue size if we have limited memory (less than 1GB available)
        if (availableMemory < 1024 * 1024 * 1024) { // Less than 1GB
            baseQueueSize = Math.max(50, Math.min(500, t * 25));
            plugin.getLogger().info("Low memory detected, reducing queue size to " + baseQueueSize);
        }
        
        int queueSize = baseQueueSize;
        
        fetchService = new ThreadPoolExecutor(
                t, t,
                keepAlive, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize, true),
                ThreadFactoryProxy.getDefaultThreadFactory("AJLBFETCH"),
                new ThreadPoolExecutor.CallerRunsPolicy() // Handle rejected tasks gracefully
        );
        fetchService.allowCoreThreadTimeOut(true);
        
        // Log memory and thread pool configuration for debugging
        plugin.getLogger().info("TopManager initialized - Threads: " + t + ", Queue: " + queueSize + 
                ", Available Memory: " + (availableMemory / 1024 / 1024) + "MB");
                
        plugin.getScheduler().runTaskTimerAsynchronously(() -> {
            synchronized (rolling) {
                rolling.add(getQueuedTasks()+getActiveFetchers());
                // Keep only last 50 entries, but remove in batches to reduce overhead
                if(rolling.size() > 60) {
                    // Remove 10 oldest entries when we exceed 60, reducing synchronization frequency
                    for(int i = 0; i < 10 && rolling.size() > 50; i++) {
                        rolling.remove(0);
                    }
                }
            }
        }, 0, 2);

        boardCache = initialBoards;
    }

    Map<PositionBoardType, Long> positionLastRefresh = new HashMap<>();
    List<PositionBoardType> positionFetching = new CopyOnWriteArrayList<>();
    LoadingCache<PositionBoardType, StatEntry> positionCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .refreshAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(10000)
            .removalListener(notification -> {
                if(!notification.getCause().equals(RemovalCause.REPLACED)) positionLastRefresh.remove((PositionBoardType) notification.getKey());
            })
            .build(new CacheLoader<PositionBoardType, StatEntry>() {
                @Override
                public @NotNull StatEntry load(@NotNull PositionBoardType key) {
                    return plugin.getCache().getStat(key.getPosition(), key.getBoard(), key.getType());
                }

                @Override
                public @NotNull ListenableFuture<StatEntry> reload(@NotNull PositionBoardType key, @NotNull StatEntry oldValue) {
                    if(plugin.isShuttingDown() || System.currentTimeMillis() - positionLastRefresh.getOrDefault(key, 0L) < cacheTime()) {
                        return Futures.immediateFuture(oldValue);
                    }
                    ListenableFutureTask<StatEntry> task = ListenableFutureTask.create(() -> {
                        positionLastRefresh.put(key, System.currentTimeMillis());
                        return plugin.getCache().getStat(key.getPosition(), key.getBoard(), key.getType());
                    });
                    if(plugin.isShuttingDown()) return Futures.immediateFuture(oldValue);
                    fetchService.execute(task);
                    return task;
                }
            });

    /**
     * Get a leaderboard position
     * @param position The position to get
     * @param board The board
     * @return The StatEntry representing the position on the board
     */
    public StatEntry getStat(int position, String board, TimedType type) {
        PositionBoardType key = new PositionBoardType(position, board, type);
        StatEntry cached;

        try {
            cached = positionCache.getIfPresent(key);
            if (cached == null) {
                if (BlockingFetch.shouldBlock(plugin)) {
                    cached = positionCache.getUnchecked(key);
                } else {
                    if (!positionFetching.contains(key)) {
                        if (plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Starting fetch on " + key);
                        positionFetching.add(key);
                        fetchService.submit(() -> {
                            positionCache.getUnchecked(key);
                            positionFetching.remove(key);
                            if (plugin.getAConfig().getBoolean("fetching-de-bug"))
                                Debug.info("Fetch finished on " + key);
                        });
                    }
                    if (plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Returning loading for " + key);
                    cacheStatPosition(position, new BoardType(board, type), null);
                    return StatEntry.loading(plugin, position, board, type);
                }
            }
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return StatEntry.error(position, board, type);
            } else {
                throw e;
            }
        }

        cacheStatPosition(position, new BoardType(board, type), cached.playerID);

        return cached;
    }

    // Limit the size of positionPlayerCache to prevent unbounded growth
    private static final int MAX_POSITION_CACHE_SIZE = 10000;
    
    public final Map<UUID, Map<BoardType, Integer>> positionPlayerCache = new ConcurrentHashMap<>();

    private void cacheStatPosition(int position, BoardType boardType, UUID playerUUID) {
        for (Map.Entry<UUID, Map<BoardType, Integer>> entry : positionPlayerCache.entrySet()) {
            if(entry.getKey().equals(playerUUID)) continue;
            entry.getValue().remove(boardType, position);
        }

        if(playerUUID == null) return;

        Map<BoardType, Integer> newMap = positionPlayerCache.getOrDefault(playerUUID, new HashMap<>());

        newMap.put(boardType, position);

        positionPlayerCache.put(playerUUID, newMap);
        
        // Prevent unbounded growth by removing oldest entries when cache gets too large
        if (positionPlayerCache.size() > MAX_POSITION_CACHE_SIZE) {
            // Remove 10% of entries when limit is exceeded
            int toRemove = MAX_POSITION_CACHE_SIZE / 10;
            Iterator<Map.Entry<UUID, Map<BoardType, Integer>>> iterator = positionPlayerCache.entrySet().iterator();
            while (iterator.hasNext() && toRemove > 0) {
                iterator.next();
                iterator.remove();
                toRemove--;
            }
            plugin.getLogger().info("Position cache size exceeded " + MAX_POSITION_CACHE_SIZE + 
                    ", removed oldest entries. Current size: " + positionPlayerCache.size());
        }
    }

    Map<PlayerBoardType, Long> statEntryLastRefresh = new HashMap<>();
    LoadingCache<PlayerBoardType, StatEntry> statEntryCache = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .refreshAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(10000)
            .removalListener(notification -> {
                if(!notification.getCause().equals(RemovalCause.REPLACED)) statEntryLastRefresh.remove((PlayerBoardType) notification.getKey());
            })
            .build(new CacheLoader<PlayerBoardType, StatEntry>() {
                @Override
                public @NotNull StatEntry load(@NotNull PlayerBoardType key) {
                    return plugin.getCache().getStatEntry(key.getPlayer(), key.getBoard(), key.getType());
                }

                @Override
                public @NotNull ListenableFuture<StatEntry> reload(@NotNull PlayerBoardType key, @NotNull StatEntry oldValue) {
                    long msSinceRefresh = System.currentTimeMillis() - statEntryLastRefresh.getOrDefault(key, 0L);
                    double cacheTime = Math.max(cacheTime()*1.5, plugin.getAConfig().getInt("min-player-cache-time"));
                    // The cache time is randomized a bit so that players are spread out more
                    if(plugin.isShuttingDown() || msSinceRefresh < (cacheTime + ((cacheTime / 2) * Math.random()))) {
                        return Futures.immediateFuture(oldValue);
                    }
                    ListenableFutureTask<StatEntry> task = ListenableFutureTask.create(() -> {
                        statEntryLastRefresh.put(key, System.currentTimeMillis());
                        return plugin.getCache().getStatEntry(key.getPlayer(), key.getBoard(), key.getType());
                    });
                    if(plugin.isShuttingDown()) return Futures.immediateFuture(oldValue);
                    fetchService.execute(task);
                    return task;
                }
            });

    /**
     * Get a leaderboard position
     * @param player The position to get
     * @param board The board
     * @return The StatEntry representing the position on the board
     */
    public StatEntry getStatEntry(OfflinePlayer player, String board, TimedType type) {
        PlayerBoardType key = new PlayerBoardType(player, board, type);

        try {
            StatEntry cached = statEntryCache.getIfPresent(key);

            if (cached == null) {
                if (BlockingFetch.shouldBlock(plugin)) {
                    cached = statEntryCache.getUnchecked(key);
                } else {
                    fetchService.submit(() -> statEntryCache.getUnchecked(key));
                    return StatEntry.loading(player, key.getBoardType());
                }
            }

            return cached;
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return StatEntry.error(-4, board, type);
            } else {
                throw e;
            }
        }
    }

    public StatEntry getCachedStatEntry(OfflinePlayer player, String board, TimedType type) {
        return getCachedStatEntry(player, board, type, true);
    }
    public StatEntry getCachedStatEntry(OfflinePlayer player, String board, TimedType type, boolean fetchIfAbsent) {
        PlayerBoardType key = new PlayerBoardType(player, board, type);

        StatEntry r;
        try {
            r = statEntryCache.getIfPresent(key);
            if(fetchIfAbsent && r == null) {
                fetchService.submit(() -> statEntryCache.getUnchecked(key));
            }
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return StatEntry.error(-4, board, type);
            } else {
                throw e;
            }
        }
        return r;
    }

    public StatEntry getCachedStat(int position, String board, TimedType type) {
        return getCachedStat(new PositionBoardType(position, board, type), true);
    }
    public StatEntry getCachedStat(PositionBoardType positionBoardType, boolean fetchIfAbsent) {
        StatEntry r;
        try {
            r = positionCache.getIfPresent(positionBoardType);
            if (r == null && fetchIfAbsent) {
                fetchService.submit(() -> positionCache.getUnchecked(positionBoardType));
            }
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return StatEntry.error(positionBoardType.getPosition(), positionBoardType.getBoard(), positionBoardType.getType());
            } else {
                throw e;
            }
        }
        return r;
    }


    Map<String, Long> boardSizeLastRefresh = new HashMap<>();
    LoadingCache<String, Integer> boardSizeCache = CacheBuilder.newBuilder()
            .expireAfterAccess(24, TimeUnit.HOURS)
            .refreshAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(10000)
            .removalListener(notification -> {
                if(!notification.getCause().equals(RemovalCause.REPLACED)) boardSizeLastRefresh.remove((String) notification.getKey());
            })
            .build(new CacheLoader<String, Integer>() {
                @Override
                public @NotNull Integer load(@NotNull String key) {
                    return plugin.getCache().getBoardSize(key);
                }

                @Override
                public @NotNull ListenableFuture<Integer> reload(@NotNull String key, @NotNull Integer oldValue) {
                    if(plugin.isShuttingDown() || System.currentTimeMillis() - boardSizeLastRefresh.getOrDefault(key, 0L) < Math.max(cacheTime()*2, 5000)) {
                        return Futures.immediateFuture(oldValue);
                    }
                    ListenableFutureTask<Integer> task = ListenableFutureTask.create(() -> {
                        boardSizeLastRefresh.put(key, System.currentTimeMillis());
                        return plugin.getCache().getBoardSize(key);
                    });
                    if(plugin.isShuttingDown()) return Futures.immediateFuture(oldValue);
                    fetchService.execute(task);
                    return task;
                }
            });


    /**
     * Get the size of a leaderboard (number of players)
     * @param board The board
     * @return The number of players in that board
     */
    public int getBoardSize(String board) {

        try {
            Integer cached = boardSizeCache.getIfPresent(board);

            if (cached == null) {
                if (BlockingFetch.shouldBlock(plugin)) {
                    cached = boardSizeCache.getUnchecked(board);
                } else {
                    fetchService.submit(() -> boardSizeCache.getUnchecked(board));
                    return -2;
                }
            }

            return cached;
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return -4;
            } else {
                throw e;
            }
        }

    }

    Map<BoardType, Long> totalLastRefresh = new HashMap<>();
    LoadingCache<BoardType, Double> totalCache = CacheBuilder.newBuilder()
            .expireAfterAccess(24, TimeUnit.HOURS)
            .refreshAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(10000)
            .removalListener(notification -> {
                if(!notification.getCause().equals(RemovalCause.REPLACED)) totalLastRefresh.remove((BoardType) notification.getKey());
            })
            .build(new CacheLoader<BoardType, Double>() {
                @Override
                public @NotNull Double load(@NotNull BoardType key) {
                    return plugin.getCache().getTotal(key.getBoard(), key.getType());
                }

                @Override
                public @NotNull ListenableFuture<Double> reload(@NotNull BoardType key, @NotNull Double oldValue) {
                    if(plugin.isShuttingDown() || System.currentTimeMillis() - totalLastRefresh.getOrDefault(key, 0L) < Math.max(cacheTime()*2, 5000)) {
                        return Futures.immediateFuture(oldValue);
                    }
                    ListenableFutureTask<Double> task = ListenableFutureTask.create(() -> {
                        totalLastRefresh.put(key, System.currentTimeMillis());
                        return plugin.getCache().getTotal(key.getBoard(), key.getType());
                    });
                    if(plugin.isShuttingDown()) return Futures.immediateFuture(oldValue);
                    fetchService.execute(task);
                    return task;
                }
            });


    /**
     * Gets the sum of all players on the leaderboard
     * @param board the board
     * @param type the timed type
     * @return the sum of all players in the specified board for the specified timed type
     */
    public double getTotal(String board, TimedType type) {
        BoardType boardType = new BoardType(board, type);

        try {
            Double cached = totalCache.getIfPresent(boardType);

            if (cached == null) {
                if (BlockingFetch.shouldBlock(plugin)) {
                    cached = totalCache.getUnchecked(boardType);
                } else {
                    fetchService.submit(() -> totalCache.getUnchecked(boardType));
                    return -2;
                }
            }

            return cached;
        } catch(Exception e) {
            if(e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
                return -4;
            } else {
                throw e;
            }
        }

    }


    List<String> boardCache;
    long lastGetBoard = 0;
    public List<String> getBoards() {
        if(boardCache == null) {
            if(BlockingFetch.shouldBlock(plugin)) {
                return fetchBoards();
            } else {
                if(plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("need to fetch boards");
                fetchBoardsAsync();
                lastGetBoard = System.currentTimeMillis();
                return new ArrayList<>();
            }
        }

        if(System.currentTimeMillis() - lastGetBoard > cacheTime()) {
            lastGetBoard = System.currentTimeMillis();
            fetchBoardsAsync();
        }
        return boardCache;
    }

    public void fetchBoardsAsync() {
        checkWrong();
        fetchService.submit(this::fetchBoards);
    }
    public List<String> fetchBoards() {
        int f = fetching.getAndIncrement();
        if(plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Fetching ("+fetchService.getPoolSize()+") (boards): "+f);
        boardCache = plugin.getCache().getBoards();
        if(plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Finished fetching boards");
        removeFetching();
        return boardCache;
    }

    // Use LinkedList with synchronized access instead of CopyOnWriteArrayList to reduce memory pressure
    // CopyOnWriteArrayList creates a new array on every write, which with updates every 0.1s causes memory issues
    private final List<Integer> rolling = Collections.synchronizedList(new LinkedList<>());
    private void removeFetching() {
        fetching.decrementAndGet();
    }

    public int getFetching() {
        return fetching.get();
    }

    public int getFetchingAverage() {
        List<Integer> snap;
        synchronized (rolling) {
            snap = new ArrayList<>(rolling);
        }
        if(snap.size() == 0) return 0;
        int sum = 0;
        for(Integer n : snap) {
            if(n == null) break;
            sum += n;
        }
        return sum/snap.size();
    }

    LoadingCache<BoardType, Long> lastResetCache = CacheBuilder.newBuilder()
            .expireAfterAccess(12, TimeUnit.HOURS)
            .refreshAfterWrite(30, TimeUnit.SECONDS)
            .build(new CacheLoader<BoardType, Long>() {
                @Override
                public @NotNull Long load(@NotNull BoardType key) {
                    long start = System.nanoTime();
                    long lastReset = plugin.getCache().getLastReset(key.getBoard(), key.getType())/1000;
                    long took = System.nanoTime() - start;
                    long tookms = took/1000000;
                    if(tookms > 500) {
                        /*if(tookms < 5) {
                            Debug.info("lastReset fetch took " + tookms + "ms ("+took+"ns)");
                        } else {*/
                            Debug.info("lastReset fetch took " + tookms + "ms");
                        //}
                    }
                    return lastReset;
                }
            });

    public long getLastReset(String board, TimedType type) {
        return lastResetCache.getUnchecked(new BoardType(board, type));
    }


    // Limit the size of extra cache to prevent unbounded growth
    private static final int MAX_EXTRA_CACHE_SIZE = 5000;
    
    Map<ExtraKey, Cached<String>> extraCache = new HashMap<>();
    
    public String fetchExtra(UUID id, String placeholder) {
        String value = plugin.getExtraManager().getExtra(id, placeholder);
        
        // Clean up cache if it's getting too large
        if (extraCache.size() > MAX_EXTRA_CACHE_SIZE) {
            // Remove entries older than 10 minutes
            long cutoffTime = System.currentTimeMillis() - (10 * 60 * 1000);
            extraCache.entrySet().removeIf(entry -> entry.getValue().getLastGet() < cutoffTime);
            
            // If still too large after cleanup, remove oldest entries
            if (extraCache.size() > MAX_EXTRA_CACHE_SIZE) {
                int toRemove = extraCache.size() - (MAX_EXTRA_CACHE_SIZE * 3 / 4); // Remove down to 75% capacity
                Iterator<Map.Entry<ExtraKey, Cached<String>>> iterator = extraCache.entrySet().iterator();
                while (iterator.hasNext() && toRemove > 0) {
                    iterator.next();
                    iterator.remove();
                    toRemove--;
                }
            }
        }
        
        extraCache.put(new ExtraKey(id, placeholder), new Cached<>(System.currentTimeMillis(), value));
        return value;
    }
    public String getExtra(UUID id, String placeholder) {
        ExtraKey key = new ExtraKey(id, placeholder);
        Cached<String> cached = extraCache.get(key);
        if(cached == null) {
            if(BlockingFetch.shouldBlock(plugin)) {
                return fetchExtra(id, placeholder);
            } else {
                extraCache.put(key, new Cached<>(System.currentTimeMillis(), plugin.getMessages().getRawString("loading.text")));
                fetchExtraAsync(id, placeholder);
                return plugin.getMessages().getRawString("loading.text");
            }
        } else {
            if(System.currentTimeMillis() - cached.getLastGet() > cacheTime()) {
                cached.setLastGet(System.currentTimeMillis());
                fetchExtraAsync(id, placeholder);
            }
            return cached.getThing();
        }
    }
    public void fetchExtraAsync(UUID id, String placeholder) {
        fetchService.submit(() -> fetchExtra(id, placeholder));
    }

    public String getCachedExtra(UUID id, String placeholder) {
        Cached<String> r = extraCache.get(new ExtraKey(id, placeholder));
        if(r == null) {
            fetchExtraAsync(id, placeholder);
            return null;
        }
        return r.getThing();
    }

    public StatEntry getRelative(OfflinePlayer player, int difference, String board, TimedType type) {
        StatEntry playerStatEntry = getCachedStatEntry(player, board, type);
        if(playerStatEntry == null || !playerStatEntry.hasPlayer()) {
            return StatEntry.loading(plugin, board, type);
        }
        int position = playerStatEntry.getPosition() + difference;

        if(position < 1) {
            return StatEntry.noRelData(plugin, position, board, type);
        }

        return getStat(position, board, type);
    }


    private void checkWrong() {
        int currentFetching = fetching.get();
        if(currentFetching > 5000) {
            plugin.getLogger().warning("Something might be going wrong, printing some useful info");
            plugin.getLogger().warning("Fetching count: " + currentFetching + 
                    ", Active threads: " + getActiveFetchers() + 
                    "/" + getMaxFetchers() + 
                    ", Queued tasks: " + getQueuedTasks() + 
                    "/" + getQueueCapacity());
            
            if (isApproachingCapacity()) {
                plugin.getLogger().warning("Thread pool is approaching capacity! This may indicate a performance bottleneck.");
            }
            
            Thread.dumpStack();
        }
        
        // Also check if we're approaching thread pool capacity even with lower fetching counts
        if (currentFetching > 1000 && isApproachingCapacity()) {
            plugin.getLogger().warning("Thread pool approaching capacity - Fetching: " + currentFetching + 
                    ", Queue: " + getQueuedTasks() + "/" + getQueueCapacity());
        }
    }

    long lastLargeAverage = 0;

    public int cacheTime() {

        boolean recentLargeAverage = System.currentTimeMillis() - lastLargeAverage < 30000;
        boolean moreFetching = plugin.getAConfig().getBoolean("more-fetching");


        int r = moreFetching ? (recentLargeAverage ? 5000 : 1000) : 20000;

        int fetchingAverage = getFetchingAverage();

        if(fetchingAverage == Integer.MAX_VALUE) {
            return r;
        }

        int activeFetchers = getActiveFetchers();
        int totalTasks = activeFetchers + getQueuedTasks();

        if(moreFetching) {
            if(!recentLargeAverage) {
                if(fetchingAverage == 0 && activeFetchers == 0) {
                    return 500;
                }
                if(fetchingAverage > 0) {
                    r = 2000;
                }
                if(fetchingAverage >= 2) {
                    r = 5000;
                }
            }
            if((fetchingAverage >= 5 || totalTasks > 25) && activeFetchers > 0) {
                r = 10000;
            }
            if((fetchingAverage > 10 || totalTasks > 59) && activeFetchers > 0) {
                r = 15000;
            }
        }
        if((fetchingAverage > 20 || totalTasks > 75) && activeFetchers > 0) {
            r = 30000;
        }
        if((fetchingAverage > 30 || totalTasks > 100) && activeFetchers > 0) {
            r = 60000;
        }
        if(fetchingAverage > 50 || totalTasks > 125) {
            lastLargeAverage = System.currentTimeMillis();
            if(activeFetchers > 0) {
                r = 120000;
            }
        }
        if((fetchingAverage > 100 || totalTasks > 150) && activeFetchers > 0) {
            r = 180000;
        }
        if((fetchingAverage > 300 || totalTasks > 175) && activeFetchers > 0) {
            r = 3600000;
        }
        if((fetchingAverage > 400 || totalTasks > 200) && activeFetchers > 0) {
            r = 7200000;
        }


        return r;
    }

    public List<Integer> getRolling() {
        synchronized (rolling) {
            return new ArrayList<>(rolling);
        }
    }

    public int getActiveFetchers() {
        return fetchService.getActiveCount();
    }
    public int getMaxFetchers() {
        return fetchService.getMaximumPoolSize();
    }

    public int getQueuedTasks() {
        return fetchService.getQueue().size();
    }

    public int getWorkers() {
        return fetchService.getPoolSize();
    }

    public boolean boardExists(String board) {
        boolean result = getBoards().contains(board);
        if(plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Checking " + board + ": " + result);
        if(!result) {
            if(plugin.getAConfig().getBoolean("fetching-de-bug")) Debug.info("Boards: " + String.join(", ", getBoards()));
        }
        return result;
    }

    @SuppressWarnings("UnusedReturnValue")
    public Future<?> submit(Runnable task) {
        try {
            return fetchService.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (e.getMessage() != null && e.getMessage().contains(OUT_OF_THREADS_MESSAGE)) {
                informAboutThreadLimit();
            } else {
                plugin.getLogger().warning("Task rejected by thread pool. Queue might be full or executor shutting down.");
                if (plugin.getAConfig().getBoolean("fetching-de-bug")) {
                    plugin.getLogger().warning("Queue size: " + getQueuedTasks() + ", Active threads: " + getActiveFetchers());
                }
            }
            // Return a completed future to prevent null pointer exceptions
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Get current queue capacity for debugging purposes
     */
    public int getQueueCapacity() {
        return fetchService.getQueue().remainingCapacity() + fetchService.getQueue().size();
    }

    /**
     * Check if the thread pool is approaching capacity limits
     */
    public boolean isApproachingCapacity() {
        return (double) getQueuedTasks() / getQueueCapacity() > 0.8;
    }

    private void informAboutThreadLimit() {
        plugin.getLogger().warning("'" + OUT_OF_THREADS_MESSAGE + "' error detected! " +
                "This is usually caused by your server hitting the limit on the number of threads it can have. " +
                "If the server crashes, take the crash report and paste it into https://crash-report-analyser.ajg0702.us/ " +
                "to help find which plugin is using too many threads. If you need help interpreting the results, " +
                "feel free to ask in aj's discord server (invite link is on the ajLeaderboards plugin page under 'support')");
        
        // Add additional debugging info
        plugin.getLogger().warning("Current thread pool stats - Active: " + getActiveFetchers() + 
                "/" + getMaxFetchers() + ", Queued: " + getQueuedTasks() + 
                "/" + getQueueCapacity() + ", Workers: " + getWorkers());
    }
}

