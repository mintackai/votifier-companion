# Votifier Python Companion

A server-side Minecraft mod. Players **collect their vote rewards in game** with `/reward`, and everyone online sees when someone votes.

It needs [votifier-python](https://github.com/mintackai/votifier-python), the program that receives the votes. Set that up first.

## Get started

1. **Download the mod** from the [Releases page](https://github.com/mintackai/votifier-companion/releases). Pick your Minecraft version, then the `.jar` with your mod loader in its name.
2. **Download the mods it needs:** Architectury API, and on Fabric also Fabric API, for your Minecraft version and mod loader.
3. **Install:** put the `.jar` files in your server's `mods` folder and start the server once.
4. **Enter your key:** in votifier-python, type `api key create my-server` and copy the key. Paste it after `"apiKey"` in `config/votifier_python_companion/votifier.json`.
5. **Choose the rewards:** in the same file, `commands` lists the console commands that run for each vote a player collects.
6. **Load the settings:** type `/reward reload` in the server console.
7. **Test it:** in votifier-python, type `vote add YourName`. Join the server and type `/reward`.

| Minecraft | Mod loaders | Java |
| :--- | :--- | :--- |
| `1.20.1` | Fabric, Forge | 17 or newer |
| `1.21.1` | Fabric, NeoForge | 21 or newer |
| `26.1.2` | Fabric, NeoForge | 25 or newer |
| `26.3` | Fabric, NeoForge | 25 or newer |

## Commands

| Command | What it does | Who can use it |
| :--- | :--- | :--- |
| `/reward` | Collects one vote reward | Everyone |
| `/reward all` | Collects all waiting vote rewards | Everyone |
| `/reward reload` | Loads the settings again after a change | Operators (level 2) |

## Settings

The settings are in `config/votifier_python_companion/votifier.json`:

```json
{
  "apiUrl": "http://127.0.0.1:8193",
  "apiKey": "replace-with-your-votifier-python-api-key",
  "requestTimeoutSeconds": 5,
  "claimCooldownSeconds": 3,
  "announceVotes": true,
  "commands": [
    "eco add {player_name} 50",
    "give {player_name} steak 16"
  ]
}
```

| Setting | What it does |
| :--- | :--- |
| `apiUrl` | Where votifier-python is. Keep this value when it runs on the same machine; otherwise read [this](https://github.com/mintackai/votifier-python/blob/main/docs/security.md#reaching-the-api-from-another-machine-https) first. |
| `apiKey` | The key from step 4. Keep it private. |
| `requestTimeoutSeconds` | How long to wait for votifier-python to answer (`1` to `60`). |
| `claimCooldownSeconds` | Seconds a player must wait between two `/reward` commands (`0` to `300`; `0` is no waiting). |
| `announceVotes` | `false` stops the vote announcements. A vote that arrives while votifier-python is unreachable is not announced, but can still be collected. |
| `commands` | Run for each collected vote. `{player_name}`, `{uuid}` and `{claim}` are filled in. |

The same folder has `messages.json` (chat messages, `&` colour codes), `commands.json` (rename or turn off `/reward`) and `permissions.json` (permission names, for a permissions mod).

## For developers

Each Minecraft version has its own branch: `main` (1.20.1), `1.21.1`, `26.1.2` and `26.3`. Build with `./gradlew build`; every push to a version branch publishes the jars to that version's release.

The mod calls votifier-python's `POST /v1/players/{name}/claim`, `claim-all` and `POST /v1/claims/{number}/confirm`, and listens on `GET /v1/events` for announcements.
