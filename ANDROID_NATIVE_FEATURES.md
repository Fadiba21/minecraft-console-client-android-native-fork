# Android Native feature snapshot

This branch contains the current MCC Droid Android Native application snapshot based on the MCC Console Client project.

Included features:

- ChatGameAI port with local solvers, cache, Gemini support, delays, cooldowns, winner detection, statistics, and settings panel.
- Private-message detection for all players using `[Player -> You]` formats, Android notifications, and inline replies routed to the active MCC console.
- Fifteen UI sound themes with CC0 source attribution and 195 generated sound resources.
- Android runtime bundle under `app/src/main/assets/mcc-bundle.zip`.

The upstream MCC source remains available on the `master` branch. The `android-native` branch is the Android application snapshot.
