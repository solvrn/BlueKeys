package app.bluekeys;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;

/**
 * Registers the phone as a Bluetooth HID keyboard using Android's BluetoothHidDevice API
 * (same low-level mechanism as BlueTouch).  The host device (Mac, PC, TV) sees the phone
 * exactly like a physical USB/BT keyboard — the OS never knows it is a phone.
 *
 * Life-cycle:
 *   1. Call register() whenever the app comes on screen — waits for the Bluetooth stack
 *      to hand us a proxy. Android cancels the registration as soon as the app leaves
 *      the foreground (onUnregistered fires), so it has to be redone on every return.
 *   2. When onRegistered() fires, call connect(device) to push a connection to the host.
 *   3. When onConnected() fires, call sendKey() from the typing thread.
 *   4. Call close() when done.
 *
 * HID report format (8 bytes, no report-ID prefix since none is in the descriptor):
 *   [0] modifier  (0x02 = Left Shift, 0x01 = Ctrl, etc.)
 *   [1] reserved  (always 0)
 *   [2] keycode   (USB HID Usage ID for the key)
 *   [3..7] 0      (up to 5 more simultaneous keys — unused here)
 */
public class HidKeyboard {

    // Standard USB HID descriptor for a simple keyboard (no mouse, no LEDs output report).
    // This is the exact byte sequence any kernel-level HID driver expects.
    private static final byte[] DESCRIPTOR = {
        (byte)0x05,(byte)0x01,  // Usage Page (Generic Desktop Controls)
        (byte)0x09,(byte)0x06,  // Usage (Keyboard)
        (byte)0xA1,(byte)0x01,  // Collection (Application)
        (byte)0x05,(byte)0x07,  //   Usage Page (Keyboard/Keypad)
        (byte)0x19,(byte)0xE0,  //   Usage Minimum (Left Control = 0xE0)
        (byte)0x29,(byte)0xE7,  //   Usage Maximum (Right GUI     = 0xE7)
        (byte)0x15,(byte)0x00,  //   Logical Minimum (0)
        (byte)0x25,(byte)0x01,  //   Logical Maximum (1)
        (byte)0x75,(byte)0x01,  //   Report Size (1 bit)
        (byte)0x95,(byte)0x08,  //   Report Count (8 bits = 8 modifier keys)
        (byte)0x81,(byte)0x02,  //   Input (Data, Variable, Absolute) → modifier byte
        (byte)0x95,(byte)0x01,  //   Report Count (1)
        (byte)0x75,(byte)0x08,  //   Report Size (8 bits)
        (byte)0x81,(byte)0x01,  //   Input (Constant) → reserved byte
        (byte)0x95,(byte)0x06,  //   Report Count (6 keycodes)
        (byte)0x75,(byte)0x08,  //   Report Size (8 bits each)
        (byte)0x15,(byte)0x00,  //   Logical Minimum (0)
        (byte)0x25,(byte)0x65,  //   Logical Maximum (101)
        (byte)0x05,(byte)0x07,  //   Usage Page (Keyboard/Keypad)
        (byte)0x19,(byte)0x00,  //   Usage Minimum (0)
        (byte)0x29,(byte)0x65,  //   Usage Maximum (101)
        (byte)0x81,(byte)0x00,  //   Input (Data, Array, Absolute) → 6-key report
        (byte)0xC0              // End Collection
    };

    private static final int REPORT_ID = 0; // no report ID in descriptor → send 0

    public interface Listener {
        void onRegistered();
        void onUnregistered();
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String message);
    }

    private final Context ctx;
    private final Listener listener;

    // Written on the main thread, read by the typing thread → volatile.
    private volatile BluetoothHidDevice proxy;
    private volatile BluetoothDevice   host;
    private volatile boolean  connected = false;
    private volatile boolean  registered = false;
    private boolean proxyRequested = false;  // waiting for onServiceConnected
    private boolean registerPending = false; // waiting for onAppStatusChanged

    public HidKeyboard(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    private static final BluetoothHidDeviceAppSdpSettings SDP = new BluetoothHidDeviceAppSdpSettings(
        "BlueKeys",
        "Bluetooth Keyboard",
        "BlueKeys",
        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        DESCRIPTOR
    );

    /** Step 1: obtain the HID Device proxy from the Bluetooth stack and register our descriptor. */
    public void register() {
        if (registered || registerPending || proxyRequested) return;
        if (proxy != null) { registerApp(); return; }

        BluetoothAdapter adapter = adapter();
        if (adapter == null) { listener.onError("No Bluetooth adapter"); return; }
        if (!adapter.isEnabled()) {
            listener.onError("Bluetooth is off — turn it on, then reopen BlueKeys");
            return;
        }
        proxyRequested = adapter.getProfileProxy(ctx, serviceListener, BluetoothProfile.HID_DEVICE);
    }

    public boolean isRegistered() {
        return registered;
    }

    private void registerApp() {
        registerPending = proxy.registerApp(SDP, null, null, ctx.getMainExecutor(), callback);
        // Only one app at a time can act as a Bluetooth keyboard/mouse.
        if (!registerPending) listener.onError(
            "Could not register as a keyboard — is another keyboard/mouse app running?");
    }

    private final BluetoothProfile.ServiceListener serviceListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile service) {
            proxyRequested = false;
            proxy = (BluetoothHidDevice) service;
            registerApp();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            proxyRequested = false;
            registerPending = false;
            proxy = null;
            registered = false;
            connected = false;
            host = null;
            // The Bluetooth stack dropped us (it restarts now and then). Without telling
            // the screen, it kept showing a stale "Connected" while nothing worked.
            listener.onUnregistered();
        }
    };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            registerPending = false;
            registered = reg;
            if (reg) {
                listener.onRegistered();
            } else {
                connected = false;
                host = null;
                listener.onUnregistered();
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device;
                connected = true;
                listener.onConnected(device.getName() != null
                    ? device.getName() : device.getAddress());
            } else if (state == BluetoothProfile.STATE_DISCONNECTED
                    || state == BluetoothProfile.STATE_DISCONNECTING) {
                connected = false;
                host = null;
                listener.onDisconnected();
            }
        }
    };

    /** Step 2: push a connection to a paired host device. The host must accept it. */
    public boolean connect(BluetoothDevice device) {
        if (proxy == null || !registered) {
            listener.onError("Not ready yet — keep BlueKeys open for a second, then try again");
            return false;
        }
        return proxy.connect(device);
    }

    /** The Mac/PC currently connected, or null. */
    public BluetoothDevice getHost() {
        return host;
    }

    public boolean isConnected() {
        BluetoothHidDevice p = proxy;
        BluetoothDevice    h = host;
        // Ask the stack too: our flag can miss a silently dropped link.
        return connected && p != null && h != null
            && p.getConnectionState(h) == BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * Minimum time between two reports. Android queues reports for the Bluetooth link,
     * which only sends a few per sniff interval (~10–15 ms); when the queue fills, the
     * stack silently drops reports (sendReport still returns true). A dropped press loses
     * a letter, a dropped release doubles or merges letters — so every report is paced.
     */
    private static final long DEFAULT_REPORT_GAP_MS = 5;
    private volatile long reportGapMs = DEFAULT_REPORT_GAP_MS;
    private long lastReportMs = 0; // only touched by the typing thread
    // Turbo state: the modifier/key the host currently thinks is held down.
    private byte heldMod = 0, heldKey = 0;

    public void setReportGapMs(long ms) { reportGapMs = Math.max(2, ms); }

    /**
     * Send one key press + release.
     * modifier: 0x00 = none, 0x02 = Left Shift, 0x01 = Left Ctrl …
     * keycode:  USB HID Usage ID (see KeyMap.java)
     * holdMs:   how long to hold the key before releasing (0 = as short as is safe)
     *
     * Call from the typing thread only (it sleeps).
     */
    public boolean sendKey(byte modifier, byte keycode, long holdMs)
            throws InterruptedException {
        // Copy the fields: a disconnect on the main thread can null them mid-call.
        BluetoothHidDevice p = proxy;
        BluetoothDevice    h = host;
        if (!connected || p == null || h == null) return false;

        // A key left down by sendKeyFast must be released first, or pressing the same
        // key again would look like "no change" to the host and be lost.
        if (!releaseAll()) return false;

        // Shift goes down in its own report first, like a real typist; some hosts read
        // a key and its modifier arriving in the same report as an unshifted key.
        if (modifier != 0 && !send(p, h, modifier, (byte) 0)) return false;
        if (!send(p, h, modifier, keycode)) return false;

        boolean released;
        try {
            if (holdMs > 0) Thread.sleep(holdMs);
        } finally {
            // Always release, even when Stop interrupts the hold — otherwise the host
            // thinks the key is still down and auto-repeats it.
            released = send(p, h, (byte) 0, (byte) 0);
        }
        return released;
    }

    /**
     * Turbo: one report per character. A report lists the keys that are down, so the
     * next key's press report also releases the previous key. Only a repeated key or a
     * modifier change needs an extra report. Call releaseAll() when done typing.
     */
    public boolean sendKeyFast(byte modifier, byte keycode) {
        BluetoothHidDevice p = proxy;
        BluetoothDevice    h = host;
        if (!connected || p == null || h == null) return false;

        if (modifier == heldMod && keycode == heldKey) {      // same key twice: release between
            if (!send(p, h, (byte) 0, (byte) 0)) return false;
            heldMod = 0; heldKey = 0;
        }
        if (modifier != heldMod) {                            // modifier change in its own report
            if (!send(p, h, modifier, (byte) 0)) return false;
            heldMod = modifier; heldKey = 0;
        }
        if (!send(p, h, modifier, keycode)) return false;
        heldKey = keycode;
        return true;
    }

    /** Releases whatever sendKeyFast left down. Safe to call anytime. */
    public boolean releaseAll() {
        if (heldMod == 0 && heldKey == 0) return true;
        BluetoothHidDevice p = proxy;
        BluetoothDevice    h = host;
        heldMod = 0; heldKey = 0;
        if (p == null || h == null) return false;
        return send(p, h, (byte) 0, (byte) 0);
    }

    private boolean send(BluetoothHidDevice p, BluetoothDevice h, byte modifier, byte keycode) {
        // SystemClock.sleep ignores Stop's interrupt, so the final release is never skipped.
        long wait = lastReportMs + reportGapMs - SystemClock.uptimeMillis();
        if (wait > 0) SystemClock.sleep(wait);
        byte[] report = new byte[8];
        report[0] = modifier;
        report[2] = keycode;
        boolean ok = p.sendReport(h, REPORT_ID, report);
        lastReportMs = SystemClock.uptimeMillis();
        return ok;
    }

    /** Rough time to type one character in turbo (about one report, two with Shift). */
    public static long minMsPerChar(byte modifier) {
        return (modifier != 0 ? 2 : 1) * DEFAULT_REPORT_GAP_MS;
    }

    public void disconnect() {
        if (proxy != null && host != null) proxy.disconnect(host);
    }

    public void close() {
        disconnect();
        if (proxy != null) {
            proxy.unregisterApp();
            BluetoothAdapter adapter = adapter();
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
            proxy = null;
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        return bm != null ? bm.getAdapter() : null;
    }
}
