# Italy Travel Pocket Guide

A phone-friendly Italian phrasebook, sentence builder, itinerary, and offline sightseeing companion for Rome, Sorrento, and the Amalfi Coast. The website uses static HTML, CSS, and JavaScript. The sideloaded Android app bundles the same assets and adds native conversation and document tools.

## Colosseum companion · version 1.19

Open **Guide** or tap **Open Colosseum guide** beside the itinerary event. Six stops explain the arches, corridors, arena, underground, seating, and panoramic views. Each stop pairs its description with three real photographs, things to notice, and expandable history. The guide includes 15 distinct credited photographs, a timeline, and a return to the Forum/Palatine itinerary.

All guide photographs and text are bundled in the APK. No connection or model download is needed for the guide. The suggested sequence adapts to your booked tour and permitted route; arena, underground, and attic access depend on your ticket. Historical photographs may show different barriers or displays from the current visit. The selected stop lasts for the current app session.

Photo creators, source pages, licenses, and image changes are readable under **Photo credits & historical sources**. The photographs retain their individual licenses; `photo-credits.json` records their metadata and exact asset hashes.

## Install or update Android

Download the APK attached to the current GitHub release on your Android phone, open it, and allow installation from the browser or Files app if Android requests it. Choose **Update** over an existing installation. Do not uninstall or clear app storage to update: those actions can remove saved information and downloaded speech models.

Package: `com.koalaman64.italytravelpocketguide`. Version: **1.19**, version code **20**, Android 8 or newer. The release is non-debuggable and uses the existing signing certificate so it can update the previously installed app. Signing keys are not stored in this repository. A build made using a different developer key will not update an existing installation signed with the release key.

## Other features

- **Phrases, Builder, and Vocab:** travel Italian, pronunciation, phrase search, word replacement, and tap-to-hear speech.
- **Itinerary and Today:** dated trip entries, useful Italian, Maps actions, and destination photo guides with See / Eat / Drink recommendations.
- **Saved and learning:** saved sentences, listening practice, and local progress.
- **Conversation on Android:** face-to-face English/Italian text, offline translation and speech after model/voice preparation; Italian typing and screen rotation.
- **Documents on Android:** local document attachments and explicit backup/restore flows. Personal documents and app data are not part of this source release.

For offline conversation, open **Conversation → More → Setup → Prepare models** while connected, and install offline English and Italian device voices. Verify speech and translation on the actual phone before relying on them. Maps needs connectivity or separately downloaded map data.

The web app can be installed from Safari or Chrome using **Add to Home Screen**. Open it once online to populate the offline cache. The Android APK is self-contained for its bundled text and photographs.

## Build and validate

Use Node.js 22 or newer and Python 3 for the web tests:

```sh
node --test tests/*.test.cjs
python -m http.server 8766 --bind 127.0.0.1
```

Open the local app and exercise Guide. The responsive fixture is `/tests/colosseum-browser.html?width=320`; repeat at 360 and 390. It checks normal and 200% text, including the Android Conversation control as a layout simulation. The existing conversation fixture is `/tests/conversation-browser.html?width=360&height=740`.

The pinned trip-contract validator and original HTML fixture are included under `tests/fixtures/`. Their hashes remain enforced. Internal coordination notes and private historical source archives are deliberately excluded.

With JDK 17+ and Android SDK 35 configured, run from `android/`:

```sh
./gradlew testReleaseUnitTest lintRelease testReleaseBoundary assembleRelease
```

On Windows use `gradlew.bat`. The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. Asset and manifest policy is enforced during the release build. Update `android/app/asset-boundary.json` and `sw.js` together when adding bundled files.

Local tests and browser checks do not establish physical-device behavior. See [release validation](docs/validation-1.19.md) for the checks performed and remaining device verification.
