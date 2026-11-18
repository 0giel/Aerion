package pl.aerion.screens;

import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server mod: passes the windows Aerion players share on to the Aerion players near them. */
public final class AerionScreens implements DedicatedServerModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("aerion-screens");

	@Override
	public void onInitializeServer() {
		Relay.config = Config.load();
		LOG.info("Aerion screens: {} (range {} blocks, {} pictures/s, {} kbit/s per player)", Relay.config.enabled ? "on" : "off",
				Relay.config.range, Relay.config.fps, Relay.config.kbps);
	}
}
