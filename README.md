# Competitive Bingo

Reports game observations to a clan bingo server so it can track progress as you play.
The server handles teams, scoring and validation.

## Setup

1. Ask your organiser for a server URL and join code.
2. Enter both in the sidebar and press **Sync**.
3. Fix any readiness checks shown in the panel. Reporting starts when every check passes
   and the game is live.

After a successful sync, the plugin can reconnect on login while its saved resume permission
is valid. Changing the server or join code closes the connection; press Sync to reconnect.
Bearer tokens stay in memory.

Organisers can self-host [Competitive Bingo Web](https://github.com/millman97/CompetitiveBingoApp)
or use another server implementing the [API contract](docs/api-contract.md).

## Tracking

The plugin reports only metrics requested by the board:

- Cumulative counters printed in chat: kills, completions, chests, laps and similar activities.
- Drops from NPCs, pickpocketing and activity rewards. Player loot is excluded.
- Drop stacks matching the board's value rules.
- Completion times, personal bests and new collection-log entries.
- NPC kills reported through Loot Tracker, including monsters without an official counter.

The panel lists targets and recent upload results. If the server supplies a board, **Show board**
opens a snapshot that can be refreshed. Board edits require **Re-sync** to update tracked metrics.

Where the game allows manual entries, select a target and enter a count, quantity or time.
These submissions are marked for server-side review.

## Limits

Loot Tracker must be enabled for drops and observed kills. Kills without a server loot signal
are not observed. Encounters known to send multiple loot packets should use their official
`kc.*` counter. Quest speedruns without quest context and repeated pet rolls need manual evidence.

The Fractured Archive's total timer is recognised. Its KC parser coverage uses the standard
raid-counter format; the exact live completion message still needs confirmation.

Uploads use a bounded memory queue: 200 events and 8 MiB. Failed or unsent observations can be
lost on restart. A later cumulative count may reconcile missed counts; other losses may need
manual evidence. Tests focus on parsing, delivery failures, privacy and resume settings.

## Privacy

There is no default server. Requests begin only after you configure a URL and join code and
press Sync. Events include your account hash, display name, world, tick, timestamp and metric.
Manual entries include the text you submitted.

Screenshots are off by default and require both your opt-in and the game's request for evidence.
**Hide chatbox** hides chat and private messages during capture; a frame with visible chat is
discarded when that option is enabled. Other overlays and player names may remain visible.
Capture and encoding time out after five seconds, after which an event may be sent without an image.

Data goes to your configured server, which is not operated or verified by RuneLite developers.

## Development and licence

The project targets Java 11. `./gradlew test` runs unit tests; `./gradlew run` launches the
development client.

Chat parsing and screenshot redaction draw on [Dink](https://github.com/pajlads/DinkPlugin)
and RuneLite. Derived files retain their copyright notices. Licensed under [BSD 2-Clause](LICENSE).
