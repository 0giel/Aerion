package pl.aerion.mod;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import pl.aerion.mod.platform.Game;

/**
 * "Host a world" from the title screen: pick a world as usual, and once it is loaded it opens to
 * other players by itself. With the e4mc mod in the instance, e4mc posts a public address for
 * friends over the internet; without it the world is reachable on the local network.
 */
public final class Host {
	private Host() {}

	/** Set from the button until the chosen world is open, or the player went back. */
	private static boolean pending;

	public static void start(Screen parent) {
		pending = true;
		Game.setScreen(new SelectWorldScreen(parent));
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (!pending) return;
		if (Game.screen() instanceof TitleScreen) {
			pending = false;
			return;
		}
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || Game.screen() != null) return;
		pending = false;
		if (server.isPublished()) return;
		int port = HttpUtil.getAvailablePort();
		if (!Game.publish(server, port)) {
			Game.chat(Component.literal(Lang.tr("aerion.host.failed")));
			return;
		}
		if (hasE4mc()) {
			Game.chat(Component.literal(Lang.tr("aerion.host.e4mc")));
		} else {
			String address = localAddress() + ":" + port;
			Game.chat(Component.literal(Lang.tr("aerion.host.lan").replace("{address}", address)));
		}
	}

	public static boolean hasE4mc() {
		FabricLoader loader = FabricLoader.getInstance();
		return loader.isModLoaded("e4mc_minecraft") || loader.isModLoaded("e4mc");
	}

	/** This computer's address on the home network, for friends in the same house. */
	private static String localAddress() {
		try {
			for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
				for (InetAddress a : Collections.list(n.getInetAddresses())) {
					if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
				}
			}
		} catch (Exception e) {
			AerionClient.LOG.debug("Aerion UI: no local address: {}", e.toString());
		}
		return "localhost";
	}
}
