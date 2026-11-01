package pl.aerion.mod.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fullbright sets the gamma past the slider's range, as fullbright mods usually do. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
	@Accessor("value")
	void aerion$setValue(T value);
}
