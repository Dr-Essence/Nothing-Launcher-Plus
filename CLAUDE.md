# Working rules for this module

- **Never assume.** Inspect and research before every change: read the decompiled code, the
  user's logs (LSPosed log, full logcat), videos and dumps, and find the cause in that
  evidence. A change that rests on an untested idea of how Android or the vendor stack behaves
  is a guess: get the evidence first (ask for the exact dump or log that settles it) instead
  of shipping a build to find out.
- **Watch everything the user sends.** Every video, screenshot and report recording is looked
  at, frame by frame where it matters, before anything is said about what it shows. Layout
  numbers from dumps never stand in for looking: judge how a screen looks from the pictures.
- **Test before each build.** Compile and check every change against the code it touches
  before giving a build; say plainly what was and was not verified, and never present a guess
  as a fix.
- **Never fight Nothing's or Android's features.** A feature works with Nothing Launcher /
  SystemUI / widgets / Android, not against them. If one of their mechanisms gets in the way
  (a check, a relayout, a scale, a portrait lock), find it in the decompiled code and either
  switch that one piece off where it is not needed or fix the mechanism itself, rather than
  letting both run and undoing its result afterwards (no re-measuring, re-padding or
  re-positioning what Nothing just laid out, no racing its own passes).
- **Clean dead code.** Remove what a change makes unused (methods, fields, hooks, settings,
  diagnostics that served their purpose) in the same change.
- **Keep the README's Status section current with every change.** Each commit that changes
  behaviour also updates it: the current version and state, what is pending, which features
  have not been tested on a phone yet, which tests are pending (and what evidence they need),
  and the bugs fixed. A change is not finished while the Status section still describes the
  build before it.
- The module must never crash or stall what it hooks: guard everything handed to the host,
  never block the main thread, and never make the launcher delete or lose the user's items.

# Project direction

- **Emoji covers are a beta.** They will be replaced by **Nothing Dot Matrix Display**:
  - a super-optimized engine that works directly with Nothing Launcher;
  - it understands each emoji's emotion, reflections, particles and 3D shape;
  - it renders every emoji with as little power as possible;
  - every individual emoji is reworked and rendered in 3D, with multiple animations made for
    each one.

  Keep the README's Roadmap and the beta notes in line with this.
