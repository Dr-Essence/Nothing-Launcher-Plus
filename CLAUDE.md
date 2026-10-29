# Working rules for this module

- **Never fight Nothing's code.** A feature works with Nothing Launcher / SystemUI / widgets,
  not against them. If Nothing's own mechanism gets in the way of a feature (a check, a
  relayout, a scale, a portrait lock), find it in the decompiled code and switch that one
  piece off where it is not needed, rather than letting both run and undoing its result
  afterwards (no re-measuring, re-padding or re-positioning what Nothing just laid out, no
  racing its own passes).
- Inspect before changing: read the decompiled code and the user's logs and videos, and fix
  the cause, not the symptom. Do not guess.
- The module must never crash or stall what it hooks: guard everything handed to the host,
  never block the main thread, and never make the launcher delete or lose the user's items.
