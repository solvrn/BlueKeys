package app.bluekeys;

// Maps printable ASCII characters to {modifier, keycode} pairs for a US-layout HID keyboard.
// modifier: 0=none, 0x02=Left Shift. keycode: standard USB HID Usage IDs for the keyboard page.
public class KeyMap {

    public static final byte BACKSPACE = (byte) 0x2A;
    public static final byte SPACE     = (byte) 0x2C;
    public static final byte ENTER     = (byte) 0x28;
    public static final byte ESCAPE    = (byte) 0x29;
    public static final byte TAB       = (byte) 0x2B;
    public static final byte DELETE    = (byte) 0x4C; // forward delete
    public static final byte K         = (byte) 0x0E;
    public static final byte HOME      = (byte) 0x4A;
    public static final byte END       = (byte) 0x4D;

    public static final byte CTRL      = (byte) 0x01;
    public static final byte SHIFT     = (byte) 0x02;
    public static final byte CMD       = (byte) 0x08; // Left GUI = ⌘ on a Mac

    /**
     * Swaps "smart" punctuation (common in text pasted from the web) for the plain
     * ASCII version a keyboard can type, e.g. ’ → ' and — → -.
     */
    public static String toTypeable(String text) {
        return text
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u2018', '\'').replace('\u2019', '\'')   // ‘ ’
            .replace('\u201C', '"').replace('\u201D', '"')     // “ ”
            .replace('\u2013', '-').replace('\u2014', '-')     // – —
            .replace('\u2212', '-')                            // − (minus sign)
            .replace('\u00A0', ' ').replace('\u202F', ' ')     // non-breaking spaces
            .replace("\u2026", "...");                          // …
    }

    /** Returns {modifier, keycode}, or null if the character has no US-keyboard mapping. */
    public static byte[] get(char c) {
        // Lowercase a-z → keycodes 0x04-0x1D, no modifier
        if (c >= 'a' && c <= 'z') return key(0, 0x04 + (c - 'a'));

        // Uppercase A-Z → same keycodes, Left-Shift modifier
        if (c >= 'A' && c <= 'Z') return key(0x02, 0x04 + (c - 'A'));

        // Digits 1-9 → 0x1E-0x26, 0 → 0x27
        if (c >= '1' && c <= '9') return key(0, 0x1E + (c - '1'));
        if (c == '0') return key(0, 0x27);

        switch (c) {
            // Whitespace
            case ' ':  return key(0,    0x2C);
            case '\n': return key(0,    0x28);
            case '\t': return key(0,    0x2B);

            // Unshifted punctuation
            case '-':  return key(0,    0x2D);
            case '=':  return key(0,    0x2E);
            case '[':  return key(0,    0x2F);
            case ']':  return key(0,    0x30);
            case '\\': return key(0,    0x31);
            case ';':  return key(0,    0x33);
            case '\'': return key(0,    0x34);
            case '`':  return key(0,    0x35);
            case ',':  return key(0,    0x36);
            case '.':  return key(0,    0x37);
            case '/':  return key(0,    0x38);

            // Shifted punctuation (Shift + the unshifted key above)
            case '_':  return key(0x02, 0x2D);
            case '+':  return key(0x02, 0x2E);
            case '{':  return key(0x02, 0x2F);
            case '}':  return key(0x02, 0x30);
            case '|':  return key(0x02, 0x31);
            case ':':  return key(0x02, 0x33);
            case '"':  return key(0x02, 0x34);
            case '~':  return key(0x02, 0x35);
            case '<':  return key(0x02, 0x36);
            case '>':  return key(0x02, 0x37);
            case '?':  return key(0x02, 0x38);

            // Shift + digit row
            case '!':  return key(0x02, 0x1E);
            case '@':  return key(0x02, 0x1F);
            case '#':  return key(0x02, 0x20);
            case '$':  return key(0x02, 0x21);
            case '%':  return key(0x02, 0x22);
            case '^':  return key(0x02, 0x23);
            case '&':  return key(0x02, 0x24);
            case '*':  return key(0x02, 0x25);
            case '(':  return key(0x02, 0x26);
            case ')':  return key(0x02, 0x27);

            default: return null;
        }
    }

    private static byte[] key(int modifier, int keycode) {
        return new byte[]{ (byte) modifier, (byte) keycode };
    }
}
