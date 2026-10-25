# Nothing Launcher+

An LSPosed module that adds **custom folders** to the Nothing Launcher app drawer.

Nothing Launcher's **Smart (Beta)** drawer layout sorts apps into folders by category ("Games",
"Tools", …). The categories come from Nothing's server, and you can't change them. This module
adds your own folders next to them. Apps you put in a custom folder leave their automatic one.

## Features

Everything new is opt-in: with the defaults the drawer looks exactly like stock Smart (Beta)
plus your folders.

- Create, rename and delete drawer folders and pick the apps in each.
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
  tears and sweat run down the face and form again dot by dot, fire flickers, clouds rain,
  coffee steams, buildings light their windows one by one, speakers pulse, notes rise, stars
  twinkle, clocks tick, sirens flash, fish blow bubbles, and so on, mapped to every keyboard
  emoji by the same generator as the motions.
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
- **The name is on the screen too**: the folder name is lit on the same dots below the emoji in a
  compact 3 x 5 LED font (accents and emoji in names are rasterized onto the dots, in colour), and a
  name too long for the tile scrolls through it a dot at a time.

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
