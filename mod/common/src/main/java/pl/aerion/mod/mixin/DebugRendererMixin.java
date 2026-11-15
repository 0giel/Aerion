package pl.aerion.mod.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.aerion.mod.AerionClient;
import pl.aerion.mod.features.BlockOutline;
import pl.aerion.mod.features.ChunkBorders;
import pl.aerion.mod.features.Hitboxes;
import pl.aerion.mod.features.LightLevels;
import pl.aerion.mod.features.TntTimer;
import pl.aerion.mod.features.Waypoints;

/** Hitboxes and light numbers join the shapes the game collects for the frame. */
@Mixin(DebugRenderer.class)
abstract class DebugRendererMixin {
	@Inject(method = "emitGizmos", at = @At("TAIL"))
	private void aerion$hitboxes(Frustum frustum, double x, double y, double z, float partial, CallbackInfo ci) {
		try {
			long t = System.nanoTime();
			Hitboxes.emit(partial);
			pl.aerion.mod.Cost.part("hitboxes").add(t);
			t = System.nanoTime();
			LightLevels.emit();
			pl.aerion.mod.Cost.part("lightLevels").add(t);
			t = System.nanoTime();
			BlockOutline.emit();
			pl.aerion.mod.Cost.part("blockOutline").add(t);
			t = System.nanoTime();
			Waypoints.emit(partial);
			pl.aerion.mod.Cost.part("waypoints").add(t);
			t = System.nanoTime();
			ChunkBorders.emit();
			pl.aerion.mod.Cost.part("chunkBorders").add(t);
			t = System.nanoTime();
			TntTimer.emit(partial);
			pl.aerion.mod.Cost.part("tntTimer").add(t);
		} catch (RuntimeException e) {
			AerionClient.warnOnce("hitboxes", e);
		}
	}
}
