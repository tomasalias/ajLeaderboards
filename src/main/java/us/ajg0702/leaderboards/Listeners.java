package us.ajg0702.leaderboards;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.player.PlayerLoginProcessEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;


import static us.ajg0702.leaderboards.LeaderboardPlugin.message;

public class Listeners implements Listener {

    private final LeaderboardPlugin plugin;

    public Listeners(LeaderboardPlugin plugin) {
        this.plugin = plugin;
        LuckPermsProvider.get().getEventBus().subscribe(this.plugin, PlayerLoginProcessEvent.class, this::onJoin);
    }

    public void onJoin(PlayerLoginProcessEvent e) {
        if(!plugin.getAConfig().getBoolean("update-stats")) return;
        if(!plugin.getAConfig().getBoolean("update-on-join")) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getCache().updatePlayerStats(Bukkit.getPlayer(e.getUniqueId())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getCache().cleanPlayer(e.getPlayer());
    }
}
