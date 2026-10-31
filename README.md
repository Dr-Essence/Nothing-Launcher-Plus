# Nothing Launcher+

An LSPosed module that adds **custom folders** to the Nothing Launcher app drawer.

Nothing Launcher's **Smart (Beta)** drawer layout sorts apps into folders by category ("Games",
"Tools", …). The categories come from Nothing's server, and you can't change them. This module
adds your own folders next to them. Apps you put in a custom folder leave their automatic one.

> **Heavily experimental alpha.** Expect bugs, and expect things to change from build to build.

## Who this is for

- **Nothing and CMF phones only.** The module hooks Nothing's own apps (Nothing Launcher,
  Nothing's System UI, Nothing Widgets, NothingCardService). It does nothing useful on other
  phones and is not meant for them.
- **Tested on one phone so far:** a CMF Phone 1 on Nothing OS 4.1 (Android 16). Other Nothing
  devices and Nothing OS versions are untested.
- **Why it exists:** it started as a project for my own CMF Phone 1, which I don't use as my main
  phone but as an Android "iPod" (an *Andropod*): music, games with a controller, the home
  screen in landscape. Much of what it does comes from that.
- **Feedback from other Nothing devices is very welcome, and a huge help.** If you try it on
  another phone, please open an issue with:
  - your device and Nothing OS version;
  - what works and what doesn't;
  - an LSPosed log (LSPosed → Logs → save), and a screen recording if something looks wrong;
  - for a screen that looks or turns wrong, a debug report: in the module's app, Diagnostics →
    **Capture debug report** (root). Choose how long it waits first (10 s to 10 min: time to
    set the problem up, such as locking the phone, turning it or opening the screen that
    misbehaves) and how long it captures (15 s to 10 min). Meanwhile it records the screen
    and, every few seconds, takes a screenshot, the screen composer's layers, the windows and
    the view layout of every visible window (System UI's included) and how System UI draws
    what is behind the lock screen, PIN page and shade (scrims and blur) and the busiest
    processes (`top`); logcat runs throughout. At the end it adds the memory of the module's
    app, Nothing Launcher and System UI (`dumpsys meminfo`).
    It adds System UI's state, input, display, the system settings, the game service, battery,
    where the phone keeps its CPU / GPU clocks and temperatures, Nothing's Game Bar app, the
    PerfMTK module's scripts, settings and `--help` (if installed), SurfaceFlinger and the
    composer libraries it has loaded and an 8-second SurfaceFlinger layer trace (Perfetto;
    for the landscape fingerprint) and the LSPosed log, and saves one
    `NLPlus-report-….tar.gz` in Download; a notification says when it is ready. While it
    runs you can tell what it is doing: one buzz when capturing starts and two when the
    report is saved (felt on the lock screen too), an ongoing notification counting down
    ("Capture starts in…", "Capturing…", "Saving the report…"), and a small "● REC" pill
    with the seconds left, drawn by System UI, so it shows on the lock screen too. **Stop
    capture** ends it early and still saves it. It can hold personal information
    (screenshots, the recording, notifications, logs), so look through it before posting it
    publicly. The **Diagnostic logging** switch on the same page (on for now) writes the
    module's diagnostic lines to the LSPosed log: orientation changes, lock screen layout,
    fingerprint events and portrait-only system screens.
    **Test notifications** on the same page posts 1, 3, 6 or 12 sample notifications (short,
    long, conversation, progress, picture and reminder), now or in 10 seconds so the phone can
    be locked first, to check how the lock screen lays them out; **Clear test notifications**
    removes them. It asks for the notification permission the first time.

### Before you install: protect your phone

- **Don't use it on a phone you depend on.** It is alpha software that hooks System UI and the
  system framework; a phone you can afford to have misbehave for a while is the right place for it.
- **Have a way out before you need one.** Know how to start your phone with all modules
  disabled (Magisk and KernelSU have a safe mode started by pressing volume down during boot;
  check your root manager's documentation), and how to turn the module off in LSPosed.
  If System UI keeps crashing, Android may offer a factory reset ("Rescue Party"); don't accept
  it, boot into your root manager's safe mode instead.
- **Back up first.** Use the module's **Export** to save your folders and options. Keep a
  backup of your own data as well, as you would before any system modification.
- **Scope only what you use.** In LSPosed, tick only the apps for the features you want (see
  *Install*). System UI and System framework hooks only load after restarting System UI or
  rebooting.
- **Turn on one feature at a time**, and use it for a while before the next, so you know what
  caused a problem if one appears.
- **Leave the test switches off** unless you are testing them: *Lock screen rotation (test)*
  changes the lock screen, PIN screen, fingerprint overlay and always-on display.
- **Keep the module on internal storage** (it moves itself there; see *Install*).
- **Turning a feature off, or uninstalling the module, puts Nothing's behaviour back.** If
  something stays wrong after that, restart System UI or reboot. Check other LSPosed modules
  too: a burn-in protection module once moved the status bar in a way that looked like this
  module's doing.

## For the best experience

These are separate projects, not part of this module. Each has its own risks, so read its
instructions first.

- **[PerfMTK](https://github.com/JUANIMAN/PerfMTK)** (Magisk / KernelSU module; **only for
  Nothing phones with a MediaTek chip**, such as the CMF Phone 1 and CMF Phone 2 Pro, not for
  Snapdragon ones): Powersave / Balanced / Performance profiles, CPU
  and GPU frequency tuning and thermal limits. The module's planned game options will set CPU
  speeds through PerfMTK rather than on their own.
- **[PerfMTK Manager](https://github.com/JUANIMAN/PerfMTK-Manager)** (same phones): PerfMTK's
  app, for picking a profile, the thermal limits and per-app profiles. PerfMTK's README also
  describes its optional **PerfMTK-Hook** LSPosed module, for switching profiles automatically
  per app.

- **Shout-out: [HeliBoard](https://github.com/Helium314/HeliBoard)**, the best Android keyboard
  there is: open source, private (no internet access), and endlessly customisable. With it in
  the module's LSPosed scope, you can type with a game controller (see *Typing with a
  controller (HeliBoard)*).
- **Shout-out: [NothingTweaks](https://github.com/RevealedSoulEven/NothingTweaks)** (LSPosed
  module, root): system tweaks for Nothing OS, such as status bar and clock options, a network
  speed indicator, navigation bar options, lock screen extras (double tap to sleep, scrambled
  PIN), screenshot and screen recording options, in-display fingerprint colours and a charging
  limit. It goes well with this module.

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
  opens the module's App drawer page. A **Nothing Launcher+** row at the end of *Nothing
  Launcher settings* opens the module's main menu.
- Long-press a drawer folder for the launcher's own popup: **Add to home screen** creates a real
  home-screen folder with the same name and apps; **Edit** (custom folders) opens the folder editor.
- **Landscape (beta)**: the home screen and app drawer follow auto-rotate. Each home page is
  rearranged into a wide grid automatically, while your portrait layout stays as it is — see
  *Landscape* below.
- **Game controllers**: navigate home and the drawer with a DualSense, DualShock, Xbox, GameSir,
  Backbone or any standard gamepad, and type with the controller like on a PlayStation in
  **[HeliBoard](https://github.com/Helium314/HeliBoard)**, the recommended keyboard for the best
  experience — see *Controllers* below.
- **Export / Import** saves folders, options and cover images to one file, so the setup survives
  a reinstall.
- Changes apply right away through a reload broadcast. You don't need to restart the launcher.

## Emoji animations

> **Beta.** Emoji covers are a beta. They will be replaced by the **Nothing Dot Matrix
> Display** engine (see [Roadmap](#roadmap)).

- **Emoji covers (beta)** (on by default; App drawer page): off, every folder shows its apps as
  Nothing draws them, and the emoji chosen for each folder stays saved for when it is switched
  back on (image covers stay). **No emoji covers in battery saver** (off by default) does the
  same only while battery saver is on: the tiles change as it turns on and back off. Hidden
  covers do not animate.
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
  the drawer, in folders and in search, is a little dot-matrix screen of its own: a bezel
  round a pane of glass with a hairline rim, and on it a 22 x 22 grid of round dots lit in the
  icon's own colours, each casting a dim halo on the glass. An icon in the launcher's circle
  is a round screen of dark glass; an icon with a shape of its own keeps it, its glass lit
  green from behind and the bezel following its outline. Nothing is cut to a circle, and an
  old icon the launcher put on a white plate is shown off the plate, in its own shape. Now and
  then, each icon on its own timing, a faint green scan line rolls down its dots. When icons
  come into view each powers on like a little screen: dots light from the centre outwards on
  a spring with a brief white LED flash, icons a few milliseconds apart. App names too long
  for their icon scroll like the folder names, a dot at a time. One hook on the launcher's
  icon drawable covers every surface; each icon is made into a screen once and cached.
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
- **Ndot status bar** (option): System UI is in the module's scope; the status bar is lit
  like the drawer's folder screens on any wallpaper: the clock and date in Nothing's NDot in
  the green of the folder name panels with a soft glow, and every icon (network type,
  signal, Wi-Fi, airplane mode, headphones, alarm...), the battery and the network speed in
  the same green, their own shapes untouched. Another option puts the date before the time.
- **Green quick settings** (option): the tiles' icons and labels glow green, and a tile that
  is on lights deep retro-display green where it was white, or, with **Amber dashboard
  tiles**, the warm amber of a retro car dashboard. The icons' own animations still play.
- **System font**: choose any of Nothing's fonts already on the phone (NDot, NType, and the
  ones inside Nothing's widgets app), each previewed in the chooser on a dot-matrix screen
  made for it, in its own letters. It is applied the way Android applies its own fonts: with
  System Framework in the module's LSPosed scope, the font map system_server gives every app
  is rewritten with the chosen font as soon as you pick it, so every app opened from then on
  uses it, no reboot; System UI and the launcher are restarted at once. A font only the
  widgets app has is first added to the system's fonts as a file (one restart). If the system
  ever crashes while a new font is in use, the next start leaves it off. Phone default
  undoes it.
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

Both off by default; switch them on in the module's **Sound** page.

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
- **Folders** open whole and centred between the system bars: the header is just the name's
  line and the folder has as many rows as fit under it (more apps go to further pages, as in
  portrait). Nothing places them 346 px down under a 399 px header, which left room for about
  one row of apps on a landscape screen and cut the rest off.
- **The launcher's own screens** (home and drawer settings, customization, icon packs) keep
  Nothing's portrait lock: the home screen layout preview there is only drawn right in
  portrait.
- **Nothing's widget cards** (Calm / breathing, battery, photos, …) and their settings open in
  landscape. Nothing Widgets (`com.nothing.hearthstone`) and NothingCardService declare every
  screen portrait-only, and Android applies that as a card launches, so cards opened from a
  landscape home screen showed on their side. The lock is lifted in system_server as the card's
  activity is created (System Framework in scope), on that activity's own copy of its info, and
  again in the apps themselves (tick both apps in the module's LSPosed scope). Cards also turn
  without being restarted, so a breathing session keeps going. Nothing lays its cards out for a
  portrait screen only (it ships no landscape layout for any of them), so in landscape each
  card (any translucent, portrait-only screen of those apps, Calm and Relax included, and any
  Nothing adds later) is laid out on a canvas the size of the portrait screen, exactly as in
  portrait, and what it shows there (its views that draw, close button included) is centred
  in the room the system bars leave, scaled down evenly only if it does not fit. The card's
  layout and code see a portrait screen, so it looks and works as in portrait on any screen
  size, aspect ratio or density. The canvas is put in place as the turn is told to the card,
  before it is laid out for the turned screen, and is exactly the size the card has in
  portrait, so turning never lays the card out again: it only moves and scales, settled
  before the first turned frame is drawn (it used to show small for a moment, or for seconds
  when its views settled late). A card darkens the screen behind it with the system's dim
  layer, which the system takes away for a turn and fades back in after it (the home screen
  flashed grey behind the card); from its first turn on, the card draws the same darkening
  itself as its window's background, which turns with it, while its opening stays as Nothing
  made it. (Stretched across the landscape screen, the breathing card
  was taller than the screen: opened in landscape it showed as a full-screen gradient with no
  corners or close button, and after a turn it filled the height with its close button cut
  off.)
- **Nothing's system screens built for any orientation follow auto-rotate** (switch: *Status
  bar & quick settings → System screens in landscape*; it starts as Home screen rotation was
  set), where Nothing declares them portrait-only: SystemUI's quick settings widget picker
  (`QSWidgetActivity`, a scrolling list) and the Settings app's pages (lists; the orientation
  log showed `SettingsHomepageActivity` and `SubSettings` asking for portrait), except
  PIN / password / lock confirmation, fingerprint and face set-up, first set-up and the
  start-up home, which stay portrait. Each portrait-only screen of
  a system app you open is written to the LSPosed log once (`portrait-only screen: …`, with
  diagnostic logging on), so
  others (Settings pages and the like) can be checked in Nothing's code and added. Screens
  that are only a notice (the overheating warning, the AI screenshot tip) stay as they are.
  System framework scope and a reboot are needed for this.
- **The lock screen and Nothing's Customisation page stay portrait** by default, as Nothing
  made them: neither has a landscape design (the lock screen left its clock in a corner and its
  notifications in a narrow column, the Customisation page fell back to its foldable layout,
  tiny on a phone).
- **Lock screen rotation (test)**, off by default. When it is on, the lock screen follows
  auto-rotate: SystemUI's own switch for that, `KeyguardStateController
  .isKeyguardScreenRotationAllowed()` (also set by the `lockscreen.rot_override` property and
  `config_enableLockScreenRotation`), answers yes. In landscape, SystemUI's resources
  (`values-land`) turn split shade on, and the lock screen would swap Nothing's own layout
  (the `default` keyguard blueprint) for AOSP's `split-shade` one. Nothing added its pieces
  (date, widgets, depth-effect wallpaper) to its default layout only, so the logged landscape
  layout showed just the clock across the top with the notifications over it. That swap
  (`KeyguardBlueprintRepository.applyBlueprint`) is switched off, so Nothing's layout stays.
  SystemUI still puts the notifications on the right half in landscape, so turned, Nothing's
  clock, date and widgets end at the middle in the same layout pass (`split_shade_guideline`
  at 50%, as AOSP's split layout does with its clock) and center on one half, with or without
  notifications (Nothing's centered layout spread its widgets across the middle, where the
  charging line under them ran into them). Each landscape layout is still logged (`lock screen layout: …`).
  The fingerprint sensor under the screen stays where it is however the screen turns.
  SystemUI's sensor overlay (`UdfpsControllerOverlay`, which lights the sensor and reads the
  finger) turns the sensor's spot with the screen (`RotationUtils.rotateBounds`) everywhere
  except on the lock screen (`shouldRotate()`), which AOSP assumes never turns, so in landscape
  it stayed at the portrait spot, off the screen. That one exception is lifted while the lock
  screen turns (not while going to sleep). The lock icon over the sensor
  (`DefaultDeviceEntrySection`) is placed from the sensor's unturned spot; it is given the spot
  turned the same way, in the lock screen's own coordinates (logged as
  `lock screen fingerprint: icon at …`). The overlay turns its `sensorBounds` Rect in place on
  every layout (twice when it shows), and after `updateOverlayParams` that Rect is the overlay
  parameters' own, so the spot was turned again and again and the scanning animation landed
  near the top left; while the lock screen turns, each layout starts from a fresh copy of the
  unturned spot, so it is turned exactly once. The PIN / pattern / password screen (bouncer)
  is blacked out in screen recordings (a secure window), so its layout is logged too: in
  landscape Nothing's portrait-only PIN layout was squeezed under its lock icon. SystemUI
  carries AOSP's own landscape bouncer (`keyguard_*_motion_layout`, which jumps to
  `split_constraints` in landscape: entry beside the keys), switched on by the unreleased flag
  `lockscreen.enable_landscape` (`update_bouncer_constraints` is already on for phones). That
  flag is answered yes only inside the bouncer's own code (choosing and setting up its layout,
  and its turn listener), not for the shade or quick settings; so while the test switch is
  on, the bouncer uses that layout in portrait too. Should that code ever fail, the error is
  kept from System UI and the bouncer goes back to Nothing's layouts. The bouncer's
  fingerprint icon is placed over the sensor as on the lock screen. Restart System UI after
  turning the switch on, so the bouncer is built again. In the landscape bouncer, Nothing's
  bouncer lock glyph (placed 348 px from the top, for portrait) is left out, and the message
  above the PIN starts at the top of its half instead of 448 px down
  (`nt_message_area_margin_top`), which had pushed the PIN entry below the bouncer; both come
  back as Nothing made them in portrait. Nothing's clock, measured as wide as the screen, gets
  the half's width, also in the pass where SystemUI lays out the clock alone (kept at full
  width it stuck out past the screen's edge and vanished). With the fingerprint sensor on the
  left (the screen turned 270°), Nothing's clock, date and widgets go to the right half
  (Nothing's own layout put its clock on the left over the sensor's icon), and the lock screen's notifications to the left half (only while the lock screen itself shows;
  pulled down, the shade is laid out as SystemUI makes it). Turning straight from one
  landscape side to the other keeps the screen's size and orientation, so Android tells System
  UI of no configuration change and Nothing never laid its lock screen out again: the lock
  icon stayed over the sensor's old side and the clock and notifications kept their old
  halves. On that turn System UI is asked for the relayout it does on a configuration change
  (`KeyguardBlueprintInteractor.refreshBlueprint`) and the notifications are placed again
  (logged as `lock screen flip: …`). The always-on display follows the
  screen's turn like the lock screen (its landscape layout is logged for fitting it). Fingerprint events in landscape are logged
  (`fingerprint: …`: finger down / up, the overlay window's place, what the fingerprint
  service answers). **Fingerprint in landscape**: the Goodix driver reads only with the
  panel's high-brightness mode (HBM) on, and MediaTek's display composer
  (`hwcomposer.mtk_common.so`) switches HBM on for a frame only when it holds a buffer named
  `NTFingerprintDimLayer` (`HWCLayer::setupPrivateHandle` finds the name). Nothing's
  SurfaceFlinger leaves the layer *named* `NTFingerprintDimLayer` out of composition whenever
  the display's transform hint is ROT_90 or ROT_270: its Layer constructor tags the layer by
  name, and the per-layer step that builds the composition list returns early for the tag when
  `mActiveDisplayTransformHint` is 4 or 7. (Found with kernel uprobes on CompositionEngine's
  checks: in landscape the layer never reached them, while other layers did.) So in landscape
  HBM never came on and every touch timed out (`GF_ERROR_UI_READY_TIMEOUT`). Nothing's two
  sensor layers, the dim and the illumination dot, are also drawn in the panel's portrait
  coordinates, so the dot fell off screen when turned. With the lock screen turning, the module
  keeps Nothing's mechanism and makes it work turned (logged as `landscape fingerprint: …`):
  the dim layer's layer name leaves out the tag (its buffer, which the composer reads, keeps
  Nothing's name), and both layers are placed in the panel's portrait coordinates in every
  rotation (AOSP's `rotateBounds`; identity in portrait) through a parent container layer they
  move into in Nothing's own show transactions, turned again after each turn: their own matrix
  cannot place them, as Nothing's `BLASTBufferQueue` gives them a destination frame, and
  SurfaceFlinger then takes their geometry from that frame alone. With colour inversion on, the dim keeps Nothing's name, as SurfaceFlinger
  exempts the tagged layer from colour transforms.
  **Notifications, turned**: the module keeps Nothing's lock screen layout ("default"
  blueprint) where landscape would switch to AOSP's "split-shade" one, but the default layout's
  notification section is the portrait one: it starts the notifications below the clock's
  column (logged `nssl_placeholder` top 1026 of a 1080 high lock screen), so in landscape they
  were laid out off the screen and only their icons showed. Turned with split shade, that
  placeholder's top is where AOSP's split-shade section puts it (the screen's top,
  `keyguard_split_shade_top_margin` down). Their column then ran over the fingerprint icon
  (column from x 85, icon at 198..384): on the lock screen itself SystemUI's own margin on the
  sensor's side (`SharedNotificationContainer.updateConstraints`) is widened to end the column
  16dp short of the turned icon, so no notification, however many or however tall, reaches
  the circle; placed again whenever the icon's spot changes.
  **PIN page, turned**: Nothing draws the PIN page's backdrop on the scrim that covers the
  screen. In portrait that is the notifications scrim, see-through (0.54) in Nothing's bouncer
  colour with the blur behind it; turned (split shade) the behind scrim covers the screen and
  the notifications scrim only the notification column, so the backdrop was the behind
  scrim's opaque black, and with an opaque scrim System UI drew no blur. While the lock screen
  turns, the PIN page's backdrop goes on the behind scrim with the portrait values, and the
  blur comes back.
  **Charging line, turned**: Nothing centers the lock screen's charging / status line across
  the screen; turned, it ran over the bottom of Nothing's widgets (charging text at y 952, the
  widgets to 999), on the lock screen and the always-on display. With the halves, it is
  centered in the other half, under the notifications and the fingerprint icon.
  **Shade over the turned lock screen**: System UI's falsing check (BrightLine) keeps the
  screen's size and dpi from when it started (portrait: 1080 x 2400) and its distance check
  sets its thresholds from them once, so turned, vertical swipes were measured against the
  wrong axis (a swipe of 530 or 606 px rejected against 617 px; the turned screen's own
  threshold is 496). The pull down on the lock screen was aborted as a false touch, System UI
  opened quick settings over the lock screen instead, and they were hard to leave: Nothing's
  `closeQsIfPossible` leaves them open in split shade, so a swipe up opened the bouncer and,
  once it was put away, the lock screen came back with them still open. While the lock screen
  turns, the falsing check's size and dpi follow the rotation (its thresholds worked out from
  them with System UI's own settings and sums), and quick settings over the lock screen close
  on back or when the panel closes, as Nothing closes them when split shade returns to the
  lock screen.
  **Always-on display, turned**: its burn-in movement (`AodBurnInViewModel`, applied as
  translationY to the clock, date, AOD notification icons and Nothing's widget container) moves
  them within Nothing's `burn_in_prevention_offset_y` (100dp), sized for portrait; turned, the
  lock screen's content already fills the height, so they went about 416 px down and the
  widgets were cut off. In landscape the movement's vertical offset is kept, where its
  `BurnInModel` is made, within the room those views leave on the lock screen (logged as
  `always-on display: …`); they keep drifting against burn-in. Portrait is unchanged.
  A finger on the sensor also pulled the shade down: Nothing's touch handling asks
  `UdfpsUtils.isWithinSensorArea` with the touch as it is on screen against the sensor's
  unturned bounds ("rotate to portrait" off), so in landscape the sensor window never took the
  touch. While turned, the touch is turned to portrait first with UdfpsUtils' own
  `getPortraitTouch`, as AOSP does by default.
  Not from this module: "Remove anything covering this area" is Nothing's accidental-touch
  protection, shown when its proximity sensor (top edge in portrait, a side in landscape) is
  covered, e.g. by a hand holding the phone sideways.
- **Status bar.** The clock pressed against the screen's edge, investigated in 0.29.8–0.30.0,
  came from another LSPosed module (OLED burn-in protection shifting the status bar), not from
  this one; the guard and the diagnostic hooks added for it are removed again.
- **Hidden navigation bar.** With Nothing's "hide navigation bar" on, the taskbar window
  (which draws the gesture pill on phones) is made 0 px tall when it is created; when the
  home screen turned, the taskbar's `onRotationChanged` sized it again to the pill's height
  without that check, so the pill came back in landscape. That one resize is skipped while
  the bar is hidden.
- A **live wallpaper** that turns with the home screen draws its first frames after a turn
  itself: one that rebuilds its renderer for the new size (Living Worlds does) shows black,
  or a white frame, until it has drawn, most visibly when going home in portrait after the
  home screen was last in landscape. That is the wallpaper's own redraw.
- **Smart drawer tiles** are sized for about one and a half rows, with icons scaled to the tile.

### Landscape grid

The landscape page is the portrait page turned: a C×R portrait grid becomes R columns by as
many of the C rows as the landscape page holds at the portrait cell size (5×9 → 9×4 on a
1080 px tall phone, the portrait cells exactly), so every page fits on any grid, screen size or
density.
Each item sits at its portrait place turned (its portrait row is its landscape column, its
portrait column its landscape row) with its own spans: icons, folders and square widgets turned
that way never meet; a widget of another shape whose spot is taken goes to the nearest free
spot that holds it whole; items of a portrait column that does not fit on the landscape page
go to the nearest free spot. The cells are the portrait cells turned (a portrait row's height
is a landscape column's width, a portrait column's width a landscape row's height), at their
portrait size unless the portrait rows do not fit across (then scaled the same both ways), so
the page keeps Nothing's sizes and spacing; the grid is centred on the page. Icons, folders and widget contents keep their portrait shape
and are as large as their landscape cells hold them, never larger than in portrait: a widget
keeps the least margin Nothing gives it in portrait, and where a landscape row is too low for
an icon and its name at near portrait size (5-column grids) icons and folders show at their
portrait size without names, as Nothing's own landscape profile does. A widget as wide as the portrait page (a
calendar) fills the landscape page's width and is as tall as in portrait, as far as the page
allows; it is placed after the other items, in the room they leave. The grid in use is logged
(`landscape grid 9x5 cells … gap … in … px`).

**Crowded pages.** A landscape page with portrait-size cells holds fewer cells than the
portrait page (36 against 45 on 5×9). If any home page holds more than that (a page full of
apps), the landscape grid gets a row for every portrait column instead, with cells as much
smaller as that needs (9×5 cells of 167 px on 5×9), so every item keeps a cell of its own and
nothing overlaps. This is decided from the launcher's items as the landscape pages are built
(and by the arrangement, should a page still not fit), and goes back to portrait-size cells
on the next turn once no page is crowded. Icons are refitted to the cells either way.

**Arranging landscape on its own.** Icons, folders and widgets can be moved and resized on the
landscape home screen like in portrait. Each keeps that landscape place and size (remembered
per item and per grid, in the launcher's own data), while the launcher's database, and so the
portrait home screen, keeps its portrait place and size. Items never arranged in landscape
are placed by turning the portrait page, around the ones that were. Only a move or resize of
an item already shown counts: the launcher builds new views whenever it binds its pages again
(a rotation, a grid or label change), and their first places are not edits. Places are kept per
portrait grid, so they stay when the landscape page's rows change; a place that no longer fits
the landscape page is left for the item's turned place.

Portrait is guarded at the database itself: every item write ends in one call
(`ModelDbController.update`), whichever launcher method made it (the small methods that set an
item's cells are inlined by ART into the drop, reorder and resize code, past hooks on them; a
landscape drop with a reorder wrote landscape cells into portrait this way). In landscape, a
write that keeps an item on its page keeps its portrait cells and spans in the row, and its
landscape place is remembered; a move to another page or into a folder is written as it is.
Back in portrait, items the launcher changed in memory while in landscape get their portrait
cells back before the pages are built.

The landscape grid only acts on the launcher's own home pages: Nothing draws its home screen
previews (the layout preview in its settings) with the same page classes, and those are left
exactly as Nothing lays them out. The portrait page is folded onto it:
cut into bands as tall as the landscape page and laid side by side, each item where it is in
its band (a 5×9 page on a 4-row landscape page: portrait rows 0–3 on the left, rows 4–7 next
to them). Larger items are placed first; an item whose spot is taken or off the page goes to
the nearest free spot that holds it whole, so nothing overlaps, and is only shown smaller if
no spot on the page holds it. A widget as wide as the portrait page (a calendar) is as wide
as the landscape page.

Nothing insets each widget in its cells by a padding its profile works out per span
(`Rect o(spanX, spanY)` on its DeviceProfile subclass, found by signature); app widgets, its
card widgets and the sizes widgets are told all use it. It works that out from its own idea of
the cell, which in landscape is not the cell in use, and came out negative, so widgets grew
past their cells into their neighbours. Landscape takes the portrait profile's padding, as its
cells are the portrait ones.

Pages mark a widget's cells from its item (its model cell and portrait spans; a Nothing card
even its portrait cell), not from where it is shown, so on a landscape page the cells it was
shown on read free while its portrait ones read taken: drags and resizes met phantom widgets
and went over real ones. On the launcher's landscape pages every view marks the cells it is
shown at, as Nothing already does for icons and folders. Nothing's resize frame also set a
widget up from its item, putting it back to its portrait spans (the calendar shrank to five
columns the moment it was touched) and freeing only those cells; while it sets up a widget on
a landscape page the item reads as shown there, and a widget that may be as wide (or tall) as
the portrait page may be resized as wide (or tall) as the landscape one.

The launcher makes its profiles one after another and keeps a copy of each, which it copies
its own from and Nothing sizes its widgets with. A landscape profile made before the portrait
one had none to take its cell from (stock 396×92 cells, 85 px icons), so the portrait cell is
remembered as the launcher measures it, and the landscape profiles are made again once all
are there. Each page's items are laid out on it in their portrait
reading order, left to right and top to bottom: widgets first, then apps and folders in the
room left. A widget taller than the landscape page is shown as tall as the page.

The arrangement is done on each page's own views once the launcher has put its items there,
never by reading the launcher's model while it loads (which held the model lock on the main
thread and froze rotation for seconds). Each item is first put in its turned portrait cell,
which is always on the page, so the launcher never drops one for being off the grid.

The launcher deletes any item it binds onto a cell that is already taken ("Collision while
binding workspace item"). Turned cells can overlap until the page is arranged, so in landscape
this deleted widgets for good, in portrait too. While a landscape page is bound no cell counts
as taken, and a collision delete in landscape is refused and logged
(`NothingLauncher+: landscape grid: kept …`).

A rotation relays the launcher out and rebinds its pages, which are filled a moment later: the
first frames on the turned screen were the old pages stretched to it, then empty pages. Android
keeps showing the last frame while it turns the screen until the app draws, so drawing is held
from the rebind until the pages are filled and arranged (as the launcher does at startup), for
one second at most, and only while the launcher has focus: an app opening over it in
landscape turns it too, and the app's opening animation must keep drawing.

The launcher's loader waits for an idle main thread between its steps (home screen, then apps,
shortcuts and widgets), with no time limit. A view redrawn every frame keeps the main thread
from ever going idle, and the widget list then stayed empty ("Widgets and shortcuts aren't
available") until the launcher went to the background. Dot-matrix icons animate only while
they are in view (the closed drawer and other pages get one still frame, rechecked once a
second), and the loader waits for idle at most three seconds per step.

- The launcher database keeps the portrait layout: moving or resizing an item within its page
  in landscape is remembered for landscape only and leaves its portrait place as it is.
- An item added in landscape, or moved onto another page there, goes to a free spot of the
  portrait page: the launcher deletes items that overlap or leave the grid when it next loads,
  so every landscape write is checked against the portrait page. The page is then arranged
  again in place, without reloading the launcher.
- **Drawer folders** open in landscape as in portrait, name over apps: the folder sheet gets as
  many columns as the drawer has tiles and only as many rows as leave its name room (Nothing's
  sheet gives the list at least four rows, which squeezed the name off a landscape screen).

Nothing also blocks landscape outright: `NTLauncher.handleConfigurationChanged` ignores every
change to landscape ("Skip to handle the config change for the landscape orientation"), and the
landscape profiles are filtered out of the list the launcher picks from. With rotation on, the
module handles the change the way AOSP does and lets the home screen activity, and only it,
pick from the full list. Nothing's list is left as it is: the taskbar, which draws the gesture
handle and hides it when "Hide navigation bar" is on, and the settings previews keep the
profiles Nothing made them for (given landscape ones, the hidden handle came back in
landscape).

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

### Typing with a controller (HeliBoard)

For the best experience use **[HeliBoard](https://github.com/Helium314/HeliBoard)**
(`helium314.keyboard`, open source; tick it in the module's LSPosed scope; switch: *Home
screen → Controller typing in HeliBoard*, on by default). With the keyboard up, the controller types on it the way a PlayStation's on-screen keyboard works:

| Button | Keyboard |
| --- | --- |
| D-pad / left stick | Move the highlight over everything HeliBoard shows: keys, suggestions, the toolbar (open or closed) and pinned keys, the emoji pages with their categories and bottom row, the clipboard and its buttons, the one-handed mode buttons, and the panels that open over the keys. Rows wrap left and right; lists and the toolbar scroll as it moves |
| Cross (A) | Touch what is highlighted, for as long as it is held: a tap types or picks; held, it does what a held finger does (backspace and arrows repeat; a key opens its accents / long-press keys, a suggestion opens more suggestions, an emoji its skin tones, a toolbar key pins). While held with a panel open, the d-pad slides over the panel and letting go picks |
| Square (X) | Backspace (held, it repeats) |
| Triangle (Y) | Space |
| L1 / R1 | Move the text cursor left / right |
| L2 | Shift (twice quickly: caps lock) |
| R2 | Done (the keyboard's enter / action key) |
| Circle (B) | Back: close an open panel, else from emoji / clipboard back to the letters, else close the keyboard |

Cross is a finger: a touch is given to HeliBoard's window at the highlight (down when Cross
goes down, up when it comes up), so HeliBoard's own code handles every press, repeat,
long-press, popup and pick exactly as with touch. The highlight is drawn in that window's
overlay, above all of HeliBoard's views, and stays on what it is on when the keys change
(shift, symbols, emoji), or moves to what is now nearest. Only controller buttons are taken,
and only while the keyboard is up: a hardware keyboard's arrows stay HeliBoard's, and with the
keyboard closed every button goes to the app. The hooks use HeliBoard's own names, kept in its
release builds (`-dontobfuscate`), and were checked against HeliBoard 4.2's release APK.

## Performance (CMF Phone 1 only, experimental, root)

A **Performance** page appears in the module's menu on a CMF Phone 1 (`ro.product.device`
Tetris) and nowhere else. Everything on it was built from what this phone reports (its
clock, GPU and thermal files, Android's display and game services, PerfMTK's own files).

- **Ndot performance overlay**: a small panel over every app and the lock screen, updated
  twice a second,
  in MangoHud's style (its row colours and a frame time graph) with
  Scene's contents and size, drawn by the module (it is
  not MangoHud's code: MangoHud is a Linux Vulkan / OpenGL layer drawn inside the game, which
  Android only loads into debuggable apps):
  - CPU and GPU load rings with their clocks;
  - FPS and frame time in any app (the focused window found on whichever display has one), read
    four times a second on their own: the frames presented in the last half second up to now
    (MangoHud's default sampling period), and each new frame's time on a Nothing dot
    screen that scrolls as frames come (an empty column for a read with none, or a rest of
    200 ms or more) (SurfaceFlinger's present times for the focused window's layer: its
    SurfaceView in a game or video, else the window itself; with no focused window, as on the
    lock screen, System UI's; a still screen falls to 0);
  - GPU load, clock and temperature; CPU load and hottest core;
  - each cluster's set clock, then every core's load and real clock, as Scene lists them
    ("off" when a core is parked). On this phone every clock file reads the cluster's set
    clock (PerfMTK's limit), so a core's real clock is measured as Scene does: its CPU cycles
    over half a second (`simpleperf stat -a --per-core -e cpu-cycles`), else the cluster's;
  - RAM and swap use, DDR clock;
  - SoC temperature, battery temperature and the power drawn from the battery, each on its
    own row.

  Sensors that report a value below −40 °C are off: a powered-down GPU reports −274 °C, so
  the GPU's last real temperature is shown, marked "idle"; other sensors show "—". The files are root-only, so the module app reads them in one root shell and
  sends each reading to System UI, which draws the panel: Android hides an app's overlay on
  the lock screen (its layer, 111000, is below the lock screen's, 171000, and the keyguard
  policy hides windows below it), while a System UI panel window above it shows, as Nothing's
  fingerprint overlay does. Drag the panel to move it; it stays where it is left and takes
  only the touches on itself. No "Display over other apps" permission is needed. After a
  reboot it comes back when the Performance page is opened.
- **PerfMTK profile**: switches [PerfMTK](https://github.com/JUANIMAN/PerfMTK) to Performance,
  Balanced, Powersave or Powersave+ (`perfmtk <profile>`). PerfMTK's own per-app profiles
  still switch it as apps change. PerfMTK also sets the refresh rate with each profile, so
  the module leaves the refresh rate to it.
- **Thermal limits**: PerfMTK's thermal throttling on or off (`perfmtk --thermal
  enable|disable`, from its own help). Off runs hotter: for short sessions.
- **120 fps unlock (per game, off by default)**: some games pick their frame rate from a list
  of known phones (the reports showed two Unreal games asking the display for 60 Hz
  themselves, while Android, MediaTek and PerfMTK all allowed 120). Inside each game you pick,
  and nowhere else, the phone presents itself as a Samsung Galaxy S24 Ultra (Snapdragon 8
  Gen 3): Android's `Build` fields and the matching `ro.product.*` / `ro.soc.*` properties
  read through Java. The hardware and board names stay the phone's own. Tick each game in
  the module's LSPosed scope too, then restart the game. **Use it only for offline or
  single-player games:** games with online play or anti-cheat can treat a changed device as
  cheating and ban the account, and nothing can rule that out. It does not help games that
  choose by their graphics chip, which the driver still reports as Mali.

- **In Nothing's Game Bar** (tick *Game Bar* / `com.nothing.ntgamedashboard` in the module's
  LSPosed scope): three buttons after Nothing's own in its settings grid: **Perf overlay**
  (each tap on or off), **PerfMTK** profile (each tap the next: performance, balanced,
  powersave; PerfMTK's own per-app profiles still switch it as apps change) and **Thermal**
  limits on / off, showing its state. The Game Bar's grid is a fixed list of setting keys
  that it binds by name and leaves empty for a key it does not know; the module adds its keys,
  fills their rows. The overlay is started by the Game Bar itself: Android lets the system
  start a foreground service for an app in the background, not that app itself (asked from
  the module's own receiver it crashed with `ForegroundServiceStartNotAllowedException`). For
  the PerfMTK buttons it asks the module app, which has root and only listens to the system,
  which the Game Bar runs as.

Planned next: refresh rate options through PerfMTK's profiles (`--reload`).

## Roadmap

- **Nothing Dot Matrix Display**: a new engine for the emoji covers, which are a beta until
  it lands. It is planned as:
  - **Built for Nothing Launcher:** it works directly with Nothing Launcher.
  - **Very low power:** heavily optimized to render every emoji with as little power as
    possible.
  - **Understands each emoji:** its emotion, reflections, particles and 3D shape.
  - **Every emoji reworked one by one:** each one rendered in 3D, with several animations made
    for that emoji alone.

## Requirements

- Nothing Launcher **4.0.20**. Later versions will probably work too, because the hooks avoid
  obfuscated names (see below).
- LSPosed (or another Xposed implementation with API 93+ and `xposedsharedprefs` support).

## Install

1. Build the APK (`./gradlew assembleRelease`) or download it from the *Build module* GitHub
   Actions run.
2. Install it, enable it in LSPosed and select **Nothing Launcher** as the scope, plus only
   what you use:
   - **System UI**: the lit status bar, quick settings and *Lock screen rotation (test)*;
   - **System Framework** (`android`, needs a reboot): system font and widget cards in landscape;
   - **Nothing Widgets** and **NothingCardService**: widget cards in landscape;
   - **HeliBoard**: typing with a controller.
   The module installs on internal storage only: LSPosed reads it at boot, before a memory
   card used as internal storage (adopted storage, `/mnt/expand/…`) is mounted, and a module
   there was not loaded after a reboot ("Failed to open APK … No such file"), and Android,
   checking its apps before the card is mounted, deleted the module's data (its settings and
   folders) as belonging to a missing app. An install can still land there whatever the APK
   asks for (the "Force allow apps on external" developer option, or an installer that picks
   the card), so after an update, and whenever its page is opened, the module moves itself
   back to the phone's storage (root; `pm move-package com.dressence.nothinglauncherplus
   internal`), and its page says so.
3. Force-stop Nothing Launcher, or use "Restart Nothing Launcher" (root) on the app's Home screen page.
4. In Nothing Launcher settings, set *Drawer layout* to **Smart (Beta)** and tap **Custom folders**.

## Updates and signing

Every build is signed with the same key (`signing/update.jks`, committed on purpose), so a new
APK installs straight over the old one and keeps your folders. After an update the module
restarts Nothing Launcher by itself (root), and LSPosed loads the new code right away: no
uninstall, no reboot. Set `SIGNING_KEYSTORE`, `SIGNING_PASSWORD` and `SIGNING_KEY_ALIAS` to build
with a private key instead.

Builds from before v0.5.0 were signed with throwaway keys: switch once with **Export**,
uninstall, install, **Import**.

## Code layout

The module's code is grouped by where it runs:

| Package | What it is |
| --- | --- |
| `xposed` | The entry point (`ModuleEntry`, named in `assets/xposed_init`), hook helpers (`Hooks`: installing a feature so its failure is its own, guarding code the apps call back into), the settings as hooked apps read them (`ConfigStore`) and Nothing's NDot font (`NdotFont`). |
| `launcher` | Everything in Nothing Launcher: the Smart drawer's folders and covers, the folder menu, landscape, controllers, sounds, dot-matrix icons (`DotIcons`, `IconScreen`) and scrolling names. |
| `systemui` | The status bar and quick settings: `Clock`, `Battery`, `GreenIcons`, `GreenTexts`, `QuickSettings`, `NetworkSpeed`, all in the lit look of `Led`. |
| `perf` | CMF Phone 1 only: the device check and root helper (`Device`), the Ndot performance overlay (`OverlayService` reads, `PerfHud` draws) and the per-game 120 fps unlock (`GameUnlock`), the Game Bar's buttons (`GameBar`, in the Game Bar; `GameBarReceiver`, in the module app). |
| `system` | The system font, set in the font map system_server gives every app (`SystemFonts`), and a probe writing to the LSPosed log which window decides the screen's orientation each time it changes (`OrientationProbe`, `orientation: requested … by …`, naming the activity or window at the end of the chain). |
| `widgets` | Nothing's widget apps in landscape. |
| `keyboard` | Typing in HeliBoard with a game controller (`HeliBoardGamepad`). |
| `anim`, `sound` | The emoji animations and dot-matrix screens, and the music and interface sounds. |
| `ui` | The module's settings app: a menu of pages (App drawer, Home screen, Sound, Status bar & quick settings, Lock screen, System font, Backup, Diagnostics). |

Code the module hands to an app (a posted runnable, a listener, a view or drawable it draws)
runs as that app's own, so it is guarded: a fault costs that one feature, never the app. A
font for the system font map must be a real font file every app can open, or every app
drawing text with it would crash.

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
| Landscape grid | `CellPosMapper`, `Workspace.insertNewWorkspaceScreen`, `DeviceProfile.getCellSize`, `ModelWriter.updateItemInfoProps`, `Launcher.bindInflatedItems`/`finishBindingItems`, `CellLayout.markCellsAsOccupiedForView`/`isOccupied`, `ModelWriter.deleteItemFromDatabase` (reason text "Collision while binding") | Names kept |
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
