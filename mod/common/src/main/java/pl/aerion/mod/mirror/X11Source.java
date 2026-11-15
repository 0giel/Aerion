package pl.aerion.mod.mirror;

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
 * Input is sent as X events to the window itself, so the game keeps the real pointer; some
 * programs ignore such events.
 */
final class X11Source implements WindowSource {
	/** What jna-platform's X11 lacks. */
	private interface Xlib extends Library {
		Xlib INSTANCE = Native.load("X11", Xlib.class);

		Pointer XGetImage(X11.Display d, X11.Window w, int x, int y, int width, int height, NativeLong planes, int format);

		int XDestroyImage(Pointer image);

		int XSetInputFocus(X11.Display d, X11.Window w, int revertTo, NativeLong time);
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

	private int[] size(long id) {
		X11.XWindowAttributes a = new X11.XWindowAttributes();
		x.XGetWindowAttributes(display, new X11.Window(id), a);
		return new int[] {Math.max(1, a.width), Math.max(1, a.height)};
	}

	/** The innermost window at (`fx`, `fy`) of window `id`, and the point in its coordinates. */
	private record Target(X11.Window w, int x, int y) {}

	private Target target(long id, float fx, float fy) {
		int[] s = size(id);
		X11.Window w = new X11.Window(id);
		int px = Math.round(fx * s[0]), py = Math.round(fy * s[1]);
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
		return new Target(w, px, py);
	}

	/** The point (`fx`, `fy`) of window `id` in the coordinates of its inner window `w`. */
	private Target in(long id, X11.Window w, float fx, float fy) {
		int[] s = size(id);
		IntByReference ix = new IntByReference(), iy = new IntByReference();
		x.XTranslateCoordinates(display, new X11.Window(id), w, Math.round(fx * s[0]), Math.round(fy * s[1]), ix, iy, new X11.WindowByReference());
		return new Target(w, ix.getValue(), iy.getValue());
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
		sendButton(t, X11.ButtonPress, xButton);
		sendButton(t, X11.ButtonRelease, xButton);
		x.XFlush(display);
	}

	@Override
	public synchronized void scroll(long id, float fx, float fy, int notches) {
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

	@Override
	public synchronized void type(long id, String text) {
		for (int cp : text.codePoints().toArray()) {
			String sym = cp == ' ' ? "space" : cp < 128 && Character.isLetterOrDigit(cp) ? String.valueOf((char) Character.toLowerCase(cp)) : String.format("U%04X", cp);
			keysym(id, sym, Character.isUpperCase(cp) || SHIFTED.indexOf(cp) >= 0);
		}
		x.XFlush(display);
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

	@Override
	public synchronized void key(long id, String name) {
		String sym = switch (name) {
			case "enter" -> "Return";
			case "backspace" -> "BackSpace";
			case "tab" -> "Tab";
			case "escape" -> "Escape";
			case "left" -> "Left";
			case "right" -> "Right";
			case "up" -> "Up";
			case "down" -> "Down";
			default -> null;
		};
		if (sym != null) keysym(id, sym, false);
		x.XFlush(display);
	}
}
