package pl.aerion.screens.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import pl.aerion.net.Proto;
import pl.aerion.net.ScreenPayload;

/**
 * Windows shared with other players, through a server with the Aerion Screens server mod. A
 * panel the player shares (off by default, per panel) is sent as a few small JPEG pictures a
 * second; panels others share near the player arrive the same way and hang where their owner put
 * them, read-only: clicks and keys never reach someone else's window. Without the server mod
 * nothing is sent and the menu says sharing isn't possible here.
 */
public final class ScreenShare {
	private ScreenShare() {}

	/** The most remote panels shown at once, and the largest picture accepted. */
	private static final int MAX_REMOTE = 16, MAX_SIDE = 2048;

	private static final ConcurrentLinkedQueue<byte[]> INBOX = new ConcurrentLinkedQueue<>();
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Aerion shared windows");
		t.setDaemon(true);
		return t;
	});

	private record Key(UUID owner, int id) {}

	private static final Map<Key, Mirrors.Panel> REMOTE = new HashMap<>();
	private static Object connection;
	private static boolean helloSent, lastView;
	private static volatile boolean supported;
	private static volatile int serverFps = 5, maxFrameKb = 200;
	private static String allowSent;
	private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
	private static long lastPanels;

	/** Whether this server passes shared windows on (it answered the hello). */
	public static boolean supported() {
		return supported;
	}

	/** Whether to show others' windows (Aerion menu → Windows → settings); on unless switched off. */
	private static boolean view() {
		return !"0".equals(Screens.settings.opt("others", "1"));
	}

	/** A message on the channel (any thread). */
	public static void received(byte[] data) {
		if (INBOX.size() < 256) INBOX.add(data);
	}

	private static void send(byte[] message) {
		var conn = Minecraft.getInstance().getConnection();
		if (conn != null) conn.send(new ServerboundCustomPayloadPacket(new ScreenPayload(message)));
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		Object conn = mc.getConnection();
		if (conn != connection) {
			connection = conn;
			helloSent = false;
			supported = false;
			clearRemote();
			WindowAudio.stopAll();
		}
		if (conn == null || mc.player == null) return;
		boolean view = view();
		if (!helloSent || view != lastView) {
			send(Proto.hello(view));
			helloSent = true;
			lastView = view;
			if (!view) clearRemote();
		}
		for (byte[] b; (b = INBOX.poll()) != null;) handle(Proto.read(b));
		if (supported) {
			sendAllow();
			sendPanels();
		}
		// The sound of own shared windows, and others' sound from where their windows hang.
		List<Mirrors.Panel> sounding = new ArrayList<>();
		if (supported && soundOn()) for (Mirrors.Panel p : Mirrors.all()) if (live(p) && p.sentAs != null) sounding.add(p);
		WindowAudio.tick(sounding);
		float master = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
		RemoteAudio.tick(mc, othersVolume() * master);
	}

	private static boolean soundOn() {
		return !"0".equals(Screens.settings.opt("shareSound", "1"));
	}

	/** How loud others' windows are, 0–1 (Aerion menu → Windows). */
	private static float othersVolume() {
		try {
			return Math.max(0, Math.min(100, Integer.parseInt(Screens.settings.opt("othersVolume", "80")))) / 100f;
		} catch (NumberFormatException e) {
			return 0.8f;
		}
	}

	/** A piece of an own shared window's sound (sound thread). */
	static void sendAudio(Mirrors.Panel p, byte[] sound) {
		if (!live(p) || p.sentAs == null || sound.length > Proto.MAX_AUDIO) return;
		send(Proto.audio(p.netId, p.audioSeq++, sound));
	}

	/** Who may watch, when it changed (per server, kept with the panels). */
	private static void sendAllow() {
		Mirrors.Allow a = Mirrors.allow();
		String sig = a.everyone() + ":" + String.join(",", a.names());
		if (sig.equals(allowSent)) return;
		send(Proto.allow(a.everyone(), a.names()));
		allowSent = sig;
	}

	/** Width and JPEG quality of the pictures sent (Aerion menu → Windows → sharing quality). */
	private static int sendWidth() {
		return switch (quality()) {
			case "low" -> 480;
			case "medium" -> 640;
			case "max" -> 1280;
			default -> 960;
		};
	}

	private static float sendQuality() {
		return switch (quality()) {
			case "low" -> 0.55f;
			case "medium" -> 0.65f;
			case "max" -> 0.85f;
			default -> 0.75f;
		};
	}

	private static String quality() {
		return Screens.settings.opt("shareQuality", "high");
	}

	/** How wide windows must be read so shared pictures can be this sharp (0 when nothing is shared). */
	static int captureWidth() {
		if (!supported) return 0;
		for (Mirrors.Panel p : Mirrors.all()) if (p.shared) return sendWidth();
		return 0;
	}

	private static void handle(Proto.Message m) {
		switch (m) {
			case Proto.Welcome w -> {
				supported = w.version() == Proto.VERSION;
				serverFps = Math.max(1, Math.min(15, w.fps()));
				maxFrameKb = Math.max(16, Math.min(900, w.maxFrameKb()));
				allowSent = null;
				if (supported) Screens.LOG.info("Aerion: this world passes shared windows on ({} pictures/s)", serverFps);
			}
			case Proto.TheirPanel tp -> {
				if (!view()) return;
				Key key = new Key(tp.owner(), tp.panel().id());
				Mirrors.Panel p = REMOTE.get(key);
				if (p == null) {
					if (REMOTE.size() >= MAX_REMOTE) return;
					p = new Mirrors.Panel();
					p.remote = true;
					REMOTE.put(key, p);
					Screens.LOG.info("Aerion Screens: {} shares the window \"{}\" nearby", tp.name(), tp.panel().title());
				}
				Proto.Panel q = tp.panel();
				p.owner = tp.name().length() > 32 ? tp.name().substring(0, 32) : tp.name();
				p.title = q.title();
				p.x = q.x();
				p.y = q.y();
				p.z = q.z();
				p.yaw = q.yaw();
				p.pitch = q.pitch();
				p.width = q.width();
				p.aspect = q.aspect();
				p.radius = q.radius();
				p.curved = q.curved();
				p.sized = true;
			}
			case Proto.TheirFrame tf -> {
				Mirrors.Panel p = REMOTE.get(new Key(tf.owner(), tf.id()));
				if (p == null || p.decoding) return;
				p.decoding = true;
				Mirrors.Panel target = p;
				DECODER.execute(() -> {
					try {
						WindowSource.Frame f = decode(tf.jpeg());
						if (f != null) target.pending = f;
					} finally {
						target.decoding = false;
					}
				});
			}
			case Proto.TheirAudio ta -> {
				Key key = new Key(ta.owner(), ta.id());
				Mirrors.Panel p = REMOTE.get(key);
				if (p != null && view() && othersVolume() > 0) RemoteAudio.play(key, p, ta.sound());
			}
			case Proto.TheirRemove tr -> {
				RemoteAudio.stop(new Key(tr.owner(), tr.id()));
				Mirrors.Panel p = REMOTE.remove(new Key(tr.owner(), tr.id()));
				if (p != null) Mirrors.release(p);
			}
			case null, default -> {}
		}
	}

	private static void clearRemote() {
		RemoteAudio.stopAll();
		for (Mirrors.Panel p : REMOTE.values()) Mirrors.release(p);
		REMOTE.clear();
	}

	/** Others' panels to draw (render thread). */
	static List<Mirrors.Panel> remote() {
		return REMOTE.isEmpty() ? List.of() : new ArrayList<>(REMOTE.values());
	}

	// --- Sending -------------------------------------------------------------------------------

	private static boolean live(Mirrors.Panel p) {
		return p.shared && supported && !Mirrors.hidden();
	}

	/** Whether this own panel is seen by others now (for the "live" mark). */
	public static boolean isLive(Mirrors.Panel p) {
		return !p.remote && live(p);
	}

	/** Where shared panels hang, when it changed (and now and then anyway); stops ones no longer shared. */
	private static void sendPanels() {
		long now = System.currentTimeMillis();
		boolean refresh = now - lastPanels > 5000;
		if (refresh) lastPanels = now;
		for (Mirrors.Panel p : Mirrors.all()) {
			boolean on = live(p);
			if (!on) {
				if (p.sentAs != null) {
					send(Proto.remove(p.netId));
					p.sentAs = null;
				}
				continue;
			}
			if (p.netId == 0) p.netId = NEXT_ID.getAndIncrement();
			Proto.Panel q = new Proto.Panel(p.netId, p.x, p.y, p.z, p.yaw, p.pitch, p.width, p.aspect, p.radius, p.curved, p.title).clamped();
			if (refresh || !q.equals(p.sentAs)) {
				send(Proto.panel(q));
				p.sentAs = q;
			}
		}
	}

	/** A panel taken away: others stop seeing it. */
	static void removed(Mirrors.Panel p) {
		if (p.sentAs != null) send(Proto.remove(p.netId));
		p.sentAs = null;
	}

	/** On the capture thread: a new picture of an own panel, sent when it is shared and due. */
	static void offer(Mirrors.Panel p, WindowSource.Frame f) {
		if (!live(p) || p.sentAs == null) return;
		long now = System.currentTimeMillis();
		if (now - p.lastSent < 1000L / serverFps) return;
		p.lastSent = now;
		try {
			// Sharper when the server allows it: smaller and softer until it fits.
			int limit = maxFrameKb * 1024, width = sendWidth();
			float q = sendQuality();
			byte[] jpeg = encode(f, width, q);
			for (int step = 0; jpeg.length > limit && step < 4; step++) {
				if (q > 0.5f) q -= 0.15f;
				else width = Math.round(width * 0.75f);
				jpeg = encode(f, width, q);
			}
			if (jpeg.length > limit) return;
			int count = (jpeg.length + Proto.PART - 1) / Proto.PART;
			if (count > Proto.MAX_PARTS) return;
			int frame = ++p.frameNo;
			for (int i = 0; i < count; i++) {
				int from = i * Proto.PART;
				send(Proto.part(p.netId, frame, i, count, jpeg, from, Math.min(Proto.PART, jpeg.length - from)));
			}
		} catch (Exception e) {
			Screens.warnOnce("share-send", e);
		}
	}

	private static byte[] encode(WindowSource.Frame f, int maxWidth, float quality) throws Exception {
		float k = Math.min(1f, maxWidth / (float) f.width());
		int w = Math.max(1, Math.round(f.width() * k)), h = Math.max(1, Math.round(f.height() * k));
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			int sy = Math.min(f.height() - 1, (int) (y / k));
			for (int x = 0; x < w; x++) row[x] = f.argb()[sy * f.width() + Math.min(f.width() - 1, (int) (x / k))];
			img.setRGB(0, y, w, 1, row, 0, w);
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(ios);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(img, null, null), param);
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	/** A picture from someone else: its size is checked before it is decoded. */
	private static WindowSource.Frame decode(byte[] jpeg) {
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
			var readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) return null;
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0), h = reader.getHeight(0);
				if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) return null;
				BufferedImage img = reader.read(0);
				int[] px = img.getRGB(0, 0, w, h, null, 0, w);
				for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
				return new WindowSource.Frame(w, h, px);
			} finally {
				reader.dispose();
			}
		} catch (Exception e) {
			return null;
		}
	}
}
