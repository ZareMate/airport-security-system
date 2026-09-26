# Airport Security System

Server-side NeoForge mod for checking client keybind and translation-key resolution.

Airport Security System sends temporary client-only sign data containing configured probes, opens the sign editor, reads the client's resolved text, and evaluates the responses on the server. The probe does not place or modify a sign in the server world.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- LuckPerms on the server for player permission checks

The Airport Security System client mod is optional. Without it, the server-side check still works; the client overlay/loading UI is only available when the mod is installed on the client.

## Commands

Manual checks can be started with:

```
/airport_security_system <player>
```

Short alias:

```
/ass <player>
```

The command requires the configured command permission unless the command source has permission level 3 or higher.

## LuckPerms

The default permissions are:

| Permission | Purpose |
| --- | --- |
| `airport_security_system.command` | Allows use of `/airport_security_system <player>` and `/ass <player>`. |
| `airport_security_system.join.bypass` | Exempts a player from automatic join checks. |
| `airport_security_system.alerts` | Allows a player to receive detected/inconclusive result broadcasts. |

These nodes can be changed in `config/airport_security_system-common.toml`:

```toml
command_permission = "airport_security_system.command"
join_bypass_permission = "airport_security_system.join.bypass"
broadcast_permission = "airport_security_system.alerts"
```

Example:

```
/lp group admin permission set airport_security_system.command true
/lp group admin permission set airport_security_system.join.bypass true
/lp group admin permission set airport_security_system.alerts true
```

The server console can run the command without a player permission check.

## Probe configuration

Probes are configured with `blacklisted_keys`:

```toml
blacklisted_keys = [
    "METEOR:key.meteor-client.open-gui",
    "KEYBIND:xray.config.toggle",
    "TRANSLATE:bleachhack.module.killaura"
]
```

Supported probe types:

- `KEYBIND` — resolves the entry as a Minecraft keybind component.
- `TRANSLATE` — resolves the entry as a translation component.
- `METEOR` — resolves the entry using the Meteor-specific translation/keybind probe behavior.

A bare key without a type prefix is still accepted for backwards compatibility. The configuration parser also recognizes `xray.debug.init` and `xray.overlay` as translation probes when no explicit type is supplied.

The default configuration contains probes for several known client/mod translation keys and keybinds. Edit `config/airport_security_system-common.toml` to add or remove entries.

## Automatic join checks

Automatic checking is controlled by:

```toml
auto_check_on_join = true
join_check_chance_percent = 10
only_first_join = false
join_check_delay_ticks = 40
```

With the defaults:

- Automatic checking is enabled.
- Each eligible login has a 10% chance of being selected.
- The check starts 40 ticks (2 seconds) after login.
- `only_first_join = true` limits automatic checks to once per UUID while the server is running.
- Players with `airport_security_system.join.bypass` are skipped.

The per-batch response timeout defaults to:

```toml
timeout_ticks = 120
```

That is 120 ticks (6 seconds).

## Results

A completed check can produce:

- **DETECTED** — one or more configured probes resolved to an unexpected/blacklisted value.
- **INCONCLUSIVE** — the client did not provide a usable response, timed out, or protected a probe from normal resolution.
- **CLEAN** — no configured blacklisted probes were detected.

Manual results are returned to the command sender. Detected and inconclusive results are also broadcast to players with operator permission level 3 or the configured `airport_security_system.alerts` permission.

Clean results are not broadcast.


## Admin Notes integration

Airport Security System has optional support for the [Admin Notes](https://github.com/ZareMate/admin-notes) mod (version 2.0.0 or newer).

When Admin Notes is installed, completed security checks automatically maintain **one system note per player**.

If multiple detections are found, they are combined into the same note:

```
x-ray detected
freecam detected (last: dd-mm-yyyy)
```

The note is updated on later checks rather than creating another note.

- All Airport Security System detections for a player use one system note.
- Multiple detection categories are stored together in that note.
- Existing older per-category Airport Security System notes are automatically consolidated the next time a detection is recorded.
- A clean check replaces the Airport Security System detection note with:
  ```
  cleared (last: dd-mm-yyyy)
  ```
- Repeated clean checks update that same cleared note instead of creating duplicates.
- Existing manually authored Admin Notes are left unchanged.

The integration is optional and uses the Admin Notes API without requiring the Admin Notes mod to be installed.

### Player lookup

Both `/airport_security_system <player>` and `/ass <player>` accept an online player's:

- Minecraft player name, for example `/ass Piotrusek69`
- UUID, for example `/ass 887a052d-5618-4096-a154-46c4afa13794`
- UUID without dashes is also accepted.

The target must currently be online because the security check requires a live client connection.

## Discord webhook

Discord reporting is optional:

```toml
webhook_enabled = true
webhook_url = "https://discord.com/api/webhooks/YOUR_ID/YOUR_TOKEN"
```

Detected and inconclusive results are sent to Discord.

Automatic clean checks do not send a webhook message. Manual clean checks do send their result when the webhook is enabled.

Keep the webhook URL private.

## Client UI

When the Airport Security System mod is installed on the client, it provides a verification overlay and blocking screen while a check is active.

During the NeoForge configuration phase, the server can perform an optional client configuration handshake. The client then displays the Airport Security System state before the world is shown.

During a server-side check, the client UI can display:

- Airport Security verification status
- Probe progress
- Detected/protected counts
- Completion or failure state

The UI does not perform the detection itself. Detection and result evaluation remain server-side.

## How the probe works

For each batch, the server creates a detached sign block entity, fills its text with probe components, and sends the sign state/data only to the checking client.

The server then:

1. Opens the sign editor for the client.
2. Immediately hides the temporary sign from that client's view.
3. Intercepts the expected sign-update packet.
4. Evaluates the returned text against the configured probe.
5. Restores the original block state to the checking client's view.
6. Continues with the next batch or finishes the check.

The expected sign response is intercepted by the server mixin before normal sign handling.

## Enforcement

Airport Security System does not:

- kick players
- ban players
- damage players
- teleport players
- modify inventories
- execute punitive commands
- place or modify blocks in the server world

The mod is a detection/reporting system. Detection is heuristic because a modified client can suppress or spoof client-side text resolution.

## Configuration

The main configuration file is:

```
config/airport_security_system-common.toml
```

Available settings include:

```toml
blacklisted_keys = [...]
command_permission = "airport_security_system.command"
join_bypass_permission = "airport_security_system.join.bypass"
broadcast_permission = "airport_security_system.alerts"

auto_check_on_join = true
join_check_chance_percent = 10
only_first_join = false
join_check_delay_ticks = 40

webhook_enabled = false
webhook_url = ""

timeout_ticks = 120
log_clean_checks = true
```

## Build

Requires Java 21.

```bash
gradle clean build
```

The built mod JAR is generated under:

```
build/libs/
```
