# Security policy

## Supported versions

Only the latest release of **Nothing Launcher Plus** gets fixes. Please update and check the
problem still happens before reporting.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report privately through GitHub instead: on this repository open the
**Security** tab and choose **Report a vulnerability**. Only the maintainer
sees the report.

Include what you can of:

- the version, your phone model and Android / Nothing OS build;
- what happens, and the steps or input that trigger it;
- logs (`logcat`, the module's own log) or a proof of concept.

## What counts as a security problem here

This is an Xposed (LSPosed) module that runs inside Nothing Launcher, System UI
and other system processes, and some features use root. These are in scope:

- anything that lets another app or untrusted input run code or gain
  privileges through the module's hooks, its settings or its root helpers;
- the module exposing data from the processes it hooks to other apps;
- a crash or hang of System UI or the launcher that can be triggered from
  outside (by an app, a notification, a file) and is not recoverable;
- the module making the launcher delete or lose the user's items;
- anything that can cause a bootloop the module's own safeguards do not catch.

Ordinary bugs, crashes that need no special input, and feature requests
belong in regular issues.

## What to expect

You will get an acknowledgement as soon as the report has been read. Confirmed
problems are fixed in the next release, and you will be credited in its notes
unless you would rather not be. Please allow a fix to ship before disclosing
the details publicly.
