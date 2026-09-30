# Colosseum companion 1.19

Version 1.19/code 20 adds six photo-led Colosseum stops, 15 distinct real photographs, offline historical descriptions and credits, a timeline, and an itinerary shortcut. It preserves the existing personal-data schema and signing configuration. Guide selection is session-only.

## Verification

- Web: 338 Node tests passed, including content and photo integrity, local references, image fallback, safe text rendering, stop navigation, load/dispose behavior, keyboard/swipe navigation, and unchanged personal-data validation.
- Android: 136 release unit tests, lint, the 33 positive/negative asset-boundary checks, release assembly, and actual APK manifest/asset verification passed. Lint has zero errors and six existing warnings. APK signature verification passed with the existing signing certificate.
- Browser: all six stops, all 15 distinct photographs, history disclosures, and the itinerary/guide round trip exercised. No console errors or horizontal overflow observed. Layout fixture passed at 320, 360, and 390 pixels with 100% and 200% text and a simulated visible Conversation control. This is layout evidence, not Android runtime proof.
- Independent review: GPT-6 Luna/medium reviewed navigation, data boundaries, offline assets, accessible controls, representative photos, and publication scope. No remaining actionable feature findings.
- Offline browser: after the local server was stopped, a reload still opened the app and all six stops, all 15 photographs, and the credits remained readable from the offline cache.

No Android phone was connected during this release. Installation, native navigation, and cold-start offline behavior on the target device remain unverified for 1.19. To verify after installation: open Guide, browse all six stops and their photographs, expand history/credits, return to Itinerary, then repeat with networking disabled. Preserve existing app storage and documents.

## Public source

The public change is a clean snapshot of the app and its tests, including the Android features that previously existed only locally. Internal coordination notes, local logs, signing keys, personal documents, generated test screenshots, and private Git ancestry are excluded. The frozen itinerary regression uses the exact original HTML bytes and pinned hash; the unrelated private archive is not published.

## Recovery

Keep the same signing certificate for upgrades and use an increasing version code. Do not uninstall or clear storage to work around an update problem. Report an installation failure before attempting recovery; Android may reject a downgrade. Existing backup and document recovery flows are unchanged.
