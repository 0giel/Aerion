package pl.aerion.screens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import pl.aerion.net.Proto;
import pl.aerion.net.ScreenPayload;

/**
 * Passes shared windows on (server thread only). Players who said hello and want to see windows
 * get the panels of other players hanging in their dimension within range, with the last picture
 * at once and each new one as it comes. Each sender is held to a number of windows, a picture
 * size, a picture rate and a byte rate; what goes over is dropped, never queued.
 */
public final class Relay {
	private Relay() {}

	static Config config = new Config();

	private static final class Shared {
		Proto.Panel panel;
		ResourceKey<Level> dimension;
		byte[] last;
		long lastSent;
		// The picture being put together.
		int frame = -1, got;
		byte[][] parts;
	}

	private static final class Sender {
		final Map<Integer, Shared> panels = new HashMap<>();
		boolean view;
		long windowStart;
		long bytes;
	}

	private record Key(UUID owner, int id) {}

	/** Players with Aerion (who said hello), by id. */
	private static final Map<UUID, Sender> PLAYERS = new HashMap<>();
	/** What each viewer has been told about. */
	private static final Map<UUID, Set<Key>> SEEN = new HashMap<>();
	private static int ticks;

	private static void send(ServerPlayer to, byte[] message) {
		to.connection.send(new ClientboundCustomPayloadPacket(new ScreenPayload(message)));
	}

	/** A message from a player. */
	public static void received(ServerPlayer from, byte[] data) {
		if (!config.enabled) return;
		Proto.Message m = Proto.read(data);
		if (m == null) return;
		UUID id = from.getUUID();
		if (m instanceof Proto.Hello h) {
			if (h.version() != Proto.VERSION) return;
			Sender s = PLAYERS.computeIfAbsent(id, k -> new Sender());
			s.view = h.view();
			if (!s.view) forgetFor(from);
			send(from, Proto.welcome(config.fps, config.maxPanels));
			return;
		}
		Sender s = PLAYERS.get(id);
		if (s == null) return;
		switch (m) {
			case Proto.PanelMsg pm -> panel(from, s, pm.panel());
			case Proto.Part part -> part(from, s, part);
			case Proto.Remove r -> {
				if (s.panels.remove(r.id()) != null) gone(new Key(id, r.id()));
			}
			default -> {}
		}
	}

	private static void panel(ServerPlayer from, Sender s, Proto.Panel p) {
		Shared sh = s.panels.get(p.id());
		if (sh == null) {
			if (s.panels.size() >= config.maxPanels) return;
			sh = new Shared();
			s.panels.put(p.id(), sh);
		}
		// A window hangs near its owner: nobody places pictures far across the world.
		double dx = p.x() - from.getX(), dy = p.y() - from.getY(), dz = p.z() - from.getZ();
		if (dx * dx + dy * dy + dz * dz > 64 * 64) return;
		boolean moved = sh.panel != null && !sh.panel.equals(p);
		sh.panel = p;
		sh.dimension = from.level().dimension();
		if (moved) {
			Key key = new Key(from.getUUID(), p.id());
			byte[] msg = Proto.theirPanel(from.getUUID(), from.getName().getString(), p);
			for (ServerPlayer viewer : viewersOf(from.level().getServer(), key)) send(viewer, msg);
		}
	}

	private static void part(ServerPlayer from, Sender s, Proto.Part part) {
		Shared sh = s.panels.get(part.id());
		if (sh == null || sh.panel == null) return;
		// The byte rate of all this player's windows, over one second.
		long now = System.currentTimeMillis();
		if (now - s.windowStart > 1000) {
			s.windowStart = now;
			s.bytes = 0;
		}
		s.bytes += part.bytes().length;
		if (s.bytes > config.kbps * 1000L / 8) return;
		if (part.frame() != sh.frame) {
			if (part.count() * (long) Proto.PART > config.maxFrameKb * 1024L + Proto.PART) {
				sh.frame = -1;
				return;
			}
			sh.frame = part.frame();
			sh.parts = new byte[part.count()][];
			sh.got = 0;
		}
		if (sh.parts == null || part.count() != sh.parts.length || sh.parts[part.index()] != null) return;
		sh.parts[part.index()] = part.bytes();
		sh.got++;
		if (sh.got < sh.parts.length) return;
		int size = 0;
		for (byte[] b : sh.parts) size += b.length;
		byte[] jpeg = new byte[size];
		int at = 0;
		for (byte[] b : sh.parts) {
			System.arraycopy(b, 0, jpeg, at, b.length);
			at += b.length;
		}
		sh.parts = null;
		sh.frame = -1;
		if (size > config.maxFrameKb * 1024 || now - sh.lastSent < 1000 / config.fps) return;
		sh.last = jpeg;
		sh.lastSent = now;
		Key key = new Key(from.getUUID(), part.id());
		byte[] msg = Proto.theirFrame(from.getUUID(), part.id(), jpeg);
		for (ServerPlayer viewer : viewersOf(from.level().getServer(), key)) send(viewer, msg);
	}

	/** Viewers who have been told about this window. */
	private static List<ServerPlayer> viewersOf(MinecraftServer server, Key key) {
		List<ServerPlayer> out = new ArrayList<>();
		if (server == null) return out;
		for (var e : SEEN.entrySet()) {
			if (!e.getValue().contains(key)) continue;
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) out.add(p);
		}
		return out;
	}

	private static void gone(Key key) {
		MinecraftServer server = null;
		for (var e : SEEN.entrySet()) {
			if (!e.getValue().remove(key)) continue;
			if (server == null) server = serverOf(e.getKey());
			ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(e.getKey());
			if (p != null) send(p, Proto.theirRemove(key.owner(), key.id()));
		}
	}

	private static MinecraftServer current;

	private static MinecraftServer serverOf(UUID ignored) {
		return current;
	}

	private static void forgetFor(ServerPlayer viewer) {
		Set<Key> seen = SEEN.remove(viewer.getUUID());
		if (seen != null) for (Key k : seen) send(viewer, Proto.theirRemove(k.owner(), k.id()));
	}

	/** A player left: their windows go away for everyone. */
	public static void left(ServerPlayer player) {
		UUID id = player.getUUID();
		Sender s = PLAYERS.remove(id);
		SEEN.remove(id);
		if (s != null) for (int panelId : new ArrayList<>(s.panels.keySet())) gone(new Key(id, panelId));
	}

	/** A few times a second: who sees which windows now. */
	public static void tick(MinecraftServer server) {
		current = server;
		if (!config.enabled || PLAYERS.isEmpty() || ++ticks % 5 != 0) return;
		double range = config.range;
		for (var v : PLAYERS.entrySet()) {
			if (!v.getValue().view) continue;
			ServerPlayer viewer = server.getPlayerList().getPlayer(v.getKey());
			if (viewer == null) continue;
			Set<Key> seen = SEEN.computeIfAbsent(v.getKey(), k -> new HashSet<>());
			Set<Key> now = new HashSet<>();
			for (var o : PLAYERS.entrySet()) {
				if (o.getKey().equals(v.getKey())) continue;
				ServerPlayer owner = server.getPlayerList().getPlayer(o.getKey());
				if (owner == null) continue;
				for (var e : o.getValue().panels.entrySet()) {
					Shared sh = e.getValue();
					if (sh.panel == null || sh.dimension != viewer.level().dimension()) continue;
					double dx = sh.panel.x() - viewer.getX(), dy = sh.panel.y() - viewer.getY(), dz = sh.panel.z() - viewer.getZ();
					if (dx * dx + dy * dy + dz * dz > range * range) continue;
					Key key = new Key(o.getKey(), e.getKey());
					now.add(key);
					if (seen.add(key)) {
						send(viewer, Proto.theirPanel(o.getKey(), owner.getName().getString(), sh.panel));
						if (sh.last != null) send(viewer, Proto.theirFrame(o.getKey(), e.getKey(), sh.last));
					}
				}
			}
			for (Key k : new ArrayList<>(seen)) {
				if (!now.contains(k)) {
					seen.remove(k);
					send(viewer, Proto.theirRemove(k.owner(), k.id()));
				}
			}
		}
	}
}
