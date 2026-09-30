package io.github.offsetpanda.zenithautomount.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import io.github.offsetpanda.zenithautomount.BuildConstants;
import io.github.offsetpanda.zenithautomount.ZenithAutoMountPlugin;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.MODULE_LOG;

/** Recovers only the exact normal Minecart the bot actually rode; never searches for carts. */
public final class AutoMountModule extends Module {
    // Commands/lifecycle events and the network send callback share this lock.
    // Mutable entity/position reads stay on the client event loop.
    private final Object targetStateLock = new Object();
    private boolean recoveryEnabled;
    private Entity rememberedMinecart;
    private boolean recovering;
    private boolean requirePassengerMount;
    private long stateGeneration;
    private boolean interactionPending;
    private boolean attemptLogged;
    private boolean targetOccupied;

    @Override
    public boolean enabledSetting() {
        return ZenithAutoMountPlugin.CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientTickEvent.class, this::handleClientTick),
            of(ClientDisconnectEvent.class, event -> clearRecovery("disconnected")),
            of(ClientConfigurationEvent.Entering.class, event -> clearRecovery("world transition"))
        );
    }

    /** Priority -5 observes passenger/removal state after the core priority-0 handlers. */
    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId(BuildConstants.PLUGIN_ID)
            .setPriority(-5)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundSetPassengersPacket.class, new PassengerUpdateHandler())
                .inbound(ClientboundRemoveEntitiesPacket.class, new EntityRemovalHandler())
                .inbound(ClientboundRespawnPacket.class, new RespawnHandler())
                .build())
            .build();
    }

    @Override
    public void onEnable() {
        final long generation;
        synchronized (targetStateLock) {
            clearTarget();
            recoveryEnabled = true;
            requirePassengerMount = false;
            generation = stateGeneration;
            MODULE_LOG.info("[AutoMount] enabled");
        }
        final ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return;
        // One initial snapshot, not a second retry driver. OFF/reset invalidates it.
        client.executeInEventLoop(() -> {
            synchronized (targetStateLock) {
                if (recoveryEnabled && generation == stateGeneration
                    && Proxy.getInstance().getClient() == client
                    && CACHE.getPlayerCache().isAlive()) {
                    observeCurrentVehicle();
                }
            }
        });
    }

    @Override
    public void onDisable() {
        disableRecovery();
    }

    /** Returns actual module state; ZenithProxy saves the config after commands. */
    public synchronized boolean setAutomountEnabled(final boolean enabled) {
        if (!enabled) disableRecovery();
        ZenithAutoMountPlugin.CONFIG.enabled = enabled;
        syncEnabledFromConfig();
        final boolean actualEnabled = isEnabled();
        ZenithAutoMountPlugin.CONFIG.enabled = actualEnabled;
        if (!actualEnabled) disableRecovery();
        return actualEnabled;
    }

    private void disableRecovery() {
        synchronized (targetStateLock) {
            if (recoveryEnabled) MODULE_LOG.info("[AutoMount] disabled");
            recoveryEnabled = false;
            clearTarget();
        }
    }

    private void handleClientTick(final ClientTickEvent event) {
        synchronized (targetStateLock) {
            if (!recoveryEnabled) return;
            if (!CACHE.getPlayerCache().isAlive()) {
                clearTarget("player not alive");
                return;
            }
            if (observeCurrentVehicle()) return;
            final Entity target = validRememberedMinecart();
            if (target == null) return;
            beginRecovery(target);
            attemptRemount(target);
        }
    }

    private void handlePassengerUpdate(final ClientboundSetPassengersPacket packet) {
        synchronized (targetStateLock) {
            if (!recoveryEnabled) return;
            if (!CACHE.getPlayerCache().isAlive()) {
                clearTarget("player not alive");
                return;
            }
            // The built-in handler already mounted/dismounted the cached player.
            // This captures even a mount and dismount between consecutive ticks.
            final Entity player = CACHE.getPlayerCache().getThePlayer();
            if (player.isInVehicle() && packet.getEntityId() == player.getVehicleId()) {
                requirePassengerMount = false;
            }
            if (observeCurrentVehicle()) return;
            final Entity target = validRememberedMinecart();
            if (target != null && packet.getEntityId() == target.getEntityId() && !recovering) {
                beginRecovery(target);
                attemptRemount(target);
            }
        }
    }

    /** Returns true whenever the player is already in a vehicle. Called on the client loop. */
    private boolean observeCurrentVehicle() {
        final Entity player = CACHE.getPlayerCache().getThePlayer();
        if (!player.isInVehicle()) return false;
        // Same-dimension respawn can retain old passenger fields in Zenith's cache.
        // A fresh server passenger update must establish the next ride after a reset.
        if (requirePassengerMount) return true;
        final Entity vehicle = CACHE.getEntityCache().get(player.getVehicleId());
        if (isValidMinecart(vehicle) && vehicle.getPassengerIds().contains(player.getEntityId())) {
            if (rememberedMinecart != vehicle || recovering) {
                final boolean remounted = recovering && rememberedMinecart == vehicle;
                clearTarget();
                rememberedMinecart = vehicle; // A real ride in B always replaces A.
                MODULE_LOG.info(remounted ? "[AutoMount] remount confirmed id={}"
                    : "[AutoMount] mounted minecart id={}", vehicle.getEntityId());
            }
        } else {
            clearTarget("player already mounted");
        }
        return true;
    }

    private boolean isValidMinecart(final Entity entity) {
        return entity != null && !entity.isRemoved() && entity.getEntityType() == EntityType.MINECART
            && CACHE.getEntityCache().get(entity.getEntityId()) == entity;
    }

    private Entity validRememberedMinecart() {
        final Entity target = rememberedMinecart;
        if (target != null && !isValidMinecart(target)) {
            clearTarget("target removed/invalid");
            return null;
        }
        return target;
    }

    private void beginRecovery(final Entity target) {
        if (recovering) return;
        recovering = true;
        stateGeneration++;
        MODULE_LOG.info("[AutoMount] dismount detected id={}", target.getEntityId());
    }

    /** Eye-to-current-AABB distance, using only cached coordinates and normal server reach. */
    private boolean isReachable(final Entity target) {
        final var player = CACHE.getPlayerCache();
        final var box = target.collisionBox();
        final double x = player.getX();
        final double y = player.getEyeY();
        final double z = player.getZ();
        final double dx = x - Math.clamp(x, box.minX(), box.maxX());
        final double dy = y - Math.clamp(y, box.minY(), box.maxY());
        final double dz = z - Math.clamp(z, box.minZ(), box.maxZ());
        // Exclude Zenith's optional additionalEntityReach; never inflate the cart's box.
        final double reach = Math.max(0, BOT.getAttributeValue(AttributeType.Builtin.ENTITY_INTERACTION_RANGE, 3.0f));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** One immediate attempt, then at most one per client tick; no cooldown or retry scheduler. */
    private void attemptRemount(final Entity target) {
        if (!isReachable(target)) {
            clearTarget("target out of range"); // Cannot restart without a real ride.
            return;
        }
        if (!target.getPassengerIds().isEmpty()) {
            if (!targetOccupied) MODULE_LOG.info("[AutoMount] target occupied id={}", target.getEntityId());
            targetOccupied = true;
            return;
        }
        targetOccupied = false;
        if (interactionPending) return; // No backlog if the network loop is busy.
        final ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected() || client.getChannel() == null) return;
        final long generation = stateGeneration;
        interactionPending = true;
        try {
            // sendAsync queues an unguarded send. Guard on the network loop immediately
            // before send(), so OFF/success/reset cancels even an already-queued attempt.
            client.getChannel().eventLoop().execute(() -> {
                synchronized (targetStateLock) {
                    if (generation != stateGeneration) return;
                    interactionPending = false;
                    if (!recoveryEnabled || !recovering || rememberedMinecart != target
                        || Proxy.getInstance().getClient() != client || !client.isConnected()
                        || client.getPacketProtocol().getOutboundState() != ProtocolState.GAME) return;
                    // The cache map is concurrent; no mutable position reads on this loop.
                    if (CACHE.getEntityCache().get(target.getEntityId()) != target) {
                        clearTarget("target removed/invalid");
                        return;
                    }
                    if (!attemptLogged) {
                        MODULE_LOG.info("[AutoMount] remount attempt id={}", target.getEntityId());
                        attemptLogged = true;
                    }
                    client.send(new ServerboundInteractPacket(
                        target.getEntityId(), InteractAction.INTERACT, Hand.MAIN_HAND, false));
                }
            });
        } catch (final RejectedExecutionException e) {
            clearTarget("connection closing");
        }
    }

    private void handleEntityRemoval(final ClientboundRemoveEntitiesPacket packet) {
        synchronized (targetStateLock) {
            if (rememberedMinecart == null) return;
            for (final int id : packet.getEntityIds()) {
                if (id == rememberedMinecart.getEntityId()) {
                    clearTarget("target removed");
                    return;
                }
            }
        }
    }

    private void clearRecovery(final String reason) {
        synchronized (targetStateLock) {
            requirePassengerMount = true;
            clearTarget(reason);
        }
    }

    private void clearTarget(final String reason) {
        if (rememberedMinecart != null) MODULE_LOG.info("[AutoMount] {} id={}", reason, rememberedMinecart.getEntityId());
        clearTarget();
    }

    /** Caller holds targetStateLock. Invalidates snapshots and queued sends too. */
    private void clearTarget() {
        rememberedMinecart = null;
        recovering = false;
        interactionPending = false;
        attemptLogged = false;
        targetOccupied = false;
        stateGeneration++;
    }

    private final class PassengerUpdateHandler implements ClientEventLoopPacketHandler<ClientboundSetPassengersPacket, ClientSession> {
        @Override
        public boolean applyAsync(final ClientboundSetPassengersPacket packet, final ClientSession session) {
            handlePassengerUpdate(packet);
            return true;
        }
    }

    private final class EntityRemovalHandler implements ClientEventLoopPacketHandler<ClientboundRemoveEntitiesPacket, ClientSession> {
        @Override
        public boolean applyAsync(final ClientboundRemoveEntitiesPacket packet, final ClientSession session) {
            handleEntityRemoval(packet);
            return true;
        }
    }

    private final class RespawnHandler implements ClientEventLoopPacketHandler<ClientboundRespawnPacket, ClientSession> {
        @Override
        public boolean applyAsync(final ClientboundRespawnPacket packet, final ClientSession session) {
            clearRecovery("respawn/world transition");
            return true;
        }
    }
}
