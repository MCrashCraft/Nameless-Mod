# Configuration and operation

## Switch and commands

`config/namelessexchange-common.toml`:

```toml
enabled = false
```

The addon ships off. Change this file or use the persistent operator commands:

| Command | Action |
| --- | --- |
| `/namelessexchange:exchange admin enable` | Enable new exchanges and save the switch. |
| `/namelessexchange:exchange admin disable` | Disable new exchanges and save the switch. |
| `/namelessexchange:exchange admin status` | Show the switch, initialization, connection configuration and held-receipt count. |
| `/namelessexchange:exchange admin reload` | Reread the existing NamelessMC API connection in memory. |
| `/namelessexchange:exchange <dollars>` | Exchange a positive whole amount, up to $230,400. |
| `/namelessexchange:exchange all` | Exchange eligible main-inventory/hotbar bills. |
| `/namelessexchange:exchange status` | Show only the caller's current/latest receipt. |

The shorter `/exchange` is available when unclaimed by another mod. There is no automatic periodic cash withdrawal. Changing the switch never mints/refunds bills or adjusts Store balances itself. A pending operation after cash debit is allowed to record its website outcome even after the switch is disabled.

LuckPerms nodes: `namelessexchange.use` defaults to true for players; `namelessexchange.admin` defaults to operator level 2. Explicit LuckPerms denies override those defaults. Console may administer the addon but cannot exchange cash. If LuckPerms is installed but its player permissions are unavailable, access fails closed. Without LuckPerms, the stated public/operator defaults apply.

## Website connection

Reads only `api.url` and `api.key` in the private `config/namelessforge/main.yaml`. API URLs must use HTTPS; literal loopback HTTP is accepted for isolated local testing. Both `/api/v2` and `index.php?route=/api/v2` forms are accepted. No key is included in the addon or corresponding source. Do not put private config in a public export.

Each trade freshly verifies the website account and the single verified Minecraft integration for the actual caller's UUID. Banned, unvalidated, unverified, malformed, duplicate or mismatched links are refused before cash debit. The resolved numeric account ID is pinned for that operation. The addon sends one whole Store credit for each whole dollar using the Store add-credits endpoint. It has no fee, reverse conversion or configurable alternate recipient.

The addon does not require the Store fulfillment module to be enabled on the Minecraft side; that is a separate feature of NamelessMC Forge. It does require the website's Store credit API. Product payment settings remain website administration. The existence of a Store Credits-only UI option is not evidence that every installed checkout implementation enforces it; this addon does not patch the website.

## Durable receipts and recovery

Receipts are stored server-side in `config/namelessexchange/exchange-journal.json`. The journal contains operation IDs, actual player UUIDs, numeric website IDs, dollar amounts, debit-slot plans, state and timestamps. It contains no API key. On POSIX systems writes use owner-only permissions. The journal is bounded; a full, malformed, aliased or unwritable journal blocks new exchanges rather than silently discarding history.

Normal flow: `PREPARED` → `DEBITED` → `POSTING` → `CREDITED`. A definitively unchanged inventory may produce `CANCELLED`. Any uncertainty becomes `HELD`. An incomplete operation found at launch is held; it is never submitted again automatically. At most one unresolved receipt is allowed for a player.

Staff recovery is deliberately manual in this first beta. Disable new trades, preserve the receipt and inspect the website's actual credit/transaction history and the game-side debit evidence. An account balance difference alone cannot prove this specific trade succeeded: other purchases or credits may have occurred. Resolve an uncertain trade only once after establishing what happened; do not both credit and refund. Keep an external audit note with the receipt ID. This release provides no automatic retry, balance-based reconciliation or administrative cash minting shortcut. Do not clear a held receipt merely to let the player try again.

Never restore the player inventory/world to an earlier point while retaining credits already granted on the website. Removing the addon does not undo those credits. Retain the private receipt journal for review even if the addon is disabled or removed.
