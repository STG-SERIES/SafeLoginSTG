# SafeLoginSTG

Hybrid authentication plugin for **Paper / Purpur 1.21.11**.

Premium (Mojang) usernames and cracked usernames share one flow: Paper **Dialog** UI for password create/login, PBKDF2 hashed storage, and client cookies so the same name can switch between premium and cracked clients safely.

| | |
|---|---|
| **Version** | `1.0.0` |
| **Minecraft** | `1.21.11` |
| **API** | Paper API `1.21.11-R0.1-SNAPSHOT` |
| **Java** | `21+` |
| **Organization** | [STG-SERIES](https://github.com/STG-SERIES) |
| **License** | All rights reserved (private) |

---

## Features

- **Mojang premium check** — usernames that exist on Mojang follow the premium path
- **Cracked accounts** — must create a password on first join and enter it every join
- **Premium first join** — must create a password once; later joins from the same trusted client auto-login
- **Same-username hybrid** — if a cracked client uses a premium name, password is required; when the premium client returns after a cracked session, password is required once, then auto-login resumes
- **Native Dialog UI** — register / login screens (Paper Dialog API)
- **Secure storage** — PBKDF2-HMAC-SHA256 hashes in `accounts.yml` (never plaintext)
- **OP-only admin commands** — set or reset passwords
- **Unauthenticated lockdown** — movement, chat, commands, interact, combat blocked until auth succeeds
- **Auth timeout** — kicks players who do not finish auth in time

---

## Requirements

- Paper or Purpur **1.21.11**
- Java **21** or newer
- Server typically in **`online-mode=false`** for cracked + premium hybrid  
  (`online-mode=true` treats everyone as verified premium sessions)

---

## Installation

1. Build the plugin (or download a release JAR):

   ```bash
   ./gradlew build
   ```

   Output: `build/libs/SafeLoginSTG-1.0.0.jar`

2. Copy the JAR into your server’s `plugins/` folder.
3. Start (or restart) the server.
4. Edit `plugins/SafeLoginSTG/config.yml` if needed.

---

## How authentication works

### Cracked-only username (not on Mojang)

1. First join → **Creating a Password** dialog (password + confirmation).
2. Every later join → **Login** dialog (password every time).

### Premium username (exists on Mojang)

1. First join → create a password (same register dialog).
2. Later joins from the **same trusted client** → **automatic login** (no password).
3. Join from a **cracked client** with that name → password required every time.
4. After a cracked session, the **premium client** must enter the password **once**, then auto-login works again.
5. Switching cracked ↔ premium repeatedly requires a password each time the client type changes.

Trusted clients are remembered with a **client cookie** plus premium/cracked tokens stored server-side. Separate launcher folders (different `.minecraft` data) are treated as different clients.

### Fully online-mode servers

If `online-mode=true`, players are treated as verified premium sessions (Mojang lookup skipped for that path). First-time password rules still apply as implemented for premium accounts.

---

## Commands

All `/SLSTG` commands are **OP-only** (console / RCON allowed).

| Command | Description |
|---|---|
| `/SLSTG setpswd <player> <new password>` | Sets a new hashed password for the player |
| `/SLSTG reset <player>` | Deletes the account; next login shows first-time signup again |

Aliases for set: `setpassword`.

If the target of `/SLSTG reset` is online, they are immediately sent back to the register dialog.

---

## Permissions

| Permission | Default | Description |
|---|---|---|
| `safeloginstg.admin` | `op` | Admin commands (plugin.yml); runtime still requires **OP** / console |
| `safeloginstg.bypass` | `false` | Skip authentication entirely |

---

## Configuration

`plugins/SafeLoginSTG/config.yml`:

```yaml
# Minimum password length for cracked accounts
min-password-length: 4
# Maximum password length
max-password-length: 64
# Seconds before an unauthenticated player is kicked
auth-timeout-seconds: 120
```

---

## Data

Stored in `plugins/SafeLoginSTG/accounts.yml`:

- Password **hash** only (PBKDF2)
- Last auth mode (`PREMIUM` / `CRACKED`)
- Premium / cracked client tokens
- Timestamps

Do not share this file publicly.

---

## Building from source

```bash
# Requires JDK 21+
./gradlew build
```

On Windows:

```bat
gradlew.bat build
```

---

## Version docs

Full documentation for each release:

| Version | Document |
|---|---|
| **1.0.0** (current) | [docs/versions/v1.0.0.md](docs/versions/v1.0.0.md) |
| Index | [docs/versions/README.md](docs/versions/README.md) |

---

## Project layout

```
SafeLoginSTG/
├── src/main/java/com/safeloginstg/
│   ├── SafeLoginSTG.java          # Plugin entry
│   ├── auth/                      # Auth service, hashing, modes, cookies
│   ├── command/                   # /SLSTG
│   ├── dialog/                    # Paper Dialog register/login UI
│   ├── listener/                  # Join + lockdown listeners
│   ├── mojang/                    # Mojang premium username check
│   └── storage/                   # YAML account repository
├── src/main/resources/
│   ├── plugin.yml
│   └── config.yml
├── docs/versions/                 # Per-version full READMEs
├── build.gradle.kts
└── README.md
```

---

## Notes / limitations

- Mojang API rate limits: on failure/rate-limit the plugin treats the name as cracked (safer default).
- Client cookies identify launchers/clients, not a cryptographic Mojang session handshake. For strongest premium proof on offline servers, a ProtocolLib-style session verifier (e.g. FastLogin) would be needed.
- If premium and cracked launchers share the exact same game data directory, cookies may collide.

---

## Support

Private project under **STG-SERIES**. Contact maintainers in the organization for issues and updates.
