package pl.aerion.mod.mirror;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows of a Windows desktop. Pictures come from PrintWindow, which also reads covered windows
 * (not minimised ones). Input is posted as window messages: classic programs take it, many
 * modern ones (Chromium, Electron) read input another way and may not react.
 */
final class Win32Source implements WindowSource {
	private static final int PW_RENDERFULLCONTENT = 2;
	private static final int WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202, WM_RBUTTONDOWN = 0x0204, WM_RBUTTONUP = 0x0205;
	private static final int WM_MOUSEWHEEL = 0x020A, WM_CHAR = 0x0102, WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101;
	private final User32 u = User32.INSTANCE;

	/** What jna-platform's User32 lacks. */
	private interface User32Extra extends com.sun.jna.Library {
		User32Extra INSTANCE = Native.load("user32", User32Extra.class, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

		boolean IsIconic(WinDef.HWND hwnd);

		boolean DrawIconEx(WinDef.HDC hdc, int x, int y, WinDef.HICON icon, int w, int h, int step, com.sun.jna.Pointer brush, int flags);
	}
	private final int ownPid = Kernel32.INSTANCE.GetCurrentProcessId();

	@Override
	public List<Win> list() {
		List<Win> out = new ArrayList<>();
		u.EnumWindows((hwnd, data) -> {
			if (!u.IsWindowVisible(hwnd)) return true;
			IntByReference pid = new IntByReference();
			u.GetWindowThreadProcessId(hwnd, pid);
			if (pid.getValue() == ownPid) return true;
			char[] title = new char[512];
			int n = u.GetWindowText(hwnd, title, title.length);
			if (n == 0) return true;
			WinDef.RECT r = new WinDef.RECT();
			u.GetClientRect(hwnd, r);
			if (r.right - r.left < 80 || r.bottom - r.top < 60) return true;
			char[] cls = new char[256];
			int c = u.GetClassName(hwnd, cls, cls.length);
			out.add(new Win(Pointer2.of(hwnd), new String(title, 0, n), new String(cls, 0, c)));
			return true;
		}, null);
		return out;
	}

	private static WinDef.HWND hwnd(long id) {
		return new WinDef.HWND(new com.sun.jna.Pointer(id));
	}

	@Override
	public Frame capture(long id, int maxWidth) {
		WinDef.HWND h = hwnd(id);
		if (User32Extra.INSTANCE.IsIconic(h)) return null;
		WinDef.RECT r = new WinDef.RECT();
		if (!u.GetClientRect(h, r)) return null;
		int w = r.right - r.left, ht = r.bottom - r.top;
		if (w <= 0 || ht <= 0) return null;
		WinDef.HDC screen = u.GetDC(h);
		WinDef.HDC mem = GDI32.INSTANCE.CreateCompatibleDC(screen);
		WinDef.HBITMAP bmp = GDI32.INSTANCE.CreateCompatibleBitmap(screen, w, ht);
		var old = GDI32.INSTANCE.SelectObject(mem, bmp);
		try {
			// The client area only (flag 1), drawn even when covered.
			if (!u.PrintWindow(h, mem, PW_RENDERFULLCONTENT | 1)) return null;
			WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
			info.bmiHeader.biWidth = w;
			info.bmiHeader.biHeight = -ht;
			info.bmiHeader.biPlanes = 1;
			info.bmiHeader.biBitCount = 32;
			info.bmiHeader.biCompression = WinGDI.BI_RGB;
			Memory buf = new Memory((long) w * ht * 4);
			if (GDI32.INSTANCE.GetDIBits(mem, bmp, 0, ht, buf, info, WinGDI.DIB_RGB_COLORS) == 0) return null;
			int[] px = buf.getIntArray(0, w * ht);
			for (int i = 0; i < px.length; i++) px[i] |= 0xFF000000;
			return WindowSource.fit(w, ht, px, maxWidth);
		} finally {
			GDI32.INSTANCE.SelectObject(mem, old);
			GDI32.INSTANCE.DeleteObject(bmp);
			GDI32.INSTANCE.DeleteDC(mem);
			u.ReleaseDC(h, screen);
		}
	}

	@Override
	public long pid(long id) {
		IntByReference pid = new IntByReference();
		u.GetWindowThreadProcessId(hwnd(id), pid);
		return pid.getValue();
	}

	private static final int WM_GETICON = 0x007F, ICON_BIG = 1, ICON_SMALL2 = 2, GCLP_HICON = -14, DI_NORMAL = 3, ICON_SIZE = 32;

	/** The window's icon (asked of the window, else its class), drawn into 32×32 pixels. Untested. */
	@Override
	public Frame icon(long id) {
		WinDef.HWND h = hwnd(id);
		long handle = 0;
		for (int kind : new int[] {ICON_BIG, ICON_SMALL2}) {
			WinDef.DWORDByReference result = new WinDef.DWORDByReference();
			u.SendMessageTimeout(h, WM_GETICON, new WinDef.WPARAM(kind), new WinDef.LPARAM(0), WinUser.SMTO_ABORTIFHUNG, 100, result);
			handle = result.getValue().longValue();
			if (handle != 0) break;
		}
		if (handle == 0) handle = u.GetClassLongPtr(h, GCLP_HICON).longValue();
		if (handle == 0) return null;
		WinDef.HICON icon = new WinDef.HICON(new com.sun.jna.Pointer(handle));
		WinDef.HDC mem = GDI32.INSTANCE.CreateCompatibleDC(null);
		WinGDI.BITMAPINFO info = new WinGDI.BITMAPINFO();
		info.bmiHeader.biWidth = ICON_SIZE;
		info.bmiHeader.biHeight = -ICON_SIZE;
		info.bmiHeader.biPlanes = 1;
		info.bmiHeader.biBitCount = 32;
		info.bmiHeader.biCompression = WinGDI.BI_RGB;
		com.sun.jna.ptr.PointerByReference bits = new com.sun.jna.ptr.PointerByReference();
		WinDef.HBITMAP bmp = GDI32.INSTANCE.CreateDIBSection(mem, info, WinGDI.DIB_RGB_COLORS, bits, null, 0);
		if (bmp == null || bits.getValue() == null) {
			GDI32.INSTANCE.DeleteDC(mem);
			return null;
		}
		var old = GDI32.INSTANCE.SelectObject(mem, bmp);
		try {
			if (!User32Extra.INSTANCE.DrawIconEx(mem, 0, 0, icon, ICON_SIZE, ICON_SIZE, 0, null, DI_NORMAL)) return null;
			int[] px = bits.getValue().getIntArray(0, ICON_SIZE * ICON_SIZE);
			boolean alpha = false;
			for (int p : px) alpha |= (p >>> 24) != 0;
			// Old icons without an alpha channel: whatever was drawn is opaque.
			if (!alpha) for (int i = 0; i < px.length; i++) if ((px[i] & 0xFFFFFF) != 0) px[i] |= 0xFF000000;
			return new Frame(ICON_SIZE, ICON_SIZE, px);
		} finally {
			GDI32.INSTANCE.SelectObject(mem, old);
			GDI32.INSTANCE.DeleteObject(bmp);
			GDI32.INSTANCE.DeleteDC(mem);
		}
	}

	private WinDef.LPARAM at(long id, float fx, float fy) {
		WinDef.RECT r = new WinDef.RECT();
		u.GetClientRect(hwnd(id), r);
		int x = Math.round(fx * (r.right - r.left)), y = Math.round(fy * (r.bottom - r.top));
		return new WinDef.LPARAM((y << 16) | (x & 0xFFFF));
	}

	private static final int WM_MOUSEMOVE = 0x0200, MK_LBUTTON = 1;

	@Override
	public void press(long id, float fx, float fy, int button) {
		boolean right = button == 1;
		u.PostMessage(hwnd(id), right ? WM_RBUTTONDOWN : WM_LBUTTONDOWN, new WinDef.WPARAM(right ? 2 : 1), at(id, fx, fy));
	}

	@Override
	public void release(long id, float fx, float fy, int button) {
		boolean right = button == 1;
		u.PostMessage(hwnd(id), right ? WM_RBUTTONUP : WM_LBUTTONUP, new WinDef.WPARAM(0), at(id, fx, fy));
	}

	@Override
	public void move(long id, float fx, float fy, boolean held) {
		u.PostMessage(hwnd(id), WM_MOUSEMOVE, new WinDef.WPARAM(held ? MK_LBUTTON : 0), at(id, fx, fy));
	}

	@Override
	public void scroll(long id, float fx, float fy, int notches) {
		// The wheel message takes screen coordinates.
		WinDef.RECT r = new WinDef.RECT();
		u.GetWindowRect(hwnd(id), r);
		WinDef.RECT c = new WinDef.RECT();
		u.GetClientRect(hwnd(id), c);
		int x = r.left + Math.round(fx * (c.right - c.left)), y = r.top + Math.round(fy * (c.bottom - c.top));
		u.PostMessage(hwnd(id), WM_MOUSEWHEEL, new WinDef.WPARAM((long) (notches * 120) << 16), new WinDef.LPARAM((y << 16) | (x & 0xFFFF)));
	}

	@Override
	public void type(long id, String text) {
		for (char ch : text.toCharArray()) u.PostMessage(hwnd(id), WM_CHAR, new WinDef.WPARAM(ch), new WinDef.LPARAM(1));
	}

	@Override
	public void focusGame() {
		// The game's own window: the biggest visible one of this process.
		WinDef.HWND[] best = {null};
		int[] area = {0};
		u.EnumWindows((hwnd, data) -> {
			if (!u.IsWindowVisible(hwnd)) return true;
			IntByReference pid = new IntByReference();
			u.GetWindowThreadProcessId(hwnd, pid);
			if (pid.getValue() != ownPid) return true;
			WinDef.RECT r = new WinDef.RECT();
			u.GetWindowRect(hwnd, r);
			int a = (r.right - r.left) * (r.bottom - r.top);
			if (a > area[0]) {
				area[0] = a;
				best[0] = hwnd;
			}
			return true;
		}, null);
		if (best[0] != null) u.SetForegroundWindow(best[0]);
	}

	@Override
	public void key(long id, String name) {
		int vk = switch (name) {
			case "enter" -> 0x0D;
			case "backspace" -> 0x08;
			case "tab" -> 0x09;
			case "escape" -> 0x1B;
			case "left" -> 0x25;
			case "up" -> 0x26;
			case "right" -> 0x27;
			case "down" -> 0x28;
			default -> 0;
		};
		if (vk == 0) return;
		u.PostMessage(hwnd(id), WM_KEYDOWN, new WinDef.WPARAM(vk), new WinDef.LPARAM(1));
		if (vk == 0x0D || vk == 0x08 || vk == 0x09 || vk == 0x1B) u.PostMessage(hwnd(id), WM_CHAR, new WinDef.WPARAM(vk), new WinDef.LPARAM(1));
		u.PostMessage(hwnd(id), WM_KEYUP, new WinDef.WPARAM(vk), new WinDef.LPARAM(0xC0000001L));
	}

	/** A window handle as a number, and back. */
	private static final class Pointer2 {
		static long of(WinDef.HWND h) {
			return com.sun.jna.Pointer.nativeValue(h.getPointer());
		}
	}

	static {
		// Load early so a missing library fails here, not on first use.
		Native.getDefaultStringEncoding();
	}
}
