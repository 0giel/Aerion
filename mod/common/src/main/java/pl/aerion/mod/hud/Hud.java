package pl.aerion.mod.hud;

import com.google.gson.JsonObject;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Colors;
import pl.aerion.mod.Draw;
import pl.aerion.mod.Lang;
import pl.aerion.mod.Theme;
import pl.aerion.mod.features.Features;
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
	public record Style(int text, int background, int border, int radius, int padding, boolean shadow, String align) {}

	/** Everything a module needs while measuring and drawing. */
	public record Ctx(Minecraft mc, LocalPlayer player, JsonObject cfg, Style style, Theme theme, boolean preview) {
		String opt(String key, String fallback) {
			return HudConfig.opt(cfg, key, fallback);
		}

		boolean flag(String key, boolean fallback) {
			return "1".equals(opt(key, fallback ? "1" : "0")) || "true".equals(opt(key, ""));
		}
	}

	interface Module {
		/** Width and height at scale 1, padding included; {0, 0} hides the module. */
		int[] size(Ctx c);

		void draw(Ctx c, Gfx g);
	}

	static final int LINE = 10;

	/** Modules made of text lines. */
	abstract static class Lines implements Module {
		abstract List<String> lines(Ctx c);

		/** Optional colour swatch before a line (e.g. potion colour); 0 for none. */
		int swatch(Ctx c, int line) {
			return 0;
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
			for (String s : l) w = Math.max(w, Gfx.textWidth(s));
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
				int tw = Gfx.textWidth(text);
				int inner = s[0] - 2 * p - sw;
				int x = p + sw + switch (c.style().align()) {
					case "center" -> (inner - tw) / 2;
					case "right" -> inner - tw;
					default -> 0;
				};
				int y = p + i * LINE;
				int color = swatch(c, i);
				if (color != 0) g.fill(p, y + 1, p + 5, y + 6, color);
				g.text(text, x, y, c.style().text(), c.style().shadow());
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
				out.add("XYZ " + String.format(fmt + " / " + fmt + " / " + fmt, x, y, z));
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
		});
		MODULES.put("clock", new Lines() {
			List<String> lines(Ctx c) {
				String pattern = (c.flag("h24", true) ? "HH:mm" : "h:mm") + (c.flag("seconds", false) ? ":ss" : "")
						+ (c.flag("h24", true) ? "" : " a");
				return List.of(LocalTime.now().format(DateTimeFormatter.ofPattern(pattern)));
			}
		});
		MODULES.put("memory", new Lines() {
			List<String> lines(Ctx c) {
				Runtime r = Runtime.getRuntime();
				long used = (r.totalMemory() - r.freeMemory()) >> 20;
				long max = r.maxMemory() >> 20;
				return List.of(format(c.opt("format", "{used}/{max} MB"), "used", used, "max", max, "percent", used * 100 / Math.max(1, max)));
			}
		});
		MODULES.put("toggles", new Lines() {
			List<String> lines(Ctx c) {
				List<String> out = new ArrayList<>();
				if (Features.sprintToggled()) out.add(Lang.tr("aerion.toggle.sprint"));
				if (Features.sneakToggled()) out.add(Lang.tr("aerion.toggle.sneak"));
				if (Features.fullbrightOn()) out.add(Lang.tr("aerion.toggle.fullbright"));
				if (Features.freelookActive()) out.add(Lang.tr("aerion.toggle.freelook"));
				if (out.isEmpty() && c.preview()) out.add(Lang.tr("aerion.toggle.sprint"));
				return out;
			}
		});
		MODULES.put("potions", new Lines() {
			private List<MobEffectInstance> effects(Ctx c) {
				return c.player() == null ? List.of() : new ArrayList<>(c.player().getActiveEffects());
			}

			List<String> lines(Ctx c) {
				List<String> out = new ArrayList<>();
				boolean duration = c.flag("duration", true);
				for (MobEffectInstance e : effects(c)) {
					String name = e.getEffect().value().getDisplayName().getString();
					int level = e.getAmplifier() + 1;
					String text = name + (level > 1 ? " " + roman(level) : "");
					if (duration) text += "  " + (e.isInfiniteDuration() ? "∞" : time(e.getDuration()));
					out.add(text);
				}
				if (out.isEmpty() && c.preview()) out.add("Speed II  1:30");
				return out;
			}

			@Override
			int swatch(Ctx c, int line) {
				if (!c.flag("colors", true)) return 0;
				List<MobEffectInstance> list = effects(c);
				if (line >= list.size()) return c.preview() ? 0xFF7CAFC6 : 0;
				return 0xFF000000 | list.get(line).getEffect().value().getColor();
			}
		});
		MODULES.put("armor", new Armor());
		MODULES.put("keystrokes", new Keystrokes());
		MODULES.put("minimap", new Minimap());
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
			for (ItemStack i : list) text = Math.max(text, Gfx.textWidth(label(c, i)));
			if (list.isEmpty()) text = Gfx.textWidth("100%");
			return vertical(c)
					? new int[] {16 + (text > 0 ? text + 3 : 0) + 2 * p, n * 17 - 1 + 2 * p}
					: new int[] {n * Math.max(18, text + 2) + 2 * p, 16 + (text > 0 ? LINE : 0) + 2 * p};
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			List<ItemStack> list = items(c);
			int p = c.style().padding();
			int text = 0;
			for (ItemStack i : list) text = Math.max(text, Gfx.textWidth(label(c, i)));
			int step = Math.max(18, text + 2);
			for (int k = 0; k < list.size(); k++) {
				ItemStack i = list.get(k);
				String l = label(c, i);
				int color = c.style().text();
				if (i.isDamageableItem()) {
					float left = 1f - (float) i.getDamageValue() / i.getMaxDamage();
					if (left < 0.15f) color = 0xFFFF5555;
					else if (left < 0.35f) color = 0xFFFFAA33;
				}
				if (vertical(c)) {
					int y = p + k * 17;
					g.item(i, p, y);
					g.text(l, p + 19, y + 4, color, c.style().shadow());
				} else {
					int x = p + k * step;
					g.item(i, x + (step - 16) / 2, p);
					g.text(l, x + (step - Gfx.textWidth(l)) / 2, p + 17, color, c.style().shadow());
				}
			}
			if (list.isEmpty() && c.preview()) {
				for (int k = 0; k < 4; k++) g.fill(p + 2, p + 2 + k * 17, p + 14, p + 14 + k * 17, 0x55FFFFFF);
			}
		}
	}

	/** Movement keys and mouse buttons, lit while held; CPS under the mouse buttons. */
	static final class Keystrokes implements Module {
		static final int KEY = 18;
		static final int GAP = 2;

		@Override
		public int[] size(Ctx c) {
			int p = c.style().padding();
			int rows = 2 + (c.flag("mouse", true) ? 1 : 0) + (c.flag("space", true) ? 1 : 0);
			int h = rows * KEY + (rows - 1) * GAP - (c.flag("space", true) ? KEY / 2 : 0);
			return new int[] {3 * KEY + 2 * GAP + 2 * p, h + 2 * p};
		}

		private void key(Ctx c, Gfx g, int x, int y, int w, int h, String label, boolean down) {
			Style s = c.style();
			int accent = c.theme() != null ? c.theme().accent : 0xFF7AA2FF;
			int body = down ? Colors.alpha(accent, 0xCC) : (s.background() != 0 ? s.background() : 0x66000000);
			Draw.rounded(g, x, y, w, h, s.radius(), body);
			int tw = Gfx.textWidth(label);
			int color = down ? 0xFF111111 : s.text();
			g.text(label, x + (w - tw) / 2, y + (h - 8) / 2, color, s.shadow() && !down);
		}

		@Override
		public void draw(Ctx c, Gfx g) {
			var o = c.mc().options;
			int p = c.style().padding();
			int col = KEY + GAP;
			key(c, g, p + col, p, KEY, KEY, o.keyUp.getTranslatedKeyMessage().getString(), o.keyUp.isDown());
			int y = p + col;
			key(c, g, p, y, KEY, KEY, o.keyLeft.getTranslatedKeyMessage().getString(), o.keyLeft.isDown());
			key(c, g, p + col, y, KEY, KEY, o.keyDown.getTranslatedKeyMessage().getString(), o.keyDown.isDown());
			key(c, g, p + 2 * col, y, KEY, KEY, o.keyRight.getTranslatedKeyMessage().getString(), o.keyRight.isDown());
			y += col;
			if (c.flag("mouse", true)) {
				int w = (3 * KEY + 2 * GAP - GAP) / 2;
				boolean cps = c.flag("cps", true);
				key(c, g, p, y, w, KEY, cps ? Cps.left() + " CPS" : "LMB", o.keyAttack.isDown());
				key(c, g, p + w + GAP, y, w, KEY, cps ? Cps.right() + " CPS" : "RMB", o.keyUse.isDown());
				y += col;
			}
			if (c.flag("space", true)) {
				key(c, g, p, y, 3 * KEY + 2 * GAP, KEY / 2, "", o.keyJump.isDown());
			}
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
		int background = switch (bg) {
			case "none" -> 0;
			case "theme" -> Colors.alpha(surface, opacity * 255 / 100);
			default -> Colors.alpha(Theme.color(bg, surface), opacity * 255 / 100);
		};
		// Theme text on the theme surface; plain white over the world without a background.
		int text = switch (txt) {
			case "theme" -> background != 0 && t != null ? t.text : 0xFFFFFFFF;
			default -> Theme.color(txt, 0xFFFFFFFF);
		};
		int borderColor = switch (border) {
			case "none" -> 0;
			case "accent" -> t != null ? t.accent : 0xFF7AA2FF;
			default -> Theme.color(border, 0);
		};
		int radius = HudConfig.num(own.has("radius") ? own : base, "radius", 0);
		int padding = HudConfig.num(own.has("padding") ? own : base, "padding", 3);
		boolean shadow = HudConfig.bool(own.has("shadow") ? own : base, "shadow", true);
		String align = pick(own, base, "align", "left");
		return new Style(text, background, borderColor, Math.max(0, Math.min(8, radius)), Math.max(0, Math.min(10, padding)), shadow, align);
	}

	private static String pick(JsonObject own, JsonObject base, String key, String fallback) {
		String v = HudConfig.str(own, key, null);
		return v != null ? v : HudConfig.str(base, key, fallback);
	}

	public static float scaleOf(JsonObject module) {
		return Math.max(25, Math.min(400, HudConfig.num(module, "scale", 100))) / 100f;
	}

	/** Draw every enabled module; `preview` fills empty ones with samples (editor). */
	public static void render(Gfx g, boolean preview) {
		Minecraft mc = Minecraft.getInstance();
		HudConfig cfg = AerionClient.hud();
		if (cfg == null) return;
		// The editor draws the modules itself, also while the whole HUD is switched off.
		if (!preview && (!cfg.enabled() || Game.hudHidden() || mc.player == null || Game.screen() instanceof EditorScreen)) return;
		Theme t = AerionClient.theme();
		BOUNDS.clear();
		for (var e : MODULES.entrySet()) {
			JsonObject m = cfg.module(e.getKey());
			if (!HudConfig.bool(m, "enabled", false)) continue;
			Ctx c = new Ctx(mc, mc.player, m, style(cfg, m, t), t, preview);
			try {
				int[] s = e.getValue().size(c);
				if (s[0] <= 0 || s[1] <= 0) continue;
				float k = scaleOf(m);
				int w = Math.round(s[0] * k), h = Math.round(s[1] * k);
				int x = Math.round(HudConfig.num(m, "x", 0) / 1000f * Math.max(0, g.width() - w));
				int y = Math.round(HudConfig.num(m, "y", 0) / 1000f * Math.max(0, g.height() - h));
				BOUNDS.put(e.getKey(), new int[] {x, y, w, h});
				g.push();
				g.translate(x, y);
				g.scale(k);
				Style st = c.style();
				if (st.border() != 0) Draw.control(g, 0, 0, s[0], s[1], st.radius(), st.border(), st.background());
				else if (st.background() != 0) Draw.rounded(g, 0, 0, s[0], s[1], st.radius(), st.background());
				e.getValue().draw(c, g);
				g.pop();
			} catch (RuntimeException ex) {
				// One broken module must not take the whole HUD (or the frame) down.
				AerionClient.warnOnce("hud." + e.getKey(), ex);
			}
		}
	}
}
