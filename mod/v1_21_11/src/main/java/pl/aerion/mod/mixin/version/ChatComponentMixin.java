package pl.aerion.mod.mixin.version;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pl.aerion.mod.features.ChatTweaks;

/** Times and counted repeats on new chat messages. */
@Mixin(ChatComponent.class)
abstract class ChatComponentMixin {
	@Shadow
	@Final
	private List<net.minecraft.client.GuiMessage> allMessages;

	@Shadow
	protected abstract void refreshTrimmedMessages();

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private Component aerion$message(Component message) {
		return ChatTweaks.process(message, () -> {
			if (!allMessages.isEmpty()) {
				allMessages.remove(0);
				refreshTrimmedMessages();
			}
		});
	}
}
