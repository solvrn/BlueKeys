package app.bluekeys;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.text.method.ScrollingMovementMethod;
import android.content.Intent;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity implements HidKeyboard.Listener {

    private static final int REQ_BT_PERMS = 1;

    private TextView    status;
    private Button      connectBtn;
    private TextView    textInput;
    private TextView    charCount;
    private SeekBar     wpmSlider;
    private TextView    wpmLabel;
    private SeekBar     naturalSlider;
    private TextView    naturalLabel;
    private Switch      turboSwitch;
    private Switch      codeSwitch;
    private RadioGroup  osGroup;
    private TextView    turboWarning;
    private Button      typeBtn;
    private ProgressBar progress;
    private TextView    progressLabel;

    private HidKeyboard keyboard;
    private Typer  typer;
    private Thread      typingThread;
    private BluetoothDevice lastHost; // reconnected automatically after re-registering
    private String      hostName = "";
    private boolean     typing = false;
    private boolean     resumed = false;
    private boolean     typeWhenConnected = false; // Type it was tapped while reconnecting
    private SharedPreferences prefs;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ── lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        status        = findViewById(R.id.status);
        connectBtn    = findViewById(R.id.connectBtn);
        textInput     = findViewById(R.id.textInput);
        charCount     = findViewById(R.id.charCount);
        wpmSlider     = findViewById(R.id.wpmSlider);
        wpmLabel      = findViewById(R.id.wpmLabel);
        naturalSlider = findViewById(R.id.naturalSlider);
        naturalLabel  = findViewById(R.id.naturalLabel);
        turboSwitch   = findViewById(R.id.turboSwitch);
        codeSwitch    = findViewById(R.id.codeSwitch);
        osGroup       = findViewById(R.id.osGroup);
        turboWarning  = findViewById(R.id.turboWarning);
        typeBtn       = findViewById(R.id.typeBtn);
        progress      = findViewById(R.id.progress);
        progressLabel = findViewById(R.id.progressLabel);

        keyboard = new HidKeyboard(this, this);
        typer    = new Typer(keyboard);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        textInput.setMovementMethod(new ScrollingMovementMethod()); // long text scrolls

        wpmSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) {
                wpmLabel.setText((p + 20) + " WPM");
            }
        });

        naturalSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean b) {
                naturalLabel.setText(p + "%");
            }
        });

        turboSwitch.setOnCheckedChangeListener((btn, on) -> {
            turboWarning.setVisibility(on ? View.VISIBLE : View.GONE);
            wpmSlider.setEnabled(!on);
            naturalSlider.setEnabled(!on);
            wpmSlider.setAlpha(on ? 0.4f : 1.0f);
            naturalSlider.setAlpha(on ? 0.4f : 1.0f);
        });

        // Restore last session's settings (the listeners above update the labels).
        wpmSlider.setProgress(prefs.getInt("wpm", 60) - 20);
        naturalSlider.setProgress(prefs.getInt("natural", 65));
        turboSwitch.setChecked(prefs.getBoolean("turbo", false));
        codeSwitch.setChecked(prefs.getBoolean("code", false));

        // Remember Mac vs Windows separately for each computer.
        osGroup.setOnCheckedChangeListener((g, id) -> {
            if (lastHost != null) prefs.edit()
                .putBoolean("mac_" + lastHost.getAddress(), id == R.id.osMac).apply();
        });

        findViewById(R.id.pasteBtn).setOnClickListener(v -> pasteClipboard());
        findViewById(R.id.clearBtn).setOnClickListener(v -> setText(""));
        connectBtn.setOnClickListener(v -> showDevicePicker());
        typeBtn.setOnClickListener(v -> { if (typing) stopTyping(); else startTyping(); });

        setTypingState(false);
        setText("");
        handleIntent(getIntent());

        Button updateBtn = findViewById(R.id.updateBtn);
        updateBtn.setOnClickListener(v -> startActivity(
            new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UpdateChecker.DOWNLOAD_URL))));
        UpdateChecker.pingOncePerDay(this, prefs);
        UpdateChecker.checkOncePerDay(this, prefs, newVersion -> ui.post(() -> {
            updateBtn.setText("Update available: " + newVersion + " — tap to download");
            updateBtn.setVisibility(View.VISIBLE);
        }));

        askBluetoothPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Testing hook: adb shell am start -n app.bluekeys/.MainActivity --es text "…" */
    private void handleIntent(Intent intent) {
        String t = intent != null ? intent.getStringExtra("text") : null;
        if (t != null && !typing) setText(t);
        int gap = intent != null ? intent.getIntExtra("gap", 0) : 0;   // testing: report gap in ms
        if (gap > 0) keyboard.setReportGapMs(gap);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        prefs.edit()
            .putInt("wpm", wpmSlider.getProgress() + 20)
            .putInt("natural", naturalSlider.getProgress())
            .putBoolean("turbo", turboSwitch.isChecked())
            .putBoolean("code", codeSwitch.isChecked())
            .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Android drops the keyboard registration whenever BlueKeys leaves the screen
        // (even behind the permission popup), so register again on every return.
        if (hasBtPermission()) keyboard.register();
    }

    @Override
    protected void onDestroy() {
        stopTyping();
        if (typingThread != null) {
            // Give the typing thread a moment to send its final key-release.
            try { typingThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (keyboard != null) keyboard.close();
        super.onDestroy();
    }

    // ── permissions ───────────────────────────────────────────────────────────

    private boolean hasBtPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Explains why before Android's own prompt appears. After Android stops showing its
     * prompt (denied twice), the only way back is the app's settings page, so go there.
     */
    private void askBluetoothPermission() {
        if (hasBtPermission()) return;
        boolean blocked = prefs.getBoolean("btAsked", false)
            && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Allow Bluetooth?")
            .setMessage("BlueKeys needs the \"Nearby devices\" permission so your phone can "
                + "pair with your computer and work as its keyboard.\n\n"
                + "It is used only for that. Nothing else on your phone is accessed."
                + (blocked ? "\n\nTurn on \"Nearby devices\" under Permissions." : ""))
            .setNegativeButton("Not now", (d, w) ->
                status.setText("Tap Connect to allow Bluetooth"));
        if (blocked) {
            b.setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", getPackageName(), null))));
        } else {
            b.setPositiveButton("Continue", (d, w) -> {
                prefs.edit().putBoolean("btAsked", true).apply();
                requestPermissions(new String[]{ Manifest.permission.BLUETOOTH_CONNECT },
                                   REQ_BT_PERMS);
            });
        }
        b.show();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        // When granted, onResume (which runs right after this) does the registering.
        if (req == REQ_BT_PERMS && !hasBtPermission()) {
            status.setText("Tap Connect to allow Bluetooth");
        }
    }

    // ── device picker ─────────────────────────────────────────────────────────

    private void showDevicePicker() {
        if (!hasBtPermission()) {
            askBluetoothPermission();
            return;
        }

        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) { toast("No Bluetooth on this device"); return; }
        if (!adapter.isEnabled()) { toast("Please turn Bluetooth on first"); return; }

        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("No paired devices")
                .setMessage("Pair your computer (Mac/PC) with this phone in Bluetooth settings first, "
                    + "then come back and tap Connect.")
                .setPositiveButton("OK", null).show();
            return;
        }

        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            String n = devices.get(i).getName();
            names[i] = (n != null && !n.isEmpty()) ? n : devices.get(i).getAddress();
        }

        new AlertDialog.Builder(this)
            .setTitle("Connect to which device?")
            .setItems(names, (d, i) -> {
                lastHost = devices.get(i);
                hostName = names[i];
                prefs.edit().putString("host", lastHost.getAddress()).apply();
                status.setText("Connecting to " + names[i] + "…");
                boolean ok = keyboard.connect(devices.get(i));
                if (!ok) status.setText("Could not start connection — is the app registered?");
            })
            .show();
    }

    // ── text ──────────────────────────────────────────────────────────────────

    private void pasteClipboard() {
        if (typing) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        CharSequence t = (clip != null && clip.getItemCount() > 0)
            ? clip.getItemAt(0).coerceToText(this) : null;
        if (t == null || t.length() == 0) {
            toast("Clipboard is empty — copy some text first.");
            return;
        }
        setText(t.toString());
    }

    private void setText(String t) {
        if (typing) return;
        textInput.setText(t);
        textInput.scrollTo(0, 0);
        charCount.setText(t.isEmpty() ? "" : t.length() + " characters");
        progressLabel.setText("");
    }

    // ── typing ────────────────────────────────────────────────────────────────

    private void startTyping() {
        typeWhenConnected = false;
        if (!keyboard.isConnected()) {
            if (lastHost == null) lastHost = savedHost();
            if (lastHost == null) {
                toast("Not connected — tap Connect and choose your Mac or PC.");
                return;
            }
            // Reconnect to the last Mac/PC and start typing as soon as it's back.
            typeWhenConnected = true;
            status.setText("Reconnecting to " + hostName + " — typing starts when connected…");
            if (keyboard.isRegistered()) keyboard.connect(lastHost);
            else keyboard.register(); // onRegistered() then reconnects
            return;
        }
        String text = KeyMap.toTypeable(textInput.getText().toString());
        if (text.isEmpty()) {
            toast("Nothing to type — tap Paste first.");
            return;
        }

        int skipped = 0;
        for (int i = 0; i < text.length(); i++) {
            if (KeyMap.get(text.charAt(i)) == null) skipped++;
        }
        if (skipped > 0) {
            toast(skipped + " character(s) like emoji or non-English letters can't be "
                + "typed on a US keyboard and will be skipped.");
        }

        boolean turbo      = turboSwitch.isChecked();
        int    wpm         = wpmSlider.getProgress() + 20;
        double naturalness = naturalSlider.getProgress() / 100.0;
        typer.setParams(wpm, naturalness, turbo, codeSwitch.isChecked(),
                        osGroup.getCheckedRadioButtonId() != R.id.osWin);

        startService(new Intent(this, TypingService.class));
        setTypingState(true);
        progress.setMax(text.length());
        progress.setProgress(0);

        long etaMs = 0;
        for (int i = 0; i < text.length(); i++) {
            byte[] m = KeyMap.get(text.charAt(i));
            if (m != null) etaMs += turbo ? HidKeyboard.minMsPerChar(m[0]) : 12000 / wpm;
        }
        int etaSec = (int) Math.ceil(etaMs / 1000.0);
        progressLabel.setText("ETA ~" + formatTime(etaSec));

        final long startMs = System.currentTimeMillis();
        final int totalChars = text.length();
        typingThread = new Thread(() -> {
            try {
                boolean ok = typer.typeText(text, (done, total) -> ui.post(() -> {
                    progress.setProgress(done);
                    long elapsed = System.currentTimeMillis() - startMs;
                    if (done > 0) {
                        int remainSec = (int) ((elapsed * (totalChars - done)) / (done * 1000L));
                        progressLabel.setText(done + " / " + total + "  ~" + formatTime(remainSec) + " left");
                    }
                }));
                ui.post(() -> onTypingFinished(ok ? "Done."
                    : keyboard.isConnected() ? "Stopped." : "Stopped — connection lost."));
            } catch (InterruptedException e) {
                ui.post(() -> onTypingFinished("Stopped."));
            }
        });
        typingThread.start();
    }

    private void stopTyping() {
        if (typer != null) typer.cancelled = true;
        if (typingThread != null) typingThread.interrupt();
    }

    private void onTypingFinished(String message) {
        setTypingState(false);
        progressLabel.setText(message);
        stopService(new Intent(this, TypingService.class));
    }

    private void setTypingState(boolean typing) {
        this.typing = typing;
        typeBtn.setText(typing ? "Stop" : "Type it");
        typeBtn.setBackgroundColor(typing ? 0xFFB71C1C : 0xFF1565C0);
        progress.setVisibility(typing ? View.VISIBLE : View.INVISIBLE);
        updateKeepScreenOn();
    }

    /**
     * A screen lock sends BlueKeys to the background, which ends keyboard mode and drops
     * the Mac — so keep the screen awake while connected (and while typing).
     */
    private void updateKeepScreenOn() {
        if (typing || keyboard.isConnected())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ── HidKeyboard.Listener ──────────────────────────────────────────────────

    @Override public void onRegistered() {
        ui.post(() -> {
            if (lastHost == null) lastHost = savedHost();
            if (lastHost != null && keyboard.connect(lastHost)) {
                status.setText("Reconnecting to " + hostName + "…");
            } else {
                status.setText("Ready — tap Connect to choose your Mac or PC.");
            }
        });
    }

    @Override public void onUnregistered() {
        ui.post(() -> {
            status.setText("Keyboard mode paused — BlueKeys must stay on screen.");
            updateKeepScreenOn();
            // Dropped while still on screen (Bluetooth restart): sign up again by itself.
            if (resumed) ui.postDelayed(() -> { if (resumed) keyboard.register(); }, 1500);
        });
    }

    @Override public void onConnected(String name) {
        ui.post(() -> {
            hostName = name;
            status.setText("Connected to " + name);
            updateKeepScreenOn();
            // Remember the host even when the Mac started the connection, so a later
            // re-registration (app switch, screen lock) reconnects to it automatically.
            BluetoothDevice h = keyboard.getHost();
            if (h != null) {
                lastHost = h;
                prefs.edit().putString("host", h.getAddress()).apply();
                boolean mac = prefs.getBoolean("mac_" + h.getAddress(), guessMac(name));
                osGroup.check(mac ? R.id.osMac : R.id.osWin); // also saves it
            }
            // Give the Mac a moment to set the keyboard up, or the first keys get lost.
            if (typeWhenConnected) ui.postDelayed(() -> {
                if (typeWhenConnected && !typing) startTyping();
            }, 800);
        });
    }

    @Override public void onDisconnected() {
        ui.post(() -> {
            if (typeWhenConnected) toast("Couldn't reconnect — is the Mac's Bluetooth on?");
            typeWhenConnected = false;
            status.setText("Disconnected");
            updateKeepScreenOn();
        });
    }

    @Override public void onError(String message) {
        ui.post(() -> { status.setText("Error: " + message); toast(message); });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The Mac/PC picked last time, so a fresh launch reconnects without the picker. */
    private BluetoothDevice savedHost() {
        String addr = prefs.getString("host", null);
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (addr == null || adapter == null) return null;
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            if (d.getAddress().equals(addr)) {
                hostName = d.getName() != null ? d.getName() : addr;
                return d;
            }
        }
        return null; // unpaired since — fall back to the picker
    }

    /** First guess for a new computer, from its Bluetooth name; one tap fixes it. */
    private static boolean guessMac(String name) {
        String n = name == null ? "" : name.toLowerCase();
        return n.contains("mac") || n.contains("apple") || n.contains("imac");
    }

    private static String formatTime(int sec) {
        if (sec < 1) return "<1s";
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m " + (sec % 60) + "s";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
