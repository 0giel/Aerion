package pl.aerion.mod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the reach and combo modules show: how far the last hit reached (from the eyes to the
 * nearest point of the target's box) and how many hits in a row landed without being hit back.
 * Only the local player's own attacks are seen, as the game sends them.
 */
public final class Combat {
	private Combat() {}

	/** A combo ends after this long without a hit. */
	private static final long COMBO_GAP = 2500;

	private static double reach = -1;
	private static long reachAt, lastHit;
	private static int combo;
	private static int lastHurt;

	/** Called when the player attacks an entity. */
	public static void attacked(LocalPlayer player, Entity target) {
		if (!(target instanceof LivingEntity)) return;
		Vec3 eye = player.getEyePosition();
		AABB box = target.getBoundingBox();
		double x = Math.max(box.minX, Math.min(eye.x, box.maxX));
		double y = Math.max(box.minY, Math.min(eye.y, box.maxY));
		double z = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
		reach = eye.distanceTo(new Vec3(x, y, z));
		long now = System.currentTimeMillis();
		reachAt = now;
		combo = now - lastHit > COMBO_GAP ? 1 : combo + 1;
		lastHit = now;
	}

	/** Called every tick: being hit ends the combo. */
	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		int hurt = p.hurtTime;
		if (hurt > lastHurt) combo = 0;
		lastHurt = hurt;
		if (System.currentTimeMillis() - lastHit > COMBO_GAP) combo = 0;
	}

	/** The last hit's reach in blocks, or -1 before any. */
	public static double reach() {
		return reach;
	}

	/** Milliseconds since the last hit. */
	public static long sinceReach() {
		return reachAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - reachAt;
	}

	public static int combo() {
		return combo;
	}
}
