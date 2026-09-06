import argparse
import json
import os
import socket
import time

from pynput import keyboard

import win32gui

TOGGLE_KEY_CHAR = "`"  # heat HUD toggle
SK_DIR = os.path.join(os.path.expanduser("~"), ".sk-utils")
STATS_FILE = os.path.join(SK_DIR, "mission_stats.jsonl")


def _read_sk_config():
    """~/.sk-utils/config.properties — the SAME file the mod reads, so the port
    plane and party size can never drift between the two sides."""
    cfg = {}
    try:
        with open(os.path.join(SK_DIR, "config.properties"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                cfg[key.strip()] = value.strip()
    except OSError:
        pass
    return cfg


_SK_CONFIG = _read_sk_config()
BASE_PORT = int(_SK_CONFIG.get("udp_base_port") or 40000)
MAX_ALTS = 20  # alt listener ports = BASE_PORT+1 .. BASE_PORT+MAX_ALTS
MAIN_PORT = BASE_PORT  # the main account's listener — PvP auto-queue includes it

# Token payout per SUCCESSFUL run, by mission (the routines subfolder name), used by the
# Ctrl+\ crowns/hour figures. Tokens never touch the wallet, so they cannot be measured
# from the crown delta the mod records — they have to be declared here.
#
# Value is (TOKENS PER KNIGHT, CROWNS PER TOKEN); the run total is multiplied by
# PARTY_SIZE, since every knight is paid. Both halves vary per mission — shadow_beast's
# tokens are worth 75 each, sovereign_slime's 3500/15 — so a single flat rate would
# misprice one of them.
#
# UNKNOWN MISSIONS PAY 0 by design: silently crediting a default inflated axes_of_evil by
# ~7k crowns/hr and made it look like the best farm on record. Under-reporting a new
# mission is the safer error — add it here once its payout is known.
# Also give 0 to any PARTIAL run (mission_data numFloors below the real floor count):
# it never completes the mission, so it never earns the completion tokens.
PARTY_SIZE = int(_SK_CONFIG.get("party_size") or 4)
TOKENS_PER_RUN_DEFAULT = (0, 0)
TOKENS_PER_RUN = {
    "shadow_beast":        (3, 75),        # 3/knight worth 75cr  -> 900/run
    "sovereign_slime":     (3, 3500 / 15), # 3/knight worth 3500/15cr -> 2800/run
    "axes_of_evil":        (0, 0),         # no boss, no tokens (user-confirmed)
    "beyond_axes_of_evil": (0, 0),         # ditto
}

# Dev-mode STARTUP default. Runtime state lives in debug_enabled, toggled by the
# '=' hotkey, which also broadcasts DEBUGMODE to every client — flipping the mod's
# debug.log writing on the fly (no rebuild) and un-gating the [DEV] hotkeys
# (Ctrl+B combat, Ctrl+N loot mode) in this script.
DEBUG = False


def parse_ports(value):
    ports = []

    for part in value.split(","):
        part = part.strip()

        if "-" in part:
            start, end = part.split("-", 1)
            ports.extend(range(int(start), int(end) + 1))
        elif part:
            ports.append(int(part))

    return ports


def active_window():
    return win32gui.GetForegroundWindow()


def get_window_title(hwnd):
    if not hwnd:
        return ""

    try:
        return win32gui.GetWindowText(hwnd)
    except Exception:
        return ""


def get_wm_class(hwnd):
    """
    Windows equivalent-ish of X11 WM_CLASS.

    On Windows this returns the window class name, not the same thing as
    Linux WM_CLASS, but it is useful for identifying game windows.
    """
    if not hwnd:
        return ""

    try:
        return win32gui.GetClassName(hwnd)
    except Exception:
        return ""


def select_windows(count):
    windows = set()

    for i in range(count):
        print()
        print(f"Focus Spiral Knights window {i + 1}, then press Enter here.")
        input("> ")

        hwnd = active_window()
        title = get_window_title(hwnd)
        cls = get_wm_class(hwnd)

        windows.add(hwnd)

        print(f"Selected HWND: {hwnd}")
        print(f"Title: {title}")
        print(f"Class: {cls}")

        time.sleep(0.25)

    return windows


def key_to_char(key):
    """
    Convert pynput key object to lowercase character when possible.
    """
    if isinstance(key, keyboard.KeyCode):
        return key.char.lower() if key.char else None

    return None


def is_toggle_key(key):
    return key_to_char(key) == TOGGLE_KEY_CHAR


def is_follow_key(key):
    if key_to_char(key) == "\x06":  # ctrl+f appears as ACK (\x06 in ASCII on Windows)
        return True


def is_pvp_queue_key(key):
    # ctrl+q appears as DC1 (\x11 in ASCII on Windows)
    return key_to_char(key) == "\x11"


def is_combat_bot_key(key):
    # ctrl+b appears as STX (\x02 in ASCII on Windows)
    return key_to_char(key) == "\x02"


def is_scene_scan_key(key):
    # ctrl+d appears as EOT (\x04 in ASCII on Windows)
    return key_to_char(key) == "\x04"


def is_dump_pos_key(key):
    # ctrl+l appears as FF (\x0c in ASCII on Windows)
    return key_to_char(key) == "\x0c"


def is_loot_mode_key(key):
    # ctrl+n appears as SO (\x0e in ASCII on Windows)
    return key_to_char(key) == "\x0e"


def is_routine_key(key):
    # ctrl+r appears as DC2 (\x12 in ASCII on Windows)
    return key_to_char(key) == "\x12"


def is_stats_key(key):
    # ctrl+\ appears as FS (\x1c in ASCII on Windows)
    return key_to_char(key) == "\x1c"


def is_forge_stats_key(key):
    # ctrl+] appears as GS (\x1d in ASCII on Windows)
    return key_to_char(key) == "\x1d"


def is_auction_key(key):
    # ctrl+e appears as ENQ (\x05 in ASCII on Windows)
    return key_to_char(key) == "\x05"


def is_auction_sell_key(key):
    # ctrl+w appears as ETB (\x17 in ASCII on Windows)
    return key_to_char(key) == "\x17"


def is_item_dump_key(key):
    # ctrl+u appears as NAK (\x15 in ASCII on Windows)
    return key_to_char(key) == "\x15"


def is_debug_key(key):
    return key_to_char(key) == "="


def _read_stats(f):
    """Parse mission_stats.jsonl leniently: one row per JSON object, not per line.

    A run's worth of history should never be unreadable because of one malformed line.
    Rows have been seen concatenated onto a single line ("}{" with no newline between),
    so instead of json.loads per line this walks each line with raw_decode and takes
    every object it finds, skipping anything that won't parse.
    """
    dec = json.JSONDecoder()
    rows, skipped = [], 0
    for line in f:
        s = line.strip()
        i, n = 0, len(s)
        while i < n:
            while i < n and s[i] != "{":
                i += 1
                skipped += 1
            if i >= n:
                break
            try:
                obj, end = dec.raw_decode(s, i)
            except ValueError:
                skipped += n - i
                break
            rows.append(obj)
            i = end
    if skipped:
        print(f"  (skipped {skipped} unparseable char(s) in {os.path.basename(STATS_FILE)})")
    return rows


FORGES_FILE = os.path.join(os.path.expanduser("~"), ".sk-utils", "forges.jsonl")


def print_forge_stats():
    """Print a forge summary of ~/.sk-utils/forges.jsonl: total forges, boxes by
    star tier, the pooled empirical box rate, the exactly-one-box forge rate, the
    9->10 two-box forge rate, and pieces fully burnt (level 10).

    The box rate is POOLED across tiers on purpose: the drop chance is per forge
    and uniform across star levels (measured 2026-08-07: 3.44% pooled, every
    tier's CI overlapping) — tiers differ in forge SPEED and box VALUE, not rate.
    """
    STARS = {"Cracked": "0*", "Dim": "1*", "Warm": "2*",
             "Glowing": "3*", "Shining": "4*", "Radiant": "5*"}
    try:
        with open(FORGES_FILE, "r", encoding="utf-8") as f:
            lines = f.readlines()
    except FileNotFoundError:
        print(f"\nNo forge log at {FORGES_FILE}\n")
        return
    except Exception as exc:
        print(f"\nCould not read forge log: {exc}\n")
        return

    total = boxes_total = burnt = 0
    l9_forges = l9_boxes = 0
    one_box = l9_two = two_box_other = 0  # exactly-1-box forges; 2-box 9->10 forges; 2+-box forges NOT at 9->10
    by_star = {}  # label -> [forges, boxes]
    for ln in lines:
        ln = ln.strip()
        if not ln:
            continue
        try:
            r = json.loads(ln)
        except Exception:
            continue  # tolerate a torn/corrupt line rather than losing the report
        if r.get("result_raw") == "null":
            continue  # legacy no-op forges (newer builds no longer log these at all)
        total += 1
        star = STARS.get(str(r.get("crystal_name", "")).split(" ")[0], "?")
        s = by_star.setdefault(star, [0, 0])
        s[0] += 1
        b = int(r.get("forge_boxes") or 0)
        s[1] += b
        boxes_total += b
        if b == 1:
            one_box += 1
        lb = int(r.get("level_before") or 0)
        if lb == 9:
            l9_forges += 1
            l9_boxes += b
            if b == 2:
                l9_two += 1
        elif b >= 2:
            two_box_other += 1  # only the 9->10 finisher can double-drop; anything else is a log anomaly
        if int(r.get("level_after") or 0) >= 10:
            burnt += 1

    if not total:
        print("\nNo (real) forges logged yet.\n")
        return

    import math

    def _rate_ci(k, n):
        # boxes per forge, multi-drops included (rate can exceed the hit count)
        p = k / n
        z = 1.96
        d = 1 + z * z / n
        c = (p + z * z / (2 * n)) / d
        h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
        return 100 * p, 100 * (c - h), 100 * (c + h)

    p, lo, hi = _rate_ci(boxes_total, total)
    print("\n=== FORGE STATS ===")
    print(f"  Total forges:       {total:,}")
    print("  Boxes by star level:")
    for star in sorted(by_star):
        f_n, b_n = by_star[star]
        print(f"    {star}: {b_n:,} box(es) from {f_n:,} forges")
    print(f"  Box rate (pooled):  {boxes_total:,}/{total:,} = {p:.2f}%  [95% CI {lo:.2f}-{hi:.2f}%]")
    p1, lo1, hi1 = _rate_ci(one_box, total)
    print(f"  1-box forges:       {one_box:,}/{total:,} = {p1:.2f}%  [95% CI {lo1:.2f}-{hi1:.2f}%]")
    if l9_forges:
        p9, lo9, hi9 = _rate_ci(l9_boxes, l9_forges)
        print(f"  Box rate (9->10):   {l9_boxes:,}/{l9_forges:,} = {p9:.2f}%  [95% CI {lo9:.2f}-{hi9:.2f}%]")
        p92, lo92, hi92 = _rate_ci(l9_two, l9_forges)
        print(f"  2-box 9->10 forges: {l9_two:,}/{l9_forges:,} = {p92:.2f}%  [95% CI {lo92:.2f}-{hi92:.2f}%]")
    if two_box_other:
        print(f"  NOTE: {two_box_other:,} multi-box forge(s) logged OUTSIDE 9->10 — "
              "the 'only the finisher can double-drop' assumption does not hold in this log")
    print(f"  Pieces burnt (L10): {burnt:,}")
    print()


def print_mission_stats():
    """Print an in-terminal summary of ~/.sk-utils/mission_stats.jsonl.

    Runs are grouped BY MISSION: runtimes and crown yields aren't comparable across
    missions, so one blended average would hide which mission is actually worth
    farming. Rows logged before missions were tagged group under "(untagged)".
    """
    try:
        with open(STATS_FILE, "r", encoding="utf-8") as f:
            rows = _read_stats(f)
    except FileNotFoundError:
        print(f"\nNo mission stats file at {STATS_FILE}\n")
        return
    except Exception as exc:
        print(f"\nCould not read mission stats: {exc}\n")
        return

    if not rows:
        print("\nNo mission runs logged yet.\n")
        return

    groups = {}
    for r in rows:
        groups.setdefault(r.get("mission") or "(untagged)", []).append(r)

    # Busiest mission first; the combined section only earns its place with 2+ missions.
    for name, grp in sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        _summarize_runs(grp, name)
    if len(groups) > 1:
        _summarize_runs(rows, "ALL MISSIONS")


def _summarize_runs(rows, label):
    """Print one summary block for `rows` under `label`."""
    total = len(rows)
    if total == 0:
        return

    successes = [r for r in rows if r.get("status") == "SUCCESS"]
    n_succ = len(successes)

    succ_runtimes = [r["runtime_ms"] for r in successes if isinstance(r.get("runtime_ms"), (int, float))]
    succ_crowns = [r["crowns"] for r in successes if isinstance(r.get("crowns"), (int, float))]
    all_runtimes = [r["runtime_ms"] for r in rows if isinstance(r.get("runtime_ms"), (int, float))]
    all_crowns = [r["crowns"] for r in rows if isinstance(r.get("crowns"), (int, float))]
    all_deaths = [r["deaths"] for r in rows if isinstance(r.get("deaths"), (int, float))]

    avg_runtime = sum(succ_runtimes) / len(succ_runtimes) if succ_runtimes else 0
    avg_crowns = sum(succ_crowns) / len(succ_crowns) if succ_crowns else 0
    avg_runtime_all = sum(all_runtimes) / len(all_runtimes) if all_runtimes else 0
    avg_crowns_all = sum(all_crowns) / len(all_crowns) if all_crowns else 0
    avg_deaths = sum(all_deaths) / len(all_deaths) if all_deaths else 0

    def fmt_ms(ms):
        secs = int(round(ms / 1000))
        return f"{secs // 60}m {secs % 60:02d}s"

    print()
    print(f"=== {label}: {total} run{'' if total == 1 else 's'} ===")
    print(f"  Success rate:           {100.0 * n_succ / total:.1f}%  ({n_succ}/{total})")
    if n_succ:
        print(f"  Avg runtime (success):  {fmt_ms(avg_runtime)}")
    else:
        print("  Avg runtime (success):  n/a")
    print(f"  Avg runtime (all runs): {fmt_ms(avg_runtime_all) if all_runtimes else 'n/a'}")
    if n_succ:
        print(f"  Avg crowns (success):   {avg_crowns:,.0f}")
    else:
        print("  Avg crowns (success):   n/a")
    print(f"  Avg crowns (all runs):  {f'{avg_crowns_all:,.0f}' if all_crowns else 'n/a'}")
    print(f"  Avg deaths (all runs):  {avg_deaths:.2f}")
    # Crowns/hour: EFFECTIVE = what the cycle actually earns (all runs, failures
    # included); THEORETICAL = the rate if every run succeeded (successes only).
    # Each SUCCESS also pays that mission's completion tokens (see TOKENS_PER_RUN),
    # (not in the logged crowns field): theoretical adds the full 900/run,
    # effective adds it scaled by the success rate (failures pay no tokens).
    # CAVEAT: that payout assumes the run COMPLETES the mission. A partial run
    # (mission_data numFloors below the real floor count) never finishes it, so
    # its token value is 0 — set TOKENS_PER_RUN above for such missions.
    # Summed PER ROW from each run's own mission, so the combined section stays
    # right when the missions in it pay differently.
    def _token_crowns(row):
        per_knight, each = TOKENS_PER_RUN.get(row.get("mission") or "(untagged)",
                                              TOKENS_PER_RUN_DEFAULT)
        return PARTY_SIZE * per_knight * each

    token_total = sum(_token_crowns(r) for r in successes)
    eff_crowns = avg_crowns_all + token_total / total
    avg_tokens_succ = token_total / n_succ if n_succ else 0
    cph_eff = 3600000.0 * eff_crowns / avg_runtime_all if avg_runtime_all > 0 else None
    cph_theo = 3600000.0 * (avg_crowns + avg_tokens_succ) / avg_runtime if avg_runtime > 0 else None
    print(f"  Crowns/hour (effective):   {f'{cph_eff:,.0f}' if cph_eff is not None else 'n/a'}")
    print(f"  Crowns/hour (theoretical): {f'{cph_theo:,.0f}' if cph_theo is not None else 'n/a'}")
    print()


def send(sock, ports, key, down):
    msg = f"{key} {1 if down else 0}".encode()

    for port in ports:
        sock.sendto(msg, ("127.0.0.1", port))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ports", default="%d-%d" % (BASE_PORT + 1, BASE_PORT + MAX_ALTS))
    parser.add_argument("--window-count", type=int, default=0)
    args = parser.parse_args()

    ports = parse_ports(args.ports)
    windows = select_windows(args.window_count) if args.window_count else set()

    game_class = ""
    if windows:
        first_hwnd = list(windows)[0]
        game_class = get_wm_class(first_hwnd)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    follow_enabled = False
    pvp_queue_enabled = False
    combat_bot_enabled = False
    loot_mode_enabled = False
    routine_enabled = False
    heat_hud_visible = True  # HUD shows by default (matches SocketInputState.showHeatHud)
    debug_enabled = DEBUG  # runtime dev-mode state ('=' toggles; mod-side default is also off)

    print()
    print(f"Sending to ports: {ports}")
    print()
    print("=== AUTONOMOUS (full automation toggles) ===")
    print("Ctrl+R  Endless mission cycle on the main: launch the active mission")
    print("        (routines/active_mission.txt) -> invite alts -> clear all floors")
    print("        -> relaunch -> loop. Toggle off to stop.")
    print("        Per-run stats -> ~/.sk-utils/mission_stats.jsonl")
    print("Ctrl+Q  Blast Network (PvP) auto-queue on all chars (main included):")
    print("        queue -> match -> return to ready room -> requeue")
    print("Ctrl+E  Auction bot LIVE mode on the main: sweep the AH and BUY/BID per")
    print("        ~/.sk-utils/auction_watch.txt (spends crowns!). Finds -> auction_finds.log")
    print("Ctrl+W  Auction auto-SELLER on the main: keep inventory LISTED per")
    print("        ~/.sk-utils/auction_sells.txt — relists an item only when ALL its")
    print("        live listings are gone (pays listing fees!)")
    print()
    print("=== DATA ===")
    print("Ctrl+\\  Summarize mission_stats.jsonl in this terminal (success rate, avg runtime")
    print("        & crowns for successes, avg deaths across all runs)")
    print("Ctrl+]  Summarize forges.jsonl in this terminal (forges, boxes by star, box rates:")
    print("        pooled, 1-box, 9->10, 9->10 2-box)")
    print("Ctrl+U  Dump every item's display + config name to ~/.sk-utils/item_configs.txt")
    print("        (the lookup table for writing auction_watch.txt rules)")
    print()
    print("=== MANUAL ASSISTS ===")
    print("Ctrl+F  Auto-follow: alts trail the main (manual synchronized play;")
    print("        NOT required for the Ctrl+R cycle)")
    print("`       Custom heat-status HUD on all windows")
    if DEBUG:
        print("Ctrl+B  [DEV] Combat bot on all chars: fire at the nearest enemy")
        print("Ctrl+N  [DEV] Loot mode on the main: sweep nearby heat/crowns; alts follow")
    print()
    print("=== RECON (for authoring routines) ===")
    print("Ctrl+D  Dump the loaded scene (tiles/actors/walk-grid) to the main's debug.log")
    print("Ctrl+L  Dump the main's current map coords to the main's debug.log (works with debug off)")
    print("=       Toggle DEBUG mode: mod debug.log writing on all clients (no rebuild)")
    print("        + the [DEV] hotkeys here (Ctrl+B combat bot, Ctrl+N loot mode)")
    print()
    print("G / H / 1 / 2 / 3 (barrier / vial / sprite abilities) are handled in-game by the mod")
    print()

    def focused_on_game_window():
        if not windows:
            return True

        hwnd = active_window()
        curr_class = get_wm_class(hwnd)

        return hwnd in windows or curr_class == game_class

    def on_press(key):

        nonlocal follow_enabled
        nonlocal pvp_queue_enabled
        nonlocal combat_bot_enabled
        nonlocal loot_mode_enabled
        nonlocal routine_enabled
        nonlocal heat_hud_visible
        nonlocal debug_enabled

        # Ctrl+\ prints a mission-stats summary to this terminal. No game focus
        # required — it just reads the log file, so it works while viewing the terminal.
        if is_stats_key(key):
            print_mission_stats()
            return

        # Ctrl+] prints a forge-stats summary (forges, boxes by star, pooled box
        # rate, pieces burnt). Also file-read only — no game focus required.
        if is_forge_stats_key(key):
            print_forge_stats()
            return

        # Ctrl+F toggles auto-follow
        if is_follow_key(key):
            if not focused_on_game_window():
                print(
                    "Focused window is not a game window. Auto-follow toggle ignored."
                )
                return

            follow_enabled = not follow_enabled
            print(f"Auto-Follow {'ON' if follow_enabled else 'OFF'}")
            send(sock, ports, "AUTOFOLLOW", follow_enabled)
            return

        # Ctrl+Q toggles Blast Network auto-queue on all characters, main included.
        if is_pvp_queue_key(key):
            if not focused_on_game_window():
                print(
                    "Focused window is not a game window. PvP auto-queue toggle ignored."
                )
                return

            pvp_queue_enabled = not pvp_queue_enabled
            print(f"PvP Auto-Queue (Blast Network) {'ON' if pvp_queue_enabled else 'OFF'}")
            msg = f"PVPQUEUE {1 if pvp_queue_enabled else 0}".encode()
            for port in ports + [MAIN_PORT]:
                sock.sendto(msg, ("127.0.0.1", port))
            return

        # Ctrl+B toggles the combat bot on all characters, main included.
        # DEV-ONLY: combat is driven automatically by the mission cycle now.
        if is_combat_bot_key(key):
            if not debug_enabled:
                return
            if not focused_on_game_window():
                print(
                    "Focused window is not a game window. Combat bot toggle ignored."
                )
                return

            combat_bot_enabled = not combat_bot_enabled
            print(f"Combat Bot {'ON' if combat_bot_enabled else 'OFF'}")
            msg = f"COMBATBOT {1 if combat_bot_enabled else 0}".encode()
            for port in ports + [MAIN_PORT]:
                sock.sendto(msg, ("127.0.0.1", port))
            return

        # Ctrl+D dumps the loaded scene (statics + actors) to the main's
        # debug.log. One-shot per press; sent to the main only.
        if is_scene_scan_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Scene scan ignored.")
                return

            print("Scene scan requested (see main debug.log)")
            sock.sendto(b"SCENESCAN 1", ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+L dumps the main player's current map coordinates to the main's
        # debug.log (always written, even with the mod's debug flag off). One-shot
        # per press; sent to the main only. Handy for authoring routine coords.
        if is_dump_pos_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Position dump ignored.")
                return

            print("Player position dump requested (see main debug.log)")
            sock.sendto(b"DUMPPOS 1", ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+N toggles loot mode: the main sweeps nearby heat/crowns and returns
        # to start; alts trail via auto-follow. Sent to the main only.
        # DEV-ONLY: looting is driven automatically by the mission cycle now.
        if is_loot_mode_key(key):
            if not debug_enabled:
                return
            if not focused_on_game_window():
                print("Focused window is not a game window. Loot mode toggle ignored.")
                return

            loot_mode_enabled = not loot_mode_enabled
            print(f"Loot Mode {'ON' if loot_mode_enabled else 'OFF'}")
            msg = f"LOOTMODE {1 if loot_mode_enabled else 0}".encode()
            sock.sendto(msg, ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+R toggles the endless mission cycle on the main: launch the active
        # mission -> alts auto-join -> clear all floors -> relaunch -> loop. Alts
        # auto-join on their own now, so auto-follow (Ctrl+F) is NOT required. Sent
        # to the main only.
        if is_routine_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Mission cycle toggle ignored.")
                return

            routine_enabled = not routine_enabled
            print(f"Mission Cycle {'START' if routine_enabled else 'STOP'}")
            msg = f"ROUTINE {1 if routine_enabled else 0}".encode()
            sock.sendto(msg, ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+E toggles the auction bot's LIVE mode on the main: while on it
        # sweeps the AUCTION HOUSE for the rules in ~/.sk-utils/auction_watch.txt
        # and BUYS/BIDS within each rule's cap; matches go to
        # ~/.sk-utils/auction_finds.log. The table is re-read on every sweep.
        if is_auction_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Auction sweep ignored.")
                return

            print("Auction LIVE mode toggled -> buys/bids per ~/.sk-utils/auction_watch.txt")
            sock.sendto(b"AUCTIONSCAN 1", ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+W toggles the auction auto-SELLER on the main: while on it keeps
        # inventory listed on the AH per ~/.sk-utils/auction_sells.txt — when ALL
        # live listings of a rule's item are gone (sold/expired), it lists fresh
        # ones. The table is re-read on every pass; listing fees come out of the
        # main's wallet.
        if is_auction_sell_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Auction seller ignored.")
                return

            print("Auction SELLER toggled -> lists per ~/.sk-utils/auction_sells.txt")
            sock.sendto(b"AUCTIONSELL 1", ("127.0.0.1", MAIN_PORT))
            return

        # Ctrl+U dumps every item's display name + config name to
        # ~/.sk-utils/item_configs.txt — the lookup table for writing
        # auction_watch.txt rules. Reads the config manager only; no network.
        if is_item_dump_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Item dump ignored.")
                return

            print("Item catalog dump requested -> ~/.sk-utils/item_configs.txt")
            sock.sendto(b"ITEMDUMP 1", ("127.0.0.1", MAIN_PORT))
            return

        # = toggles DEBUG mode: the mod's debug.log writing on every client
        # (DEBUGMODE broadcast; volatile flag, no rebuild) + this script's [DEV]
        # hotkeys (Ctrl+B / Ctrl+N).
        if is_debug_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Debug toggle ignored.")
                return

            debug_enabled = not debug_enabled
            print(f"DEBUG mode {'ON' if debug_enabled else 'OFF'} (mod debug.log + [DEV] hotkeys)")
            msg = f"DEBUGMODE {1 if debug_enabled else 0}".encode()
            for port in ports + [MAIN_PORT]:
                sock.sendto(msg, ("127.0.0.1", port))
            return

        # ` toggles the custom heat-status HUD on every instance (main + alts).
        if is_toggle_key(key):
            if not focused_on_game_window():
                print("Focused window is not a game window. Heat HUD toggle ignored.")
                return

            heat_hud_visible = not heat_hud_visible
            print(f"Heat HUD {'shown' if heat_hud_visible else 'hidden'}")
            msg = f"HEATHUD {1 if heat_hud_visible else 0}".encode()
            for port in ports + [MAIN_PORT]:
                sock.sendto(msg, ("127.0.0.1", port))
            return

    try:
        with keyboard.Listener(on_press=on_press) as listener:
            listener.join()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
