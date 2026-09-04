# sk-utils.
**evil little nuclear bomb of a mod. use at your own discretion.**



### Building from source

- Prerequisites: **Windows** (sorry Mac/mobile players!), a **JDK 25 or newer** (`javac`/`java` on PATH),
  **Python 3 with `pynput` + `pywin32`**, and, of course, **SK.** The **Knight Launcher** for SK is also
  highly recommended. The first build downloads `javassist.jar` automatically.
- Run **`.\build.ps1`**. It auto-detects your game install; override with `-InstallDir <path>`,
  the `SK_INSTALL_DIR` env var, or `install_dir=` in config.properties. It should output
  `sk-utils-mod.zip`.
- The mod typically **auto-heals itself between SK game updates**, so *all you need to
  do in most cases* is **rebuild it using `build.ps1` and load the new `sk-utils-mod.zip` into KL as a mod.**
- To pass the source on, run **`.\package-source.ps1`** → `sk-utils-src.zip`. It packs an
  explicit include list (sources, build scripts, multibox.py, config template, README).

---

## -1. A little backstory and some words of warning

**UPDATE: For the last time! I won't be finishing the FSC routine for autopilot,
I won't be adding autopilot for random floor layouts, and I won't be adding Mac support.**
If there are any genuine bugs or problems with the mod *then* open an issue on the Github. 


If you're reading this, well, you've decided to take a look at this weird little mod!

At the time of writing, this mod has the following functionality:
- mostly-complete multibox, with synced movement, attacks, shields, sprite abilities, etc. Only thing
I never cracked was swapping weapons, so you'll have to run splitscreen and swap weapons on each client manually.
- handsfree autopilot on missions
- handsfree blast network botting for mod calibrators (where do you think those 13k mod cals came from?)
- AH sniper bot (that I didn't even really use...)
- a handy HUD that shows the heat levels of your gear mid-floor
- a funny little DPS counter
- data utilities to tally forge probabilities and autopilot mission success rates
- abandoned code section that would've let bots dodge the wheels in FSC (Yes, this warranted an entire code file.)

All of this is obviously highly bannable!
That being said, to quell your fears of the Ban Stick a little, this mod does *not* talk to the server.

It writes *nothing* to `projectx.log` or any other file that the devs can remotely dump.

As such, **there is *no* automatic server-side tell that you are using this mod**, at least not at time of writing.

The only way you get banned is if you attract enough attention for a dev/GM to *actively tune into your botted game session*.
*Don't* pass around ill-gotten mixmasters between your main+alts like hot potatoes, *don't* bot right after major game updates, and *don't* brag about it.

**TLDR: Don't be a dummy like me and you should be fine.**

---

## 0. Install & configure
- **Download this zip folder**, which contains the source code for the mod.
- **Run `build.ps1`. This will produce `sk-utils-mod.zip`. That is what you want to put into Knight Launcher.**
- Before you boot the game up with the mod, **set the `main_account` variable in `~/.sk-utils/config.properties`**.
- The `/.sk-utils/` subfolder that ships with the mod **goes in your Users/YourWindowsUsername/ folder.** It has some of my
  pre-fab routines, the config file that you need to set, and stuff like debug logs and data.
- The mod is built against ONE exact game version. Game updates will require the mod to be re-built.
- Runtime config lives in **`~/.sk-utils/config.properties`**, where **`~/`** refers to your user
 folder (`C:/Users/XYZ/` or whatever).
  - `main_account=` — **required.** The KNIGHT (character) name of the main/controlling
    account.
  - `party_size=` — total accounts being botted/multiboxed, main + alts *(default 4)*.
  When in full-auto mode, the bot waits for exactly this many knights on every floor.
  - `udp_base_port=` — first port of the mod's local control plane *(default 40000)*: the
    main listens on it, alts take the next 20. Change only on a port conflict;
    `multibox.py` reads this same file so both sides stay in step.
- **Main vs alt detection:** a client running under Steam is the MAIN; clients launched
  on standalone client are ALTS. If your setup differs for some reason, set the environment
  variable `SK_ROLE=main` or `SK_ROLE=alt` on a client before launching it.
- The hotkey layer is `multibox.py` (Python 3 with `pynput` + `pywin32`), run on the same
  machine as the clients.
- When running missions on full-auto, the bot reads routine files from
  `~/.sk-utils/routines/` folder (and `prop_masks/` if present). The mod ships with a few default
  routines that I used to use (Axes of Evil, Snarby, Beyond Axes of Evil for when mirrored farming was briefly a thing).

  The RJP and FSC routines in the routines folder were *never finished*.
  
  Feel free to develop your own autopilot routines!

  **Where they go:** the source package ships a `.sk-utils/` folder. Copy it into your user
  folder as `~/.sk-utils` (i.e. `C:\Users\<you>\.sk-utils`) before first launch; it carries the
  routines, prop masks, auction watch table and a config template.


---

## 1. Hotkeys at a glance

Start the hotkey layer with `python multibox.py` in a terminal beside your game windows. It
listens globally; most keys only fire while a game window is focused, and everything is a
toggle unless noted.

| Key | What it does |
|---|---|
| `Ctrl+R` | **Autopilot** — endless hands-free mission cycle (§6 onward) |
| `Ctrl+F` | **Auto-follow** — alts trail the main and mirror your actions (§2) |
| `Ctrl+Q` | **Blast Network auto-queue** — BN farm on four accounts (§3) |
| `Ctrl+E` | **Auction bot** LIVE mode — sweeps the AH and buys/bids per your watch table (§4) |
| `Ctrl+U` | Dump the item catalog to `~/.sk-utils/item_configs.txt` (for writing auction rules) |
| `` ` `` | Show/hide the heat HUD + DPS card on every client window (§5) |
| `Ctrl+\` | Print a mission-stats summary in `multibox.py`'s terminal |
| `Ctrl+]` | Print a forge-stats summary in `multibox.py`'s terminal |
| `Ctrl+D` / `Ctrl+L` | Routine-authoring recon: dump the loaded scene config / your map coordinates to the main’s `debug.log` |
| `=` | Debug mode — `debug.log` writing on all clients, plus the [DEV] keys `Ctrl+B` (combat bot) and `Ctrl+N` (loot sweep) |

---

## 2. Manual play — auto-follow (`Ctrl+F`)

You're playing the game, dragging some alts on bk/spikes farm, but it's just a little too slow...got it.

`Ctrl+F` toggles auto-follow, which makes your alts mirror your every move. While it’s on, every alt:

- follows the main, with a catch-up dash when it falls far behind;
- mirrors your attacks, shield, dash (aimed where you’re aiming) and your sprite ability
  presses;
- auto-accepts invites from your main;
- pops a **Health capsule** below 1/3 HP and a **Remedy capsule** on any status effect from
  its own quickbar — alts do this whether or not follow is on, so keep them stocked.

Additionally, pressing `G` makes everyone pop a random barrier item (if present) from quickbar
(useful for shredding silkwings/greavers), and pressing `H` makes everyone pop a random throwable vial.
Your keys are read from the game’s own control scheme, so rebinding is fine (see Control
scheme under §8).

---

## 3. Blast Network auto-queue (`Ctrl+Q`)

Still making crowns too slow? I got you...carry on the Prince of Krogmo's legacy. Who decided that
mod calibrators should cost so damn much anyways?

Hands-free PvP payout farming. All four accounts, main included, queue team Blast Network, play,
return to the ready room and requeue until you toggle this mode off.

Once a match is live the rig kicks in: the losing side (the team opposite the main — or, when
exactly three clients are fully kitted with Krogmo Coin boosters, the team opposite that pair)
places a bomb on itself and quits, feeding the win, while a nudged movement key keeps the main
from idling out.

---

## 4. Auction house bot (`Ctrl+E` / `Ctrl+U`)

This one's for the actual greedy bastards. I never really used this one much.
Snatching stuff from people who want it was always a little too evil for my tastes, but the gun is in your hands now.

AH sniper that runs from anywhere. Rules live in
`~/.sk-utils/auction_watch.txt` (seeded on first use, re-read before every sweep, no rebuild):

```
<item name substring> | <max price> | <exclusion, exclusion, ...>
Aura             | 100k | Haunted Aura, Ghostly Aura
Mirrored Lockbox | 200k
```

`Ctrl+E` with `multibox.py` open toggles **LIVE mode**: while on it sweeps the AH every few seconds and
**spends real crowns** — buys any matching buyout at or under your cap, and bids up to the cap on bid-only
listings (re-bidding when outbid). Finds are logged to `~/.sk-utils/auction_finds.log`.

If you're having trouble getting matches for your item, `Ctrl+U` dumps every item’s display name + config name to
`~/.sk-utils/item_configs.txt` — variants often come last in config names (“Shadow Valiant Visor”
is `...Valiant Visor, Shadow`, for example).

---

## 5. HUD, forge helpers & data files

- **Heat HUD** (the backtick key, `` ` ``, toggles it on every game client): each client shows
  its gear slots with level and heat-% bars, plus a **SYNC** button — swaps your equipped gear
  to the highest level that *every* knight in the party has available, so gear levels line up.
- **DPS Breakdown** card (same backtick toggle): each knight’s share of the party’s damage in the
  current dungeon; resets whenever you enter a new one.
- **Forge helpers**: **Ctrl+click the Forge button** in the forge window to max-crystal forge every
  heat-ready item in one go and swap fully-leveled equipped gear for fresh copies from your
  inventory. Autopilot mode does both in every lobby on its own.
- **Data in `~/.sk-utils/`**: `mission_stats.jsonl` — one row per Autopilot run (`Ctrl+\`
  summarises: success rate, runtimes, crowns/hour per mission); `forges.jsonl` — every forge
  and box drop (`Ctrl+]` summarises); `auction_finds.log`. `debug.log` stays silent by
  default — press `=` to turn debug writing on across all clients when something misbehaves
  (it grows fast); the `Ctrl+D` / `Ctrl+L` recon dumps write regardless.

---

## 6. Autopilot (`Ctrl+R`)

Too lazy to even play the game? Should've figured as much, you wouldn't be here otherwise. ;)

`Ctrl-R` toggles full autopilot mode, which takes your goons and runs them through a mission/floor
of your choice fully hands-free. Now go and be free. (and restart your clients every 8-12 hrs or so
, SK's memory leak isn't something I can fix.)

1. The hotkey `Ctrl+R` toggles endless cycling of the mission defined in `active_mission.txt`,
  which in turn lives in  `~/.sk-utils/routines/` (more talk on this folder later).
   The main will create a private lobby for the selected mission, then **invite** the missing
   alts; the alts auto-accept.
2. On every floor the bot waits for all `party_size` knights to load in, runs the floor's routine, then
  descends; last floor done → relaunch → repeat.
3. In each fresh lobby, before walking everyone to ele, every client runs its **lobby passes**
   and the lobby routine holds until they finish (30 s backstop):
   - **Forge pass** — forge every heat-ready item; replace fully-leveled equipped gear.
   - **Sprite feed pass** — the equipped battle sprite (below level 100) is fed its
     bracket food (Mote <15, Dust <30, Stone <50, Orb <75, Star to 100; the appetite bar
     holds 5 feeds and refills one per 12 min) and leveled up whenever its heat bar
     fills — with another bracket food, or an **Evo / Advanced Evo / Ultimate Evo
     Catalyst** at 14→15, 49→50, and 89/94/99. Items are found by config name in each
     character's own inventory — keep foods and catalysts stocked; a missing item pauses
     that sprite's feeding and warns in chat once per session.

     **NOTE: Evo catalyst feeding is a little buggy atm.** You might have to do those levels manually.

     **NOTE 2: I only ever used this with autofeeding my Drakons.** No clue if it works with other pets, probably not.

**Auto-abort (relaunch a fresh mission)** fires on any of:
- **60 sec no progress** on a single step (position watchdog — re-anchors whenever the main really
  moves, so long-but-moving steps like combat or loot sweep never trip it);
- **`KEY_LIFT`/`LIFT` or `GATE` timeout** (floor unfinishable without it);
- **`CRITICAL_MOVETO` or `ELEVATOR` gather timeout** (30 sec — a missing player would strand
  the party, so these fail closed);
- **the main dying twice** in one run (alts follow main, so if main is lying dead on the floor
  we need to restart.)
- **a party wipe** — main *and* every alt at 0 hp continuously for 10 sec.
- **the main not moving for 2 minutes**, *anywhere at all* — dungeon, Haven, ready room, or
  stuck on a loading screen (dead-man's switch, checked every input-poll frame rather than
  per scene tick; an unreadable position counts as not moving). The 60 s watchdog only
  exists while a routine runs; this should catch everything else.

---

## 7. Routine files & layout

Your full-auto mission routines live in `~/.sk-utils/routines/`. These consist of
some metadata about each mission and the exact steps the bot will follow to complete them.
(more about what *steps* exactly are available later.)
The folder should be structured as follows:

```
routines/
├── active_mission.txt      # one line: the active mission's subfolder name
├── mission_lobby.txt       # SHARED lobby routine — every mission starts here
└── <mission>/
    ├── mission_data.txt    # "<launchId> <numFloors> <difficulty>"  e.g. "shadow_beast 3 HARD"
    └── <floorKey>.txt      # these are routine files. you should have one per non-lobby floor in the mission, named by its scene key
```

- `active_mission.txt` selects the mission; bot re-reads on every `Ctrl+R` toggle.
- `mission_data.txt`: `numFloors` **excludes** the lobby; difficulty = `EASY`/`MEDIUM`/`HARD`.
  **`numFloors` is authoritative, not descriptive** — set it *below* the mission's real floor
  count to farm a partial run (e.g. `1` to repeat floor 1 of a mission, which is what I used to do with Axes of Evil).
  Once that many floors are done the run counts as complete and the main relaunches.
  This file may also carry optional extra lines after the first. Currently supported:
  - `hazard_configs=generic marker, some other config` — extra placeable config
    substrings **this mission only** treats as hazardous terrain.
    Don't set or include this line if you don't know what you're doing.
    This is for dungeons that mark randomised hazard spawn points with generic placeables.
  - `2 | Construct, Slime, Undead` — The mod was designed for everyone to have an
    **autogun-line gun in weap slot 1** and a **blaster-line gun in weap slot 2**. The use of other weapons\
    will require you to dig into my source code to customize the weapon firing cadences.
    By default, bots will shoot at enemies with weapon 1. This line lets you specify which monster families,
    if any, your knights will switch to weapon 2 against.
    `2 |` with nothing after the bar = never switch (weapon 1 always).
    **No line at all = the default Construct, Slime, Undead trio shown above**.
- **Format of routine files:** one step per line — `TYPE <params...>`. `TYPE` is case-insensitive; every
  command's params start with world-coordinate `X Y` (1 tile = 1.0); some take more
  (e.g. `WAIT X Y SECONDS`). `#` starts a comment; blank lines ignored. More on this below.
- **Strict parsing:** wrong param count / unknown type / bad number fails the whole routine file.

---

## 8. Global invariants

Stuff to keep in mind when you're designing new routines or revising old ones:

- **Only the MAIN reads the routine.** Alts breadcrumb-follow the main's exact path.
- The mod was designed for everyone to have a **autogun-line gun in weap slot 1** and a **blaster-line gun in weap slot 2**.
  You'll have to dig into my code if you want this to work with any other weapons...sorry!
- **Movement is driven by A\*** over a walkability grid. Breakable shrubs/stone, explosive,
  crystal and treasure blocks, as well as block clusters connected to ghost blocks are considered walkable
  (shot on approach); unbreakable blocks, monster objects, levers and heart treasure blocks are routed around.
- **Ghost blocks:** shooting a `Block/Ghost` destroys every block connected to it, so the whole
  connected cluster counts as a destructible gate rather than a wall — it stays routable whether
  or not it currently stands, and gets shot open when the party reaches it (which also covers the
  block respawning mid-crossing). `Ctrl+D`'s walk-grid dump marks those cells `G`.
- **Automatic shield-bump:** during non-combat movement everyone briefly shields (~750 ms) when a
  monster comes within 1 tile.
- **Combat orbit:** combat phases orbit ~1.5 tiles around the step's `(X,Y)`, so leave that much
  clear ground. Enemy/clear detection measures from the fixed `(X,Y)`, not the orbiting main.
- **Sprite ability 1 fires automatically** during `COMBAT`, `COMBATLOOT` and `PLATFORM`'s arena every 19 s,
at the enemy closest to each knight respectively. Squeezes out a little extra damage/utility during autopilot runs.
- **Party gathers** (`CRITICAL_MOVETO`, `PLATFORM`, `ELEVATOR`) time out after 30 s.
- **End-of-floor screens are dismissed automatically** while the cycle runs.
- **Controls follow YOUR key layout** — see below. Nothing in the bot assumes WASD/X.

### Control scheme

The mod reads the game's own bindings instead of hardcoding keys, so rebinding in the
options screen doesn't break anything. They come from the projectx Java Preferences node in your registry
(`HKCU\Software\JavaSoft\Prefs\projectx`). 

Bindings load at startup and re-load on each `Ctrl+R` toggle. Anything unbound or unreadable falls back to
my own personal hardcoded default, and `debug.log` records the resolved scheme on load
(`[keybinds] move=WASD defend=X dodge=LSHIFT+X action=mouse1 …`).

---

## 9. Step commands

Type codes as they appear in debug logs:

| Code | Command | Code | Command |
|-----:|---------|-----:|---------|
| 0 | `MOVETO` | 10 | `GATE` |
| 1 | `COMBATLOOT` | 11 | `BUTTONSWEEP` |
| 2 | `ATTACKMOVE` | 12 | `HAZARD_MOVETO` |
| 3 | `SHOOT` | 13 | `CRITICAL_MOVETO` |
| 4 | `PLATFORM` | 14 | `TREASURESWEEP` |
| 5 | `ELEVATOR` | 15 | `WAIT` (incl. `WAIT3`) |
| 6 | `LOOT` | 16 | `KEY_DROP` |
| 7 | `BUTTON` | 17 | `SNARBY` |
| 8 | `MINERALS` | 18 | `LIFT` (incl. `STATUE_LIFT`) |
| 9 | `KEY_LIFT` | 19 | `DROP` (incl. `STATUE_DROP`) |
| — | | 20 | `KILL` |
| — | | 21 | `PRECISE_MOVETO` |
| — | | 22 | `SWITCHSWEEP` |
| — | | 23 | `COMBAT` |
| — | | 24 | `HAZARD_LOOT` |
| — | | 25 | `ALCH_CHARGE` |
| — | | 26 | `PIN_MOVETO` |

### Movement

**`MOVETO X Y`** *(type 0)* — Path to `(X,Y)`, clearing breakables en route. No combat.
Blind to hazards along the way.

**`CRITICAL_MOVETO X Y`** *(type 13)* — Path to `(X,Y)`, hold until the party gathers, then
advance. Gather timeout **aborts the run**.

**`WAIT X Y SECONDS`** *(type 15)* — Stand still for `SECONDS`, then advance. **No pathing.**
A prior step must have positioned the party at `(X,Y)`. `WAIT3 X Y` = `WAIT X Y 3`.

**`PIN_MOVETO X Y START` … `PIN_MOVETO END`** *(type 26)* — A **scoped pair**, not a one-shot
move. `START` paths in and seats the main **hard against collision** at `(X,Y)` — no arrival
tolerance at all, it walks into the spot until the push stops making progress — and then
**holds** it: every tick until `END`, the main is pushed back into that spot, so recoil and
shoves can't accumulate. I used this exclusively for Beyond the Axes of Evil, where main had to
charge and shoot an alchemer at a ghost block repeatedly until the block cleared. Example below:
```
PIN_MOVETO 11.67 -8.67 START
ALCH_CHARGE <bankX> <bankY> <gateW> <gateZ>
PIN_MOVETO END
```

**`PRECISE_MOVETO X Y`** *(type 21)* — `MOVETO` with a **same-tile guarantee**: the main paths
in, then fine-settles onto `(X,Y)`; the alts follow.
Advances only when **every** knight stands on the tile containing `(X,Y)`.
(ordinary arrival thresholds stop up to a tile short). Gather timeout **aborts the run**, like
`CRITICAL_MOVETO`. Use this when the next step needs the party on one exact tile.

### Combat

**`COMBATLOOT X Y [RANGE]`** *(type 1)* — Path to `(X,Y)` → fight (orbiting `(X,Y)`) until no
living monster within `RANGE` tiles of `(X,Y)` (optional; default 10) → loot the drops.
`RANGE` sets **enemy detection only** — the loot sweep keeps its own radius.

**`COMBAT X Y [RANGE]`** *(type 23)* — `COMBATLOOT` without the loot pass: path to `(X,Y)` →
fight (orbiting `(X,Y)`) until no living monster within `RANGE` tiles of `(X,Y)` (optional;
default 10) → return to `(X,Y)` → advance.

**`ATTACKMOVE X Y [RANGE]`** *(type 2)* — Path to `(X,Y)` with combat on; done when arrived and
no living monster within `RANGE` tiles of the main (optional; default 10). **Does not orbit, does not loot.**

**`PLATFORM X Y`** *(type 4)* — Path to a Party Platform at `(X,Y)` → clear a 3-wave arena
(orbiting `(X,Y)`) → loot. Wave tracking is by monster identity.

**`KILL X Y`** *(type 20)* — `SHOOT` for stationary **monsters** (e.g. wheel launchers): alts fire
weapon 2 at `(X,Y)` until the Monster within 1.5 tiles of it **leaves the actor map**. Completion
is presence-based with a seen-latch. 20 s fire budget, then advances (fail-open); a
target never seen at all advances after 2 s of confirmed absence. **No pathing.** Position
first; key-carry-safe (the main never fires).

### Loot & gathering

**`LOOT X Y`** *(type 6)* — Path to `(X,Y)`, sweep for all loot within 10 tiles, then return to `(X,Y)`.

**`HAZARD_LOOT X Y`** *(type 24)* — `LOOT`, but the **approach** to `(X,Y)` waits-and-crosses
spike-trap fields like `HAZARD_MOVETO`.

**`TREASURESWEEP X Y [RANGE]`** *(type 14)* — `MOVETO(X,Y)` → shoot `Block/Treasure` within
`RANGE` tiles (optional; default 8) → loot from `(X,Y)`. Skips stubborn blocks (5 s unreachable
/ 6 s shooting) and skips the whole step after 40 sec rather than stall the run.

**`MINERALS X Y`** *(type 8)* — Shoot mineral veins within 5 tiles of the main, then spreads the
party to grab one drop each. **No pathing.** Position party at `(X, Y)` beforehand.
One mineral per character per floor.

### Buttons, keys, gates, liftables

**`BUTTON X Y`** *(type 7)* — Path precisely onto the button at `(X,Y)` (8 s timeout → advance).
Will automatically shoot shrubs/stone blocks covering buttons first.

**`SHOOT X Y`** *(type 3)* — Alts fire weapon 2 (I almost always have an Arcana in this slot)
at `(X,Y)` from where the main stands until the target block is destroyed / switch flips (4 sec timeout).
**No pathing.** Position first.

**`BUTTONSWEEP X Y [RANGE]`** *(type 11)* — `MOVETO(X,Y)` →  then, for each button within `RANGE` tiles
(optional; default ~7.75, possibly under a covering breakable — shrub / stone / crystal):
shoot cover, step on, press → return to `(X,Y)`.

**`SWITCHSWEEP X Y [RANGE]`** *(type 22)* — `BUTTONSWEEP` for **levers** (`Dynamic/Switch/Lever/…`):
`MOVETO(X,Y)` → for each lever within `RANGE` tiles (optional; default ~7.75), approach to firing
range and **shoot it** until its state changes → return to `(X,Y)`. The **main** fires, so never
use it while carrying a key.

**`KEY_LIFT X Y`** *(type 9)* / **`LIFT X Y`** *(type 18)* — Pick up the liftable at `(X,Y)`
and carry it. Timeout **aborts the run**.
*Carrying = hands full:* any attack or damage taken **drops it**. No combat steps between
a `LIFT` and its matching `GATE`/`DROP`. `SHOOT` is safe, as alts do the shooting.

**KEY_LIFT/LIFT differ in what they'll pick up:**

| | matches | searches |
|---|---|---|
| `KEY_LIFT` | **gold keys only** | closest key within **1 tile** of `(X,Y)` |
| `LIFT` (`STATUE_LIFT`) | anything liftable — `Dynamic/Lift Objects/…` (statue, totem, *and* keys) | closest one within **0.5 tile** of `(X,Y)` |

`KEY_LIFT` is **tolerant** because a key is often put down by an earlier `KEY_DROP`, which
lands it anywhere within about a tile of *its* coordinate. This is safe only because `KEY_LIFT`
won't match anything but a gold key, so a wider net can't grab the wrong object.

`LIFT` is **strict** because it matches every liftable type: a statue is placed by the level and
sits on its coordinate, so 0.5 tile absorbs a nudge without ever latching onto a neighbouring
object.

**`KEY_DROP X Y`** *(type 16)* / **`DROP X Y`** *(type 19)* — Carry-safe path to `(X,Y)`, one
tap to set the carried object down. Timeout just advances.

**`GATE X Y`** *(type 10)* — Unlock the gold gate at `(X,Y)` with a carried key; advances
when the gate opens. Requires an intact gold key from a prior `KEY_LIFT`. Timeout **aborts the
run**.

**`ALCH_CHARGE X Y W Z`** *(type 25)* — I used this exclusively for Beyond Axes of Evil.
Charge-shot a switch a straight line can't reach. Make sure the main has a 5* alchemer in weapon slot 2.
The main equips weapon 2, holds attack aimed at `(X,Y)` for ~1.6 s, releases, then checks the
**ghost block at `(W,Z)`** — repeating until that block is gone. **No pathing**.
The charged shot's recoil knocks the main back, so wrap it in a `PIN_MOVETO … START/END` scope
to hold the firing spot. Gives up after 45 s and **aborts the run**.


### Hazards (spike traps)

**`HAZARD_MOVETO X Y`** *(type 12)* — Cross a spike field to `(X,Y)`, waiting at the 
edge for each trap's DOWN window. Shoots breakable blocks (shrubs, ghost blocks, whatever)
that obstruct the path as they come into range.

### Note on ghost blocks

Shooting a `Block/Ghost` destroys every block connected to it, opening up a new path that can be a
long winding wall. There is **no dedicated command** to handle this. Any moving step opens one
for you, because the block-clearing behavior treats a ghost block within 4 tiles as a target
*regardless of whether it sits on the path*.

- *Authoring:* `Ctrl+D`'s walk grid marks each ghost block `G`. Route a step to within
  ~4 tiles of it, and the gate opens; the **next** step then plans through the corridor.
- *Invariant:* unbreakable blocks connected to a ghost block stay **impassable** to the bot
  until the ghost is actually destroyed — A\* will not plan through a standing gate, so the step that goes
  near the ghost must come **before** any step that routes through it.
- *Respawns are self-healing:* an obstructing ghost block that respawns mid-crossing (or while
  `HAZARD_MOVETO` waits at a trap edge) is shot again automatically.
- *Corollary:* don't route a key/statue carry within 4 tiles of a ghost block — the main
  will fire at it and drop the carry.

### Boss fights

**`SNARBY X Y`** *(type 17)* — Snarbolax fight anchored at `(X,Y)`.
- All characters orbit in a 1.5 tile radius around `(X,Y)` with shields up.
- The Snarbolax is stunnable when he is howling, dodging, or using his chain-bite attack.
- Shields drop when the boss is in a stunnable action within 3.5 tiles of the bell or the main.
- When the Snarbolax dwells 250 ms within 3.5 tiles of a Beast Bell, **everyone** shoots the bell to stun it;
  then all attack with weapon 1 until it recovers.
- Knights will not drop shields or shoot the bell while Snarbolax is burrowing, warping,
  or doing his tail-whip.
- If the boss is using his chain-bite and is within 1 tile of the main, all characters dash.
- Loops until the Snarbolax is dead.

I never managed to get around to RJP, Twins, or FSC before the ban hammer came down. Good luck, pirates!

### Floor exit

**`ELEVATOR X Y`** *(type 5)* — Path the party onto the elevator at `(X, Y)`, gather, end the floor.
Gather timeout **aborts the run**. This must be the last step of every floor.

---

## 10. Routine authoring checklist

1. **Stationary commands don't path** — position the party before `SHOOT`, `MINERALS`, `WAIT`, `KILL`, etc.
2. **Nothing that deals damage between a `KEY_LIFT`/`LIFT` and its `GATE`/`DROP`**; keep the carry path
   clear of brambles and traps.
3. **Traps or hazards → use `HAZARD_MOVETO`**, never `MOVETO`.
4. **Ghost blocks → route a step within ~4 tiles of the `G` before anything that
   goes through the ghost block-gated area.**
5. Gather with `CRITICAL_MOVETO` before timing-critical crossings, or to step on party platforms.
6. **`(X,Y)` anchors combat and loot** — mob detection and drops are collected from there. Make sure there are
  3x3 tiles of clear ground around `(X, Y)` for knights to orbit around during combat.
7. **End every floor on its `ELEVATOR`.**
8. **Fail-closed steps**: The active mission will abort and be relaunched if any `KEY_LIFT`,
  `LIFT`, `GATE`, `CRITICAL_MOVETO`, `ELEVATOR` commands timeout.