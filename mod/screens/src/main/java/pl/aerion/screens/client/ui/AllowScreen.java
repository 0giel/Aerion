package pl.aerion.screens.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pl.aerion.screens.client.Mirrors;

/** Who may see this player's shared windows on this server: everyone, or the players chosen. */
final class AllowScreen extends MenuScreen {
	private String typed = "";

	AllowScreen(Screen parent) {
		super(Component.translatable("aerion_screens.who"), parent);
	}

	@Override
	protected void build() {
		var allow = Mirrors.allow();
		row(y -> button(rowX, y, rowW, CommonComponents.optionNameValue(Component.translatable("aerion_screens.who"),
				Component.translatable(allow.everyone() ? "aerion_screens.everyone" : "aerion_screens.chosen")), b -> {
					Mirrors.setAllow(!allow.everyone(), allow.names());
					refresh();
				}));
		if (!allow.everyone()) {
			Set<String> names = new LinkedHashSet<>(allow.names());
			var mc = net.minecraft.client.Minecraft.getInstance();
			var conn = mc.getConnection();
			String self = mc.player != null ? mc.player.getName().getString() : "";
			if (conn != null) {
				for (var info : conn.getOnlinePlayers()) {
					String n = info.getProfile().name();
					if (!n.equalsIgnoreCase(self) && names.size() < 40) names.add(n);
				}
			}
			if (names.isEmpty()) note(Component.translatable("aerion_screens.nobody"));
			for (String n : names) {
				row(y -> {
					boolean on = allow.names().stream().anyMatch(a -> a.equalsIgnoreCase(n));
					Component state = Component.translatable(on ? "aerion_screens.may" : "aerion_screens.mayNot");
					int w = font.width(state) + 16;
					button(rowX + rowW - w, y, w, state, b -> {
						List<String> next = new ArrayList<>(allow.names());
						if (on) next.removeIf(a -> a.equalsIgnoreCase(n));
						else next.add(n);
						Mirrors.setAllow(false, next);
						refresh();
					});
					addRenderableWidget(new StringWidget(rowX, y + 6, font.width(n), 10, Component.literal(n), font));
				});
			}
			// Anyone by name, also players not here now.
			row(y -> {
				Component add = Component.translatable("aerion_screens.add");
				int bw = font.width(add) + 16;
				EditBox box = addRenderableWidget(new EditBox(font, rowX, y, rowW - bw - 4, 20, Component.translatable("aerion_screens.addHint")));
				box.setValue(typed);
				box.setResponder(v -> typed = v);
				button(rowX + rowW - bw, y, bw, add, b -> {
					String n = typed.trim();
					if (n.matches("[A-Za-z0-9_]{1,16}") && allow.names().stream().noneMatch(a -> a.equalsIgnoreCase(n))) {
						List<String> next = new ArrayList<>(allow.names());
						next.add(n);
						Mirrors.setAllow(false, next);
						typed = "";
					}
					refresh();
				});
			});
			// The server checks names only: on servers without account checks a name proves nothing.
			note(Component.translatable("aerion_screens.offline"));
		}
		bottom(List.of(), List.of());
	}
}
