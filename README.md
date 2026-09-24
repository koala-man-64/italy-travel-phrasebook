# Italy Travel Pocket Guide

An Italian phrasebook and sentence builder for Rome, Sorrento and the Amalfi Coast. It's one self-contained `index.html` with no build step and no dependencies, laid out for phones.

**Open it:** https://koala-man-64.github.io/italy-travel-phrasebook/

## What's inside

- **Phrases:** 60 travel phrases with pronunciation guides and local tips. Search ignores accents, and you can filter by category.
- **Builder:** assemble a sentence from four slots (intent, item, detail, closer) across 10 travel categories. The live preview color-codes each part and shows pronunciation and the English meaning.
- **Vocab:** time, directions, numbers and connecting words. Tap a word to hear it.
- **Show to staff:** a full-screen, high-contrast card for any phrase or built sentence. It can flip 180° for the person across the table and keeps the screen awake where the browser supports it.

Audio uses the browser's built-in speech synthesis and needs an Italian voice on the device. It has a slow mode for practice.

## Offline use

Nothing is fetched after the page loads, but there is no service worker yet, so the web link itself needs a connection to open. To use it with no connection at all, save `index.html` to the device and open it from there.
