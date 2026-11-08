package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.Comparator;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Chroma;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.Txt;
import pl.aerion.mod.features.Features;
import pl.aerion.mod.features.Overlays;
import pl.aerion.mod.features.WorldLook;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Gfx;

/**
 * The HUD modules and how they are laid out. Every module takes the shared style (text and
 * background colours, border, corners, padding, shadow, alignment), may override any part of
 * it, and has its own options; positions are relative so layouts survive other window sizes.
 */
public final class Hud {
	private Hud() {}

	/** Resolved look of one module. */
	public record Style(int text, int background, int border, int borderWidth, int radius, int padding, boolean shadow, String align, boolean smooth, boolean wave, int chromaSpeed,
			boolean outline, int gradient, String bar, int barColor) {
		/** The same look with every colour made `k` times as opaque (fading in and out). */
		Style faded(float k) {
			if (k >= 0.999f) return this;
			return new Style(fade(text, k), fade(background, k), fade(border, k), borderWidth, radius, padding, shadow, align, smooth, wave, chromaSpeed, outline,
					fade(gradient, k), bar, fade(barColor, k));
		}
	}

	private static int fade(int color, float k) {
		return color == 0 ? 0 : Colors.alpha(color, Math.round(((color >>> 24) & 0xFF) * k));
	}

	/** Everything a module needs while measuring and drawing. */
	public record Ctx(Minecraft mc, LocalPlayer player, JsonObject cfg, Style style, Theme theme, boolean preview) {
		String opt(String key, String fallback) {
			return HudConfig.opt(cfg, key, fallback);
		}

		boolean flag(String key, boolean fallback) {
			return "1".equals(opt(key, fallback ? "1" : "0")) || "true".equals(opt(key, ""));
		}

		/** A number option as the menu stores it (text in "options"). */
		int num(String key, int fallback) {
			try {
				return Integer.parseInt(opt(key, String.valueOf(fallback)).trim());
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
	}

	interface Module {
		/** Width and height at scale 1, padding included; {0, 0} hides the module. */
		int[] size(Ctx c);

		/** 0 good, 1 fair, 2 bad: a module that turns worse pulses once. */
		default int grade(Ctx c) {
			return 0;
		}

		void draw(Ctx c, Gfx g);
	}

	static final int LINE = 10;
	private static final float SMOOTH_SIZE = 8.5f;

	/** Width of text in the module's font. */
	static int width(Ctx c, String s) {
		return c.style().smooth() ? (int) Math.ceil(Txt.width(s, SMOOTH_SIZE)) : Gfx.textWidth(s);
	}

	/** Text in the module's font: the game's, or smooth with its shadow drawn as a second layer. */
	static void text(Ctx c, Gfx g, String s, int x, int y, int color, boolean shadow) {
		// Chroma as a wave: every letter a little further along the hue circle.
		if (c.style().wave() && color == c.style().text()) {
			int pen = x;
			for (int cp : s.codePoints().toArray()) {
				String ch = new String(Character.toChars(cp));
				plain(c, g, ch, pen, y, Chroma.color(-pen / 150f + 1f, c.style().chromaSpeed()), shadow);
				pen += width(c, ch);
			}
			return;
		}
		plain(c, g, s, x, y, color, shadow);
	}

	private static void plain(Ctx c, Gfx g, String s, int x, int y, int color, boolean shadow) {
		if (c.style().outline()) {
			int edge = Colors.alpha(0xFF000000, (color >>> 24) & 0xFF);
			if (!c.style().smooth()) {
				for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) g.text(s, x + d[0], y + d[1], edge, false);
				g.text(s, x, y, color, false);
			} else {
				for (float[] d : new float[][] {{-0.8f, 0}, {0.8f, 0}, {0, -0.8f}, {0, 0.8f}, {-0.6f, -0.6f}, {0.6f, 0.6f}, {-0.6f, 0.6f}, {0.6f, -0.6f}}) {
					Txt.draw(g, s, x + d[0], y + 4f + d[1], SMOOTH_SIZE, edge);
				}
				Txt.draw(g, s, x, y + 4f, SMOOTH_SIZE, color);
			}
			return;
		}
		if (!c.style().smooth()) {
			g.text(s, x, y, color, shadow);
			return;
		}
		if (shadow) Txt.draw(g, s, x + 0.6f, y + 4.6f, SMOOTH_SIZE, Colors.alpha(0xFF000000, 0x90));
		Txt.draw(g, s, x, y + 4f, SMOOTH_SIZE, color);
	}

	/** Green, yellow or red by how good a value is, when the module asks for it. */
	static int graded(boolean good, boolean fair) {
		return good ? 0xFF55DD77 : fair ? 0xFFFFE25A : 0xFFFF5555;
	}

	/** Modules made of text lines. */
	abstract static class Lines implements Module {
		abstract List<String> lines(Ctx c);

		/** Optional colour swatch before a line (e.g. potion colour); 0 for none. */
		int swatch(Ctx c, int line) {
			return 0;
		}

		/** Colour of a line's text. */
		int color(Ctx c, int line) {
			return c.style().text();
		}

		@Override
		public int grade(Ctx c) {
			int col = color(c, 0);
			return col == graded(false, false) ? 2 : col == graded(false, true) ? 1 : 0;
		}

		private int swatchSpace(Ctx c, List<String> lines) {
			for (int i = 0; i < lines.size(); i++) if (swatch(c, i) != 0) return 7;
			return 0;
		}

		@Override
		public int[] size(Ctx c) {
			List<String> l = lines(c);
			if (l.isEmpty()) return new int[] {0, 0};
			int w = 0;
			for (String s : l) w = Math.max(w, Hud.width(c, s));
			int p = c.style().padding();
			return new int[] {w + swatchSpace(c, l) + 2 * p, l.size() * LINE - 1 + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			List<String> l = lines(c);
			int[] s = size(c);
			int p = c.style().padding();
			int sw = swatchSpace(c, l);
			for (int i = 0; i < l.size(); i++) {
				String text = l.get(i);
				int tw = Hud.width(c, text);
				int inner = s[0] - 2 * p - sw;
				int x = p + sw + switch (c.style().align()) {
					case "center" -> (inner - tw) / 2;
					case "right" -> inner - tw;
					default -> 0;
				};
				int y = p + i * LINE;
				int color = swatch(c, i);
				if (color != 0) g.fill(p, y + 1, p + 5, y + 6, color);
				Hud.text(c, g, text, x, y, color(c, i), c.style().shadow());
			}
		}
	}

	static String format(String pattern, Object... pairs) {
		String out = pattern;
		for (int i = 0; i + 1 < pairs.length; i += 2) out = out.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
		return out;
	}

	static final Map<String, Module> MODULES = new LinkedHashMap<>();

	static {
		MODULES.put("fps", new Lines() {
			List<String> lines(Ctx c) {
				return List.of(format(c.opt("format", "{fps} FPS"), "fps", c.mc().getFps()));
			}

			@Override
			int color(Ctx c, int line) {
				int fps = c.mc().getFps();
				return c.flag("colored", false) ? graded(fps >= 60, fps >= 30) : c.style().text();
			}
		});
		MODULES.put("cps", new Lines() {
			List<String> lines(Ctx c) {
				return List.of(format(c.opt("format", "{left} | {right} CPS"), "left", Cps.left(), "right", Cps.right()));
			}
		});
		MODULES.put("coords", new Lines() {
			List<String> lines(Ctx c) {
				LocalPlayer p = c.player();
				double x = p == null ? 128.5 : p.getX(), y = p == null ? 64 : p.getY(), z = p == null ? -32.5 : p.getZ();
				String fmt = "1".equals(c.opt("decimals", "0")) ? "%.1f" : "%.0f";
				List<String> out = new ArrayList<>();
				if (c.flag("lines", false)) {
					out.add("X " + String.format(fmt, x));
					out.add("Y " + String.format(fmt, y));
					out.add("Z " + String.format(fmt, z));
				} else {
					out.add("XYZ " + String.format(fmt + " / " + fmt + " / " + fmt, x, y, z));
				}
				if (c.flag("biome", false) && p != null) {
					p.level().getBiome(p.blockPosition()).unwrapKey().ifPresent(k -> out.add(k.identifier().getPath().replace('_', ' ')));
				}
				if (c.flag("direction", true)) out.add(Compass.label(p == null ? 0 : p.getYRot(), false));
				if (c.flag("nether", false) && p != null) {
					boolean inNether = p.level().dimension().equals(net.minecraft.world.level.Level.NETHER);
					double k = inNether ? 8 : 0.125;
					out.add((inNether ? "Overworld " : "Nether ") + String.format("%.0f / %.0f", x * k, z * k));
				}
				return out;
			}
		});
		MODULES.put("direction", new Lines() {
			List<String> lines(Ctx c) {
				return List.of(Compass.label(c.player() == null ? 0 : c.player().getYRot(), c.flag("degrees", true)));
			}
		});
		MODULES.put("ping", new Lines() {
			List<String> lines(Ctx c) {
				int ping = 0;
				if (c.player() != null && c.mc().getConnection() != null) {
					PlayerInfo info = c.mc().getConnection().getPlayerInfo(c.player().getUUID());
					if (info != null) ping = info.getLatency();
				}
				return List.of(format(c.opt("format", "{ping} ms"), "ping", ping));
			}

			private int latency(Ctx c) {
				if (c.player() == null || c.mc().getConnection() == null) return 0;
				PlayerInfo info = c.mc().getConnection().getPlayerInfo(c.player().getUUID());
				return info == null ? 0 : info.getLatency();
			}

			@Override
			int color(Ctx c, int line) {
				int ms = latency(c);
				return c.flag("colored", false) ? graded(ms < 80, ms < 150) : c.style().text();
			}
		});
		MODULES.put("clock", new Lines() {
			List<String> lines(Ctx c) {
				String pattern = (c.flag("h24", true) ? "HH:mm" : "h:mm") + (c.flag("seconds", false) ? ":ss" : "")
						+ (c.flag("h24", true) ? "" : " a");
				String time = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern));
				if (!c.flag("date", false)) return List.of(time);
				return List.of(time, LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
			}
		});
		MODULES.put("memory", new Lines() {
			List<String> lines(Ctx c) {
				Runtime r = Runtime.getRuntime();
				long used = (r.totalMemory() - r.freeMemory()) >> 20;
				long max = r.maxMemory() >> 20;
				return List.of(format(c.opt("format", "{used}/{max} MB"), "used", used, "max", max, "percent", used * 100 / Math.max(1, max)));
			}

			@Override
			int color(Ctx c, int line) {
				Runtime r = Runtime.getRuntime();
				long percent = (r.totalMemory() - r.freeMemory()) * 100 / Math.max(1, r.maxMemory());
				return c.flag("colored", false) ? graded(percent < 70, percent < 90) : c.style().text();
			}
		});
		MODULES.put("toggles", new Lines() {
			List<String> lines(Ctx c) {
				List<String> out = new ArrayList<>();
				if (c.flag("sprint", true) && Features.sprintToggled()) out.add(Lang.tr("aerion.toggle.sprint"));
				if (c.flag("sneak", true) && Features.sneakToggled()) out.add(Lang.tr("aerion.toggle.sneak"));
				if (c.flag("fullbright", true) && Features.fullbrightOn()) out.add(Lang.tr("aerion.toggle.fullbright"));
				if (c.flag("freelook", true) && Features.freelookActive()) out.add(Lang.tr("aerion.toggle.freelook"));
				if (out.isEmpty() && c.preview()) out.add(Lang.tr("aerion.toggle.sprint"));
				return out;
			}
		});
		MODULES.put("potions", new Effects());
		MODULES.put("speed", new Lines() {
			List<String> lines(Ctx c) {
				LocalPlayer p = c.player();
				double dx = p == null ? 0.2 : p.getX() - p.xo, dy = p == null ? 0 : p.getY() - p.yo, dz = p == null ? 0.1 : p.getZ() - p.zo;
				double sq = dx * dx + dz * dz + (c.flag("vertical", false) ? dy * dy : 0);
				String value = String.format(c.flag("decimals", true) ? "%.2f" : "%.0f", Math.sqrt(sq) * 20);
				return List.of(format(c.opt("format", "{speed} m/s"), "speed", value));
			}
		});
		MODULES.put("death", new Lines() {
			List<String> lines(Ctx c) {
				LocalPlayer p = c.player();
				var last = p == null ? java.util.Optional.<net.minecraft.core.GlobalPos>empty() : p.getLastDeathLocation();
				if (last.isEmpty()) return c.preview() ? List.of(Lang.tr("aerion.hud.death") + " 128 / 64 / -32") : List.of();
				var pos = last.get().pos();
				String line = Lang.tr("aerion.hud.death") + " " + pos.getX() + " / " + pos.getY() + " / " + pos.getZ();
				if (c.flag("distance", true) && last.get().dimension().equals(p.level().dimension())) {
					line += String.format("  %.0f m", Math.sqrt(p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ())));
				}
				return List.of(line);
			}
		});
		MODULES.put("session", new Lines() {
			List<String> lines(Ctx c) {
				long s = Features.sessionMillis() / 1000;
				String time = s / 3600 + ":" + String.format("%02d:%02d", s / 60 % 60, s % 60);
				return List.of(c.flag("label", true) ? Lang.tr("aerion.hud.session") + " " + time : time);
			}
		});
		MODULES.put("day", new Lines() {
			List<String> lines(Ctx c) {
				if (c.mc().level == null) return List.of(Lang.tr("aerion.hud.day") + " 12");
				long t = WorldLook.actual(() -> Game.dayTime(c.mc().level));
				String day = Lang.tr("aerion.hud.day") + " " + t / 24000;
				if (!c.flag("time", true)) return List.of(day);
				long ofDay = (t + 6000) % 24000;
				return List.of(day + "  " + String.format("%02d:%02d", ofDay / 1000, ofDay % 1000 * 60 / 1000));
			}
		});
		MODULES.put("light", new Lines() {
			private int block(Ctx c) {
				return c.player() == null || c.mc().level == null ? 12 : c.mc().level.getBrightness(LightLayer.BLOCK, c.player().blockPosition());
			}

			private int sky(Ctx c) {
				if (c.player() == null || c.mc().level == null) return 15;
				return Math.max(0, c.mc().level.getBrightness(LightLayer.SKY, c.player().blockPosition()) - c.mc().level.getSkyDarken());
			}

			List<String> lines(Ctx c) {
				String line = Lang.tr("aerion.hud.light") + " " + block(c);
				return List.of(c.flag("sky", false) ? line + "  " + Lang.tr("aerion.hud.sky") + " " + sky(c) : line);
			}

			@Override
			int color(Ctx c, int line) {
				int l = block(c);
				return c.flag("colored", true) ? graded(l > 7, l > 0) : c.style().text();
			}
		});
		MODULES.put("packs", new Lines() {
			List<String> lines(Ctx c) {
				List<String> out = new ArrayList<>();
				for (var pack : c.mc().getResourcePackRepository().getSelectedPacks()) {
					if (pack.isRequired()) continue;
					out.add(pack.getTitle().getString());
				}
				if (out.isEmpty()) return c.flag("hideEmpty", true) && !c.preview() ? List.of() : List.of(Lang.tr("aerion.hud.noPacks"));
				java.util.Collections.reverse(out);
				return c.flag("all", false) ? out : List.of(out.get(0));
			}
		});
		MODULES.put("armorWarn", new Lines() {
			private boolean warned;

			private int threshold(Ctx c) {
				try {
					return Integer.parseInt(c.opt("threshold", "15"));
				} catch (NumberFormatException e) {
					return 15;
				}
			}

			List<String> lines(Ctx c) {
				List<String> out = new ArrayList<>();
				if (c.player() != null) {
					List<ItemStack> items = new ArrayList<>();
					for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
						items.add(c.player().getItemBySlot(slot));
					}
					if (c.flag("held", true)) items.add(c.player().getMainHandItem());
					for (ItemStack i : items) {
						if (i.isEmpty() || !i.isDamageableItem()) continue;
						int percent = (i.getMaxDamage() - i.getDamageValue()) * 100 / i.getMaxDamage();
						if (percent <= threshold(c)) out.add("! " + i.getHoverName().getString() + " " + percent + "%");
					}
				}
				if (!c.preview()) {
					if (!out.isEmpty() && !warned && c.flag("sound", true)) {
						c.mc().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2f));
					}
					warned = !out.isEmpty();
				}
				if (out.isEmpty() && c.preview()) out.add("! " + Lang.tr("aerion.module.armorWarn") + " 12%");
				return out;
			}

			@Override
			int color(Ctx c, int line) {
				if (!c.flag("blink", true)) return 0xFFFF5555;
				return Colors.alpha(0xFFFF5555, (int) (170 + 85 * Math.sin(System.currentTimeMillis() / 200.0)));
			}
		});
		MODULES.put("bossbar", new GameElement("bossbar", 182, 20));
		MODULES.put("scoreboard", new GameElement("scoreboard", 90, 100));
		MODULES.put("chatbox", new GameElement("chatbox", 320, 90));
		MODULES.put("totems", new Counter(false, new Counter.Row("totem", Items.TOTEM_OF_UNDYING)));
		MODULES.put("items", new Counter(true,
				new Counter.Row("arrow", Items.ARROW),
				new Counter.Row("pearl", Items.ENDER_PEARL),
				new Counter.Row("gapple", Items.GOLDEN_APPLE),
				new Counter.Row("rocket", Items.FIREWORK_ROCKET),
				new Counter.Row("xp", Items.EXPERIENCE_BOTTLE)));
		MODULES.put("armor", new Armor());
		MODULES.put("keystrokes", new Keystrokes());
		MODULES.put("minimap", new Minimap());
		MODULES.put("blockInfo", new BlockInfo());
		MODULES.put("fpsGraph", new FpsGraph());
		MODULES.put("playerList", new PlayerList());
	}

	static String roman(int n) {
		return switch (n) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(n);
		};
	}

	static String time(int ticks) {
		int s = ticks / 20;
		return s >= 3600 ? (s / 3600) + ":" + String.format("%02d:%02d", s / 60 % 60, s % 60) : (s / 60) + ":" + String.format("%02d", s % 60);
	}

	/** A part of the game's own HUD; only its place shows in the layout, the game draws the rest. */
	static final class GameElement implements Module {
		final String id;
		final int w, h;

		GameElement(String id, int w, int h) {
			this.id = id;
			this.w = w;
			this.h = h;
		}

		@Override
		public int[] size(Ctx c) {
			return new int[] {w, h};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			Txt.center(g, Lang.tr("aerion.module." + id), w / 2f, h / 2f, 8f, 0xFFFFFFFF);
		}
	}

	/** Where the game draws an element by itself, in GUI pixels: {x, y, w, h}. */
	private static int[] gameBox(String id, int width, int height) {
		return switch (id) {
			case "bossbar" -> new int[] {(width - 182) / 2, 12, 182, 20};
			case "scoreboard" -> new int[] {width - 90, (height - 100) / 2, 90, 100};
			default -> new int[] {0, height - 130, 320, 90};
		};
	}

	/** Before the game draws an element: moves it where the player put it; false hides it. */
	public static boolean beginElement(Gfx g, String id) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return true;
		JsonObject m = cfg.module(id);
		if ("1".equals(HudConfig.opt(m, "hide", "0"))) return false;
		if (!HudConfig.bool(m, "enabled", false)) return true;
		float k = scaleOf(m);
		int[] b = gameBox(id, g.width(), g.height());
		int[] at = position(m, g.width(), g.height(), Math.round(b[2] * k), Math.round(b[3] * k));
		float x = at[0], y = at[1];
		g.push();
		g.translate(x - b[0] * k, y - b[1] * k);
		g.scale(k);
		return true;
	}

	public static void endElement(Gfx g, String id) {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		JsonObject m = cfg.module(id);
		if (HudConfig.bool(m, "enabled", false) && !"1".equals(HudConfig.opt(m, "hide", "0"))) g.pop();
	}

	/** Active effects: the game's icon, name, level and time, each optional. */
	static final class Effects implements Module {
		private record Row(Holder<MobEffect> effect, String text, int ticks) {}

		private static int iconSize(Ctx c) {
			try {
				return Math.max(9, Math.min(18, Integer.parseInt(c.opt("iconSize", "12"))));
			} catch (NumberFormatException e) {
				return 12;
			}
		}

		private static String text(Ctx c, String name, int level, String time) {
			StringBuilder out = new StringBuilder();
			if (c.flag("name", true)) out.append(name);
			if (level > 1 && c.flag("level", true)) out.append(out.isEmpty() ? "" : " ").append(roman(level));
			if (c.flag("duration", true)) out.append(out.isEmpty() ? "" : "  ").append(time);
			return out.toString();
		}

		private static List<Row> rows(Ctx c) {
			List<Row> out = new ArrayList<>();
			if (c.player() != null) {
				for (MobEffectInstance e : c.player().getActiveEffects()) {
					String time = e.isInfiniteDuration() ? "∞" : time(e.getDuration());
					out.add(new Row(e.getEffect(), text(c, e.getEffect().value().getDisplayName().getString(), e.getAmplifier() + 1, time),
							e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
				}
			}
			if (out.isEmpty() && c.preview()) {
				out.add(new Row(MobEffects.SPEED, text(c, MobEffects.SPEED.value().getDisplayName().getString(), 2, "1:30"), 1800));
			}
			return out;
		}

		/** Room before the text: the icon, or a colour swatch without one. */
		private static int lead(Ctx c) {
			return c.flag("icon", true) ? iconSize(c) + 3 : c.flag("colors", true) ? 7 : 0;
		}

		private static int rowHeight(Ctx c) {
			return c.flag("icon", true) ? Math.max(LINE, iconSize(c) + 1) : LINE;
		}

		@Override
		public int[] size(Ctx c) {
			List<Row> list = rows(c);
			if (list.isEmpty()) return new int[] {0, 0};
			int w = 0;
			for (Row r : list) w = Math.max(w, Hud.width(c, r.text));
			int p = c.style().padding();
			return new int[] {lead(c) + w + 2 * p, list.size() * rowHeight(c) - 1 + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			int p = c.style().padding(), h = rowHeight(c), y = p, lead = lead(c);
			boolean icon = c.flag("icon", true);
			for (Row r : rows(c)) {
				int tint = 0xFF000000 | r.effect.value().getColor();
				if (icon) {
					int size = iconSize(c);
					g.sprite(Game.effectSprite(r.effect), p, y, size, size);
				} else if (c.flag("colors", true)) {
					g.fill(p, y + (h - 5) / 2, p + 5, y + (h - 5) / 2 + 5, tint);
				}
				int color = c.flag("tintText", false) ? tint : c.style().text();
				// Fading in and out in the last ten seconds.
				if (c.flag("blink", false) && r.ticks < 200) {
					color = Colors.alpha(color, (int) (130 + 125 * Math.sin(System.currentTimeMillis() / 150.0)));
				}
				Hud.text(c, g, r.text, p + lead, y + (h - 9) / 2, color, c.style().shadow());
				y += h;
			}
		}
	}

	/** Whether the module replaces the icons the game draws in the corner. */
	public static boolean hidesGameEffects() {
		HudConfig cfg = AerionClient.hud();
		if (cfg == null || !cfg.enabled()) return false;
		JsonObject m = cfg.module("potions");
		return HudConfig.bool(m, "enabled", false) && "1".equals(HudConfig.opt(m, "hideVanilla", "1"));
	}

	/** How many of some items the player carries, each with its icon or name. */
	static final class Counter implements Module {
		record Row(String key, Item item) {}

		private final boolean hideZero;
		private final List<Row> rows;

		Counter(boolean hideZero, Row... rows) {
			this.hideZero = hideZero;
			this.rows = List.of(rows);
		}

		private static int count(Ctx c, Item item) {
			if (c.player() == null) return 0;
			var inv = c.player().getInventory();
			int n = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (s.is(item)) n += s.getCount();
			}
			return n;
		}

		private List<Row> shown(Ctx c) {
			List<Row> out = new ArrayList<>();
			for (Row r : rows) {
				if (rows.size() > 1 && !c.flag(r.key, true)) continue;
				if (!c.preview() && c.flag("hideZero", hideZero) && count(c, r.item) == 0) continue;
				out.add(r);
			}
			return out;
		}

		private String label(Ctx c, Row r) {
			String n = format(c.opt("format", "{count}"), "count", count(c, r.item));
			return c.flag("icon", true) ? n : new ItemStack(r.item).getHoverName().getString() + ": " + n;
		}

		@Override
		public int[] size(Ctx c) {
			List<Row> list = shown(c);
			if (list.isEmpty()) return new int[] {0, 0};
			int p = c.style().padding(), w = 0;
			for (Row r : list) w = Math.max(w, Hud.width(c, label(c, r)));
			boolean icon = c.flag("icon", true);
			return new int[] {(icon ? 19 : 0) + w + 2 * p, list.size() * (icon ? 17 : LINE) - 1 + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			int p = c.style().padding(), y = p;
			boolean icon = c.flag("icon", true);
			for (Row r : shown(c)) {
				int color = c.flag("warn", true) && count(c, r.item) == 0 ? 0xFFFF5555 : c.style().text();
				if (icon) g.item(new ItemStack(r.item), p, y);
				Hud.text(c, g, label(c, r), p + (icon ? 19 : 0), icon ? y + 4 : y, color, c.style().shadow());
				y += icon ? 17 : LINE;
			}
		}
	}

	/** Armour pieces (and optionally the held item) with their durability. */
	static final class Armor implements Module {
		private List<ItemStack> items(Ctx c) {
			List<ItemStack> out = new ArrayList<>();
			if (c.player() == null) return out;
			for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				ItemStack i = c.player().getItemBySlot(s);
				if (!i.isEmpty()) out.add(i);
			}
			if (c.flag("hand", true) && !c.player().getMainHandItem().isEmpty()) out.add(c.player().getMainHandItem());
			if (c.flag("offhand", false) && !c.player().getOffhandItem().isEmpty()) out.add(c.player().getOffhandItem());
			return out;
		}

		private String label(Ctx c, ItemStack i) {
			String mode = c.opt("durability", "percent");
			if (!i.isDamageableItem() || mode.equals("none")) return i.getCount() > 1 ? String.valueOf(i.getCount()) : "";
			int left = i.getMaxDamage() - i.getDamageValue();
			return mode.equals("value") ? String.valueOf(left) : (left * 100 / i.getMaxDamage()) + "%";
		}

		private boolean vertical(Ctx c) {
			return !c.opt("layout", "vertical").equals("horizontal");
		}

		@Override
		public int[] size(Ctx c) {
			List<ItemStack> list = items(c);
			int n = list.isEmpty() && c.preview() ? 4 : list.size();
			if (n == 0) return new int[] {0, 0};
			int p = c.style().padding();
			int text = 0;
			for (ItemStack i : list) text = Math.max(text, Hud.width(c, label(c, i)));
			if (list.isEmpty()) text = Hud.width(c, "100%");
			return vertical(c)
					? new int[] {16 + (text > 0 ? text + 3 : 0) + 2 * p, n * 17 - 1 + 2 * p}
					: new int[] {n * Math.max(18, text + 2) + 2 * p, 16 + (text > 0 ? LINE : 0) + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			List<ItemStack> list = items(c);
			int p = c.style().padding();
			int text = 0;
			for (ItemStack i : list) text = Math.max(text, Hud.width(c, label(c, i)));
			int step = Math.max(18, text + 2);
			for (int k = 0; k < list.size(); k++) {
				ItemStack i = list.get(k);
				String l = label(c, i);
				int color = c.style().text();
				if (i.isDamageableItem() && c.flag("colored", true)) {
					float left = 1f - (float) i.getDamageValue() / i.getMaxDamage();
					if (left < 0.15f) color = 0xFFFF5555;
					else if (left < 0.35f) color = 0xFFFFAA33;
				}
				if (vertical(c)) {
					int y = p + k * 17;
					g.item(i, p, y);
					Hud.text(c, g, l, p + 19, y + 4, color, c.style().shadow());
				} else {
					int x = p + k * step;
					g.item(i, x + (step - 16) / 2, p);
					Hud.text(c, g, l, x + (step - Hud.width(c, l)) / 2, p + 17, color, c.style().shadow());
				}
			}
			if (list.isEmpty() && c.preview()) {
				for (int k = 0; k < 4; k++) g.fill(p + 2, p + 2 + k * 17, p + 14, p + 14 + k * 17, 0x55FFFFFF);
			}
		}
	}

	/** Movement keys and mouse buttons, lit while held; CPS under the mouse buttons. */
	static final class Keystrokes implements Module {
		static final int GAP = 2;
		private static final Map<String, Float> PRESS = new HashMap<>();

		private static int keySize(Ctx c) {
			try {
				return Math.max(12, Math.min(30, Integer.parseInt(c.opt("keySize", "18"))));
			} catch (NumberFormatException e) {
				return 18;
			}
		}

		@Override
		public int[] size(Ctx c) {
			int p = c.style().padding(), k = keySize(c);
			int full = (c.flag("wasd", true) ? 2 : 0) + (c.flag("mouse", true) ? 1 : 0);
			int h = full * k + Math.max(0, full - 1) * GAP;
			if (c.flag("space", true)) h += (full > 0 ? GAP : 0) + k / 2;
			return new int[] {3 * k + 2 * GAP + 2 * p, Math.max(k / 2, h) + 2 * p};
		}

		private void key(Ctx c, Gfx g, int x, int y, int w, int h, String label, boolean down) {
			Style s = c.style();
			String pressed = c.opt("pressed", "accent");
			int accent = Chroma.parse(pressed, 0xFF7AA2FF);
			int body = down ? Colors.alpha(accent, 0xCC) : (s.background() != 0 ? s.background() : 0x66000000);
			// Presses fade in and out and sink a pixel, rather than switching.
			String id = x + "," + y + label;
			float t = c.flag("animate", true) ? PRESS.merge(id, 0f, (old, z) -> old) : down ? 1f : 0f;
			t += ((down ? 1f : 0f) - t) * Math.min(1f, Hud.dt * 18f);
			if (Math.abs((down ? 1f : 0f) - t) < 0.02f) t = down ? 1f : 0f;
			PRESS.put(id, t);
			int idle = s.background() != 0 ? s.background() : 0x66000000;
			body = Colors.mix(idle, Colors.alpha(accent, 0xCC), t);
			int dy = Math.round(t);
			Draw.rounded(g, x, y + dy, w, h - dy, s.radius(), body);
			int color = Colors.mix(s.text(), 0xFF111111, t);
			Hud.text(c, g, label, x + (w - Hud.width(c, label)) / 2, y + dy + (h - 8) / 2, color, s.shadow() && t < 0.5f);
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			var o = c.mc().options;
			int p = c.style().padding(), k = keySize(c);
			int col = k + GAP;
			int y = p;
			if (c.flag("wasd", true)) {
				key(c, g, p + col, y, k, k, o.keyUp.getTranslatedKeyMessage().getString(), o.keyUp.isDown());
				y += col;
				key(c, g, p, y, k, k, o.keyLeft.getTranslatedKeyMessage().getString(), o.keyLeft.isDown());
				key(c, g, p + col, y, k, k, o.keyDown.getTranslatedKeyMessage().getString(), o.keyDown.isDown());
				key(c, g, p + 2 * col, y, k, k, o.keyRight.getTranslatedKeyMessage().getString(), o.keyRight.isDown());
				y += col;
			}
			if (c.flag("mouse", true)) {
				int w = (3 * k + GAP) / 2;
				boolean cps = c.flag("cps", true);
				key(c, g, p, y, w, k, cps ? Cps.left() + " CPS" : "LMB", o.keyAttack.isDown());
				key(c, g, p + w + GAP, y, w, k, cps ? Cps.right() + " CPS" : "RMB", o.keyUse.isDown());
				y += col;
			}
			if (c.flag("space", true)) {
				key(c, g, p, y, 3 * k + 2 * GAP, k / 2, "", o.keyJump.isDown());
			}
		}
	}

	/** The block under the crosshair: its item, name and, if wanted, hardness. */
	static final class BlockInfo implements Module {
		private record Seen(ItemStack item, String name, float hardness) {}

		private static Seen seen(Ctx c) {
			if (c.mc().level != null && c.mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
				BlockState state = c.mc().level.getBlockState(hit.getBlockPos());
				if (!state.isAir()) {
					return new Seen(new ItemStack(state.getBlock().asItem()), state.getBlock().getName().getString(),
							state.getDestroySpeed(c.mc().level, hit.getBlockPos()));
				}
			}
			return c.preview() ? new Seen(new ItemStack(Items.DIAMOND_ORE), "Diamond Ore", 3f) : null;
		}

		private static String detailLine(Ctx c, Seen s) {
			if (!c.flag("state", false)) return "";
			return s.hardness < 0 ? Lang.tr("aerion.hud.unbreakable") : Lang.tr("aerion.hud.hardness").replace("{n}", String.format(Locale.ROOT, "%.1f", s.hardness));
		}

		@Override
		public int[] size(Ctx c) {
			Seen s = seen(c);
			if (s == null) return new int[] {0, 0};
			int p = c.style().padding();
			int w = Math.max(Hud.width(c, s.name), Hud.width(c, detailLine(c, s)));
			return new int[] {(s.item.isEmpty() ? 0 : 19) + w + 2 * p, 16 + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			Seen s = seen(c);
			if (s == null) return;
			int p = c.style().padding(), x = p;
			if (!s.item.isEmpty()) {
				g.item(s.item, p, p);
				x += 19;
			}
			String d = detailLine(c, s);
			if (d.isEmpty()) {
				Hud.text(c, g, s.name, x, p + 4, c.style().text(), c.style().shadow());
			} else {
				Hud.text(c, g, s.name, x, p, c.style().text(), c.style().shadow());
				Hud.text(c, g, d, x, p + 9, Colors.alpha(c.style().text(), 0xAA), c.style().shadow());
			}
		}
	}

	/** FPS over the last seconds, one sample every quarter second, the current value on top. */
	static final class FpsGraph implements Module {
		private static final int SAMPLES = 100;
		private static final int[] HISTORY = new int[SAMPLES];
		private static int next, count;
		private static long lastSample;

		private static void sample(Minecraft mc) {
			long now = System.currentTimeMillis();
			if (now - lastSample < 250) return;
			lastSample = now;
			HISTORY[next] = mc.getFps();
			next = (next + 1) % SAMPLES;
			count = Math.min(SAMPLES, count + 1);
		}

		private static int graphW(Ctx c) {
			return Math.max(50, Math.min(200, c.num("width", 100)));
		}

		private static int graphH(Ctx c) {
			return Math.max(20, Math.min(80, c.num("height", 40)));
		}

		@Override
		public int[] size(Ctx c) {
			int p = c.style().padding();
			return new int[] {graphW(c) + 2 * p, (c.flag("value", true) ? LINE + 2 : 0) + graphH(c) + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			Minecraft mc = c.mc();
			sample(mc);
			int p = c.style().padding(), w = graphW(c), h = graphH(c), y = p;
			boolean colored = c.flag("colored", true);
			if (c.flag("value", true)) {
				int fps = mc.getFps();
				Hud.text(c, g, fps + " FPS", p, y, colored ? graded(fps >= 60, fps >= 30) : c.style().text(), c.style().shadow());
				y += LINE + 2;
			}
			int top = y, bottom = y + h;
			g.fill(p, bottom - 1, p + w, bottom, Colors.alpha(c.style().text(), 0x40));
			int peak = 60;
			for (int i = 0; i < count; i++) peak = Math.max(peak, HISTORY[i]);
			// A faint line at 60 FPS, the usual target.
			int line60 = bottom - Math.round(h * 60f / peak);
			if (line60 > top) g.fill(p, line60, p + w, line60 + 1, Colors.alpha(c.style().text(), 0x22));
			int cols = Math.min(count, w / 2);
			for (int i = 0; i < cols; i++) {
				int v = HISTORY[Math.floorMod(next - 1 - i, SAMPLES)];
				int bh = Math.max(1, Math.round(h * Math.min(1f, (float) v / peak)));
				int bx = p + w - (i + 1) * 2;
				int color = colored ? graded(v >= 60, v >= 30) : c.style().text();
				g.fill(bx, bottom - bh, bx + 1, bottom, Colors.alpha(color, 0xCC));
			}
		}
	}

	/** Players on the server, sorted by name, with their ping and game mode if wanted. */
	static final class PlayerList extends Lines {
		@Override
		List<String> lines(Ctx c) {
			List<String> out = new ArrayList<>();
			if (c.mc().getConnection() == null) {
				if (c.preview()) {
					out.add("Alex  32 ms");
					out.add("Steve  45 ms");
				}
				return out;
			}
			int max = Math.max(1, Math.min(50, c.num("max", 10)));
			boolean ping = c.flag("ping", true), mode = c.flag("dimension", false);
			List<PlayerInfo> players = new ArrayList<>(c.mc().getConnection().getOnlinePlayers());
			players.sort(Comparator.comparing(pi -> pi.getProfile().name().toLowerCase(Locale.ROOT)));
			for (PlayerInfo pi : players) {
				if (out.size() >= max) break;
				String line = pi.getProfile().name();
				if (ping) line += "  " + pi.getLatency() + " ms";
				if (mode && pi.getGameMode() != null) line += "  " + pi.getGameMode().getShortDisplayName().getString();
				out.add(line);
			}
			return out;
		}
	}

	/** Last drawn bounds (x, y, w, h in GUI pixels) for the editor. */
	static final Map<String, int[]> BOUNDS = new HashMap<>();

	public static Style style(HudConfig cfg, JsonObject module, Theme t) {
		JsonObject base = cfg.style();
		JsonObject own = module.get("style") instanceof JsonObject o ? o : new JsonObject();
		String bg = pick(own, base, "background", "theme");
		String txt = pick(own, base, "text", "theme");
		String border = pick(own, base, "border", "none");
		int opacity = Math.max(0, Math.min(100, HudConfig.num(own.has("opacity") ? own : base, "opacity", 55)));
		int surface = t != null ? t.surface : 0xFF000000;
		int speed = HudConfig.num(own.has("chromaSpeed") ? own : base, "chromaSpeed", Chroma.DEFAULT_SPEED);
		int background = switch (bg) {
			case "none" -> 0;
			case "theme" -> Colors.alpha(surface, opacity * 255 / 100);
			case Chroma.VALUE -> Colors.alpha(Chroma.color(0, speed), opacity * 255 / 100);
			default -> Colors.alpha(Theme.color(bg, surface), opacity * 255 / 100);
		};
		// Theme text on the theme surface; plain white over the world without a background.
		int text = switch (txt) {
			case "theme" -> background != 0 && t != null ? t.text : 0xFFFFFFFF;
			case Chroma.VALUE -> Chroma.color(0, speed);
			default -> Theme.color(txt, 0xFFFFFFFF);
		};
		int borderColor = switch (border) {
			case "none" -> 0;
			case "accent" -> t != null ? t.accent : 0xFF7AA2FF;
			case Chroma.VALUE -> Chroma.color(0.5f, speed);
			default -> Theme.color(border, 0);
		};
		int radius = HudConfig.num(own.has("radius") ? own : base, "radius", 0);
		int padding = HudConfig.num(own.has("padding") ? own : base, "padding", 3);
		boolean shadow = HudConfig.bool(own.has("shadow") ? own : base, "shadow", true);
		String align = pick(own, base, "align", "left");
		int borderWidth = HudConfig.num(own.has("borderWidth") ? own : base, "borderWidth", 1);
		boolean smooth = pick(own, base, "font", "smooth").equals("smooth");
		boolean outline = HudConfig.bool(own.has("outline") ? own : base, "outline", false);
		String grad = pick(own, base, "gradient", "none");
		int gradient = switch (grad) {
			case "none" -> 0;
			case "accent" -> Colors.alpha(t != null ? t.accent : 0xFF7AA2FF, opacity * 255 / 100);
			default -> Colors.alpha(Theme.color(grad, surface), opacity * 255 / 100);
		};
		String bar = pick(own, base, "bar", "none");
		int barColor = borderColor != 0 ? borderColor : t != null ? t.accent : 0xFF7AA2FF;
		return new Style(text, background, borderColor, Math.max(1, Math.min(4, borderWidth)), Math.max(0, Math.min(8, radius)), Math.max(0, Math.min(10, padding)), shadow, align, smooth,
				txt.equals(Chroma.VALUE) && HudConfig.bool(own.has("chromaWave") ? own : base, "chromaWave", false), speed,
				outline, background == 0 ? 0 : gradient, bar, barColor);
	}

	private static String pick(JsonObject own, JsonObject base, String key, String fallback) {
		String v = HudConfig.str(own, key, null);
		return v != null ? v : HudConfig.str(base, key, fallback);
	}

	public static float scaleOf(JsonObject module) {
		return Math.max(25, Math.min(400, HudConfig.num(module, "scale", 100))) / 100f;
	}

	/** Seconds since the last frame, for the animations. */
	static float dt = 0.016f;
	private static long lastFrame;

	/** Fade, pop and pulse state of one module. */
	private static final class Anim {
		float vis, pulse;
		int grade = -1;
		/** When the situation last began and last held, in seconds of game time (-1: never). */
		double since = -1, lastTrue = -1;
		boolean cond;
	}

	private static double seconds() {
		return System.nanoTime() / 1e9;
	}

	/** A module's number option in seconds or milliseconds, clamped. */
	private static int numOpt(JsonObject m, String key, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(HudConfig.opt(m, key, String.valueOf(fallback)).trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Whether the module should be on screen now: its situation holds (after `delay` seconds), for at
	 * most `showFor` seconds after it began (0: for as long as it holds), and `linger` seconds past its end.
	 */
	private static boolean wanted(Anim a, JsonObject m, Minecraft mc) {
		boolean cond = Situations.condition(m, mc);
		double now = seconds();
		if (cond && !a.cond) a.since = now;
		if (cond) a.lastTrue = now;
		a.cond = cond;
		int delay = numOpt(m, "delay", 0, 0, 10), showFor = numOpt(m, "showFor", 0, 0, 60), linger = numOpt(m, "linger", 0, 0, 30);
		if (!cond) return linger > 0 && a.lastTrue >= 0 && now - a.lastTrue < linger;
		double elapsed = now - a.since;
		return elapsed >= delay && (showFor == 0 || elapsed < delay + showFor);
	}

	private static final Map<String, Anim> ANIMS = new HashMap<>();

	/** A module's top-left corner: from its anchor and margin, or from the free x/y it was dragged to. */
	static int[] position(JsonObject m, int screenW, int screenH, int w, int h) {
		String a = HudConfig.opt(m, "anchor", "free");
		if (!"free".equals(a) && a.length() <= 2) {
			int margin;
			try {
				margin = Math.max(0, Math.min(40, Integer.parseInt(HudConfig.opt(m, "margin", "4").trim())));
			} catch (NumberFormatException e) {
				margin = 4;
			}
			int x = a.contains("l") ? margin : a.contains("r") ? screenW - w - margin : (screenW - w) / 2;
			int y = a.contains("t") ? margin : a.contains("b") ? screenH - h - margin : (screenH - h) / 2;
			return new int[] {Math.max(0, x), Math.max(0, y)};
		}
		return new int[] {Math.round(HudConfig.num(m, "x", 0) / 1000f * Math.max(0, screenW - w)),
				Math.round(HudConfig.num(m, "y", 0) / 1000f * Math.max(0, screenH - h))};
	}

	/** Overshoots a little before settling: the "springy" pop. */
	static float spring(float t) {
		t = Math.max(0, Math.min(1, t));
		float c1 = 1.70158f, c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	/** Draw every enabled module; `preview` fills empty ones with samples (editor). */
	public static void render(Gfx g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		long now = System.nanoTime();
		if (lastFrame != 0) dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		if (!preview && mc.player == null) ANIMS.clear();
		if (!preview && !Game.hudHidden() && mc.player != null) Overlays.draw(g);
		// The editor draws the modules itself, also while the whole HUD is switched off.
		if (!preview && (!cfg.enabled() || Game.hudHidden() || mc.player == null || Game.screen() instanceof EditorScreen)) return;
		Theme t = AerionClient.theme();
		BOUNDS.clear();
		for (var e : MODULES.entrySet()) {
			JsonObject m = cfg.module(e.getKey());
			if (!HudConfig.bool(m, "enabled", false)) continue;
			if (!preview && e.getValue() instanceof GameElement) continue;
			Ctx c = new Ctx(mc, mc.player, m, style(cfg, m, t), t, preview);
			try {
				// Fades in and out when a situation shows or hides it (the editor always shows it).
				Anim a = ANIMS.computeIfAbsent(e.getKey(), k -> new Anim());
				boolean want = preview || wanted(a, m, mc);
				boolean animate = c.flag("animate", true);
				// How long a fade takes (ms): 50 to 1500.
				float rate = 1000f / numOpt(m, "fade", 170, 50, 1500);
				if (a.grade < 0 && a.vis == 0 && want) a.vis = 1;
				a.vis = !animate || preview ? (want ? 1 : 0) : Math.max(0, Math.min(1, a.vis + (want ? 1 : -1) * dt * rate));
				if (a.vis <= 0.001f) continue;
				int[] s = e.getValue().size(c);
				if (s[0] <= 0 || s[1] <= 0) continue;
				// A module that turns worse (green to yellow to red) pulses once.
				if (!preview && c.flag("pulse", true)) {
					int grade = e.getValue().grade(c);
					if (a.grade >= 0 && grade > a.grade) a.pulse = 1;
					a.grade = grade;
				} else {
					a.grade = Math.max(a.grade, 0);
				}
				a.pulse = Math.max(0, a.pulse - dt * 2.2f);
				float k = scaleOf(m);
				int w = Math.round(s[0] * k), h = Math.round(s[1] * k);
				int[] pos = position(m, g.width(), g.height(), w, h);
				int x = pos[0], y = pos[1];
				BOUNDS.put(e.getKey(), new int[] {x, y, w, h});
				float pop = animate ? 0.88f + 0.12f * spring(a.vis) : 1f;
				float bump = 1f + 0.12f * a.pulse * a.pulse;
				g.push();
				g.translate(x + w / 2f, y + h / 2f);
				g.scale(pop * bump);
				g.translate(-w / 2f, -h / 2f);
				g.scale(k);
				Style st = c.style().faded(a.vis);
				if (st != c.style()) c = new Ctx(mc, mc.player, m, st, t, preview);
				paintBox(g, st, s[0], s[1]);
				if (a.pulse > 0) {
					int flash = e.getValue().grade(c) >= 2 ? 0xFF5555 : 0xFFE25A;
					Draw.rounded(g, 0, 0, s[0], s[1], st.radius(), Colors.alpha(flash, Math.round(0x66 * a.pulse)));
				}
				e.getValue().draw(c, g);
				g.pop();
			} catch (RuntimeException ex) {
				// One broken module must not take the whole HUD (or the frame) down.
				AerionClient.warnOnce("hud." + e.getKey(), ex);
			}
		}
	}

	/** Background (flat or shaded), border and accent bar of a module box. */
	private static void paintBox(Gfx g, Style st, int w, int h) {
		if (st.gradient() != 0) {
			Draw.roundedGradient(g, 0, 0, w, h, st.radius(), st.background(), st.gradient());
			if (st.border() != 0) Draw.control(g, 0, 0, w, h, st.radius(), st.border(), 0, st.borderWidth());
		} else if (st.border() != 0) {
			Draw.control(g, 0, 0, w, h, st.radius(), st.border(), st.background(), st.borderWidth());
		} else if (st.background() != 0) {
			Draw.rounded(g, 0, 0, w, h, st.radius(), st.background());
		}
		if ("none".equals(st.bar())) return;
		int r = Math.min(st.radius(), Math.min(w, h) / 2), t = 2;
		switch (st.bar()) {
			case "left" -> g.fill(0, r, t, h - r, st.barColor());
			case "right" -> g.fill(w - t, r, w, h - r, st.barColor());
			case "top" -> g.fill(r, 0, w - r, t, st.barColor());
			case "bottom" -> g.fill(r, h - t, w - r, h, st.barColor());
			default -> {
			}
		}
	}
}
