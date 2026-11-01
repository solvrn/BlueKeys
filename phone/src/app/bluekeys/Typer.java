package app.bluekeys;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Types text through HidKeyboard with timing that matches a real human typist.
 *
 * What makes it human:
 *   1. Log-normal inter-key intervals — humans don't type at a fixed beat; the
 *      distribution of pauses between keys is right-skewed (a few long gaps drag
 *      the average up, most gaps are shorter than average).
 *   2. Digraph speed-up — common letter pairs (th, he, er …) are "rolled" by
 *      adjacent fingers and arrive faster than random pairs.
 *   3. Shift delay — pressing Shift before a capital or symbol takes a little extra
 *      time because a second finger has to reach.
 *   4. Punctuation micro-pause — typists slow down slightly after a period or comma.
 *   5. Key hold duration — keys are held for a short random time (not instantaneous).
 *
 * All of these are parameterised by two user controls:
 *   wpm         — target speed (words per minute, 1 word = 5 chars)
 *   naturalness — 0.0 = robotic uniform timing, 1.0 = maximum human variation
 */
public class Typer {

    // Common English digraphs typed faster because fingers "roll" onto the next key.
    // Values are speed multipliers: 0.72 means 28% faster than the base interval.
    private static final Map<String, Double> DIGRAPH_FACTOR = new HashMap<>();
    static {
        DIGRAPH_FACTOR.put("th", 0.72); DIGRAPH_FACTOR.put("he", 0.76);
        DIGRAPH_FACTOR.put("in", 0.78); DIGRAPH_FACTOR.put("er", 0.74);
        DIGRAPH_FACTOR.put("an", 0.80); DIGRAPH_FACTOR.put("re", 0.78);
        DIGRAPH_FACTOR.put("on", 0.80); DIGRAPH_FACTOR.put("en", 0.80);
        DIGRAPH_FACTOR.put("at", 0.80); DIGRAPH_FACTOR.put("es", 0.82);
        DIGRAPH_FACTOR.put("st", 0.78); DIGRAPH_FACTOR.put("ou", 0.80);
        DIGRAPH_FACTOR.put("ed", 0.80); DIGRAPH_FACTOR.put("to", 0.78);
        DIGRAPH_FACTOR.put("it", 0.80); DIGRAPH_FACTOR.put("ti", 0.80);
        DIGRAPH_FACTOR.put("nd", 0.78); DIGRAPH_FACTOR.put("ng", 0.78);
        DIGRAPH_FACTOR.put("io", 0.80); DIGRAPH_FACTOR.put("is", 0.82);
        DIGRAPH_FACTOR.put("ha", 0.80); DIGRAPH_FACTOR.put("nt", 0.80);
        DIGRAPH_FACTOR.put("ea", 0.80); DIGRAPH_FACTOR.put("of", 0.82);
        DIGRAPH_FACTOR.put("te", 0.82); DIGRAPH_FACTOR.put("or", 0.80);
    }

    // Characters that require Shift — the finger has to reach for the Shift key first.
    private static final Set<Character> SHIFT_CHARS = new HashSet<>();
    static {
        for (char c = 'A'; c <= 'Z'; c++) SHIFT_CHARS.add(c);
        for (char c : "!@#$%^&*()_+{}|:\"<>?~".toCharArray()) SHIFT_CHARS.add(c);
    }

    public interface ProgressCallback {
        void onProgress(int charsDone, int charsTotal);
    }

    public volatile boolean cancelled = false;

    private final HidKeyboard keyboard;
    private final Random rng = new Random();

    private int    wpm         = 60;
    private double naturalness = 0.65;
    private boolean turbo      = false;
    private boolean codeMode   = false;
    private boolean mac        = true;  // which shortcuts the computer understands

    public Typer(HidKeyboard keyboard) {
        this.keyboard = keyboard;
    }

    public void setParams(int wpm, double naturalness, boolean turbo, boolean codeMode,
                          boolean mac) {
        this.codeMode    = codeMode;
        this.mac         = mac;
        this.wpm         = Math.max(20, Math.min(300, wpm));
        this.naturalness = Math.max(0.0, Math.min(1.0, naturalness));
        this.turbo       = turbo;
    }

    /**
     * Types the full text string.  Must be called from a background thread (it sleeps).
     * Returns true when finished, false if cancelled or a key send failed.
     */
    public boolean typeText(String text, ProgressCallback cb) throws InterruptedException {
        cancelled = false;
        int n = text.length();
        long lastPressMs = 0;
        try {
        for (int i = 0; i < n; i++) {
            if (cancelled || Thread.interrupted()) return false;

            char cur  = text.charAt(i);
            char prev = i > 0 ? text.charAt(i - 1) : 0;

            byte[] mapping = KeyMap.get(cur);
            // Code mode: close any suggestion pop-up before a key that would accept it
            // (Enter, Tab, "(", "." … right after a word).
            if (mapping != null && codeMode && isWordChar(prev) && !isWordChar(cur)) {
                if (!tap(0, KeyMap.ESCAPE)) return false;
            }
            if (mapping != null) {
                if (turbo) {
                    // No human timing: HidKeyboard paces reports as fast as is reliable.
                    if (!keyboard.sendKeyFast(mapping[0], mapping[1])) return false;
                } else {
                    // Gaps are measured press-to-press, so time spent holding the
                    // previous key (and Bluetooth pacing) counts towards the gap.
                    long waitMs = lastPressMs + gapBefore(prev, cur) - SystemClock.uptimeMillis();
                    if (waitMs > 0) Thread.sleep(waitMs);
                    lastPressMs = SystemClock.uptimeMillis();
                    if (!keyboard.sendKey(mapping[0], mapping[1], holdDuration())) return false;
                }
            }
            // Unmappable characters (e.g. emoji, non-English letters) are skipped
            // without a pause; MainActivity warns about them before typing starts.

            if (mapping != null && codeMode && !undoEditorHelp(cur)) return false;

            if (cb != null) cb.onProgress(i + 1, n);
        }
        return true;
        } finally {
            keyboard.releaseAll();   // never leave a turbo key held down (also on Stop)
        }
    }

    // --- code mode ---
    //
    // Code editors (Monaco on LeetCode, Ace on Programiz, CodeMirror …) add text of their
    // own as you type: "(" also inserts ")", Enter adds indentation. We already type every
    // character of the original, so that extra text ends up doubled. Typing happens at
    // the very end of the editor, so these undo it without selecting beyond the current
    // line (an earlier select-to-end version could wipe real code when the selection
    // anchor was off). Mac and Windows/Linux need different shortcuts: on Windows the
    // ⌘ key is the Windows key, and Ctrl+K is a shortcut prefix in many editors.

    private static final String AUTO_CLOSERS = "([{\"'`";

    private boolean undoEditorHelp(char typed) throws InterruptedException {
        if (typed == '\n') {
            // Some editors (Ace) add the "}" only on Enter, on the line below the cursor:
            // Delete pulls that line up, then "delete to end of line" clears it. Both do
            // nothing at the very end of the text.
            // Then a Space and "delete to start of line" remove the editor's auto-indent;
            // the space makes sure there is always something on this line to delete, so
            // the line never gets joined with the one above.
            if (!tap(0, KeyMap.DELETE)) return false;
            if (mac) {
                return tap(KeyMap.CTRL, KeyMap.K)                       // ⌃K
                    && tap(0, KeyMap.SPACE)
                    && tap(KeyMap.CMD, KeyMap.BACKSPACE);               // ⌘⌫
            }
            return tap(KeyMap.SHIFT, KeyMap.END) && tap(0, KeyMap.DELETE)    // Shift+End, Del
                && tap(0, KeyMap.SPACE)
                && tap(KeyMap.SHIFT, KeyMap.HOME) && tap(0, KeyMap.BACKSPACE); // Shift+Home, ⌫
        }
        // Forward-delete the one closer the editor may have added (no-op if it didn't).
        if (AUTO_CLOSERS.indexOf(typed) >= 0) return tap(0, KeyMap.DELETE);
        return true;
    }

    private boolean tap(int modifier, byte keycode) throws InterruptedException {
        return keyboard.sendKey((byte) modifier, keycode, 0);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // --- timing model ---

    /**
     * Time (ms) from pressing the previous key to pressing this one.
     * Uses a log-normal distribution so the mean stays at the base interval while the
     * spread grows with naturalness. Shift and punctuation pauses add a little on top,
     * so real-world speed lands slightly under the slider's WPM (as with a real typist).
     */
    private long gapBefore(char prev, char cur) {
        // base interval: 12000 / WPM ms per character (1 word = 5 chars)
        double base = 12000.0 / wpm;

        // Log-normal: ln(X) ~ N(μ, σ²)
        // Choose μ so that E[X] = base: μ = ln(base) - σ²/2
        double sigma = naturalness * 0.50; // σ=0 → no variation; σ=0.5 → realistic spread
        double mu    = Math.log(base) - (sigma * sigma) / 2.0;
        double gap   = Math.exp(mu + sigma * rng.nextGaussian());

        // Digraph speed-up: blend from no effect (naturalness=0) to full effect (naturalness=1)
        if (prev != 0) {
            String pair = ("" + Character.toLowerCase(prev) + Character.toLowerCase(cur));
            Double factor = DIGRAPH_FACTOR.get(pair);
            if (factor != null) {
                gap *= 1.0 - naturalness * (1.0 - factor);
            }
        }

        // Extra reach time for Shift characters
        if (SHIFT_CHARS.contains(cur)) {
            gap += naturalness * (20 + rng.nextInt(50)); // +20..70 ms
        }

        // Micro-pause after sentence-ending punctuation
        if (prev == '.' || prev == '!' || prev == '?') {
            gap += naturalness * (80 + rng.nextInt(250)); // +80..330 ms
        } else if (prev == ',') {
            gap += naturalness * (25 + rng.nextInt(70));  // +25..95 ms
        }

        // Hard clamp: no key before 15 ms (faster than fastest human) or after 2 s
        gap = Math.max(15, Math.min(2000, gap));
        return (long) gap;
    }

    /**
     * How long a key is physically held before release: ~80 ms at 60 WPM, shorter at
     * high speed so it always fits inside the press-to-press interval.
     */
    private long holdDuration() {
        double base = Math.min(80.0, 4800.0 / wpm); // ~80 ms at 60 WPM, ~34 ms at 140 WPM
        double sigma = naturalness * 0.30;
        double hold  = base * Math.exp(sigma * rng.nextGaussian());
        return (long) Math.max(10, Math.min(150, hold));
    }
}
