#!/usr/bin/env python3
"""Aerion's Wayland window picture: asks the desktop (xdg-desktop-portal ScreenCast) for one
window or screen, then sends its picture to the game, already made small, on standard output.

Run by the Aerion mod as `python3 -I portal-capture.py WIDTH FPS [RESTORE_TOKEN]`. It stops when
standard input closes (the game ended or let go of the window) or when the person stops sharing.

Messages on standard output: one byte for the kind, four bytes (big-endian) for the length, then
the body.
  T  the restore token (text): the same choice again later without asking
  F  a picture: width and height (four bytes each), then width*height*4 bytes, B G R x
  E  why it can't work (text), then it ends
  S  sharing stopped
For tests, AERION_PORTAL_NODE=<PipeWire node> skips the portal and reads that node.
"""
import os
import struct
import sys
import threading

out = sys.stdout.buffer
lock = threading.Lock()


def send(kind, body=b""):
    with lock:
        try:
            out.write(kind + struct.pack(">I", len(body)) + body)
            out.flush()
        except (BrokenPipeError, ValueError):
            os._exit(0)


def fail(text):
    send(b"E", text.encode())
    os._exit(1)


try:
    import gi

    gi.require_version("Gst", "1.0")
    gi.require_version("GstApp", "1.0")
    from gi.repository import Gio, GLib, Gst, GstApp  # noqa: F401
except Exception as e:  # missing python3-gi or GStreamer
    fail("missing:gi " + str(e))

Gst.init(None)
if Gst.ElementFactory.find("pipewiresrc") is None:
    fail("missing:pipewire")

WIDTH = max(64, min(1920, int(sys.argv[1]) if len(sys.argv) > 1 else 960))
FPS = max(1, min(30, int(sys.argv[2]) if len(sys.argv) > 2 else 12))
TOKEN = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else None

loop = GLib.MainLoop()
pipeline = None


def stop(*_):
    if pipeline is not None:
        pipeline.set_state(Gst.State.NULL)
    loop.quit()


def watch_stdin():
    # The game closed the pipe: nothing more to do.
    try:
        while sys.stdin.buffer.read(4096):
            pass
    except Exception:
        pass
    GLib.idle_add(stop)


def on_sample(sink):
    sample = sink.emit("pull-sample")
    if sample is None:
        return Gst.FlowReturn.EOS
    caps = sample.get_caps().get_structure(0)
    w, h = caps.get_value("width"), caps.get_value("height")
    buf = sample.get_buffer()
    ok, info = buf.map(Gst.MapFlags.READ)
    if ok:
        try:
            data = bytes(info.data)
        finally:
            buf.unmap(info)
        # Rows may be padded: keep exactly width*4 bytes of each.
        stride = len(data) // h if h else 0
        if stride != w * 4 and stride >= w * 4:
            data = b"".join(data[r * stride : r * stride + w * 4] for r in range(h))
        if len(data) == w * h * 4:
            send(b"F", struct.pack(">II", w, h) + data)
    return Gst.FlowReturn.OK


def on_bus(_bus, msg):
    if msg.type == Gst.MessageType.EOS:
        send(b"S")
        stop()
    elif msg.type == Gst.MessageType.ERROR:
        err, _ = msg.parse_error()
        send(b"E", ("stream:" + str(err)).encode())
        stop()


def play(source):
    global pipeline
    desc = (
        source + " do-timestamp=true keepalive-time=1000 always-copy=true"
        " ! videorate drop-only=true max-rate=%d ! videoconvert ! videoscale"
        " ! video/x-raw,format=BGRx,width=%d,pixel-aspect-ratio=1/1"
        " ! appsink name=sink emit-signals=true max-buffers=1 drop=true sync=false"
    ) % (FPS, WIDTH)
    pipeline = Gst.parse_launch(desc)
    pipeline.get_by_name("sink").connect("new-sample", on_sample)
    bus = pipeline.get_bus()
    bus.add_signal_watch()
    bus.connect("message", on_bus)
    pipeline.set_state(Gst.State.PLAYING)


# --- The portal ----------------------------------------------------------------------------------

PORTAL, PATH = "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop"
SC = "org.freedesktop.portal.ScreenCast"
bus = None
counter = [0]


def token():
    counter[0] += 1
    return "aerion%d_%d" % (os.getpid(), counter[0])


def request(method, args, options, then):
    """Calls a portal method that answers later with a Response signal on its request object."""
    handle = token()
    options["handle_token"] = GLib.Variant("s", handle)
    sender = bus.get_unique_name()[1:].replace(".", "_")
    path = "%s/request/%s/%s" % (PATH, sender, handle)

    def on_response(_c, _s, _p, _i, _n, params):
        bus.signal_unsubscribe(sub)
        code, results = params.unpack()
        if code != 0:
            # 1: the person said no; 2: something else.
            fail("cancelled" if code == 1 else "portal:%d" % code)
        then(results)

    sub = bus.signal_subscribe(PORTAL, "org.freedesktop.portal.Request", "Response", path, None, Gio.DBusSignalFlags.NONE, on_response)
    bus.call_sync(PORTAL, PATH, SC, method, GLib.Variant("(" + "".join(a[0] for a in args) + "a{sv})", tuple(a[1] for a in args) + (options,)), None, Gio.DBusCallFlags.NONE, -1, None)


def start_portal():
    global bus
    try:
        bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        version = bus.call_sync(PORTAL, PATH, "org.freedesktop.DBus.Properties", "Get", GLib.Variant("(ss)", (SC, "version")), None, Gio.DBusCallFlags.NONE, -1, None).unpack()[0]
    except Exception as e:
        fail("missing:portal " + str(e))
    session = {}

    def created(results):
        session["h"] = results["session_handle"]
        opts = {"types": GLib.Variant("u", 3), "multiple": GLib.Variant("b", False)}
        try:
            modes = bus.call_sync(PORTAL, PATH, "org.freedesktop.DBus.Properties", "Get", GLib.Variant("(ss)", (SC, "AvailableCursorModes")), None, Gio.DBusCallFlags.NONE, -1, None).unpack()[0]
            if modes & 2:
                opts["cursor_mode"] = GLib.Variant("u", 2)
        except Exception:
            pass
        if version >= 4:
            # Remembered until revoked, so a saved panel comes back without asking.
            opts["persist_mode"] = GLib.Variant("u", 2)
            if TOKEN:
                opts["restore_token"] = GLib.Variant("s", TOKEN)
        request("SelectSources", [("o", session["h"])], opts, selected)

    def selected(_results):
        request("Start", [("o", session["h"]), ("s", "")], {}, started)

    def started(results):
        streams = results.get("streams") or []
        if not streams:
            fail("cancelled")
        if results.get("restore_token"):
            send(b"T", results["restore_token"].encode())
        node = streams[0][0]
        reply, fds = bus.call_with_unix_fd_list_sync(PORTAL, PATH, SC, "OpenPipeWireRemote", GLib.Variant("(oa{sv})", (session["h"], {})), GLib.VariantType("(h)"), Gio.DBusCallFlags.NONE, -1, None, None)
        fd = fds.get(reply.unpack()[0])
        play("pipewiresrc fd=%d path=%d" % (fd, node))

    request("CreateSession", [], {"session_handle_token": GLib.Variant("s", token())}, created)


threading.Thread(target=watch_stdin, daemon=True).start()
test_node = os.environ.get("AERION_PORTAL_NODE")
if test_node:
    # A stand-in for the token the portal would give, so restoring can be tried too.
    send(b"T", ("test-" + test_node).encode())
    GLib.idle_add(lambda: play("pipewiresrc target-object=%s" % test_node) and False)
else:
    GLib.idle_add(lambda: start_portal() and False)
loop.run()
# A thread still reading standard input would hold up a normal exit.
os._exit(0)
