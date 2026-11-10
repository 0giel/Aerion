package pl.aerion.mod;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.resources.Identifier;
import org.w3c.dom.Node;
import pl.aerion.mod.platform.Gfx;
import pl.aerion.mod.platform.Textures;

/**
 * The launcher theme's background picture behind the game's menus: PNG, JPEG or GIF, animated
 * GIFs playing at their own speed. The file is decoded on a thread of its own; frames become
 * textures a few per frame on the render thread, and the gradient shows until they are ready.
 */
public final class MenuPicture {
	private MenuPicture() {}

	/** Decoded pixels for all frames together stay under this (bytes), whatever the GIF. */
	private static final long BUDGET = 96L * 1024 * 1024;
	private static final int MAX_FRAMES = 300, MAX_W = 1920, MAX_H = 1080, UPLOADS_PER_FRAME = 6;

	private record Decoded(String path, int w, int h, List<int[]> frames, int[] delays) {}

	private static String path;
	private static volatile Decoded decoded;
	private static int generation;
	private static Identifier[] ids = new Identifier[0];
	private static int uploaded, w, h, total;
	private static int[] delays = new int[0];

	/**
	 * Called every client tick. In a world the menus show the world, not this picture, so its
	 * frames (a video's can take a few hundred MB) are let go; back in the menus they load again.
	 */
	public static void tick(net.minecraft.client.Minecraft mc) {
		if (mc.level != null && (path != null || decoded != null)) {
			release();
			AerionClient.LOG.info("Aerion UI: menu background released while in a world");
		}
	}

	/** Whether a picture is on screen instead of the gradient (the aurora stays away then). */
	public static boolean active() {
		return ids.length > 0 && uploaded == ids.length;
	}

	/** Draws the picture over the whole screen; false when there is none (yet). */
	public static boolean draw(Gfx g, Theme t) {
		String want = t.backgroundFile;
		// Loading a world draws menu backgrounds too; the picture isn't loaded for those.
		if (net.minecraft.client.Minecraft.getInstance().level != null) return false;
		if (want == null) {
			release();
			return false;
		}
		if (!want.equals(path)) start(want);
		Decoded d = decoded;
		if (d != null && d.path.equals(path) && ids.length == 0) prepare(d);
		upload();
		if (!active()) return false;
		int frame = frameAt(System.currentTimeMillis() % Math.max(1, total));
		// Cover: fill the screen, cut what sticks out, keep the picture's proportions.
		int sw = g.width(), sh = g.height();
		float scale = Math.max((float) sw / w, (float) sh / h);
		int dw = Math.round(w * scale), dh = Math.round(h * scale);
		g.texture(ids[frame], (sw - dw) / 2, (sh - dh) / 2, dw, dh, w, h, 0xFFFFFFFF);
		int dim = Math.round(Math.max(0, Math.min(1, t.backgroundDim)) * 255);
		if (dim > 0) g.fill(0, 0, sw, sh, dim << 24);
		return true;
	}

	private static int frameAt(long time) {
		long t = 0;
		for (int i = 0; i < delays.length; i++) {
			t += delays[i];
			if (time < t) return i;
		}
		return delays.length - 1;
	}

	private static void start(String file) {
		release();
		path = file;
		int gen = ++generation;
		Thread thread = new Thread(() -> {
			try {
				Decoded d = decode(file);
				if (gen == generation) decoded = d;
			} catch (Exception | OutOfMemoryError e) {
				AerionClient.LOG.warn("Aerion UI: can't read the menu background {}: {}", file, e.toString());
			}
		}, "Aerion menu picture");
		thread.setDaemon(true);
		thread.start();
	}

	private static void prepare(Decoded d) {
		w = d.w;
		h = d.h;
		delays = d.delays;
		total = 0;
		for (int ms : delays) total += ms;
		ids = new Identifier[d.frames.size()];
		for (int i = 0; i < ids.length; i++) ids[i] = Identifier.fromNamespaceAndPath("aerion", "menu_picture/" + generation + "_" + i);
		uploaded = 0;
	}

	private static void upload() {
		Decoded d = decoded;
		if (d == null || uploaded >= ids.length) return;
		int end = Math.min(ids.length, uploaded + UPLOADS_PER_FRAME);
		try {
			for (; uploaded < end; uploaded++) Textures.registerSmooth(ids[uploaded], w, h, d.frames.get(uploaded));
		} catch (RuntimeException e) {
			AerionClient.LOG.warn("Aerion UI: can't show the menu background: {}", e.toString());
			release();
			return;
		}
		// Pixels are on the graphics card now.
		if (uploaded == ids.length) decoded = null;
	}

	private static void release() {
		generation++;
		for (int i = 0; i < uploaded && i < ids.length; i++) Textures.release(ids[i]);
		ids = new Identifier[0];
		uploaded = 0;
		decoded = null;
		path = null;
	}

	// --- decoding (off the render thread) ------------------------------------------------------

	private static Decoded decode(String file) throws Exception {
		if (new File(file).isDirectory()) return decodeFrames(file);
		if (file.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) return decodeGif(file);
		BufferedImage img = ImageIO.read(new File(file));
		if (img == null) throw new IllegalArgumentException("not a picture");
		int[] size = fit(img.getWidth(), img.getHeight(), 1);
		return new Decoded(file, size[0], size[1], List.of(pixels(img, size[0], size[1])), new int[] {1000});
	}

	/**
	 * A video background, as the launcher prepares it: numbered JPEG pictures and `frames.json`
	 * with the time between them (the game can't play videos itself).
	 */
	private static Decoded decodeFrames(String dir) throws Exception {
		File folder = new File(dir);
		int delay = 125;
		File info = new File(folder, "frames.json");
		if (info.isFile()) {
			try (java.io.Reader r = new java.io.FileReader(info, java.nio.charset.StandardCharsets.UTF_8)) {
				com.google.gson.JsonObject o = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
				if (o.has("delay")) delay = Math.max(20, Math.min(1000, o.get("delay").getAsInt()));
			}
		}
		File[] files = folder.listFiles((d, n) -> n.startsWith("frame-") && n.endsWith(".jpg"));
		if (files == null || files.length == 0) throw new IllegalArgumentException("no frames");
		java.util.Arrays.sort(files);
		int count = Math.min(MAX_FRAMES, files.length);
		BufferedImage first = ImageIO.read(files[0]);
		if (first == null) throw new IllegalArgumentException("not a picture");
		int[] size = fit(first.getWidth(), first.getHeight(), count);
		List<int[]> frames = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			BufferedImage img = i == 0 ? first : ImageIO.read(files[i]);
			if (img != null) frames.add(pixels(img, size[0], size[1]));
		}
		int[] delays = new int[frames.size()];
		java.util.Arrays.fill(delays, delay);
		return new Decoded(dir, size[0], size[1], frames, delays);
	}

	/** A size within the screen-sized limit and, for animations, the memory budget. */
	private static int[] fit(int w, int h, int frames) {
		double scale = Math.min(1, Math.min((double) MAX_W / w, (double) MAX_H / h));
		double perFrame = (double) BUDGET / 4 / Math.max(1, frames);
		if (w * scale * h * scale > perFrame) scale = Math.sqrt(perFrame / ((double) w * h));
		return new int[] {Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
	}

	private static int[] pixels(BufferedImage img, int w, int h) {
		BufferedImage out = img;
		if (img.getWidth() != w || img.getHeight() != h || img.getType() != BufferedImage.TYPE_INT_ARGB) {
			out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(img, 0, 0, w, h, null);
			g.dispose();
		}
		return out.getRGB(0, 0, w, h, null, 0, w);
	}

	/** Every frame drawn onto the full canvas, as a browser shows it (offsets, disposal, delays). */
	private static Decoded decodeGif(String file) throws Exception {
		try (ImageInputStream in = ImageIO.createImageInputStream(new File(file))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
			if (!readers.hasNext()) throw new IllegalStateException("no GIF reader");
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, false);
				int count = Math.min(MAX_FRAMES, reader.getNumImages(true));
				Node screen = child(reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
				BufferedImage first = reader.read(0);
				int cw = intAttr(screen, "logicalScreenWidth", first.getWidth()), ch = intAttr(screen, "logicalScreenHeight", first.getHeight());
				int[] size = fit(cw, ch, count);
				BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
				List<int[]> frames = new ArrayList<>();
				int[] delays = new int[count];
				for (int i = 0; i < count; i++) {
					BufferedImage img = i == 0 ? first : reader.read(i);
					IIOMetadata meta = reader.getImageMetadata(i);
					Node root = meta.getAsTree("javax_imageio_gif_image_1.0");
					Node desc = child(root, "ImageDescriptor"), control = child(root, "GraphicControlExtension");
					int x = intAttr(desc, "imageLeftPosition", 0), y = intAttr(desc, "imageTopPosition", 0);
					String disposal = control != null ? attr(control, "disposalMethod", "none") : "none";
					// Browsers play 0 and 1 hundredths as a tenth of a second.
					int delay = control != null ? intAttr(control, "delayTime", 10) : 10;
					delays[i] = (delay <= 1 ? 10 : delay) * 10;
					BufferedImage before = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
					Graphics2D g = canvas.createGraphics();
					g.drawImage(img, x, y, null);
					g.dispose();
					frames.add(pixels(canvas, size[0], size[1]));
					if (disposal.equals("restoreToBackgroundColor")) {
						Graphics2D clear = canvas.createGraphics();
						clear.setComposite(AlphaComposite.Clear);
						clear.fillRect(x, y, img.getWidth(), img.getHeight());
						clear.dispose();
					} else if (before != null) {
						canvas = before;
					}
				}
				return new Decoded(file, size[0], size[1], frames, delays);
			} finally {
				reader.dispose();
			}
		}
	}

	private static BufferedImage copy(BufferedImage img) {
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	private static Node child(Node node, String name) {
		if (node == null) return null;
		for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n.getNodeName().equals(name)) return n;
		}
		return null;
	}

	private static String attr(Node node, String name, String fallback) {
		Node a = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
		return a == null ? fallback : a.getNodeValue();
	}

	private static int intAttr(Node node, String name, int fallback) {
		try {
			return Integer.parseInt(attr(node, name, String.valueOf(fallback)));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
