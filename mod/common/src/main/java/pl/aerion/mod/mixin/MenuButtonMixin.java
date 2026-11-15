package pl.aerion.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.Host;
import pl.aerion.mod.Lang;
import pl.aerion.mod.MenuLook;
import pl.aerion.mod.TitleCard;
import pl.aerion.mod.hud.HudEditor;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;
import pl.aerion.mod.platform.Painter;

/** A button to Aerion's HUD layout and menu in the corner of the title and pause screens; on the title screen also "Host a world" on the player's card. */
@Mixin({TitleScreen.class, PauseScreen.class})
abstract class MenuButtonMixin extends Screen {
	private MenuButtonMixin(Component title) {
		super(title);
	}

	/**
	 * The title screen's buttons the player hid (Aerion → menu look): Realms, the row of small
	 * icons. The buttons below move up so no gap is left.
	 */
	private void hideButtons() {
		boolean realms = !MenuLook.hidden("realms"), icons = !MenuLook.hidden("icons");
		String realmsText = Component.translatable("menu.online").getString();
		int mid = width / 2;
		java.util.List<int[]> gone = new java.util.ArrayList<>();
		java.util.List<net.minecraft.client.gui.components.AbstractWidget> column = new java.util.ArrayList<>();
		for (var child : children()) {
			if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget wd) || !wd.visible) continue;
			int cx = wd.getX() + wd.getWidth() / 2;
			// The main column only (with the small icons beside its rows, as on 1.21.11): not the
			// version and copyright lines along the bottom edge.
			if (Math.abs(cx - mid) > 120 || wd.getY() >= height - 20) continue;
			column.add(wd);
			boolean isRealms = wd.getMessage().getString().equals(realmsText);
			boolean isIcon = wd.getWidth() == 20 && wd.getHeight() == 20;
			if ((isRealms && !realms) || (isIcon && !icons)) {
				wd.visible = false;
				wd.active = false;
				boolean known = false;
				for (int[] r : gone) known |= r[0] == wd.getY();
				if (!known) gone.add(new int[] {wd.getY(), wd.getHeight()});
			}
		}
		if (gone.isEmpty()) return;
		for (var wd : column) {
			if (!wd.visible) continue;
			int dy = 0;
			for (int[] r : gone) if (r[0] < wd.getY()) dy += r[1] + 4;
			if (dy > 0) wd.setY(wd.getY() - dy);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void aerion$menuButton(CallbackInfo ci) {
		Screen self = this;
		if (self instanceof TitleScreen) hideButtons();
		addRenderableWidget(Button.builder(
						Component.literal(Lang.tr("aerion.menu.button")),
						b -> Game.setScreen(new EditorScreen(new HudEditor(), self)))
				.bounds(width - 64, 4, 60, 20)
				.build());
		if (self instanceof TitleScreen) {
			// The menu's look: background, which buttons show, theme - in a panel over this screen.
			addRenderableWidget(Button.builder(Component.literal(Lang.tr("aerion.menu.look")),
							b -> Game.setScreen(new EditorScreen(new pl.aerion.mod.MenuStylePage(), self)))
					.bounds(width - 64 - 6 - 64, 4, 64, 20)
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(Lang.tr("aerion.menu.lookHint"))))
					.build());
		}
		java.util.List<int[]> own = new java.util.ArrayList<>();
		for (var child : children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractWidget wd && wd.visible) {
				own.add(new int[] {wd.getX(), wd.getY(), wd.getWidth(), wd.getHeight()});
			}
		}
		int[] card = self instanceof TitleScreen && MenuLook.player() ? TitleCard.place(width, height, own) : null;
		if (card != null) {
			int[] b = TitleCard.button(card);
			Button host = Button.builder(Component.literal(Lang.tr("aerion.host.button")), x -> Host.start(self))
					.bounds(b[0], b[1], b[2], b[3])
					.build();
			int[] m = TitleCard.model(card);
			Minecraft mc = Minecraft.getInstance();
			PlayerSkinWidget model = new PlayerSkinWidget(m[2], m[3], mc.getEntityModels(), mc.getSkinManager().createLookup(mc.getGameProfile(), false));
			model.setPosition(m[0], m[1]);
			// The title screen fades its buttons in the first time: the name and hint follow the host
			// button's alpha, and the model (drawn opaque) appears once the fade is done.
			addRenderableOnly(Painter.of(g -> {
				model.visible = host.getAlpha() >= 0.98f;
				TitleCard.draw(g, card, host.getAlpha());
			}));
			addRenderableWidget(model);
			addRenderableWidget(host);
		}
	}
}
