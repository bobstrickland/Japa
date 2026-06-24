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

- **Screen-off counting** — Rounds are counted by pressing a volume button rather than tapping the screen, so the practice can continue with the display off. This matters for long sessions where screen-on counting would otherwise be impractical.

- **Customizable mantras and counts** — Choose from a number of included mantras, and set both the number of repetitions per round and the number of rounds per day to match your own practice.

- **Audio chanting support** — Built-in audio recitation of each mantra helps with correct pronunciation. Audio playback can be:
  - Triggered by the volume button (one repetition per press), or
  - Set to repeat continuously on its own
  - Sped up or slowed down to match your pace

- **Dual-script text display** — Mantra text is shown in both Roman transliteration and Devanagari script.

- **Additional prayers screen** — A separate section includes a number of other prayers for reference (text only, no audio).

### A Technical Note
Capturing volume button presses while the screen is off — and keeping that working reliably - turned out to be the hardest part of building this app. Android aggressively manages background processes, and getting the threading model right so the app doesn't get interrupted by the system mid-session took real trial and error. If you've worked with Android's background execution limits or foreground services, you'll know exactly what kind of headache that involves.

## Tech
Android, Java, Gradle. Built with an automated CI workflow via GitHub Actions.

## Status & Roadmap
Currently in alpha testing. Feedback and issue reports welcome.
