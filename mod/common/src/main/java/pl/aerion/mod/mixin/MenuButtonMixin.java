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

	private static Component sceneLabel(String scene) {
		return Component.literal(Lang.tr("aerion.menu.scene").replace("{name}", Lang.tr("aerion.choice." + scene)));
	}

	private static net.minecraft.client.gui.components.Tooltip sceneTip(String scene) {
		String key = "world".equals(scene) && !pl.aerion.mod.MenuScene.hasWorldPicture() ? "aerion.menu.sceneWorldSoon" : "aerion.menu.sceneHint";
		return net.minecraft.client.gui.components.Tooltip.create(Component.literal(Lang.tr(key)));
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void aerion$menuButton(CallbackInfo ci) {
		Screen self = this;
		addRenderableWidget(Button.builder(
						Component.literal(Lang.tr("aerion.menu.button")),
						b -> Game.setScreen(new EditorScreen(new HudEditor(), self)))
				.bounds(width - 64, 4, 60, 20)
				.build());
		if (self instanceof TitleScreen) {
			// The menu background, switched in turn: aurora, the game's panorama, the player's world.
			Button scene = Button.builder(sceneLabel(pl.aerion.mod.MenuScene.mode()), b -> {
						String next = pl.aerion.mod.MenuScene.nextScene();
						b.setMessage(sceneLabel(next));
						b.setTooltip(sceneTip(next));
					})
					.bounds(width - 64 - 6 - 104, 4, 104, 20)
					.tooltip(sceneTip(pl.aerion.mod.MenuScene.mode()))
					.build();
			addRenderableWidget(scene);
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
