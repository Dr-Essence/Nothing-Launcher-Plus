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
- **Landscape (beta)**: the home screen and app drawer follow auto-rotate, laid out with
  AOSP's landscape layout — see *Landscape* below.
- **Game controllers**: navigate home and the drawer with a DualSense, DualShock, Xbox, GameSir,
  Backbone or any standard gamepad — see *Controllers* below.
- **Export / Import** saves folders, options and cover images to one file, so the setup survives
  a reinstall.
- Changes apply right away through a reload broadcast. You don't need to restart the launcher.

## Emoji animations

- **98 hand-tuned motions** (wave, heartbeat, hop, laugh, ring, launch, coin flip, flag wave,
  swim, flicker, …) built on spring physics: anticipation, squash and stretch, overshoot and
  settle. Each starts and ends at rest; `MotionQa`-style checks during development verify there
  are no jumps at 240 fps and no out-of-range poses.
- **Every emoji is mapped** by `tools/gen_emoji_motions.py`: exact CLDR-name overrides, then name
  keywords, then its Unicode subgroup. The folder editor shows the chosen motion and lets you pick
  any other, or none.
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
2. Install it, enable it in LSPosed and select **Nothing Launcher** as the scope.
3. Force-stop Nothing Launcher, or use the "Restart Nothing Launcher (root)" button in the app.
4. In Nothing Launcher settings, set *Drawer layout* to **Smart (Beta)** and tap **Custom folders**.

## Signing

Android installs an update only when it is signed with the same key as the installed version.
Uninstalling to switch keys deletes the saved folders. Release builds therefore always use one
key, which is kept out of this public repo:

- **CI:** set the repository secrets `SIGNING_KEYSTORE_BASE64` (the base64-encoded `.jks`) and
  `SIGNING_PASSWORD`. Without them the workflow still builds, but it signs with a throwaway debug
  key and shows a warning.
- **Local:** `SIGNING_KEYSTORE=/path/to/release.jks SIGNING_PASSWORD=... ./gradlew assembleRelease`
  (key alias `nlplus`, or set `SIGNING_KEY_ALIAS`).

Before switching an existing install to a differently signed build, use **Export** in the
module, uninstall, install the new build and **Import** the file.

Keep a backup of the keystore. If it is lost, no future update can install over your current one.

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
