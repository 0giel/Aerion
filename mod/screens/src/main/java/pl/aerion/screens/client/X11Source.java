package pl.aerion.screens.client;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows of an X11 desktop (also the XWayland ones under Wayland). Pictures come from
 * XGetImage: a window covered by another shows what covers it unless the desktop composites.
 * Pointer input is sent as X events to the window itself, so the game keeps the real pointer;
 * GTK programs (Firefox, GNOME's) ignore such events. Keys go as real input (XTest) to the window,
 * which has the keyboard focus for that moment; every program takes those.
 */
final class X11Source implements WindowSource {
	/** What jna-platform's X11 lacks. */
	private interface Xlib extends Library {
		Xlib INSTANCE = Native.load("X11", Xlib.class);

		Pointer XGetImage(X11.Display d, X11.Window w, int x, int y, int width, int height, NativeLong planes, int format);

		int XDestroyImage(Pointer image);

		int XSetInputFocus(X11.Display d, X11.Window w, int revertTo, NativeLong time);

		int XGetInputFocus(X11.Display d, X11.WindowByReference focus, IntByReference revertTo);

		int XDisplayKeycodes(X11.Display d, IntByReference min, IntByReference max);

		Pointer XGetKeyboardMapping(X11.Display d, byte first, int count, IntByReference perKeycode);

		int XChangeKeyboardMapping(X11.Display d, int first, int perKeycode, NativeLong[] keysyms, int count);

		int XQueryKeymap(X11.Display d, byte[] keys);
	}

	private static final int Z_PIXMAP = 2;
	/** Characters typed with Shift on a usual (US) layout. */
	private static final String SHIFTED = "~!@#$%^&*()_+{}|:\"<>?";
	private final X11 x = X11.INSTANCE;
	private final X11.Display display;
	private final X11.Window root;
	private final long ownPid = ProcessHandle.current().pid();

	private X11Source(X11.Display display) {
		this.display = display;
		this.root = x.XDefaultRootWindow(display);
	}

	/** The handler before ours, for errors of other connections (the game's window). */
	private static X11.XErrorHandler previous;
	/** Kept here so the garbage collector doesn't take the native callback. */
	private static X11.XErrorHandler handler;
	private static volatile X11.Display ours;

	static X11Source open() {
		X11.Display d = X11.INSTANCE.XOpenDisplay(null);
		if (d == null) throw new IllegalStateException("no X display");
		ours = d;
		// Xlib's own handler ends the whole program on any error, e.g. a window closed while it
		// is being read. Errors on our connection are expected and skipped; others go on as before.
		handler = (display, event) -> {
			if (display != null && ours != null && display.equals(ours)) return 0;
			return previous != null ? previous.apply(display, event) : 0;
		};
		previous = X11.INSTANCE.XSetErrorHandler(handler);
		return new X11Source(d);
	}

	private X11.Atom atom(String name) {
		return x.XInternAtom(display, name, false);
	}

	/** A property of 32-bit items (window ids, cardinals); empty when it isn't set. */
	private long[] longs(X11.Window w, String name) {
		X11.AtomByReference type = new X11.AtomByReference();
		IntByReference format = new IntByReference();
		NativeLongByReference count = new NativeLongByReference(), rest = new NativeLongByReference();
		PointerByReference data = new PointerByReference();
		int r = x.XGetWindowProperty(display, w, atom(name), new NativeLong(0), new NativeLong(4096), false,
				new X11.Atom(X11.AnyPropertyType), type, format, count, rest, data);
		if (r != 0 || data.getValue() == null) return new long[0];
		try {
			int n = count.getValue().intValue();
			long[] out = new long[n];
			// Format 32 items are C longs in memory.
			for (int i = 0; i < n; i++) out[i] = data.getValue().getNativeLong((long) i * NativeLong.SIZE).longValue();
			return format.getValue() == 32 ? out : new long[0];
		} finally {
			x.XFree(data.getValue());
		}
	}

	@Override
	public synchronized long pid(long id) {
		long[] pid = longs(new X11.Window(id), "_NET_WM_PID");
		return pid.length > 0 ? pid[0] : 0;
	}

	/** The icon from _NET_WM_ICON: of the sizes there, the one closest to 32 pixels. */
	@Override
	public synchronized Frame icon(long id) {
		X11.AtomByReference type = new X11.AtomByReference();
		IntByReference format = new IntByReference();
		NativeLongByReference count = new NativeLongByReference(), rest = new NativeLongByReference();
		PointerByReference data = new PointerByReference();
		int r = x.XGetWindowProperty(display, new X11.Window(id), atom("_NET_WM_ICON"), new NativeLong(0), new NativeLong(1 << 20), false,
				new X11.Atom(X11.AnyPropertyType), type, format, count, rest, data);
		if (r != 0 || data.getValue() == null) return null;
		try {
			if (format.getValue() != 32) return null;
			long n = count.getValue().longValue();
			long at = 0, best = -1;
			int bw = 0, bh = 0;
			while (at + 2 <= n) {
				int w = (int) data.getValue().getNativeLong(at * NativeLong.SIZE).longValue();
				int h = (int) data.getValue().getNativeLong((at + 1) * NativeLong.SIZE).longValue();
				if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || at + 2 + (long) w * h > n) break;
				if (best < 0 || Math.abs(w - 32) < Math.abs(bw - 32)) {
					best = at + 2;
					bw = w;
					bh = h;
				}
				at += 2 + (long) w * h;
			}
			if (best < 0) return null;
			int[] px = new int[bw * bh];
			// Format 32 items are C longs in memory; the colour is in the low 32 bits (ARGB).
			for (int i = 0; i < px.length; i++) px[i] = (int) data.getValue().getNativeLong((best + i) * NativeLong.SIZE).longValue();
			return new Frame(bw, bh, px);
		} finally {
			x.XFree(data.getValue());
		}
	}

	private String text(X11.Window w, String name) {
		X11.AtomByReference type = new X11.AtomByReference();
		IntByReference format = new IntByReference();
		NativeLongByReference count = new NativeLongByReference(), rest = new NativeLongByReference();
		PointerByReference data = new PointerByReference();
		int r = x.XGetWindowProperty(display, w, atom(name), new NativeLong(0), new NativeLong(1024), false,
				new X11.Atom(X11.AnyPropertyType), type, format, count, rest, data);
		if (r != 0 || data.getValue() == null) return "";
		try {
			byte[] b = data.getValue().getByteArray(0, count.getValue().intValue());
			return new String(b, StandardCharsets.UTF_8).replace('\0', ' ').trim();
		} finally {
			x.XFree(data.getValue());
		}
	}

	private List<X11.Window> candidates() {
		List<X11.Window> out = new ArrayList<>();
		long[] managed = longs(root, "_NET_CLIENT_LIST");
		if (managed.length > 0) {
			for (long id : managed) out.add(new X11.Window(id));
			return out;
		}
		// No window manager: the root's own children.
		X11.WindowByReference r = new X11.WindowByReference(), p = new X11.WindowByReference();
		PointerByReference kids = new PointerByReference();
		IntByReference n = new IntByReference();
		if (x.XQueryTree(display, root, r, p, kids, n) == 0 || kids.getValue() == null) return out;
		try {
			for (int i = 0; i < n.getValue(); i++) out.add(new X11.Window(kids.getValue().getNativeLong((long) i * NativeLong.SIZE).longValue()));
		} finally {
			x.XFree(kids.getValue());
		}
		return out;
	}

	@Override
	public synchronized List<Win> list() {
		List<Win> out = new ArrayList<>();
		for (X11.Window w : candidates()) {
			X11.XWindowAttributes a = new X11.XWindowAttributes();
			if (x.XGetWindowAttributes(display, w, a) == 0 || a.map_state != X11.IsViewable || a.width < 80 || a.height < 60) continue;
			long[] pid = longs(w, "_NET_WM_PID");
			if (pid.length > 0 && pid[0] == ownPid) continue;
			String title = text(w, "_NET_WM_NAME");
			if (title.isEmpty()) title = text(w, "WM_NAME");
			if (title.isEmpty()) continue;
			String cls = text(w, "WM_CLASS");
			out.add(new Win(w.longValue(), title, cls));
		}
		return out;
	}

	@Override
	public synchronized Frame capture(long id, int maxWidth) {
		X11.Window w = new X11.Window(id);
		X11.XWindowAttributes a = new X11.XWindowAttributes();
		if (x.XGetWindowAttributes(display, w, a) == 0 || a.map_state != X11.IsViewable) return null;
		// Only the part on the screen can be read; asking for more is an error.
		X11.XWindowAttributes ra = new X11.XWindowAttributes();
		x.XGetWindowAttributes(display, root, ra);
		IntByReference ax = new IntByReference(), ay = new IntByReference();
		x.XTranslateCoordinates(display, w, root, 0, 0, ax, ay, new X11.WindowByReference());
		int left = Math.max(0, -ax.getValue()), top = Math.max(0, -ay.getValue());
		int right = Math.min(a.width, ra.width - ax.getValue()), bottom = Math.min(a.height, ra.height - ay.getValue());
		if (right - left < 8 || bottom - top < 8) return null;
		// The picture is the part on the screen: clicks are placed within the same part.
		shown.put(id, new int[] {left, top, right - left, bottom - top});
		Pointer img = Xlib.INSTANCE.XGetImage(display, w, left, top, right - left, bottom - top, new NativeLong(-1L), Z_PIXMAP);
		if (img == null) return null;
		try {
			int width = img.getInt(0), height = img.getInt(4);
			Pointer data = img.getPointer(16);
			int stride = img.getInt(44), bpp = img.getInt(48);
			if (data == null || bpp != 32) return null;
			int[] px = new int[width * height];
			for (int row = 0; row < height; row++) {
				data.read((long) row * stride, px, row * width, width);
			}
			for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
			return WindowSource.fit(width, height, px, maxWidth);
		} finally {
			Xlib.INSTANCE.XDestroyImage(img);
		}
	}

	/** Per window, the part last pictured (x, y, width, height in the window). */
	private final java.util.Map<Long, int[]> shown = new java.util.HashMap<>();

	/** The point (`fx`, `fy`) of the picture, in the window's own pixels. */
	private int[] point(long id, float fx, float fy) {
		int[] r = shown.get(id);
		if (r == null) {
			X11.XWindowAttributes a = new X11.XWindowAttributes();
			x.XGetWindowAttributes(display, new X11.Window(id), a);
			r = new int[] {0, 0, Math.max(1, a.width), Math.max(1, a.height)};
		}
		return new int[] {r[0] + Math.round(fx * r[2]), r[1] + Math.round(fy * r[3])};
	}

	/**
	 * The innermost window at (`fx`, `fy`) of window `id`, the point in its coordinates and on the
	 * screen (many programs - GTK, Chromium - read the screen position, so it must be right for
	 * windows that don't start at the screen's corner).
	 */
	private record Target(X11.Window w, int x, int y, int rootX, int rootY) {}

	private Target located(X11.Window w, int px, int py) {
		IntByReference rx = new IntByReference(), ry = new IntByReference();
		x.XTranslateCoordinates(display, w, root, px, py, rx, ry, new X11.WindowByReference());
		return new Target(w, px, py, rx.getValue(), ry.getValue());
	}

	/** The window the pointer was last "in": programs get Enter/Leave as if the pointer moved. */
	private X11.Window hovered;

	private void cross(Target t) {
		if (hovered != null && hovered.longValue() == t.w.longValue()) return;
		if (hovered != null) crossing(hovered, X11.LeaveNotify, t);
		crossing(t.w, X11.EnterNotify, t);
		hovered = t.w;
	}

	private void crossing(X11.Window w, int type, Target at) {
		X11.XEvent e = new X11.XEvent();
		e.type = type;
		e.xcrossing.type = type;
		e.xcrossing.display = display;
		e.xcrossing.window = w;
		e.xcrossing.root = root;
		e.xcrossing.subwindow = X11.Window.None;
		e.xcrossing.time = new NativeLong(0);
		e.xcrossing.x = at.x;
		e.xcrossing.y = at.y;
		e.xcrossing.x_root = at.rootX;
		e.xcrossing.y_root = at.rootY;
		e.xcrossing.mode = X11.NotifyNormal;
		e.xcrossing.detail = X11.NotifyAncestor;
		e.xcrossing.same_screen = 1;
		e.xcrossing.focus = 1;
		e.setType(X11.XCrossingEvent.class);
		e.write();
		x.XSendEvent(display, w, 1, new NativeLong(type == X11.EnterNotify ? X11.EnterWindowMask : X11.LeaveWindowMask), e);
	}

	private Target target(long id, float fx, float fy) {
		int[] at = point(id, fx, fy);
		X11.Window w = new X11.Window(id);
		int px = at[0], py = at[1];
		for (int depth = 0; depth < 16; depth++) {
			IntByReference cx = new IntByReference(), cy = new IntByReference();
			X11.WindowByReference child = new X11.WindowByReference();
			if (!x.XTranslateCoordinates(display, w, w, px, py, cx, cy, child) || child.getValue() == null
					|| child.getValue().longValue() == 0) break;
			X11.Window c = child.getValue();
			IntByReference ix = new IntByReference(), iy = new IntByReference();
			x.XTranslateCoordinates(display, w, c, px, py, ix, iy, new X11.WindowByReference());
			w = c;
			px = ix.getValue();
			py = iy.getValue();
		}
		return located(w, px, py);
	}

	/** The point (`fx`, `fy`) of window `id` in the coordinates of its inner window `w`. */
	private Target in(long id, X11.Window w, float fx, float fy) {
		int[] at = point(id, fx, fy);
		IntByReference ix = new IntByReference(), iy = new IntByReference();
		x.XTranslateCoordinates(display, new X11.Window(id), w, at[0], at[1], ix, iy, new X11.WindowByReference());
		return located(w, ix.getValue(), iy.getValue());
	}

	/** While a button is held, its events go to the window it went down on (as X itself does). */
	private X11.Window pressedIn;

	private void sendButton(Target t, int type, int button) {
		X11.XEvent e = new X11.XEvent();
		e.type = type;
		e.xbutton.type = type;
		e.xbutton.display = display;
		e.xbutton.window = t.w;
		e.xbutton.root = root;
		e.xbutton.subwindow = X11.Window.None;
		e.xbutton.time = new NativeLong(0);
		e.xbutton.x = t.x;
		e.xbutton.y = t.y;
		e.xbutton.x_root = t.rootX;
		e.xbutton.y_root = t.rootY;
		e.xbutton.button = button;
		e.xbutton.same_screen = 1;
		e.xbutton.state = type == X11.ButtonRelease ? (X11.Button1Mask << (button - 1)) : 0;
		e.setType(X11.XButtonEvent.class);
		e.write();
		x.XSendEvent(display, t.w, 1, new NativeLong(type == X11.ButtonPress ? X11.ButtonPressMask : X11.ButtonReleaseMask), e);
	}

	private static int xButton(int button) {
		return button == 1 ? 3 : 1;
	}

	@Override
	public synchronized void press(long id, float fx, float fy, int button) {
		Target t = target(id, fx, fy);
		pressedIn = t.w;
		cross(t);
		sendButton(t, X11.ButtonPress, xButton(button));
		x.XFlush(display);
	}

	@Override
	public synchronized void release(long id, float fx, float fy, int button) {
		Target t = pressedIn != null ? in(id, pressedIn, fx, fy) : target(id, fx, fy);
		pressedIn = null;
		sendButton(t, X11.ButtonRelease, xButton(button));
		x.XFlush(display);
	}

	@Override
	public synchronized void move(long id, float fx, float fy, boolean held) {
		Target t = held && pressedIn != null ? in(id, pressedIn, fx, fy) : target(id, fx, fy);
		if (!held) cross(t);
		X11.XEvent e = new X11.XEvent();
		e.type = X11.MotionNotify;
		e.xmotion.type = X11.MotionNotify;
		e.xmotion.display = display;
		e.xmotion.window = t.w;
		e.xmotion.root = root;
		e.xmotion.subwindow = X11.Window.None;
		e.xmotion.time = new NativeLong(0);
		e.xmotion.x = t.x;
		e.xmotion.y = t.y;
		e.xmotion.x_root = t.rootX;
		e.xmotion.y_root = t.rootY;
		e.xmotion.state = held ? X11.Button1Mask : 0;
		e.xmotion.is_hint = 0;
		e.xmotion.same_screen = 1;
		e.setType(X11.XMotionEvent.class);
		e.write();
		x.XSendEvent(display, t.w, 1, new NativeLong(held ? X11.ButtonMotionMask | X11.PointerMotionMask : X11.PointerMotionMask), e);
		x.XFlush(display);
	}

	private void button(long id, float fx, float fy, int xButton) {
		Target t = target(id, fx, fy);
		cross(t);
		sendButton(t, X11.ButtonPress, xButton);
		sendButton(t, X11.ButtonRelease, xButton);
		x.XFlush(display);
	}

	@Override
	public synchronized void scroll(long id, float fx, float fy, int notches) {
		// GTK programs ignore a sent wheel and the game holds the real pointer: they get the arrow
		// keys instead, three per notch, as the wheel scrolls three lines.
		if (ignoresSentPointer(id) && xtest() != null) {
			int code = keycode(notches > 0 ? "Up" : "Down");
			if (code != 0) queue(id, () -> {
				for (int i = 0; i < 3 * Math.abs(notches); i++) stroke(code, 0);
			});
			return;
		}
		for (int i = 0; i < Math.abs(notches); i++) button(id, fx, fy, notches > 0 ? 4 : 5);
	}

	private void keysym(long id, String sym, boolean shift) {
		X11.KeySym ks = x.XStringToKeysym(sym);
		if (ks == null || ks.longValue() == 0) return;
		int code = x.XKeysymToKeycode(display, ks) & 0xFF;
		if (code == 0) return;
		X11.Window w = new X11.Window(id);
		for (int type : new int[] {X11.KeyPress, X11.KeyRelease}) {
			X11.XEvent e = new X11.XEvent();
			e.type = type;
			e.xkey.type = type;
			e.xkey.display = display;
			e.xkey.window = w;
			e.xkey.root = root;
			e.xkey.subwindow = X11.Window.None;
			e.xkey.time = new NativeLong(0);
			e.xkey.keycode = code;
			e.xkey.state = shift ? X11.ShiftMask : 0;
			e.xkey.same_screen = 1;
			e.setType(X11.XKeyEvent.class);
			e.write();
			x.XSendEvent(display, w, 1, new NativeLong(type == X11.KeyPress ? X11.KeyPressMask : X11.KeyReleaseMask), e);
		}
	}

	// --- Real keys (XTest) ------------------------------------------------------------------------

	private X11.XTest xtest;
	private boolean xtestTried;

	/** XTest when the X server has it (it nearly always does), else null. */
	private X11.XTest xtest() {
		if (!xtestTried) {
			xtestTried = true;
			try {
				X11.XTest t = X11.XTest.INSTANCE;
				if (t.XTestQueryExtension(display, new IntByReference(), new IntByReference(), new IntByReference(), new IntByReference())) xtest = t;
			} catch (Throwable e) {
				Screens.LOG.info("Aerion Screens: no XTest here ({}), keys go as plain events", e.toString());
			}
		}
		return xtest;
	}

	private int keycode(String sym) {
		X11.KeySym ks = x.XStringToKeysym(sym);
		return ks == null || ks.longValue() == 0 ? 0 : x.XKeysymToKeycode(display, ks) & 0xFF;
	}

	private void fake(int code, boolean down) {
		// A millisecond apart: GTK orders key events by their time, and events of the same
		// millisecond came out shuffled.
		if (code != 0) xtest.XTestFakeKeyEvent(display, code, down, new NativeLong(1));
	}

	/**
	 * The window gets the keyboard focus for the moment `send` runs, then it goes back where it
	 * was: real key events always go to the focused window, and the game must keep getting its keys.
	 */
	private void toWindow(long id, Runnable send) {
		X11.WindowByReference was = new X11.WindowByReference();
		IntByReference revert = new IntByReference();
		Xlib.INSTANCE.XGetInputFocus(display, was, revert);
		Xlib.INSTANCE.XSetInputFocus(display, new X11.Window(id), 2, new NativeLong(0));
		try {
			send.run();
		} finally {
			x.XSync(display, false);
			// The server handles real input from its own queue, a little after the request: the
			// focus may only go back once the keys have reached the window.
			try {
				Thread.sleep(40);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			X11.Window back = was.getValue();
			if (back != null && back.longValue() > 1) Xlib.INSTANCE.XSetInputFocus(display, back, revert.getValue(), new NativeLong(0));
			x.XFlush(display);
		}
	}

	private static final String[] MODIFIERS = {"Shift_L", "Control_L", "Alt_L", "Super_L"};

	/** Presses the key `code` with the modifiers in `mods` (Shift 1, Ctrl 2, Alt 4, Super 8). */
	private void stroke(int code, int mods) {
		int[] held = new int[MODIFIERS.length];
		for (int i = 0; i < MODIFIERS.length; i++) {
			if ((mods & (1 << i)) != 0) {
				held[i] = keycode(MODIFIERS[i]);
				fake(held[i], true);
			}
		}
		fake(code, true);
		fake(code, false);
		for (int i = MODIFIERS.length - 1; i >= 0; i--) fake(held[i], false);
	}

	/** A keycode no key uses, borrowed for characters the keyboard layout lacks (as xdotool does). */
	private int spare = -1;

	private int spareKeycode() {
		if (spare >= 0) return spare;
		spare = 0;
		IntByReference min = new IntByReference(), max = new IntByReference(), per = new IntByReference();
		Xlib.INSTANCE.XDisplayKeycodes(display, min, max);
		int lo = min.getValue(), n = max.getValue() - lo + 1;
		if (n <= 0) return 0;
		Pointer p = Xlib.INSTANCE.XGetKeyboardMapping(display, (byte) lo, n, per);
		if (p == null) return 0;
		try {
			int k = per.getValue();
			for (int i = n - 1; i >= 0 && spare == 0; i--) {
				boolean empty = true;
				for (int j = 0; j < k && empty; j++) empty = p.getNativeLong((long) (i * k + j) * NativeLong.SIZE).longValue() == 0;
				if (empty) spare = lo + i;
			}
		} finally {
			x.XFree(p);
		}
		return spare;
	}

	/** Types one character as real input: with the key that has it, or a borrowed keycode. */
	private void realChar(int cp) {
		long ks = cp == '\n' ? 0xFF0D : cp == '\t' ? 0xFF09 : cp >= 0x20 && cp < 0x100 ? cp : 0x01000000L | cp;
		int code = x.XKeysymToKeycode(display, new X11.KeySym(ks)) & 0xFF;
		if (code != 0) {
			for (int level = 0; level < 2; level++) {
				X11.KeySym at = x.XKeycodeToKeysym(display, (byte) code, level);
				if (at != null && at.longValue() == ks) {
					stroke(code, level == 1 ? SHIFT : 0);
					return;
				}
			}
		}
		int borrowed = spareKeycode();
		if (borrowed == 0) return;
		Xlib.INSTANCE.XChangeKeyboardMapping(display, borrowed, 1, new NativeLong[] {new NativeLong(ks)}, 1);
		x.XSync(display, false);
		stroke(borrowed, 0);
		x.XSync(display, false);
		// The program reads the key with the borrowed meaning: give it a moment before the next.
		try {
			Thread.sleep(25);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// Keys wait a moment and go to the window together: one focus change for a burst of typing
	// keeps them in order and the game's frame free.
	private final List<Runnable> pending = new ArrayList<>();
	private long pendingId;
	private java.util.concurrent.ScheduledExecutorService sender;

	private void queue(long id, Runnable keys) {
		if (!pending.isEmpty() && pendingId != id) flush();
		pendingId = id;
		pending.add(keys);
		if (sender == null) {
			sender = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "Aerion window keys");
				t.setDaemon(true);
				return t;
			});
		}
		if (pending.size() == 1) sender.schedule(() -> {
			synchronized (this) {
				flush();
			}
		}, 30, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private void flush() {
		if (pending.isEmpty()) return;
		List<Runnable> now = new ArrayList<>(pending);
		pending.clear();
		toWindow(pendingId, () -> now.forEach(Runnable::run));
	}

	@Override
	public synchronized void type(long id, String text) {
		if (xtest() != null) {
			queue(id, () -> text.codePoints().forEach(this::realChar));
			return;
		}
		for (int cp : text.codePoints().toArray()) {
			String sym = cp == ' ' ? "space" : cp < 128 && Character.isLetterOrDigit(cp) ? String.valueOf((char) Character.toLowerCase(cp)) : String.format("U%04X", cp);
			keysym(id, sym, Character.isUpperCase(cp) || SHIFTED.indexOf(cp) >= 0);
		}
		x.XFlush(display);
	}

	/** X's name for a key name from {@link Keys#name}. */
	private static String sym(String name) {
		if (name.length() == 1) return name;
		if (name.matches("f\\d{1,2}")) return "F" + name.substring(1);
		return switch (name) {
			case "enter" -> "Return";
			case "backspace" -> "BackSpace";
			case "tab" -> "Tab";
			case "escape" -> "Escape";
			case "left" -> "Left";
			case "right" -> "Right";
			case "up" -> "Up";
			case "down" -> "Down";
			case "home" -> "Home";
			case "end" -> "End";
			case "pageup" -> "Prior";
			case "pagedown" -> "Next";
			case "insert" -> "Insert";
			case "delete" -> "Delete";
			case "space" -> "space";
			default -> null;
		};
	}

	@Override
	public synchronized void key(long id, String name) {
		key(id, name, 0);
	}

	@Override
	public synchronized void key(long id, String name, int mods) {
		String sym = sym(name);
		if (sym == null) return;
		if (xtest() != null) {
			int code = keycode(sym);
			if (code != 0) queue(id, () -> stroke(code, mods));
			return;
		}
		keysym(id, sym, (mods & SHIFT) != 0);
		x.XFlush(display);
	}

	// --- The keyboard held by a window ----------------------------------------------------------------

	private volatile Thread holder;
	private X11.Window heldFrom;
	private int heldRevert;

	@Override
	public synchronized boolean holdKeyboard(long id, Runnable done) {
		releaseKeyboard();
		int esc = keycode("Escape");
		if (esc == 0) return false;
		X11.WindowByReference was = new X11.WindowByReference();
		IntByReference revert = new IntByReference();
		Xlib.INSTANCE.XGetInputFocus(display, was, revert);
		heldFrom = was.getValue();
		heldRevert = revert.getValue();
		Xlib.INSTANCE.XSetInputFocus(display, new X11.Window(id), 2, new NativeLong(0));
		x.XFlush(display);
		Mirrors.keyboardHeld = true;
		// Esc is watched in the keyboard's state, which X tells whoever has the focus.
		Thread t = new Thread(() -> {
			byte[] keys = new byte[32];
			boolean seen = false;
			while (holder == Thread.currentThread()) {
				boolean down;
				synchronized (this) {
					Xlib.INSTANCE.XQueryKeymap(display, keys);
					down = (keys[esc >> 3] & (1 << (esc & 7))) != 0;
				}
				// Back to the game once Esc is let go, so its release doesn't reach the game.
				if (down) seen = true;
				else if (seen) {
					releaseKeyboard();
					done.run();
					return;
				}
				try {
					Thread.sleep(25);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "Aerion window keyboard");
		t.setDaemon(true);
		holder = t;
		t.start();
		return true;
	}

	@Override
	public synchronized void releaseKeyboard() {
		if (holder == null) return;
		holder = null;
		Mirrors.keyboardHeld = false;
		if (heldFrom != null && heldFrom.longValue() > 1) Xlib.INSTANCE.XSetInputFocus(display, heldFrom, heldRevert, new NativeLong(0));
		heldFrom = null;
		x.XFlush(display);
	}

	/** GTK programs (Firefox, GNOME's) ignore pointer events sent to them; their windows carry _GTK_ properties. */
	private final java.util.Map<Long, Boolean> gtk = new java.util.HashMap<>();

	private boolean ignoresSentPointer(long id) {
		return gtk.computeIfAbsent(id, k -> {
			X11.Window w = new X11.Window(k);
			for (String p : new String[] {"_GTK_THEME_VARIANT", "_GTK_APPLICATION_ID", "_GTK_FRAME_EXTENTS"}) if (has(w, p)) return true;
			String cls = text(w, "WM_CLASS").toLowerCase(java.util.Locale.ROOT);
			return cls.matches(".*(firefox|navigator|thunderbird|librewolf|waterfox|floorp|zen).*");
		});
	}

	private boolean has(X11.Window w, String name) {
		X11.AtomByReference type = new X11.AtomByReference();
		IntByReference format = new IntByReference();
		NativeLongByReference count = new NativeLongByReference(), rest = new NativeLongByReference();
		PointerByReference data = new PointerByReference();
		int r = x.XGetWindowProperty(display, w, atom(name), new NativeLong(0), new NativeLong(1), false, new X11.Atom(X11.AnyPropertyType), type, format, count, rest, data);
		if (data.getValue() != null) x.XFree(data.getValue());
		return r == 0 && type.getValue() != null && type.getValue().longValue() != 0;
	}

	/** The game's own window: the biggest viewable one of this process. */
	private X11.Window ownWindow() {
		X11.Window best = null;
		int area = 0;
		for (X11.Window w : candidates()) {
			long[] pid = longs(w, "_NET_WM_PID");
			if (pid.length == 0 || pid[0] != ownPid) continue;
			X11.XWindowAttributes a = new X11.XWindowAttributes();
			if (x.XGetWindowAttributes(display, w, a) == 0 || a.map_state != X11.IsViewable) continue;
			if (a.width * a.height > area) {
				area = a.width * a.height;
				best = w;
			}
		}
		return best;
	}

	@Override
	public synchronized void focusGame() {
		X11.Window own = ownWindow();
		if (own == null) return;
		long id = own.longValue();
		X11.Window w = new X11.Window(id);
		// Ask the window manager first (as a pager would, so it isn't refused), then X itself.
		X11.XEvent e = new X11.XEvent();
		e.type = X11.ClientMessage;
		e.xclient.type = X11.ClientMessage;
		e.xclient.display = display;
		e.xclient.window = w;
		e.xclient.message_type = atom("_NET_ACTIVE_WINDOW");
		e.xclient.format = 32;
		e.xclient.data.setType(NativeLong[].class);
		e.xclient.data.l[0] = new NativeLong(2);
		e.xclient.data.l[1] = new NativeLong(0);
		e.xclient.data.l[2] = new NativeLong(0);
		e.setType(X11.XClientMessageEvent.class);
		e.write();
		x.XSendEvent(display, root, 0, new NativeLong(X11.SubstructureRedirectMask | X11.SubstructureNotifyMask), e);
		Xlib.INSTANCE.XSetInputFocus(display, w, 2, new NativeLong(0));
		x.XFlush(display);
	}
}
