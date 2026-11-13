package me.antifreecam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiFreecam extends JavaPlugin implements Listener {

    // ---- shared with the packet thread ----
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private volatile int hideBelowY;
    private volatile int fakeBlockId;
    private volatile boolean hideAir;
    private final PlayerState defaultState = new PlayerState();

    // ---- settings ----
    private int revealMargin;
    private boolean hideUndergroundEntities;
    private Set<String> worlds = new HashSet<>();
    private int refreshRadius;
    private int refreshPerTick;

    private boolean losEnabled;
    private int losInterval;
    private double maxDist;
    private double alwaysVisible;
    private boolean hidePlayers;
    private boolean hideMobs;
    private boolean blockSpectatorTp;
    private boolean forceOutOfSpectator;

    // ---- runtime ----
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private final ArrayDeque<long[]> refreshQueue = new ArrayDeque<>(); // {worldHash, cx, cz}
    private final Map<Long, World> refreshWorlds = new HashMap<>();
    private ChunkMaskListener chunkListener;

    public Map<UUID, PlayerState> getStates() { return states; }
    public PlayerState defaultState() { return defaultState; }
    public int getHideBelowY() { return hideBelowY; }
    public int getFakeBlockId() { return fakeBlockId; }
    public boolean isHideAir() { return hideAir; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);

        chunkListener = new ChunkMaskListener(this);
        PacketEvents.getAPI().getEventManager().registerListener(chunkListener);

        for (Player p : getServer().getOnlinePlayers()) updateState(p, false);
        startTasks();
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (chunkListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(chunkListener);
        }
        for (Player p : getServer().getOnlinePlayers()) {
            Set<UUID> set = hidden.get(p.getUniqueId());
            if (set == null) continue;
            for (UUID id : set) {
                Entity e = getServer().getEntity(id);
                if (e != null) p.showEntity(this, e);
            }
        }
        hidden.clear();
        states.clear();
    }

    private void loadSettings() {
        reloadConfig();
        var c = getConfig();
        hideBelowY = c.getInt("hide-below-y", 40);
        revealMargin = c.getInt("reveal-margin", 15);
        hideAir = c.getBoolean("hide-air", true);
        hideUndergroundEntities = c.getBoolean("hide-underground-entities", true);
        worlds = new HashSet<>(c.getStringList("worlds"));
        refreshRadius = Math.max(2, c.getInt("refresh.radius", 10));
        refreshPerTick = Math.max(1, c.getInt("refresh.chunks-per-tick", 4));

        losEnabled = c.getBoolean("los-hiding.enabled", true);
        losInterval = Math.max(1, c.getInt("los-hiding.check-interval-ticks", 4));
        maxDist = c.getDouble("los-hiding.max-check-distance", 64);
        alwaysVisible = c.getDouble("los-hiding.always-visible-distance", 6);
        hidePlayers = c.getBoolean("los-hiding.hide-players", true);
        hideMobs = c.getBoolean("los-hiding.hide-mobs", true);
        blockSpectatorTp = c.getBoolean("block-spectator-teleport", true);
        forceOutOfSpectator = c.getBoolean("force-out-of-spectator", false);

        String name = c.getString("fake-block", "minecraft:deepslate").toLowerCase();
        if (!name.contains(":")) name = "minecraft:" + name;
        StateType type = StateTypes.getByName(name);
        if (type == null) {
            getLogger().warning("Unknown fake-block '" + name + "', using minecraft:deepslate");
            type = StateTypes.DEEPSLATE;
        }
        ClientVersion cv = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        fakeBlockId = WrappedBlockState.getDefaultState(cv, type).getGlobalId();
    }

    private void startTasks() {
        getServer().getScheduler().cancelTasks(this);
        // state + LOS tick
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, losInterval);
        // chunk refresh queue
        getServer().getScheduler().runTaskTimer(this, this::processRefreshQueue, 20L, 1L);
    }

    // ------------------------------------------------------------------
    // Masking state
    // ------------------------------------------------------------------

    /** Recomputes whether the player is masked. Queues chunk refresh when it changes. */
    private void updateState(Player p, boolean refreshOnChange) {
        PlayerState st = states.computeIfAbsent(p.getUniqueId(), k -> new PlayerState());
        World w = p.getWorld();
        boolean enabled = worlds.isEmpty() || worlds.contains(w.getName());
        boolean bypass = p.hasPermission("antifreecam.bypass");

        double y = p.getLocation().getY();
        double reveal = hideBelowY + revealMargin;
        boolean masked;
        if (!enabled || bypass) {
            masked = false;
        } else {
            // hysteresis so players don't flicker at the boundary
            masked = st.masked ? y >= reveal : y >= reveal + 4;
        }

        boolean changed = masked != st.masked
                || enabled != st.worldEnabled
                || !w.getName().equals(st.worldName);

        st.minY = w.getMinHeight();
        st.worldEnabled = enabled;
        st.masked = masked;
        st.worldName = w.getName();

        if (changed && refreshOnChange && enabled) queueRefresh(p);
    }

    private void queueRefresh(Player p) {
        World w = p.getWorld();
        long wid = w.getUID().getMostSignificantBits();
        refreshWorlds.put(wid, w);
        int pcx = p.getLocation().getBlockX() >> 4;
        int pcz = p.getLocation().getBlockZ() >> 4;
        int r = Math.min(refreshRadius, Math.max(2, p.getSendViewDistance()));

        Set<Long> seen = new LinkedHashSet<>();
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    long[] entry = {wid, pcx + dx, pcz + dz};
                    refreshQueue.add(entry);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void processRefreshQueue() {
        for (int i = 0; i < refreshPerTick && !refreshQueue.isEmpty(); i++) {
            long[] e = refreshQueue.poll();
            World w = refreshWorlds.get(e[0]);
            if (w == null) continue;
            int cx = (int) e[1], cz = (int) e[2];
            if (w.isChunkLoaded(cx, cz)) {
                try {
                    w.refreshChunk(cx, cz);
                } catch (Throwable t) {
                    // refreshChunk can be unavailable on some builds; ignore
                }
            }
        }
        if (refreshQueue.isEmpty()) refreshWorlds.clear();
    }

    // ------------------------------------------------------------------
    // Tick: state update + entity hiding
    // ------------------------------------------------------------------

    private void tick() {
        for (Player viewer : getServer().getOnlinePlayers()) {
            updateState(viewer, true);
            PlayerState st = states.get(viewer.getUniqueId());

            boolean bypass = viewer.hasPermission("antifreecam.bypass");
            if (bypass || viewer.getGameMode() == GameMode.SPECTATOR) continue;

            boolean underHide = st != null && st.masked && st.worldEnabled && hideUndergroundEntities;
            if (!losEnabled && !underHide) continue;

            Set<UUID> hiddenSet = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            Location eye = viewer.getEyeLocation();

            for (Entity target : viewer.getNearbyEntities(maxDist, maxDist, maxDist)) {
                if (!(target instanceof LivingEntity living)) continue;
                boolean isPlayer = target instanceof Player;
                if (isPlayer && !hidePlayers && !underHide) continue;
                if (!isPlayer && !hideMobs && !underHide) continue;

                boolean visible = true;
                if (underHide && target.getLocation().getY() < hideBelowY) {
                    visible = false;
                } else if (losEnabled && ((isPlayer && hidePlayers) || (!isPlayer && hideMobs))) {
                    double dist = target.getLocation().distance(eye);
                    visible = dist <= alwaysVisible || canSee(eye, living);
                }

                UUID id = target.getUniqueId();
                if (visible && hiddenSet.remove(id)) {
                    viewer.showEntity(this, target);
                } else if (!visible && hiddenSet.add(id)) {
                    viewer.hideEntity(this, target);
                }
            }

            hiddenSet.removeIf(id -> {
                Entity e = getServer().getEntity(id);
                if (e == null || !e.isValid()) return true;
                if (!e.getWorld().equals(viewer.getWorld())
                        || e.getLocation().distanceSquared(eye) > maxDist * maxDist) {
                    viewer.showEntity(this, e);
                    return true;
                }
                return false;
            });
        }
    }

    private boolean canSee(Location eye, LivingEntity target) {
        Location base = target.getLocation();
        double h = target.getHeight();
        double w = target.getWidth() / 2.0;
        double[][] points = {
                {0, h * 0.9, 0}, {0, h * 0.5, 0}, {0, 0.1, 0},
                {w, h * 0.5, 0}, {-w, h * 0.5, 0}, {0, h * 0.5, w}, {0, h * 0.5, -w}
        };
        for (double[] p : points) {
            if (clearLine(eye, base.clone().add(p[0], p[1], p[2]))) return true;
        }
        return false;
    }

    private boolean clearLine(Location from, Location to) {
        World world = from.getWorld();
        Vector start = from.toVector();
        Vector dir = to.toVector().subtract(start);
        double length = dir.length();
        if (length < 0.5) return true;
        dir.normalize();
        for (double d = 0.5; d < length; d += 0.5) {
            Vector pos = start.clone().add(dir.clone().multiply(d));
            int x = pos.getBlockX(), y = pos.getBlockY(), z = pos.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isOccluding()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        updateState(e.getPlayer(), true);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (!blockSpectatorTp) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (e.getPlayer().hasPermission("antifreecam.bypass")) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        if (!forceOutOfSpectator) return;
        if (e.getNewGameMode() != GameMode.SPECTATOR) return;
        if (e.getPlayer().hasPermission("antifreecam.bypass")) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        states.remove(id);
        hidden.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            startTasks();
            for (Player p : getServer().getOnlinePlayers()) updateState(p, true);
            sender.sendMessage("AntiFreecam config reloaded.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            long masked = states.values().stream().filter(s -> s.masked && s.worldEnabled).count();
            sender.sendMessage("AntiFreecam: hide-below-y=" + hideBelowY
                    + ", reveal-margin=" + revealMargin
                    + ", hide-air=" + hideAir
                    + ", masked players=" + masked + "/" + states.size()
                    + ", refresh queue=" + refreshQueue.size());
            return true;
        }
        sender.sendMessage("Usage: /antifreecam <reload|status>");
        return true;
    }
}
