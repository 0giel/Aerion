package pl.aerion.mod.mirror;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import pl.aerion.mod.AerionClient;

/**
 * Others' window sound, played from where their window hangs: quieter with distance (silent past
 * 24 blocks) and to the left or right as the player turns. One line of sound per window, fed by a
 * small queue; pieces that pile up are dropped so the sound never lags far behind the picture.
 */
final class RemoteAudio {
	private RemoteAudio() {}

	private static final float RANGE = 24f;
	private static final int MAX_PLAYING = 6;
	/** Pieces (0.1 s each) stored before playing starts, and how long to wait for them at most. */
	private static final int PRIME = 2;
	private static final long PRIME_WAIT_NS = 300_000_000L;
	private static final Map<Object, Player> PLAYING = new HashMap<>();

	/** A piece of sound for a window (render thread). */
	static void play(Object key, Mirrors.Panel panel, byte[] sound) {
		Player p = PLAYING.get(key);
		if (p == null) {
			if (PLAYING.size() >= MAX_PLAYING) return;
			p = new Player(panel);
			PLAYING.put(key, p);
			p.start();
		}
		// A burst: the oldest piece goes, so the sound keeps up.
		if (!p.queue.offer(sound)) {
			p.queue.poll();
			p.queue.offer(sound);
		}
	}

	static void stop(Object key) {
		Player p = PLAYING.remove(key);
		if (p != null) p.finish();
	}

	static void stopAll() {
		for (Player p : PLAYING.values()) p.finish();
		PLAYING.clear();
	}

	/** Every tick: how loud each window is in each ear, from where the player is and looks. */
	static void tick(Minecraft mc, float volume) {
		if (PLAYING.isEmpty()) return;
		var player = mc.player;
		Vec3 cam = player == null ? null : pl.aerion.mod.platform.Game.cameraPosition();
		for (Player p : PLAYING.values()) {
			if (cam == null) {
				p.left = p.right = 0;
				continue;
			}
			double dx = p.panel.x - cam.x, dy = p.panel.y - cam.y, dz = p.panel.z - cam.z;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			float near = (float) Math.max(0, 1 - dist / RANGE);
			float loud = volume * near * near;
			// Where the window is, seen from where the player looks: -1 left … 1 right.
			double toWindow = Math.atan2(-dx, dz), facing = Math.toRadians(player.getYRot());
			// Yaw turns clockwise (south 0, west 90): a positive turn to the window is to the right.
			float side = (float) Math.sin(toWindow - facing);
			float pan = Math.max(-1, Math.min(1, side));
			p.left = loud * (float) Math.cos((pan + 1) * Math.PI / 4);
			p.right = loud * (float) Math.sin((pan + 1) * Math.PI / 4);
		}
	}

	private static final class Player extends Thread {
		final Mirrors.Panel panel;
		final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
		volatile float left, right;
		volatile boolean stop;

		Player(Mirrors.Panel panel) {
			super("Aerion others' window sound");
			this.panel = panel;
			setDaemon(true);
		}

		void finish() {
			stop = true;
			interrupt();
		}

		@Override
		public void run() {
			AudioFormat format = new AudioFormat(WindowAudio.RATE, 16, 2, true, false);
			try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
				line.open(format, WindowAudio.RATE * 4 / 2);
				line.start();
				boolean told = false, priming = true;
				float lastL = 0, lastR = 0;
				int bufferBytes = line.getBufferSize();
				while (!stop) {
					// Pieces come over the network unevenly: keep a small store (about 0.2 s) before
					// playing, and again whenever the line ran dry, so the sound doesn't crackle.
					if (priming || line.available() >= bufferBytes - 4) {
						long until = System.nanoTime() + PRIME_WAIT_NS;
						while (!stop && queue.size() < PRIME && System.nanoTime() < until) Thread.sleep(10);
						priming = false;
					}
					byte[] block = queue.poll(500, TimeUnit.MILLISECONDS);
					if (block == null) {
						priming = true;
						continue;
					}
					short[] pcm = Adpcm.decode(block);
					if (pcm == null) continue;
					float l = left, r = right;
					byte[] out = new byte[pcm.length * 4];
					for (int i = 0; i < pcm.length; i++) {
						// The volume glides across the piece: steps as the player turns would click.
						float f = (i + 1f) / pcm.length, gl = lastL + (l - lastL) * f, gr = lastR + (r - lastR) * f;
						int a = Math.round(pcm[i] * gl), b = Math.round(pcm[i] * gr);
						out[4 * i] = (byte) a;
						out[4 * i + 1] = (byte) (a >> 8);
						out[4 * i + 2] = (byte) b;
						out[4 * i + 3] = (byte) (b >> 8);
					}
					lastL = l;
					lastR = r;
					line.write(out, 0, out.length);
					if (!told) {
						told = true;
						AerionClient.LOG.info("Aerion: playing the sound of {}'s window \"{}\" (now {}% left, {}% right)", panel.owner, panel.title, Math.round(l * 100), Math.round(r * 100));
					}
				}
				line.stop();
				line.flush();
			} catch (InterruptedException ignored) {
				// stopped
			} catch (Exception e) {
				AerionClient.warnOnce("others-window-sound", e);
			}
		}
	}
}
