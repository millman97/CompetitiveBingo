# Plugin/server API contract

Schema version **1**, plugin version **0.1.0**. Field definitions and validation rules are in
the [event](schema/event.schema.json), [game configuration](schema/game-config.schema.json)
and [board](schema/board.schema.json) JSON schemas.

## 1. Division of responsibility

The plugin observes game events, filters tracked metrics and sends evidence. The server owns
teams, baselines, deduplication, scoring and review. Board documents contain display data;
the plugin renders them without calculating progress.

## 2. Conventions

- Paths are relative to the player's base URL. There is no default server. Credentials, query
  parameters and fragments in the base URL are rejected; redirects are not followed.
- HTTPS is expected; HTTP is accepted for local hosting. Bodies are UTF-8 JSON.
- Game lookup and sync are unauthenticated. Other endpoints use `Authorization: Bearer <playerToken>`.
- Timestamps are UTC epoch milliseconds. `clientTime` is untrusted.
- `accountHash` is a signed 64-bit **decimal string**. Never parse it as a JSON number or use
  the mutable `displayName` as identity.
- Optional nullable fields may be absent or null. Required response fields must be present
  and non-null; malformed responses fail visibly.
- `schemaVersion` describes payload semantics; `pluginVersion` is semver. Incompatible versions
  receive HTTP 409. Adding an optional field does not require a schema change.

## 3. Metric IDs

### 3.1 Normalisation

Lowercase the game name using the English locale, replace runs outside `[a-z0-9]` with `_`,
trim leading/trailing underscores, and add the family prefix. For example,
`Chambers of Xeric: Challenge Mode` becomes `kc.chambers_of_xeric_challenge_mode`.

| Type | Prefix | Source |
|---|---|---|
| `COUNT` | `kc.` | Cumulative chat counters, including completions, chests and laps |
| `KILL` | `kill.` | Loot Tracker's classified NPC loot occurrences |
| `DROP` | `drop.` | Item loot and configured value rules |
| `DURATION` | `time.` | Completion-time messages |
| `COLLECTION_LOG` | `collection_log.` | New collection-log item messages |

`kill.*` observations have no cumulative baseline or offline backfill. They must not be
treated as official `kc.*` counters.

### 3.2 Aliases

The plugin's `metric-aliases.json` maps irregular activity names to canonical IDs before
filtering. Servers receive the canonical ID and do not need to reproduce the alias map.

### 3.3 Matching against trackedMetrics

Matching is exact, except `drop.*` and `collection_log.*`, which match their respective
namespaces. Other wildcard forms are invalid. A value rule also enables its own metric.

### 3.4 Value-range drop metrics

`dropValueRules` emit named `drop.` metrics when a stack's unit price multiplied by quantity
falls within inclusive `minValue`/`maxValue` bounds. Missing bounds are unbounded. Missing prices
match no rule. Overlapping rules can each emit an event for the same stack.

`valuation` defaults to `GRAND_EXCHANGE`; `HIGH_ALCHEMY` is also supported. `counts` defaults to
`ITEMS` (quantity as delta); `VALUE` reports the stack's coin value as delta. Invalid metric IDs,
wildcards and inverted ranges are rejected. With no rules, the plugin performs no price lookups.
Client prices and `ruleValue` are evidence for review, not authoritative valuations.

## 4. Endpoints

### 4.1 GET /api/plugin/game?code={joinCode}

Unauthenticated game lookup, performed before sync. Example response:

```json
{
  "gameId": "g_7f3a91",
  "name": "Clan Bingo",
  "state": "LIVE",
  "startsAt": 1755950400000,
  "endsAt": 1756555200000,
  "schemaVersion": 1,
  "minPluginVersion": "0.1.0",
  "trackedMetrics": ["kc.vorkath", "drop.abyssal_whip"],
  "screenshotsRequested": false
}
```

States are `UPCOMING`, `LIVE`, `ENDED` and `LOCKED`. Unknown states are rejected. Required
fields are shown above; these additional fields are optional:

| Field | Meaning |
|---|---|
| `metricLabels` | Display labels keyed by metric ID or wildcard |
| `metricTargets` | Positive display targets; `time.` targets are milliseconds |
| `dropValueRules` | Value rules from section 3.4, optionally with a display `label` |
| `manualEntryAllowed` | Defaults to true; false hides manual submission |
| `blockedWorldTypes` | RuneLite `WorldType` names; null/empty blocks nothing; unknown names are skipped |
| `startWorld` | Preferred world; a hop is offered only if it is permitted |

Labels and targets do not affect reporting. A reached target does not stop observations.
`endsAt` is display-only; server state decides whether reporting is allowed.

### 4.2 POST /api/plugin/sync

Unauthenticated, called while logged in. Request:

```json
{
  "joinCode": "SWAMP-TOAD-42",
  "accountHash": "-4234871239847123",
  "displayName": "Safespot Ted",
  "pluginVersion": "0.1.0",
  "schemaVersion": 1
}
```

Response:

```json
{
  "playerToken": "opaque-bearer-token",
  "gameId": "g_7f3a91",
  "teamName": "The Sharks",
  "tokenExpiresAt": 1756555200000
}
```

`teamName` may be null. The token must be non-empty and expire in the future; `gameId` must
match the lookup. Sync must be idempotent for an account and join code: renew credentials
without creating another player or resetting progress.

Tokens stay in memory. Saved resume permission contains the account's server URL, join code
and expiry. Every resume performs a fresh handshake; legacy saved tokens are removed when accessed.

### 4.3 POST /api/plugin/events

Bearer auth. The body is an array of envelopes, including for a single event. Responses contain
one result per submitted `eventId`; order may differ from the request:

```json
{
  "results": [
    {
      "eventId": "777f44d8a5a8a5a82c72a18de4c026da",
      "accepted": true,
      "duplicate": false
    }
  ]
}
```

A duplicate is accepted with `duplicate: true`. Rejected events have `accepted: false` and
one of these reasons. Per-event rejections still use HTTP 200.

| Reason | Behaviour |
|---|---|
| `NOT_TRACKED` | Reject the event; Re-sync refreshes tracked metrics |
| `GAME_NOT_LIVE` | Stop delivery until Re-sync |
| `OUT_OF_WINDOW` | Reject and show the outcome |
| `INVALID` | Reject and log identifiers; unknown reasons are treated as `INVALID` |

Batches contain at most 50 events and 2 MiB of UTF-8 JSON, with one upload in flight.
Events are coalesced for one game tick and retain observation order. The memory queue holds
200 events and 8 MiB of envelope data. Oversized events and full queues fail locally.
Responses are limited to 2 MiB. A retry includes the original screenshot again.

### 4.4 POST /api/plugin/ready

Bearer auth. Sent after sync and when readiness changes, without polling:

```json
{
  "ready": false,
  "checks": [{ "id": "KILL_COUNT_FILTER", "satisfied": false }]
}
```

`ready` means all applicable checks pass. Check IDs and applicability are in section 9.1.
Any 2xx response succeeds, with or without a body. Failed reports are attempted again on the
next readiness change; authentication and game-state failures block the connection.

### 4.5 GET /api/plugin/board

Bearer auth. Optional: return 404 when no board is available. Board failures do not fail sync.
Fetched after sync and when the player presses Refresh. The [board schema](schema/board.schema.json)
defines `title`, `capturedAt`, optional `subtitle`, and `sections`:

- `GRID`: cells positioned by `row` and `col`; missing squares are blank. Dimensions may be
  inferred or extended from cells. Cells without both coordinates are skipped.
- `LIST`: cells in the supplied order.
- `TEXT`: a paragraph in `body`.

Text is rendered literally. Unknown section kinds are skipped, unknown cell states are neutral,
and invalid `#rrggbb` accents are ignored. The displayed timestamp identifies the snapshot.

### 4.6 When the plugin syncs

Sync occurs on a button press, login with valid saved resume permission, or a joined lobby's
scheduled start. An `UPCOMING` response schedules one sync at `startsAt`. If that response
postpones the start, one further sync is scheduled. An overdue `UPCOMING` response fails visibly.

There is no state polling. The next request reports token expiry, game end or version errors.
Newly added board metrics require Re-sync; removed metrics can receive `NOT_TRACKED`.
Changing destination, leaving, re-syncing or switching accounts invalidates pending sends
and callbacks from the previous connection.

## 5. The event envelope

The [event schema](schema/event.schema.json) defines the complete envelope. Example:

```json
{
  "schemaVersion": 1,
  "eventId": "777f44d8a5a8a5a82c72a18de4c026da",
  "gameId": "g_7f3a91",
  "accountHash": "-4234871239847123",
  "displayName": "Safespot Ted",
  "sessionId": "6b1f0c2e-3a44-4d8b-9f10-27c5e8a1b3d4",
  "clientTime": 1755950400000,
  "gameTick": 123456,
  "world": 302,
  "worldTypes": ["MEMBERS"],
  "metric": {
    "id": "kc.vorkath",
    "type": "COUNT",
    "value": 342,
    "delta": 1,
    "firstObservation": false
  },
  "source": "OBSERVED",
  "detail": { "activity": "Vorkath", "rawMessage": "Your Vorkath kill count is: 342." },
  "screenshot": null,
  "screenshotMediaType": null
}
```

`sessionId` is a lowercase UUID created on login or account switch. It disambiguates repeating
game ticks and is not identity or a credential. `worldTypes` contains RuneLite enum names,
including `TOURNAMENT_WORLD`, not `TOURNAMENT`.

### 5.1 value and delta

| Observed type | `value` | `delta` |
|---|---|---|
| `COUNT` | Cumulative count | `1`, including the first observation |
| `KILL` | null | `1` |
| `DROP` | null | Quantity, or stack value for a `VALUE` rule |
| `DURATION` | Milliseconds | null |
| `COLLECTION_LOG` | Supplied completed count, usually null | `1` |

The server maintains the last count per account and metric and derives authoritative increments.
For example, value 342 against stored 335 with delta 1 leaves six unexplained kills.
Durations are not cumulative; lower is better.

`firstObservation` is true when a metric is first seen for the current account. It resets on
plugin start or account switch, but survives relogs for the same account.

### 5.2 detail by type

For `OBSERVED`, select the detail shape using `metric.type`; detail has no discriminator.
The schema marks required and nullable fields.

| Type | Detail fields |
|---|---|
| `COUNT` | `activity`, `rawMessage` |
| `KILL` | `npcId`, `npcName`, nullable `combatLevel`, `signal: SERVER_NPC_LOOT`, `observationOrdinal` |
| `DROP` | `itemId`, `itemName`, `quantity`, `source`, `sourceType`, nullable `killCount`, `geValue`, `haValue`, `ruleValue`, `observationOrdinal` |
| `DURATION` | `activity`, nullable `variant`, `durationMs`, `isPersonalBest`, nullable `personalBestMs`, `rawMessage` |
| `COLLECTION_LOG` | `itemName`, nullable `completed` and `totalPossible` |

`rawMessage` has formatting removed and `<br>` converted to newline. Drop source types use
RuneLite's vocabulary; this build excludes `PLAYER` before building observations.
`killCount` is the last chat count for the source, for context only. `geValue`/`haValue` are
per-item prices; `ruleValue` is the whole-stack valuation used by a matching rule.
`observationOrdinal` distinguishes otherwise identical drops or kills on the same tick.

Observed kills require classified NPC loot. Pickpockets, encounters with known duplicate loot
packets, and kills without server loot are excluded. Collection-log counts are normally null.

### 5.3 screenshot

Base64 evidence is embedded in the envelope. `screenshotMediaType` is non-null exactly when
the screenshot is present. This build emits JPEG; the schema also permits PNG and WebP.

Capture requires the player's opt-in and `screenshotsRequested`. It starts at observation time;
waiting events may share a frame. Capture and encoding have a five-second deadline, after which
the event can proceed without evidence. Images are at most 1280px wide and target 200 KiB.
With chat hiding enabled, a frame with visible chat is discarded. Servers must enforce their own limits.

### 5.4 Manual entries

Manual events use `source: MANUAL` and this detail shape, regardless of metric type:

```json
{ "activity": "Chambers of Xeric time", "input": "25:00", "interpretedAs": "duration" }
```

`input` preserves the trimmed submitted text. `interpretedAs` is `count`, `quantity`, `duration`
or null. The namespace still determines metric type. Parsed `kc.` inputs are absolute counts
with delta 1; `kill.`/`drop.` inputs are quantities; `time.` accepts times or bare seconds.
Uninterpretable input is still submitted and can leave value/delta null, including for COUNT
and DURATION. Screenshot settings apply as in section 5.3.

`manualEntryAllowed: false` hides the control; a server refusing manual events must also reject
them on ingestion.

## 6. eventId and deduplication

Join these fields with `|`, encode as UTF-8, take SHA-256 and encode its first 16 bytes as
32 lowercase hex characters. Integers use plain decimal without grouping or a positive sign.

| Type/source | Hashed fields, in order |
|---|---|
| `COUNT`, `DURATION` | `accountHash`, `sessionId`, `metricId`, `gameTick`, `value` |
| `KILL` | `accountHash`, `sessionId`, `metricId`, `gameTick`, `npcId`, `observationOrdinal` |
| `DROP` | `accountHash`, `sessionId`, `metricId`, `gameTick`, `itemId`, `quantity`, `observationOrdinal` |
| `COLLECTION_LOG` | `accountHash`, `metricId` |
| Any `MANUAL` | `accountHash`, `sessionId`, `metricId`, `gameTick`, literal `manual`, `input` |

Test vector:

```text
input   -4234871239847123|6b1f0c2e-3a44-4d8b-9f10-27c5e8a1b3d4|kc.vorkath|123456|342
sha256  777f44d8a5a8a5a82c72a18de4c026da975a8f1133b401f430336f7452076da3
eventId 777f44d8a5a8a5a82c72a18de4c026da
```

Deduplicate on **`(gameId, eventId)`**. Retries reuse the original envelope. Collection-log IDs
omit session/time because each slot unlocks once. Hash verification checks consistency, not authenticity.

## 7. Errors

Non-2xx responses contain `error` and a player-facing `message`; a 409 may include `requiredVersion`.

| Status | Behaviour |
|---|---|
| 400, other non-retryable 4xx | Fail visibly; do not retry |
| 401 | Discard token; require Re-sync |
| 403 | Stop reporting; player removed or not assigned to a team |
| 404 | Unknown join code; fail sync. For board requests, no board is available |
| 409 | Incompatible schema/plugin version; require a compatible version |
| 410 | Game ended; stop reporting |
| 429 | Bounded resume using `Retry-After` |
| 5xx | Retry as below |

## 8. Retry

Connection failures, timeouts and 5xx receive up to three attempts with exponential backoff
starting at two seconds. A 429 honours numeric or HTTP-date `Retry-After`. Three consecutive
rate-limit responses, or a requested delay over five minutes, abandon the batch. Other 4xx fail immediately.

Failed events appear in recent activity, then leave the queue. There is no disk queue or retry
after restart. A later cumulative count can reconcile missed counts; lost delta-only events,
durations and collection-log unlocks may require manual evidence.

## 9. Behaviours the server may rely on

No requests occur before explicit initial setup and Sync. Observations require `LOGGED_IN`,
`LIVE` state and every applicable readiness check. Uploads use one connection-bound sender.
Fatal failures remain blocking; readiness changes do not clear them. Shutdown cancels pending
work without waiting for uploads.

### 9.1 Readiness gating

| Check ID | Applies when | Requirement |
|---|---|---|
| `KILL_COUNT_FILTER` | Tracking counts or durations | `BOSS_KILLCOUNT_FILTERED` is 0 |
| `COLLECTION_LOG_NOTIFICATION` | Tracking collection-log metrics | `OPTION_COLLECTION_NEW_ITEM` bit 0 is set (1 or 3) |
| `WORLD_TYPE` | Always | No active world type appears in `blockedWorldTypes` |
| `LOOT_TRACKER` | Tracking drops or observed kills | RuneLite Loot Tracker is enabled |

One failed check pauses all observations. Unknown world-type names are ignored.

## 10. What the server must not trust

The client enforces these bounds, but servers must validate independently:

| Item | Limit |
|---|---|
| Tracked metrics / value rules | 256 / 32 |
| Metric slug | 128 characters |
| Response text / manual input | 1,024 / 2,048 characters |
| Board sections | 32 |
| Board rows or columns | 50 |
| Total cells/grid positions, including blanks | 1,000 |
| Detail lines per cell | 16 |

Every event field, client price and screenshot comes from a player-controlled machine. Use
server receipt time for game windows, account hashes for identity, and stored history for
count baselines. Screenshots and event hashes do not prove an observation occurred.
