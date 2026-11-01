package pl.aerion.mod;

import java.util.Random;
import pl.aerion.mod.platform.Gfx;

/** Menu particles like the launcher's background: twinkling sparkles, rising dust or snow. */
public final class Particles {
	private Particles() {}

	private static final class P {
		float x, y, vx, vy, phase, speed;
		int size;
	}

	private static final Random RNG = new Random();
	private static P[] parts = new P[0];
	private static String kind = "";
	private static int w, h;
	private static long last;
	private static float t;

	private static P spawn(boolean anywhere) {
		P p = new P();
		boolean snow = kind.equals("snow");
		p.x = RNG.nextFloat() * w;
		p.y = anywhere ? RNG.nextFloat() * h : snow ? -4 : h + 4;
		p.size = kind.equals("sparkles") ? 1 + RNG.nextInt(2) : snow ? 1 + RNG.nextInt(2) : 1;
		// GUI pixels per 60 Hz frame, about a third of the launcher's CSS-pixel speeds.
		p.vx = (RNG.nextFloat() - 0.5f) * (snow ? 0.09f : 0.04f);
		p.vy = snow ? 0.08f + RNG.nextFloat() * 0.15f : -(0.02f + RNG.nextFloat() * 0.06f);
		p.phase = RNG.nextFloat() * 6.283f;
		p.speed = 0.4f + RNG.nextFloat() * 1.2f;
		return p;
	}

	public static void draw(Gfx g, Theme theme) {
		String k = theme.particles;
		if (k == null || k.equals("none")) return;
		int gw = g.width();
		int gh = g.height();
		if (!k.equals(kind) || gw != w || gh != h) {
			kind = k;
			w = gw;
			h = gh;
			int count = (k.equals("snow") ? 70 : k.equals("dust") ? 55 : 34) * w * h / (427 * 240);
			parts = new P[Math.max(8, Math.min(count, 160))];
			for (int i = 0; i < parts.length; i++) parts[i] = spawn(true);
			last = System.nanoTime();
		}
		long now = System.nanoTime();
		float steps = Math.min(6f, (now - last) / 16_666_667f);
		last = now;
		t += steps;
		// White on the (dimmed) backgrounds, like the launcher's text colour on dark themes.
		int color = theme.light ? 0xFFFFFF : theme.text & 0xFFFFFF;
		for (int i = 0; i < parts.length; i++) {
			P p = parts[i];
			p.x += p.vx * steps + (float) Math.sin((t + p.phase * 50) * 0.01) * 0.03f;
			p.y += p.vy * steps;
			if (p.y < -6 || p.y > h + 6 || p.x < -6 || p.x > w + 6) {
				parts[i] = p = spawn(false);
			}
			float tw = 0.5f + 0.5f * (float) Math.sin(t * 0.02f * p.speed + p.phase);
			float alpha = switch (kind) {
				case "sparkles" -> 0.10f + 0.40f * tw;
				case "snow" -> 0.40f + 0.25f * tw;
				default -> 0.15f + 0.30f * tw;
			};
			int c = ((int) (alpha * 255) << 24) | color;
			int x = Math.round(p.x);
			int y = Math.round(p.y);
			if (kind.equals("sparkles")) {
				// A small four-pointed star: a plus sign whose arms grow with the particle.
				int r = p.size;
				g.fill(x - r, y, x + r + 1, y + 1, c);
				g.fill(x, y - r, x + 1, y + r + 1, c);
			} else {
				g.fill(x, y, x + p.size, y + p.size, c);
			}
		}
	}
}
