package pl.aerion.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.features.WaypointCodes;

/** A click on "[+]" after a shared waypoint in the chat adds the point. */
@Mixin(Screen.class)
abstract class ScreenClickMixin {
	@Inject(method = "defaultHandleClickEvent", at = @At("HEAD"), cancellable = true)
	private static void aerion$waypoint(ClickEvent event, Minecraft mc, Screen screen, CallbackInfo ci) {
		if (WaypointCodes.clicked(event)) ci.cancel();
	}
}
