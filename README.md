# Nothing Launcher+

An LSPosed module that adds **custom folders** to the Nothing Launcher app drawer.

Nothing Launcher's **Smart (Beta)** drawer layout sorts apps into folders by category ("Games",
"Tools", …). The categories come from Nothing's server, and you can't change them. This module
adds your own folders next to them. Apps you put in a custom folder leave their automatic one.

## Features

Everything new is opt-in: with the defaults the drawer looks exactly like stock Smart (Beta)
plus your folders.

- Create, rename and delete drawer folders and pick the apps in each.
- **Reorder the folders** by dragging them in the module's folder list. The drawer fills its
  rows two folders at a time in that order, so you choose which folders share a row and which
  row comes first.
- **Reorder apps inside a folder** by dragging them in the folder editor. The drawer tile and
  the opened folder follow that order.
- **Folder covers**: give a folder an image (centre-cropped to a square, stored at 1024 px) or an
  emoji. The cover replaces the app previews on the drawer tile. It stays square and never
  stretches.
- **Animated emoji covers**: every emoji (1,906, Unicode 16) can be a cover, picked from a
  searchable picker, and plays a motion that suits it — see *Emoji animations* below.
- **Apps without a folder as icons**: instead of an "Others" folder, the remaining apps are listed
  as normal app icons below the folders.
- **Drawer background opacity** slider, so the wallpaper shows through behind the folders.
- Show custom folders before or after Nothing's categories, or hide the automatic categories.
- A **Custom folders** row in *Nothing Launcher settings → App drawer & Search → Drawer layout*
  opens the module.
- Long-press a drawer folder for the launcher's own popup: **Add to home screen** creates a real
  home-screen folder with the same name and apps; **Edit** (custom folders) opens the folder editor.
- **Landscape (beta)**: the home screen and app drawer follow auto-rotate. Each home page is
  rearranged into a wide grid automatically, while your portrait layout stays as it is — see
  *Landscape* below.
- **Game controllers**: navigate home and the drawer with a DualSense, DualShock, Xbox, GameSir,
  Backbone or any standard gamepad — see *Controllers* below.
- **Export / Import** saves folders, options and cover images to one file, so the setup survives
  a reinstall.
- Changes apply right away through a reload broadcast. You don't need to restart the launcher.

## Emoji animations

- **Dot matrix** (on by default, switch in the module): the whole folder tile becomes a Nothing
  dot screen. The dots fill the card the launcher actually draws (read from the tile's own
  outline, so they follow its shape and rounded corners and never spill past it), as many whole
  dots as fit with equal margins on every side, and the emoji is lit in colour as a 32 x 32 dot
  image snapped onto the same grid. Motion is rendered into the grid each
  frame (3 x 3 supersampled, four solid brightness steps per dot), so an emoji moves across the
  dots like an image on an LED panel, with edge dots dimming in between for smooth motion.
  Frames whose dots don't change aren't redrawn, and a frame is two draws of one shared dot
  mask. The module's folder list and the emoji picker show the dot versions too.
- **Living dots**: on top of its motion, every emoji plays an effect inside its own dots,
  chosen for what it is. A TV fills with static (and now and then colour bars), a crystal ball
  swirls with magic, faces blink (a real blink: half-closed, shut into a "‿" lash line, open),
  a bead of sweat or a tear swells, hangs and runs down whole, fire flickers, clouds rain,
  coffee steams, buildings light their windows one by one, speakers pulse, notes rise, stars
  twinkle, clocks tick, sirens flash, fish blow bubbles into the open water, beer fizzes
  inside the glass, and so on, mapped to every keyboard
  emoji by the same generator as the motions.
- **Glowing screens**: the lit dots on the emoji screen throw a soft halo of their own colour
  into the glass (the frame stretched with smooth filtering, plus a half-resolution copy for
  the wider falloff), redone every frame so the glow moves with the animation.
- **Dot-matrix app icons** (option): every app icon the launcher draws, on the home screen, in
  the drawer, in folders and in search, is redrawn as a 22 x 22 panel of round dots in the
  icon's own colours on dark glass in the icon's own shape, each dot casting a dim halo of
  its own colour on the glass round it; now and then, each icon on its own timing, a faint
  green scan line rolls down its dots. The emoji folder
  screens have the quirks of an old dot screen, each on its own timing: now and then a faint
  green scan line rolls down, the panel flickers dim for a moment or a band of rows jumps a
  dot sideways. One hook on the launcher's icon drawable
  covers every surface; each icon is converted once and cached. When icons come into view
  (the drawer opening, going home, a folder opening) each powers on like a little screen: dots
  light from the centre outwards on a spring with a brief white LED flash, the glow fading up
  under them, icons a few milliseconds apart.
- **Folders open and shut**: 📁's front panel tips open on its bottom hinge, showing the
  shadowed inside and the papers in it, then closes again (📂 closes up and reopens). It is
  drawn from the emoji's own dots, so it never changes size; tap it and it opens at once.
- **Objects do what the real thing does**: 🎮 buttons are pressed in combos and the d-pad
  clicks; 🕹️'s stick and ball, lifted by their own shapes, tilt in and out of the boot with spring physics and the fire button flashes;
  📷 📸 press the shutter, close iris blades over the glass only and fire a flash bloom;
  📹 🎥 pulse their red REC lamp; 📽️'s reels turn under their arms; 🎬 snaps shut; 🔒 opens
  like a real padlock, the free leg popping out while the long leg stays in the body, then
  snaps shut; 🎯 has a dart thrown into the bull, quivering as it lands with its shadow, then
  pulled out; ⚽ 🏀 ⚾ 🏐 roll with their shading held still and 🏈 🏉 tumble end over end;
  🎾's ball is bounced on the racket; 🎲 tumbles; 🌍 🌎 🌏 turn as globes and 🌐 is drawn afresh as a see-through wire globe whose meridians go right round, bright in front and dim behind; ⌛ ⏳ drain and
  are turned over; 🎠's horse rides its pole; 🎡 turns behind its legs; ☯️ ☸️ 🔁 turn on
  their tiles, as do 🔃 🔄; 🚓 🚑 🚒 flash their roof lights; 🚘 🚖 🚍 flash their headlights; 🛸's rim
  lights chase; 🚲's spoked wheels turn; 🚦 🚥 cycle with a glow; ☎️ rattles its handset,
  📞 ⏰ ring, 🛎️'s plunger is struck; 🧰's clasps flip down, its lid swings open on its hinge showing the shadowed inside, drops shut and the clasps snap back with a glint; 🎛️'s knobs are turned by hand one after another; 🔌 sparks between its pins; ⚛️'s electrons orbit;
  ⚡ 🌩️ 💥 flash; 📶 🛜 fill bar by bar; buttons and keycaps are tapped; 👾 marches; 🎚️'s
  fader slides; 🕰️ ⌚ and the clocks tick their second hand; 💬 types; 📖 📝 📅 📈 📊 turn,
  write, flip, draw and grow; 🏭 🚂 smoke from the chimney, ✈️ trails; 🚀 throws sparks;
  ☂️ ☔ catch the rain; 🔥 🕯️ 🪔 ❤️‍🔥 and the birthday candles burn, the flame itself
  licking and swaying up from its root; 🎆 sends up a shell that bursts, blooms, droops and
  burns out, 🎇 🧨 crackle; 🌍 🌎 🌏 turn as the whole Earth, put together from all three
  globes, so the far side is really there; 🚿's jets rush and spray drops; 🏏 🏑 🏒 🏸 swing
  and knock the ball away, 🏓 🥍 keep it up, ⛹️ dribbles, 🏌️ drives; ⚽ 🏀 ⚾ spin as they
  are kicked up and 🏈 🏉 are tossed end over end; 💿 📀 hold still while their rainbow
  shimmers; 🚶 🏃 stride, legs scissoring about the hip; arrows nudge the way they point;
  🍿 pops; 🌫️ 🌁 roll; 🚬's smoke wavers; 🍳 sizzles; 🌡️ 🔋 💈 🧭 🎰 🖨️ ⌨️ 🖱️
  do theirs.
- **Faces and feelings**: 😍 😻 🥰 😘 💌's own hearts beat lub-dub; 😵 😵‍💫 have stars
  circling the head; 😴's drawn Zs light one after another, 😪's bubble breathes, sleepers
  without drawn Zs give off real Z letters; 😤's clouds burst out with each snort; 😅 😰 😓
  😥 🥵's sweat and 😢 🥲 😿's tears are lifted off whole and slide down, the skin under
  them patched and the outline redrawn; 🥹's tears well and shimmer; 😗 😚 😙 😽 blow a little
  heart; 😇 👼's halo hovers; 😈 👿 🧐 flash a glint; 😎 catches the light on its lenses; 👀
  glance about within their whites; 💔 cracks apart and mends; 🤖's antenna lamp blinks; 🤹
  juggles, the balls passing behind the head; only round open eyes blink, never ^ ^ or
  brows; angry faces flush from the chin up.
- **Ndot status bar** (option): System UI is in the module's scope; the status bar clock and
  battery are drawn in Nothing's NDot, lit the green of the folder name panels with a soft
  glow, on any wallpaper. Another option puts the date before the time. Restart System UI
  (or the phone) after switching either.
- **System font**: choose any of Nothing's fonts already on the phone (NDot, NType, and the
  ones inside Nothing's widgets app). It is applied the way Android applies its own fonts:
  with System Framework in the module's LSPosed scope (one reboot to load it into the
  system, once), the system's font map that every app receives is rebuilt with the chosen
  font as soon as you pick it, so every app opened from then on uses it, no reboot; System
  UI and the launcher are restarted at once. Phone default undoes it.
- **Animation test**: in the module's settings, play any emoji's animation on a big dot
  screen without making a folder for it: step through emoji one by one, replay its reaction,
  slow it to half or a quarter speed, and compare the dots with the plain emoji.
- **Nothing is cut off**: a hop, lean or swell that would carry an emoji past the edge of
  the panel is eased back so it comes up to the edge smoothly.
- **Particles are smooth**: steam, smoke, snow, rain, bubbles, confetti, sparks, hearts,
  glints and musical notes are drawn soft and anti-aliased between the dots, so they glide
  rather than jump; glows are a halo of the emoji's own light and never wash it out.
- **Moving parts are drawn, not shuffled dot by dot**: a stick, needle, dart, shackle, reel or
  ball is cut out of the emoji (what it covered filled in from the drawing itself) and moved
  before the emoji becomes dots, in the same supersampled frame, so it moves with smooth
  anti-aliased edges. While something moves the cover renders at the display's full rate (up
  to 120 Hz) and drops back to the panel's 24 fps once it rests.
- **Music plays by its own logic**, all to one shared 96 BPM beat, so the music folders in the
  drawer groove together: 🔊 lights its own drawn sound waves inner to outer like a volume
  meter, 🎵 🎶 light each note head as it sounds, 🎼 runs a playhead along the staff, 🎹 plays a
  melody on its keys (split where the black keys sit), 🥁 🪘 are hit and ripple from the head,
  🎸 🎻 🪕 strings vibrate after each strum, 🎷 🎺 📯 blow notes out of the bell, 🎧 shows an
  equaliser between the cups and 📻 in its display, 🎤 🎙️ glint with a voice, 🔔 rings
  ding-ding from the clapper, 📢 speaks syllables through the horn, 🔇 🔕 flare their red sign,
  🪇 rattle, 🎛️ turn their knobs. Effects find their parts of the emoji by
  analysing its resting dots (screens, glass balls, eyes, water, window panes), so they work on
  any emoji with no hand-drawn assets, follow the emoji as it moves, and play at 24 fps like a
  real LED panel.
- **Reactions**: touch a folder and its emoji reacts at once (its motion plus a flare of its
  effect, a double blink for faces); opening the drawer makes the covers on screen greet you,
  one after another.
- **Each folder is a little device**: the tile is the bezel, and inside sit two screens of
  black glass with a hairline rim. The emoji has the main screen; the name has its own
  pill-shaped display of finer dots (right out to its round ends) with a red status dot, lit
  as a green LED panel, each letter dot glowing softly into the glass, in Nothing's own NDot 57
  letters
  at the launcher's own name size (NDot puts its dots on an exact grid, one every tenth of the
  font size, so every letter dot is one of the display's dots). The font is
  not shipped with the module: it is loaded from the phone (the system fonts, or Nothing's
  widgets app, which bundles it). Without it, the name is lit on the panel's dots in a compact
  3 x 5 dot font. A name too long for the tile scrolls through it a dot at a time.

- **98 hand-tuned motions** (wave, heartbeat, hop, laugh, ring, launch, coin flip, flag wave,
  swim, flicker, …) built on spring physics: anticipation, squash and stretch, overshoot and
  settle. Each starts and ends at rest; `MotionQa`-style checks during development verify there
  are no jumps at 240 fps and no out-of-range poses.
- **Every emoji is mapped** by `tools/gen_emoji_motions.py` to a motion and an effect: exact
  CLDR-name overrides, then name keywords, then its Unicode subgroup. The folder editor shows the
  chosen motion and lets you pick any other, or none.
- **Unique per folder**: speed, strength, pause length and phase come from the folder id, so two
  folders with the same motion never move in sync.
- **Cheap**: one shared vsync callback drives all covers, and only GPU-composited transform
  properties change (no redraw, no layout). Nothing runs while a cover is off-screen, the drawer
  is closed or the launcher is in the background, and plays are separated by pauses during which
  no frames are requested.
- **Frame rate**: while moving, a cover asks the panel for its highest refresh rate at the current
  resolution, capped at 120 Hz (Android 15+), and releases the request when it comes to rest.
  Motions are time-based, so they look the same at 60, 90 or 120 Hz. The system animation-scale
  setting is honoured (0 turns animations off).

Emoji names and groups come from [emojibase-data](https://github.com/milesj/emojibase) (MIT),
based on Unicode CLDR.

## Sound

Both off by default; switch them on in the module's **Sound** section.

- **Background music**, generated live (never a loop, never a recording) and tuned to
  A = 432 Hz, in stereo. Seven original styles in the manner of classic console menus. The
  PlayStation-menu kind come first, deliberately simple and matched by measurement (spectrum,
  stereo width, swell rate, register) to reference recordings: *Home Menu* (PS4-style: one warm
  chorused tone playing slow, ringing notes that overlap into chords, F3 to G4, with phrases and
  pauses; the default) and *System Browser* (PS2-style: wide filtered noise breathing in and out
  every few seconds, a cluster of every note of the scale between F3 and F4 over a low rumble, a
  slow soft ping line and rare glints sliding down). No drums, almost nothing above 2 kHz. Then *Crystal Shore*
  (90s new-age shimmer and surf), *Shop Lounge* (swung e-piano, walking bass, vibes), *Starlight
  Lullaby* (16-bit music box and flute) and *Pixel Meadow* (8-bit overworld).
- Each style has a master tone control; the calm ones keep essentially nothing above 2 kHz, and
  loudness is matched on what a phone speaker actually plays (above 200 Hz).
  A composer writes the music as it plays: motif, answer and cadence over changing progressions,
  a new progression every 16 bars and a new key every 32.
- Tapping an app lets the music recede at once: over 1.6 s it fades and closes its filter, like a
  console dimming its menu as a game starts.
- It plays only while the home screen or drawer is in front, fades in and out, never takes audio
  focus (so it can't pause your music), and goes quiet while another app plays media or a call
  is active. Synthesis runs on one audio thread into a power-saving track, about 1% of a core.
- **Interface sounds**: console-style chimes for opening and closing the drawer and folders,
  launching apps, turning pages, menus and controller focus, silent on silent or vibrate.
- **Preview** the selected style in the module; music volume has its own slider.

## Landscape

Nothing's DeviceProfile replaces AOSP's sizing with portrait-only math: cell width is derived
from the screen *height*, everything is scaled against a 1080×2392 portrait reference and the
hotseat is always assumed at the bottom. Nothing also made ten of AOSP's layout methods abstract,
deleting their landscape branches. Forcing rotation therefore squashes everything.

With **Home screen rotation** on, the launcher follows auto-rotate (through its own
`RotationHelper`, which Android 16 otherwise keeps off on phones). In phone landscape, and only
there, the module answers Nothing's sizing methods with AOSP's vertical-bar layout:

- hotseat as a bar on the side (left in seascape), workspace padding to match;
- cells filling the workspace, icon and label sizes from the grid, scaled to fit, labels hidden
  by AOSP's own logic when rows get tight;
- the 1080×2392 reference transposed, so icons keep their size;
- the Smart drawer gets 3–6 tile columns (4 on most phones) instead of two giant tiles.

Around the home screen:

- **Recents** rotates for real. Nothing emptied `RecentsOrientedState.updateHomeRotationSetting`,
  so recents drew its portrait layout turned sideways (thin cards, sideways menus); the module
  restores AOSP's flag and tells SystemUI.
- **Folders** open centred between the system bars. Nothing places them at a fixed portrait top
  margin, which pushed them off the bottom of a landscape screen.
- **The launcher's own screens** (search, home and drawer settings, icon packs, layout,
  wallpaper) are locked to portrait in Nothing's manifest; with rotation on they follow the
  device too.
- **Nothing's widget cards** (Calm / breathing, battery, photos, …) and their settings open in
  landscape. Nothing Widgets (`com.nothing.hearthstone`) and NothingCardService lock every screen
  to portrait, so cards opened from a landscape home screen were drawn on their side. Tick both
  apps in the module's LSPosed scope for this.
- **Smart drawer tiles** are sized for about one and a half rows, with icons scaled to the tile.

### Landscape grid

A portrait page of C columns × R rows is shown in landscape as **2C × ⌈R/2⌉**: the top half
of the page on the left, the bottom half on the right (a 4×6 page becomes 8×3). Cells keep
almost exactly their portrait shape, so icons, folders and widgets look as they do in portrait.

- Only the presentation changes. Positions go through AOSP's `CellPosMapper` (the hook tablets
  use for two-panel pages), so whatever you move, add or resize in landscape is saved in portrait
  coordinates, and rotating back shows your portrait layout.
- Items that sit across the middle row are shown on the half that holds most of them, in a free
  spot. Widgets taller than half the page are shown at half-page height; their real size is kept.
- The launcher deletes items that overlap or leave the grid when it next loads. Every landscape
  edit is therefore checked against the portrait page: anything that wouldn't fit there (a widget
  dropped across the middle, for example) is moved to the nearest free portrait spot and the page
  refreshes.

Nothing also blocks landscape outright: `NTLauncher.handleConfigurationChanged` ignores every
change to landscape ("Skip to handle the config change for the landscape orientation"), and the
landscape profiles are filtered out of the list the launcher picks from. With rotation on, the
module handles the change the way AOSP does and lets the launcher pick from the full list.

Each landscape profile is logged (`NothingLauncher+: landscape profile …`) with its geometry, so
LSPosed logs show exactly what was laid out. Portrait is untouched.

## Controllers

Android already maps the D-pad and left stick to arrow keys, A to select and B to back, and
Launcher3 moves its keyboard focus with them. The module adds:

| Button | Home screen | App drawer |
| --- | --- | --- |
| Y | Open drawer | Close drawer |
| L1 / R1 | Previous / next page | Scroll up / down |
| L2 / R2 | First / last page | Top / bottom |
| X | Shortcuts / menu of the focused item | same |
| Start | Home options (wallpaper, widgets, settings) | Close drawer |
| Select | Notifications | Notifications |

The focused icon or folder lifts on a spring (scale + elevation) and settles back the moment
the screen is touched.

## Requirements

- Nothing Launcher **4.0.20**. Later versions will probably work too, because the hooks avoid
  obfuscated names (see below).
- LSPosed (or another Xposed implementation with API 93+ and `xposedsharedprefs` support).

## Install

1. Build the APK (`./gradlew assembleRelease`) or download it from the *Build module* GitHub
   Actions run.
2. Install it, enable it in LSPosed and select **Nothing Launcher** as the scope (plus
   **Nothing Widgets** and **NothingCardService** for widget cards in landscape).
3. Force-stop Nothing Launcher, or use the "Restart Nothing Launcher (root)" button in the app.
4. In Nothing Launcher settings, set *Drawer layout* to **Smart (Beta)** and tap **Custom folders**.

## Updates and signing

Every build is signed with the same key (`signing/update.jks`, committed on purpose), so a new
APK installs straight over the old one and keeps your folders. After an update the module
restarts Nothing Launcher by itself (root), and LSPosed loads the new code right away: no
uninstall, no reboot. Set `SIGNING_KEYSTORE`, `SIGNING_PASSWORD` and `SIGNING_KEY_ALIAS` to build
with a private key instead.

Builds from before v0.5.0 were signed with throwaway keys: switch once with **Export**,
uninstall, install, **Import**.

## How it works (reverse-engineering notes, v4.0.20)

The launcher is built on AOSP Launcher3 (`com.android.launcher3.*`, unobfuscated). Nothing's own
code is in `com.nothing.launcher.*`, and R8 has obfuscated most of it.

| Role | Class in 4.0.20 | How the module finds it |
| --- | --- | --- |
| Per-app category state (`AppStatusInfo`) | `x7.c` | Type of `ItemInfo.mStatusInfo` |
| Group category getter (`"unknown"` → `"others"`) | `x7.c.n()` | Behavioural probe on a fresh instance |
| Smart drawer list (groups apps, builds folders) | `com.nothing.launcher.allapps.smart.h` | `AlphabeticalAppsList` subclass in the smart package |
| Category order (`OrderEvent.lastOrder` or default) | `h.e()` | Only private no-arg method returning `List` |
| Default categories + label string IDs | `com.nothing.launcher.allapps.smart.i` | Not hooked |
| Folder tile | `SmartFolderIcon.bind(GroupAdapterItem)` | Name kept |
| Opened folder sheet | `SmartFullSheet.Companion.b(ActivityContext, SmartFolderIcon)` | Signature match |
| Drawer settings screen | `AllAppSettingsFragment.onCreatePreferences` (`all_app_settings_preferences.xml`) | Name kept |
| Group item factory (`GroupAdapterItem.Companion.create`) | `smart.e$b$a.a(int, String, List)` | Signature match |
| Smart grid decoration | `smart.c` | Type of `NTLauncherAllAppsContainerView.gridDirection` |
| Grid spans | `AllAppsGridAdapter$GridSpanSizer.getSpanSize` | Name kept |
| Drawer background | `AllAppsState.getWorkspaceScrimColor` | Name kept |
| Drawer → home folder | `OptionsPopupView.show`, `LauncherModel.addAndBindAddedWorkspaceItems`, `ModelWriter.addItemToDatabase` | Names kept |
| Nothing DeviceProfile | `v6.h` (overrides sizing; base methods made abstract) | Parameter type of the Smart profile's `n(Context, …)` |
| Home rotation | `RotationHelper.notifyChange` / `mHomeRotationEnabled` | Names kept |
| Smart drawer profile | `smart.z.n(Context, v6.h)` | Type of `SmartFolderContainer.smartProfile` |
| Landscape grid | `CellPosMapper`, `Workspace.insertNewWorkspaceScreen`, `DeviceProfile.getCellSize`, `ModelWriter.updateItemInfoProps` | Names kept |
| Controller keys | `Launcher.dispatchKeyEvent` | Name kept |
| Display style pref | `pref_all_app_display_style` (false = Smart) | — |

`h.updateAdapterItems()` groups `mApps` by `mStatusInfo.n()` and adds one folder for each
category key in `h.e()`. Keys that aren't in that list never appear. The module therefore:

1. Makes `n()` return `nlplus:<folderId>` for apps the user assigned. It finds the app's
   component through the status info's intent URI field. The "hidden icons" entry is left alone.
2. Adds those keys to the list returned by `h.e()`.
3. Replaces the label in `SmartFolderIcon.bind` and in the full-sheet title. The launcher looks
   labels up from a fixed `category → string resource` map, so a custom key would otherwise show
   as "Others".
4. Calls LSPosed's `XposedBridge.deoptimizeMethod` on `updateAdapterItems` and on the adapter's
   `onBindViewHolder`. Without this, ART could inline the small hooked getters and skip the hooks.

The module never writes custom categories to the launcher's database. Nothing's category
assignments stay untouched, so disabling the module restores the stock drawer.

The config is stored as world-readable shared prefs (`config.xml`) through LSPosed's
`xposedsharedprefs`. The hook reads it with `XSharedPreferences`.
