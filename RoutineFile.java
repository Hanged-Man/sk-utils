package com.threerings.opengl.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * A stage routine (an ordered list of steps) loaded from a plain-text file, so
 * routes can be edited without rebuilding the mod and each floor can live in its
 * own file under ~/.sk-utils/routines/&lt;mission&gt;/.
 *
 * <p>File format — one step per line: {@code TYPE &lt;params...&gt;}. TYPE is
 * case-insensitive; blank lines and anything after a {@code #} are ignored.
 * <b>Each command declares its own parameter count</b> ({@link #paramCount} /
 * {@link #minParamCount}): every command's params START with X Y, and commands
 * may take more — e.g. {@code WAIT X Y SECONDS}. Trailing params may be
 * OPTIONAL (the sweeps' RANGE). Parsing is strict: a line's param count must
 * fall within [min, max] (wrong count / unknown type / non-numeric param
 * makes {@link #load} throw with the line number, and the caller refuses to
 * start rather than run a partial route).
 *
 * <p>Legacy sugar: {@code WAIT3 X Y} still parses — it becomes {@code WAIT} with
 * an implicit 3-second duration.
 *
 * <p>The step-type codes here are the single source of truth;
 * {@link SocketInputState} aliases them so its {@code tickRoutine} switch stays
 * readable. Adding a multi-param command = a constant + a {@link #parseType}
 * entry + a {@link #paramCount} entry + a tickRoutine case reading
 * {@code stepParam(i)}.
 */
public final class RoutineFile {

    // Step-type codes. SocketInputState mirrors these (T_MOVETO = MOVETO, …).
    public static final int MOVETO = 0, COMBATLOOT = 1, ATTACKMOVE = 2,
            SHOOT = 3, PLATFORM = 4, ELEVATOR = 5, LOOT = 6, BUTTON = 7,
            MINERALS = 8, KEY_LIFT = 9, GATE = 10, BUTTONSWEEP = 11, HAZARD_MOVETO = 12,
            CRITICAL_MOVETO = 13, TREASURESWEEP = 14, WAIT = 15,
            KEY_DROP = 16, SNARBY = 17, LIFT = 18, DROP = 19, KILL = 20,
            PRECISE_MOVETO = 21, SWITCHSWEEP = 22, COMBAT = 23, HAZARD_LOOT = 24,
            ALCH_CHARGE = 25, PIN_MOVETO = 26;
    /** Legacy alias: WAIT3 X Y = WAIT X Y 3 (same code; the parser adds the implicit duration). */
    public static final int WAIT3 = WAIT;

    public final int[] type;
    /** Per-step parameters, {@link #minParamCount}..{@link #paramCount} long; [0]=X, [1]=Y always. */
    public final float[][] params;
    /** Convenience views of params[i][0] / params[i][1] (every command starts with X Y). */
    public final float[] x;
    public final float[] y;

    private RoutineFile(int[] type, float[][] params, float[] x, float[] y) {
        this.type = type;
        this.params = params;
        this.x = x;
        this.y = y;
    }

    /** Maps a step-type keyword (case-insensitive) to its code, or -1 if unknown. */
    public static int parseType(String s) {
        switch (s.toUpperCase(Locale.ROOT)) {
            case "MOVETO":
                return MOVETO;
            case "COMBATLOOT":
                return COMBATLOOT;
            case "COMBAT":
                return COMBAT;
            case "HAZARD_LOOT":
                return HAZARD_LOOT;
            case "ALCH_CHARGE":
                return ALCH_CHARGE;
            case "PIN_MOVETO":
                return PIN_MOVETO;
            case "ATTACKMOVE":
                return ATTACKMOVE;
            case "SHOOT":
                return SHOOT;
            case "PLATFORM":
                return PLATFORM;
            case "ELEVATOR":
                return ELEVATOR;
            case "LOOT":
                return LOOT;
            case "BUTTON":
                return BUTTON;
            case "MINERALS":
                return MINERALS;
            case "KEY_LIFT":
                return KEY_LIFT;
            case "KEY_DROP":
                return KEY_DROP;
            case "LIFT":
            case "STATUE_LIFT": // sugar — statues are just liftables
                return LIFT;
            case "DROP":
            case "STATUE_DROP": // sugar
                return DROP;
            case "SNARBY":
                return SNARBY;
            case "KILL":
                return KILL;
            case "PRECISE_MOVETO":
                return PRECISE_MOVETO;
            case "GATE":
                return GATE;
            case "BUTTONSWEEP":
                return BUTTONSWEEP;
            case "SWITCHSWEEP":
                return SWITCHSWEEP;
            case "HAZARD_MOVETO":
                return HAZARD_MOVETO;
            case "CRITICAL_MOVETO":
                return CRITICAL_MOVETO;
            case "TREASURESWEEP":
                return TREASURESWEEP;
            case "WAIT":
            case "WAIT3": // legacy sugar — load() appends the implicit 3s
                return WAIT;
            default:
                return -1;
        }
    }

    /**
     * The keyword for a step-type code — the inverse of {@link #parseType}, for logs
     * and mission-stats failure reasons (a bare "type 14" tells the reader nothing,
     * and the codes shift whenever a command is retired). Unknown codes render as
     * {@code type <n>} rather than throwing.
     */
    public static String typeName(int type) {
        switch (type) {
            case MOVETO:
                return "MOVETO";
            case COMBATLOOT:
                return "COMBATLOOT";
            case COMBAT:
                return "COMBAT";
            case HAZARD_LOOT:
                return "HAZARD_LOOT";
            case ALCH_CHARGE:
                return "ALCH_CHARGE";
            case PIN_MOVETO:
                return "PIN_MOVETO";
            case ATTACKMOVE:
                return "ATTACKMOVE";
            case SHOOT:
                return "SHOOT";
            case PLATFORM:
                return "PLATFORM";
            case ELEVATOR:
                return "ELEVATOR";
            case LOOT:
                return "LOOT";
            case BUTTON:
                return "BUTTON";
            case MINERALS:
                return "MINERALS";
            case KEY_LIFT:
                return "KEY_LIFT";
            case GATE:
                return "GATE";
            case BUTTONSWEEP:
                return "BUTTONSWEEP";
            case SWITCHSWEEP:
                return "SWITCHSWEEP";
            case HAZARD_MOVETO:
                return "HAZARD_MOVETO";
            case CRITICAL_MOVETO:
                return "CRITICAL_MOVETO";
            case TREASURESWEEP:
                return "TREASURESWEEP";
            case WAIT:
                return "WAIT";
            case KEY_DROP:
                return "KEY_DROP";
            case SNARBY:
                return "SNARBY";
            case LIFT:
                return "LIFT";
            case DROP:
                return "DROP";
            case KILL:
                return "KILL";
            case PRECISE_MOVETO:
                return "PRECISE_MOVETO";
            default:
                return "type " + type;
        }
    }

    /**
     * The MAXIMUM numeric parameters a command takes (params[0]=X, params[1]=Y
     * always; extras are command-specific). THE arity table — extend it alongside
     * parseType when adding a command (e.g. a future TROJAN X1 Y1 X2 Y2 would
     * return 4). Trailing params may be OPTIONAL — see {@link #minParamCount}.
     */
    public static int paramCount(int type) {
        switch (type) {
            case WAIT:
                return 3; // X Y SECONDS
            case BUTTONSWEEP:
            case TREASURESWEEP:
            case SWITCHSWEEP:
                return 3; // X Y [RANGE tiles] — sweep radius; RANGE optional, see minParamCount
            case COMBAT:
            case COMBATLOOT:
            case ATTACKMOVE:
                return 3; // X Y [RANGE tiles] — enemy-detection radius; optional
            case ALCH_CHARGE:
                return 4; // X Y W Z — aim point, then the ghost gate to watch
            case PIN_MOVETO:
                return 3; // X Y MODE — see load(): written "X Y START" / "END"
            default:
                return 2; // X Y
        }
    }

    /**
     * The MINIMUM parameters a command accepts — lower than {@link #paramCount}
     * when trailing params are optional (the sweeps' RANGE). An omitted optional
     * param is simply absent from the stored row; SocketInputState's
     * {@code stepParam(idx, dflt)} supplies the command's default, so legacy
     * 2-param sweep lines keep their old radii.
     */
    public static int minParamCount(int type) {
        switch (type) {
            case BUTTONSWEEP:
            case TREASURESWEEP:
            case SWITCHSWEEP:
            case COMBAT:
            case COMBATLOOT:
            case ATTACKMOVE:
                return 2; // RANGE defaults to the command's legacy radius
            default:
                return paramCount(type);
        }
    }

    /**
     * Reads and parses the routine file at {@code path}. Throws {@link IOException}
     * if the file is missing/empty or any line is malformed (with the line number
     * and offending text), so the caller can log it and not start.
     */
    public static RoutineFile load(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile())
            throw new IOException("routine file not found: " + path);
        ArrayList<Integer> types = new ArrayList<Integer>();
        ArrayList<float[]> rows = new ArrayList<float[]>();
        BufferedReader br = new BufferedReader(new FileReader(f));
        try {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty())
                    continue;
                String[] tok = line.split("\\s+");
                int type = parseType(tok[0]);
                if (type < 0)
                    throw new IOException("line " + lineNo + ": unknown step type '" + tok[0] + "'");
                // PIN_MOVETO is a SCOPED PAIR, not a one-shot move:
                //     PIN_MOVETO X Y START   ... stationary steps ...   PIN_MOVETO END
                // The main pins itself against (X,Y) and HOLDS there for every step in
                // between (see SocketInputState's pin-hold driver). Both forms normalise
                // to a fixed 3-param row [x, y, mode] — START=1, END=0 — so the numeric
                // loader below is untouched.
                if (type == PIN_MOVETO) {
                    String last = tok[tok.length - 1].toUpperCase(Locale.ROOT);
                    float[] pin;
                    if (tok.length == 4 && "START".equals(last)) {
                        try {
                            pin = new float[] { Float.parseFloat(tok[1]), Float.parseFloat(tok[2]), 1f };
                        } catch (NumberFormatException nfe) {
                            throw new IOException("line " + lineNo + ": bad numeric parameter in: " + line);
                        }
                    } else if (tok.length == 2 && "END".equals(last)) {
                        pin = new float[] { 0f, 0f, 0f };
                    } else {
                        throw new IOException("line " + lineNo
                                + ": PIN_MOVETO takes 'X Y START' or 'END', got: " + line);
                    }
                    types.add(Integer.valueOf(type));
                    rows.add(pin);
                    continue;
                }
                // WAIT3 is sugar: takes only X Y on the line, gains an implicit 3s duration.
                boolean legacyWait3 = tok[0].equalsIgnoreCase("WAIT3");
                int max = legacyWait3 ? 2 : paramCount(type);
                int min = legacyWait3 ? 2 : minParamCount(type);
                int given = tok.length - 1;
                if (given < min || given > max)
                    throw new IOException("line " + lineNo + ": " + tok[0].toUpperCase(Locale.ROOT)
                            + " takes " + (min == max ? "exactly " + max : min + " to " + max)
                            + " parameter(s), got " + given + ": " + line);
                // The row is stored at its GIVEN length: an omitted optional param stays
                // absent, and stepParam(idx, dflt) supplies the command's default.
                float[] row = new float[legacyWait3 ? paramCount(type) : given];
                try {
                    for (int i = 0; i < given; i++)
                        row[i] = Float.parseFloat(tok[1 + i]);
                } catch (NumberFormatException nfe) {
                    throw new IOException("line " + lineNo + ": bad numeric parameter in: " + line);
                }
                if (legacyWait3)
                    row[2] = 3f; // the implicit duration
                types.add(Integer.valueOf(type));
                rows.add(row);
            }
        } finally {
            br.close();
        }
        if (types.isEmpty())
            throw new IOException("no steps in " + path);
        int n = types.size();
        int[] ta = new int[n];
        float[][] pa = new float[n][];
        float[] xa = new float[n];
        float[] ya = new float[n];
        for (int i = 0; i < n; i++) {
            ta[i] = types.get(i).intValue();
            pa[i] = rows.get(i);
            xa[i] = pa[i][0];
            ya[i] = pa[i][1];
        }
        return new RoutineFile(ta, pa, xa, ya);
    }
}
