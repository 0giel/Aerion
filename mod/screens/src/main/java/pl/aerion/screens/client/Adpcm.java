package pl.aerion.screens.client;

/**
 * IMA ADPCM: 16-bit sound in 4 bits a sample (a quarter of the size), simple and without
 * libraries. Each block starts with the predictor and step index, so lost blocks don't spoil the
 * next ones: [sample count: short][predictor: short][index: byte][4-bit codes...].
 */
final class Adpcm {
	private Adpcm() {}

	private static final int[] STEPS = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107,
			118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
			1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635,
			13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
	private static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

	/** State carried from one block to the next while encoding. */
	static final class Encoder {
		int predictor, index;

		byte[] encode(short[] pcm, int n) {
			byte[] out = new byte[5 + (n + 1) / 2];
			out[0] = (byte) (n >> 8);
			out[1] = (byte) n;
			out[2] = (byte) (predictor >> 8);
			out[3] = (byte) predictor;
			out[4] = (byte) index;
			for (int i = 0; i < n; i++) {
				int step = STEPS[index], diff = pcm[i] - predictor, code = 0;
				if (diff < 0) {
					code = 8;
					diff = -diff;
				}
				int delta = step >> 3;
				if (diff >= step) {
					code |= 4;
					diff -= step;
					delta += step;
				}
				if (diff >= step >> 1) {
					code |= 2;
					diff -= step >> 1;
					delta += step >> 1;
				}
				if (diff >= step >> 2) {
					code |= 1;
					delta += step >> 2;
				}
				predictor = clamp(predictor + ((code & 8) != 0 ? -delta : delta));
				index = Math.max(0, Math.min(88, index + INDEX[code]));
				if ((i & 1) == 0) out[5 + i / 2] = (byte) code;
				else out[5 + i / 2] |= (byte) (code << 4);
			}
			return out;
		}
	}

	/** The samples of a block, or null when it isn't one. */
	static short[] decode(byte[] in) {
		if (in.length < 5) return null;
		int n = ((in[0] & 0xFF) << 8) | (in[1] & 0xFF);
		if (n > 9600 || in.length < 5 + (n + 1) / 2) return null;
		int predictor = (short) (((in[2] & 0xFF) << 8) | (in[3] & 0xFF)), index = Math.max(0, Math.min(88, in[4]));
		short[] out = new short[n];
		for (int i = 0; i < n; i++) {
			int b = in[5 + i / 2] & 0xFF, code = (i & 1) == 0 ? b & 15 : b >> 4, step = STEPS[index];
			int delta = step >> 3;
			if ((code & 4) != 0) delta += step;
			if ((code & 2) != 0) delta += step >> 1;
			if ((code & 1) != 0) delta += step >> 2;
			predictor = clamp(predictor + ((code & 8) != 0 ? -delta : delta));
			index = Math.max(0, Math.min(88, index + INDEX[code]));
			out[i] = (short) predictor;
		}
		return out;
	}

	private static int clamp(int v) {
		return Math.max(-32768, Math.min(32767, v));
	}
}
