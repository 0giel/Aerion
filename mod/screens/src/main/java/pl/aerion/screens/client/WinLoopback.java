package pl.aerion.screens.client;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The sound of one program on Windows 10 (2004) and 11: WASAPI "process loopback" records what
 * a process and the ones it started play (a browser's helper processes too), never the game. It
 * is reached through COM, so the few interfaces needed are called by their slots here. Untested
 * on real Windows when written; failures are logged, not thrown at the player.
 */
final class WinLoopback {
	private WinLoopback() {}

	private interface MmDevApi extends Library {
		MmDevApi INSTANCE = Native.load("mmdevapi", MmDevApi.class, W32APIOptions.DEFAULT_OPTIONS);

		int ActivateAudioInterfaceAsync(WString path, Guid.GUID.ByReference riid, Pointer params, Pointer handler, PointerByReference op);
	}

	private interface Kernel32Wait extends Library {
		Kernel32Wait INSTANCE = Native.load("kernel32", Kernel32Wait.class, W32APIOptions.DEFAULT_OPTIONS);

		void Sleep(int ms);
	}

	private static final String IID_AUDIO_CLIENT = "{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}";
	private static final String IID_CAPTURE_CLIENT = "{C8ADBD64-E71E-48a0-A4DE-185C395CD317}";
	private static final String IID_UNKNOWN = "{00000000-0000-0000-C000-000000000046}";
	private static final String IID_AGILE = "{94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90}";
	private static final String IID_HANDLER = "{41D949AB-9862-444A-80F6-C261334DA5EB}";
	private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000, AUTOCONVERTPCM = 0x80000000, SRC_DEFAULT_QUALITY = 0x08000000;
	private static final int BUFFER_SILENT = 0x2;

	// --- COM by hand ----------------------------------------------------------------------------

	/** Slot `index` of the object's table of functions. */
	private static Function slot(Pointer obj, int index) {
		Pointer vtable = obj.getPointer(0);
		return Function.getFunction(vtable.getPointer((long) index * Native.POINTER_SIZE), Function.ALT_CONVENTION);
	}

	private static int call(Pointer obj, int index, Object... args) {
		Object[] all = new Object[args.length + 1];
		all[0] = obj;
		System.arraycopy(args, 0, all, 1, args.length);
		return slot(obj, index).invokeInt(all);
	}

	private static void release(Pointer obj) {
		if (obj != null) call(obj, 2);
	}

	private static boolean failed(int hr) {
		return hr < 0;
	}

	// Public: JNA calls these from native code by reflection.
	public interface QueryInterface extends Callback {
		int invoke(Pointer self, Pointer riid, PointerByReference out);
	}

	public interface Counted extends Callback {
		int invoke(Pointer self);
	}

	public interface Completed extends Callback {
		int invoke(Pointer self, Pointer operation);
	}

	/**
	 * Activations Windows hasn't finished with: their handler and the memory it reads stay alive
	 * (if Windows answers after we stopped waiting, it must not find freed memory).
	 */
	private static final java.util.Set<Object[]> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The completion handler COM asks for: a tiny object with four slots, agile as required. */
	static final class Handler {
		final CountDownLatch done = new CountDownLatch(1);
		final Memory object = new Memory(Native.POINTER_SIZE), vtable = new Memory(4L * Native.POINTER_SIZE);
		// Kept as fields so the callbacks aren't collected while Windows may call them.
		final QueryInterface qi;
		final Counted addRef, release;
		final Completed completed;

		Handler() {
			String[] known = {IID_UNKNOWN.toUpperCase(), IID_AGILE.toUpperCase(), IID_HANDLER.toUpperCase()};
			qi = (self, riid, out) -> {
				String asked = new Guid.GUID(riid).toGuidString().toUpperCase();
				for (String k : known) {
					if (k.equals(asked)) {
						out.setValue(self);
						return 0;
					}
				}
				out.setValue(null);
				return 0x80004002; // E_NOINTERFACE
			};
			addRef = self -> 1;
			release = self -> 1;
			completed = (self, op) -> {
				done.countDown();
				return 0;
			};
			vtable.setPointer(0, CallbackReference.getFunctionPointer(qi));
			vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
			vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
			vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed));
			object.setPointer(0, vtable);
		}
	}

	/**
	 * Records process `pid` (and its children) as 16-bit mono at `rate` Hz, handing `chunk`-sample
	 * pieces to `sink` until `stop` says so. Returns false when Windows can't do it here.
	 */
	static boolean record(long pid, int rate, int chunk, BooleanSupplier stop, Consumer<short[]> sink) {
		Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
		Pointer client = null, capture = null;
		try {
			// AUDIOCLIENT_ACTIVATION_PARAMS: process loopback, the process, include its tree.
			Memory params = new Memory(12);
			params.setInt(0, 1);
			params.setInt(4, (int) pid);
			params.setInt(8, 0);
			// PROPVARIANT holding a blob: vt = VT_BLOB, then the size and the pointer.
			Memory prop = new Memory(8 + 2L * Native.POINTER_SIZE);
			prop.clear();
			prop.setShort(0, (short) 0x41);
			prop.setInt(8, 12);
			prop.setPointer(8 + Native.POINTER_SIZE, params);
			Handler handler = new Handler();
			PointerByReference op = new PointerByReference();
			Guid.GUID.ByReference iid = new Guid.GUID.ByReference(new Guid.GUID(IID_AUDIO_CLIENT));
			Object[] keep = {handler, prop, params, iid};
			PENDING.add(keep);
			int hr = MmDevApi.INSTANCE.ActivateAudioInterfaceAsync(new WString("VAD\\Process_Loopback"), iid, prop, handler.object, op);
			if (failed(hr)) PENDING.remove(keep);
			if (failed(hr) || !handler.done.await(5, TimeUnit.SECONDS)) {
				Screens.LOG.info("Aerion: Windows can't record one program's sound here ({})", Integer.toHexString(hr));
				return false;
			}
			PENDING.remove(keep);
			IntByReference result = new IntByReference();
			PointerByReference unknown = new PointerByReference();
			call(op.getValue(), 3, result, unknown);
			release(op.getValue());
			if (failed(result.getValue()) || unknown.getValue() == null) {
				Screens.LOG.info("Aerion: Windows refused to record the window's sound ({})", Integer.toHexString(result.getValue()));
				return false;
			}
			client = unknown.getValue();
			// WAVEFORMATEX: PCM, mono, rate, 16 bits.
			Memory format = new Memory(18);
			format.clear();
			format.setShort(0, (short) 1);
			format.setShort(2, (short) 1);
			format.setInt(4, rate);
			format.setInt(8, rate * 2);
			format.setShort(12, (short) 2);
			format.setShort(14, (short) 16);
			hr = call(client, 3, 0, AUDCLNT_STREAMFLAGS_LOOPBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY, 10_000_000L, 0L, format, Pointer.NULL);
			if (failed(hr)) {
				Screens.LOG.info("Aerion: the window's sound couldn't be set up ({})", Integer.toHexString(hr));
				return false;
			}
			PointerByReference service = new PointerByReference();
			hr = call(client, 14, new Guid.GUID.ByReference(new Guid.GUID(IID_CAPTURE_CLIENT)), service);
			if (failed(hr) || service.getValue() == null) return false;
			capture = service.getValue();
			if (failed(call(client, 10))) return false;
			short[] piece = new short[chunk];
			int filled = 0;
			IntByReference packet = new IntByReference(), frames = new IntByReference(), flags = new IntByReference();
			PointerByReference data = new PointerByReference();
			while (!stop.getAsBoolean()) {
				Kernel32Wait.INSTANCE.Sleep(10);
				if (failed(call(capture, 5, packet))) break;
				while (packet.getValue() > 0) {
					if (failed(call(capture, 3, data, frames, flags, Pointer.NULL, Pointer.NULL))) return true;
					int n = frames.getValue();
					boolean silent = (flags.getValue() & BUFFER_SILENT) != 0 || data.getValue() == null;
					short[] got = silent ? new short[n] : data.getValue().getShortArray(0, n);
					call(capture, 4, n);
					for (int i = 0; i < n; i++) {
						piece[filled++] = got[i];
						if (filled == chunk) {
							sink.accept(piece.clone());
							filled = 0;
						}
					}
					if (failed(call(capture, 5, packet))) break;
				}
			}
			call(client, 11);
			return true;
		} catch (Throwable e) {
			Screens.warnOnce("window-sound-windows", e instanceof Exception ex ? ex : new RuntimeException(e));
			return false;
		} finally {
			release(capture);
			release(client);
			Ole32.INSTANCE.CoUninitialize();
		}
	}
}
