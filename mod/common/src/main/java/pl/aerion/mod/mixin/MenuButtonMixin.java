package pl.aerion.mod.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.Lang;
import pl.aerion.mod.hud.AerionMenu;
import pl.aerion.mod.platform.EditorScreen;
import pl.aerion.mod.platform.Game;

/** A button to Aerion's menu in the corner of the title and pause screens. */
@Mixin({TitleScreen.class, PauseScreen.class})
abstract class MenuButtonMixin extends Screen {
	private MenuButtonMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void aerion$menuButton(CallbackInfo ci) {
		Screen self = this;
		addRenderableWidget(Button.builder(
						Component.literal(Lang.tr("aerion.menu.button")),
						b -> Game.setScreen(new EditorScreen(new AerionMenu(), self)))
				.bounds(width - 64, 4, 60, 20)
				.build());
	}
}
