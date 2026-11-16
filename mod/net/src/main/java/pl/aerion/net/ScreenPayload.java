package pl.aerion.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The one channel of shared windows (`aerion:screen`), between the Aerion mod and the server mod
 * that passes shared windows on. Its body is one {@link Proto} message. The game turns channels it
 * doesn't know into empty payloads; {@code DiscardedPayloadMixin} gives this one its codec, on
 * the client and the server, with or without Fabric API.
 */
public record ScreenPayload(byte[] data) implements CustomPacketPayload {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("aerion", "screen");
	public static final Type<ScreenPayload> TYPE = new Type<>(ID);
	/** The largest body read (the game's own limit for messages to players is 1 MiB). */
	public static final int MAX = 1 << 20;

	public static final StreamCodec<FriendlyByteBuf, ScreenPayload> CODEC = CustomPacketPayload.codec(ScreenPayload::write, ScreenPayload::read);

	private void write(FriendlyByteBuf buf) {
		buf.writeBytes(data);
	}

	private static ScreenPayload read(FriendlyByteBuf buf) {
		int n = buf.readableBytes();
		if (n > MAX) throw new IllegalArgumentException("aerion:screen message too large: " + n);
		byte[] b = new byte[n];
		buf.readBytes(b);
		return new ScreenPayload(b);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
