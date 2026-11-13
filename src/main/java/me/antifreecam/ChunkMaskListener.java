package me.antifreecam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;

import java.util.UUID;

/** Rewrites outgoing chunk / block-change packets so masked players never receive underground data. */
public class ChunkMaskListener extends PacketListenerAbstract {

    private final AntiFreecam plugin;

    public ChunkMaskListener(AntiFreecam plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type != PacketType.Play.Server.CHUNK_DATA
                && type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            return;
        }
        if (event.getUser() == null) return;
        UUID uuid = event.getUser().getUUID();
        if (uuid == null) return;

        PlayerState st = plugin.getStates().get(uuid);
        // Unknown player (still joining): mask by default using overworld height
        if (st == null) st = plugin.defaultState();
        if (!st.masked || !st.worldEnabled) return;

        int hideY = plugin.getHideBelowY();

        if (type == PacketType.Play.Server.CHUNK_DATA) {
            maskChunk(event, st, hideY);
        } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange w = new WrapperPlayServerBlockChange(event);
            if (w.getBlockPosition().getY() < hideY) event.setCancelled(true);
        } else {
            WrapperPlayServerMultiBlockChange w = new WrapperPlayServerMultiBlockChange(event);
            Vector3i section = w.getChunkPosition(); // y = section index in world
            if (section.getY() * 16 + 15 < hideY) event.setCancelled(true);
        }
    }

    private void maskChunk(PacketSendEvent event, PlayerState st, int hideY) {
        WrapperPlayServerChunkData w = new WrapperPlayServerChunkData(event);
        Column column = w.getColumn();
        BaseChunk[] chunks = column.getChunks();
        ClientVersion cv = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();

        int fake = plugin.getFakeBlockId();
        boolean hideAir = plugin.isHideAir();
        boolean changed = false;

        for (int i = 0; i < chunks.length; i++) {
            BaseChunk chunk = chunks[i];
            if (chunk == null) continue;
            int sectionMinY = st.minY + i * 16;
            if (sectionMinY >= hideY) break; // sections go bottom -> top

            for (int y = 0; y < 16; y++) {
                if (sectionMinY + y >= hideY) break;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (hideAir || !chunk.get(cv, x, y, z).getType().isAir()) {
                            chunk.set(cv, x, y, z, fake);
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) event.markForReEncode(true);
    }
}
