package pl.aerion.mod.mixin;

import java.util.function.Consumer;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.BuiltinPack;

/** Adds the mod's built-in resource pack to the game's own resource packs (client side only). */
@Mixin(BuiltInPackSource.class)
abstract class BuiltInPackSourceMixin {
	@Inject(method = "loadPacks", at = @At("TAIL"))
	private void aerion$addPack(Consumer<Pack> out, CallbackInfo ci) {
		if ((Object) this instanceof ClientPackSource) {
			BuiltinPack.create().ifPresent(out);
			pl.aerion.mod.features.Effects.pack().ifPresent(out);
		}
	}
}
