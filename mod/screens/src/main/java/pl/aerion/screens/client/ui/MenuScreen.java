package pl.aerion.screens.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pl.aerion.screens.client.Platform;

/**
 * A menu in the game's own look: a title, rows of the game's buttons that scroll with the wheel
 * when they don't fit, and buttons along the bottom. Each row is built again whenever something
 * changes, so labels always tell the current state.
 */
abstract class MenuScreen extends Screen {
	static final int ROW = 22, TOP = 32, BOTTOM = 36;
	protected final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private int scroll;
	/** The width rows may use and where they start. */
	protected int rowW, rowX;

	private record Row(int height, IntConsumer build) {}

	MenuScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/** Fills the rows (with {@link #row}, {@link #label}, {@link #note}) and the bottom buttons. */
	protected abstract void build();

	@Override
	protected final void init() {
		rows.clear();
		rowW = Math.min(310, width - 20);
		rowX = (width - rowW) / 2;
		int tw = font.width(title);
		addRenderableWidget(new StringWidget((width - tw) / 2, 12, tw, 10, title, font));
		build();
		int total = rows.stream().mapToInt(Row::height).sum(), room = height - TOP - BOTTOM;
		scroll = Math.max(0, Math.min(scroll, total - room));
		int y = TOP - scroll;
		for (Row r : rows) {
			if (y >= TOP - 1 && y + r.height() <= height - BOTTOM + 1) r.build().accept(y);
			y += r.height();
		}
	}

	/** A row of widgets; `build` gets the row's top. */
	protected void row(IntConsumer build) {
		rows.add(new Row(ROW, build));
	}

	/** A heading above a group of rows. */
	protected void label(Component text) {
		rows.add(new Row(14, y -> addRenderableWidget(new StringWidget(rowX, y + 3, Math.min(rowW, font.width(text)), 10, text, font))));
	}

	/** A quieter line of explanation, wrapped. */
	protected void note(Component text) {
		int lines = Math.max(1, font.split(text, rowW).size());
		rows.add(new Row(lines * 10 + 4, y -> addRenderableWidget(new MultiLineTextWidget(rowX, y + 2, text.copy().withStyle(net.minecraft.ChatFormatting.GRAY), font).setMaxWidth(rowW))));
	}

	protected Button button(int x, int y, int w, Component text, Button.OnPress press) {
		return addRenderableWidget(Button.builder(text, press).bounds(x, y, w, 20).build());
	}

	/** Buttons side by side along the bottom, the last one "Done". */
	protected void bottom(List<Component> labels, List<Button.OnPress> presses) {
		int n = labels.size() + 1, gap = 4, w = Math.min(100, (rowW - gap * (n - 1)) / n), x = (width - (w * n + gap * (n - 1))) / 2, y = height - 28;
		for (int i = 0; i < labels.size(); i++) button(x + i * (w + gap), y, w, labels.get(i), presses.get(i));
		button(x + (n - 1) * (w + gap), y, w, CommonComponents.GUI_DONE, b -> onClose());
	}

	/** Builds the menu again (after a change). */
	protected void refresh() {
		rebuildWidgets();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double h, double v) {
		scroll = Math.max(0, scroll - (int) Math.signum(v) * ROW);
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		Platform.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Text cut to fit `w` pixels. */
	protected String fit(String s, int w) {
		if (font.width(s) <= w) return s;
		while (!s.isEmpty() && font.width(s + "…") > w) s = s.substring(0, s.length() - 1);
		return s + "…";
	}
}
