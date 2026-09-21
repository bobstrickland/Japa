# Japa
An Android app for counting japa (mantra repetition) rounds, built to solve a few practical problems that existing japa counter apps don't handle well.

## Status
In active development / alpha testing.

## Why this app
There are a number of japa counting apps already available, but most share the same limitations:
- they require the screen to be on and the user to tap the display for every count, and
- they offer little flexibility around mantra content or pacing.

For a practice that can run 1.5–2 hours a day, screen-on counting is a real problem — it drains the battery, lights up the room, and breaks the focus the practice is meant to support.

Japa is built around a different approach: counting happens through the device's volume buttons, so the screen can stay off for the entire session.

## Features

- **Screen-off counting** — Rounds are counted by pressing either volume button rather than tapping the screen, so the practice can continue with the display off. This matters for long sessions where screen-on counting would otherwise be impractical. A foreground service keeps counting reliably in the background, and an ongoing notification shows the current bead and round at a glance.

- **Customizable mantras and counts** — Choose from a number of included mantras, and set both the number of repetitions per round and the number of rounds per day to match your own practice.

- **Audio chanting support** — Built-in audio recitation of each mantra helps with correct pronunciation. Audio playback can be:
  - Triggered by the volume button (one repetition per press), or
  - Set to repeat continuously on its own
  - Sped up or slowed down to match your pace

- **Per-bead and per-round feedback** — Each bead and each completed round can be confirmed with vibration, a sound, or nothing at all, set independently. With the screen off, that feedback is the only thing you need.

- **Dual-script text display** — Mantra and prayer text is shown in either Roman transliteration or Devanagari script, at one of four text sizes.

- **Visual progress** — When the screen is on, the count is shown as a circular necklace of beads that fill as you go, along with a progress ring, the current bead and round, and a dot per completed round.

- **Included prayers screen** — A separate section collects a number of prayers for daily use — on waking, before study, before eating, before sleeping, the Hanuman Chalisa and others — each with its own background image, and with audio recitation where a recording is available.

- **Your own prayers** — Add, edit and delete your own prayers, each with its own text and an optional background image chosen from your photos. They are stored on the device and appear on their own screen alongside the included ones.

- **Prayer sets** — Group your prayers into named sets and put them in the order you actually recite them, then work through a set one prayer at a time. A prayer can belong to several sets, and deleting a set leaves the prayers themselves untouched.

- **Sharing by file** — Export a set as a `.japa` file and send it through any chat or file app. The bundle carries the prayer text, the set and its order, and the background images. On import, the app lists what is in the bundle, points out any prayers you already have, and lets you keep your versions or replace them.

- **Sharing by QR code** — For a quick hand-off with no file transfer, show a set as a QR code for someone else to scan. Background images are not included, and very large sets have to go by file instead.

- **Meditation timer** — A silent-meditation timer alongside the prayer screen: set a total sitting length and an interval, and a bell marks each interval as it passes and sounds three times at the end.

### A Technical Note
Capturing volume button presses while the screen is off — and keeping that working reliably - turned out to be the hardest part of building this app. Android aggressively manages background processes, and getting the threading model right so the app doesn't get interrupted by the system mid-session took real trial and error. If you've worked with Android's background execution limits or foreground services, you'll know exactly what kind of headache that involves.

## Tech
Android, Kotlin, Gradle. Built with an automated CI workflow via GitHub Actions.

## Status & Roadmap
Currently in alpha testing. Feedback and issue reports welcome.
