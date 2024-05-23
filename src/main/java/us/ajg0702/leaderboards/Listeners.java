package us.ajg0702.leaderboards;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class Listeners implements Listener {

    private final LeaderboardPlugin plugin;

    public Listeners(LeaderboardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if(!plugin.getAConfig().getBoolean("update-stats")) return;
        if(!plugin.getAConfig().getBoolean("update-on-join")) return;
        plugin.getScheduler().runTaskAsynchronously(() -> plugin.getCache().updatePlayerStats(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getCache().cleanPlayer(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST) // Lowest is run first
    public void onQuitFirst(PlayerQuitEvent e) {
        if(!plugin.getAConfig().getBoolean("update-stats")) return;
        if(!plugin.getAConfig().getBoolean("update-on-leave")) return;
        plugin.getCache().updatePlayerStats(e.getPlayer());
    }
}
