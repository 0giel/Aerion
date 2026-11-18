package pl.aerion.screens.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pl.aerion.screens.client.Mirrors;
import pl.aerion.screens.client.Platform;
import pl.aerion.screens.client.PortalSource;
import pl.aerion.screens.client.ScreenShare;
import pl.aerion.screens.client.WindowSource;

/** The windows of the computer to hang in the world, and the ones hanging here already. */
public final class PickerScreen extends MenuScreen {
	private List<WindowSource.Win> windows;

	public PickerScreen(Screen parent) {
		super(Component.translatable("aerion_screens.title"), parent);
	}

	@Override
	protected void build() {
		WindowSource src = Mirrors.source();
		if (src == null) {
			note(Component.translatable("aerion_screens.unsupported"));
		} else {
			if (windows == null) windows = src.pickable();
			label(Component.translatable("aerion_screens.hint"));
			if (PortalSource.wanted()) {
				String problem = PortalSource.problem;
				note(Component.translatable(problem == null ? "aerion_screens.waylandHint" : problem.startsWith("missing:") && !problem.startsWith("missing:portal") ? "aerion_screens.waylandMissing" : "aerion_screens.waylandFailed"));
			}
			if (windows.isEmpty()) note(Component.translatable("aerion_screens.none"));
			for (WindowSource.Win win : windows) {
				row(y -> button(rowX, y, rowW, Component.literal(fit(win.title(), rowW - 12)), b -> {
					Mirrors.place(win);
					onClose();
				}));
			}
		}
		List<Mirrors.Panel> panels = Mirrors.all();
		if (!panels.isEmpty()) {
			label(Component.translatable("aerion_screens.panels"));
			for (Mirrors.Panel p : new ArrayList<>(panels)) row(y -> panelRow(p, y));
			if (panels.stream().anyMatch(p -> p.shared)) note(Component.translatable(ScreenShare.supported() ? "aerion_screens.shareOk" : "aerion_screens.shareUnsupported"));
		}
		bottom(List.of(Component.translatable("aerion_screens.settings"), Component.translatable(Mirrors.hidden() ? "aerion_screens.show" : "aerion_screens.hide")),
				List.of(b -> Platform.setScreen(new SettingsScreen(this)), b -> {
					Mirrors.toggleHidden();
					refresh();
				}));
	}

	/** A hanging window: its name, then size, sharing, following the player and removing. */
	private void panelRow(Mirrors.Panel p, int y) {
		Component share = Component.translatable(p.shared ? "aerion_screens.shared" : "aerion_screens.share");
		Component pin = Component.translatable(p.pinned ? "aerion_screens.pinned" : "aerion_screens.pin");
		Component remove = Component.translatable("aerion_screens.remove");
		int sw = font.width(share) + 12, pw = font.width(pin) + 12, rw = font.width(remove) + 12;
		int x = rowX + rowW - rw;
		button(x, y, rw, remove, b -> {
			Mirrors.remove(p);
			refresh();
		});
		x -= pw + 2;
		button(x, y, pw, pin, b -> {
			Mirrors.togglePin(p);
			refresh();
		});
		x -= sw + 2;
		Button shareButton = button(x, y, sw, share, b -> {
			Mirrors.toggleShare(p);
			refresh();
		});
		shareButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("aerion_screens.shareTip")));
		x -= 22;
		button(x, y, 20, Component.literal("+"), b -> Mirrors.resize(p, 1.15f));
		x -= 22;
		button(x, y, 20, Component.literal("-"), b -> Mirrors.resize(p, 1 / 1.15f));
		String name = fit(p.title, x - rowX - 6);
		addRenderableWidget(new StringWidget(rowX, y + 6, font.width(name), 10, Component.literal(name), font));
	}
}
