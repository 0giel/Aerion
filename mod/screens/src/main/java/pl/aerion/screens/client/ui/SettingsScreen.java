package pl.aerion.screens.client.ui;

import java.util.List;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.Platform;
import pl.aerion.screens.client.Screens;

/** Settings of windows in the world, as the game's own buttons and sliders. */
final class SettingsScreen extends MenuScreen {
	SettingsScreen(Screen parent) {
		super(Component.translatable("aerion_screens.settingsTitle"), parent);
	}

	private static Screens.Settings s() {
		return Screens.settings;
	}

	@Override
	protected void build() {
		row(y -> button(rowX, y, rowW, onOff("aerion_screens.enabled", s().enabled()), b -> {
			s().setEnabled(!s().enabled());
			refresh();
		}));
		toggle("others", true);
		label(Component.translatable("aerion_screens.yours"));
		choice("quality", "medium", "low", "medium", "high");
		slider("fps", 12, 2, 30, "");
		slider("opacity", 100, 20, 100, "%");
		slider("size", 16, 5, 60, "");
		choice("shape", "curve", "curve", "straight");
		toggle("title", true);
		toggle("handle", true);
		label(Component.translatable("aerion_screens.sharing"));
		choice("shareQuality", "high", "low", "medium", "high", "max");
		toggle("shareSound", true);
		slider("othersVolume", 80, 0, 100, "%");
		row(y -> button(rowX, y, rowW, CommonComponents.optionNameValue(Component.translatable("aerion_screens.who"),
				Component.translatable(Mirrors.allow().everyone() ? "aerion_screens.everyone" : "aerion_screens.chosen")), b -> Platform.setScreen(new AllowScreen(this))));
		bottom(List.of(), List.of());
	}

	private static Component onOff(String key, boolean on) {
		return CommonComponents.optionNameValue(Component.translatable(key), on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private void toggle(String key, boolean fallback) {
		row(y -> {
			boolean on = !"0".equals(s().opt(key, fallback ? "1" : "0"));
			button(rowX, y, rowW, onOff("aerion_screens.opt." + key, on), b -> {
				s().set(key, on ? "0" : "1");
				refresh();
			});
		});
	}

	/** A button stepping through `values`. */
	private void choice(String key, String fallback, String... values) {
		row(y -> {
			String now = s().opt(key, fallback);
			int i = List.of(values).indexOf(now);
			button(rowX, y, rowW, CommonComponents.optionNameValue(Component.translatable("aerion_screens.opt." + key), Component.translatable("aerion_screens.value." + now)), b -> {
				s().set(key, values[(i + 1) % values.length]);
				refresh();
			});
		});
	}

	private void slider(String key, int fallback, int min, int max, String unit) {
		row(y -> {
			int now;
			try {
				now = Math.max(min, Math.min(max, Integer.parseInt(s().opt(key, String.valueOf(fallback)))));
			} catch (NumberFormatException e) {
				now = fallback;
			}
			Component name = Component.translatable("aerion_screens.opt." + key);
			addRenderableWidget(new AbstractSliderButton(rowX, y, rowW, 20, Component.empty(), (now - min) / (double) (max - min)) {
				{
					updateMessage();
				}

				private int number() {
					return (int) Math.round(min + value * (max - min));
				}

				@Override
				protected void updateMessage() {
					setMessage(CommonComponents.optionNameValue(name, Component.literal(number() + unit)));
				}

				@Override
				protected void applyValue() {
					s().set(key, String.valueOf(number()));
				}
			});
		});
	}
}
