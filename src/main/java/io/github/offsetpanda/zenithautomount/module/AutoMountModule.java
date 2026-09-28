package io.github.offsetpanda.zenithautomount.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.event.client.ClientConfigurationEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientTickEvent;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import io.github.offsetpanda.zenithautomount.BuildConstants;
import io.github.offsetpanda.zenithautomount.ZenithAutoMountPlugin;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.InteractAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetPassengersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CACHE;

/**
 * Keeps state for exactly one normal Minecart. It never searches the entity cache
 * for candidates; the only accepted target is the vehicle the bot actually entered.
 */
public final class AutoMountModule extends Module {
    /**
     * Volatile because a command or disconnect event may arrive while a client tick
     * is preparing an interaction. Clearing this flag prevents later retries.
     */
    private volatile boolean recoveryEnabled;
    private volatile Entity rememberedMinecart;
    /** Keeps an OFF transition atomic with a concurrent attempt to remember a cart. */
    private final Object targetStateLock = new Object();

    @Override
    public boolean enabledSetting() {
        return ZenithAutoMountPlugin.CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientTickEvent.class, this::handleClientTick),
            of(ClientDisconnectEvent.class, event -> clearRememberedMinecart()),
            of(ClientConfigurationEvent.Entering.class, event -> clearRememberedMinecart())
        );
    }

    /**
     * The core client codec has priority 0, so -5 lets its passenger/removal
     * handlers update the entity cache before this module observes the packet.
     */
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
        recoveryEnabled = true;
        scheduleRememberCurrentMinecart();
    }

    @Override
    public void onDisable() {
        recoveryEnabled = false;
        clearRememberedMinecart();
    }

    /** Called by the two command literals. ZenithProxy persists CONFIG automatically after commands. */
    public void setAutomountEnabled(final boolean enabled) {
        if (!enabled) {
            // Set this before unregistering events so an already-scheduled event cannot retry.
            recoveryEnabled = false;
            clearRememberedMinecart();
        }

        ZenithAutoMountPlugin.CONFIG.enabled = enabled;
        syncEnabledFromConfig();

        if (enabled && isEnabled()) {
            recoveryEnabled = true;
            scheduleRememberCurrentMinecart();
        }
    }

    private void handleClientTick(final ClientTickEvent event) {
        if (!recoveryEnabled || !CACHE.getPlayerCache().isAlive()) return;

        final Entity player = CACHE.getPlayerCache().getThePlayer();
        final Entity vehicle = currentVehicle(player);
        if (isNormalMinecart(vehicle)) {
            rememberMinecartIfEligible(vehicle);
            return;
        }

        // Do not try to replace or leave a different vehicle.
        if (player.isInVehicle()) return;

        final Entity target = validRememberedMinecart();
        if (target != null) {
            attemptRemount(target);
        }
    }

    private void handlePassengerUpdate(final ClientboundSetPassengersPacket packet) {
        if (!recoveryEnabled) return;

        final Entity target = validRememberedMinecart();
        if (target == null || packet.getEntityId() != target.getEntityId()) return;

        // The built-in handler has already updated the player vehicle cache.
        if (!CACHE.getPlayerCache().getThePlayer().isInVehicle()) {
            attemptRemount(target);
        }
    }

    private void handleEntityRemoval(final ClientboundRemoveEntitiesPacket packet) {
        final Entity target = rememberedMinecart;
        if (target == null) return;

        for (final int entityId : packet.getEntityIds()) {
            if (entityId == target.getEntityId()) {
                clearRememberedMinecart();
                return;
            }
        }
    }

    private void handleRespawn() {
        // A respawn can reset entities even in the same dimension.
        clearRememberedMinecart();
    }

    /** Schedules mutable-cache access on ZenithProxy's client event loop. */
    private void scheduleRememberCurrentMinecart() {
        final ClientSession client = Proxy.getInstance().getClient();
        if (client == null || !client.isConnected()) return;
        client.executeInEventLoop(this::rememberCurrentMinecartOnClientEventLoop);
    }

    private void rememberCurrentMinecartOnClientEventLoop() {
        if (!recoveryEnabled) return;
        final Entity player = CACHE.getPlayerCache().getThePlayer();
        final Entity vehicle = currentVehicle(player);
        if (isNormalMinecart(vehicle)) {
            rememberMinecartIfEligible(vehicle);
        }
    }

    /**
     * A current vehicle is the only possible target source. A different normal
     * Minecart cannot replace a still-valid remembered cart.
     */
    private void rememberMinecartIfEligible(final Entity vehicle) {
        synchronized (targetStateLock) {
            // OFF must win over an already-running tick or scheduled callback.
            if (!recoveryEnabled) return;

            final Entity currentTarget = validRememberedMinecart();
            if (currentTarget == null || currentTarget == vehicle) {
                rememberedMinecart = vehicle;
            }
        }
    }

    private Entity currentVehicle(final Entity player) {
        if (!player.isInVehicle()) return null;
        return CACHE.getEntityCache().get(player.getVehicleId());
    }

    private boolean isNormalMinecart(final Entity entity) {
        return entity != null
            && !entity.isRemoved()
            && entity.getEntityType() == EntityType.MINECART;
    }

    /**
     * Identity checking, rather than only checking an entity id, rejects a reused
     * id after an unload, respawn, dimension change, or reconnect.
     */
    private Entity validRememberedMinecart() {
        final Entity target = rememberedMinecart;
        if (target == null) return null;

        if (!isNormalMinecart(target)
            || CACHE.getEntityCache().get(target.getEntityId()) != target) {
            if (rememberedMinecart == target) {
                clearRememberedMinecart();
            }
            return null;
        }
        return target;
    }

    /**
     * Sends one normal Minecraft INTERACT packet only when the remembered cart is
     * currently reachable. The generic input API falls back to a use-item packet
     * on a missed raycast, so this target-specific packet keeps out-of-range retry
     * ticks silent and avoids accidentally using the held item.
     */
    private void attemptRemount(final Entity target) {
        if (!recoveryEnabled || target != rememberedMinecart || validRememberedMinecart() != target) return;
        if (CACHE.getPlayerCache().getThePlayer().isInVehicle()) return;
        if (!target.getPassengerIds().isEmpty()) return;

        final Vector2f rotation = RotationHelper.shortestRotationTo(target);
        if (!RaycastHelper.playerEyeRaycastThroughToTarget(target, rotation.getX(), rotation.getY()).hit()) {
            return;
        }

        // Recheck after the reach test so .automount off cannot start a later retry.
        if (!recoveryEnabled || target != rememberedMinecart || validRememberedMinecart() != target) return;

        sendClientPacketAsync(new ServerboundInteractPacket(
            target.getEntityId(),
            InteractAction.INTERACT,
            0,
            0,
            0,
            Hand.MAIN_HAND,
            // This is an automatic mount attempt, not a secondary-use interaction.
            false
        ));
    }

    private void clearRememberedMinecart() {
        synchronized (targetStateLock) {
            rememberedMinecart = null;
        }
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
            handleRespawn();
            return true;
        }
    }
}
