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
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.Host;
import pl.aerion.mod.LastPlayed;
import pl.aerion.mod.Theme;
import pl.aerion.mod.TitleSide;
import pl.aerion.mod.Lang;
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

	/** The title screen whose side was last built, so a window resize doesn't replay its entrance. */
	private static Screen aerion$lastTitle;

	@Inject(method = "init", at = @At("TAIL"))
	private void aerion$menuButton(CallbackInfo ci) {
		Screen self = this;
		addRenderableWidget(Button.builder(
						Component.literal(Lang.tr("aerion.menu.button")),
						b -> Game.setScreen(new EditorScreen(new HudEditor(), self)))
				.bounds(width - 64, 4, 60, 20)
				.build());
		int[] card = self instanceof TitleScreen ? TitleCard.place(width, height) : null;
		if (card != null) {
			int w = width, h = height;
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
				TitleCard.draw(g, w, h, host.getAlpha());
			}));
			addRenderableWidget(model);
			addRenderableWidget(host);
		}
		if (self instanceof TitleScreen) aerion$side(self);
	}

	/** The left side of the title screen: facts and shortcuts (see TitleSide). */
	private void aerion$side(Screen self) {
		Theme th = AerionClient.theme();
		if (th != null && !th.titleExtras) return;
		if (aerion$lastTitle != self) {
			aerion$lastTitle = self;
			TitleSide.opened();
		}
		LastPlayed.Entry last = LastPlayed.get();
		int n = last != null ? 3 : 2;
		int[] side = TitleSide.place(width, height, n);
		if (side == null) return;
		int i = 0;
		Button first = null;
		if (last != null) {
			String label = TitleSide.fit(Lang.tr("aerion.title.back").replace("{name}", last.name()), side[2] - 10);
			first = addRenderableWidget(aerion$button(label, TitleSide.button(side, i++), () -> LastPlayed.play(last, self)));
		}
		Button shots = addRenderableWidget(aerion$button(TitleSide.fit(Lang.tr("aerion.title.screenshots"), side[2] - 10), TitleSide.button(side, i++), () -> TitleSide.openFolder("screenshots")));
		addRenderableWidget(aerion$button(TitleSide.fit(Lang.tr("aerion.title.folder"), side[2] - 10), TitleSide.button(side, i), () -> TitleSide.openFolder(".")));
		int w = width, h = height;
		Button lead = first != null ? first : shots;
		addRenderableOnly(Painter.of(g -> TitleSide.draw(g, w, h, n, lead.getAlpha())));
	}

	private static Button aerion$button(String label, int[] b, Runnable action) {
		return Button.builder(Component.literal(label), x -> action.run()).bounds(b[0], b[1], b[2], b[3]).build();
	}
}
