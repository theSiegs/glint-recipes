# Glint tile exchange catalog

This is the catalog behind Glint's **community live tile exchange**: small data files ("recipes")
that tell the Glint launcher how to turn one app's notifications into a live tile (a delivery's
status, a flight's gate, a count of new mail). Glint ships the ones for a few Google apps built in;
with the tile exchange switched on, it fetches the rest from here.

## What's here

| Path | What it is |
|---|---|
| `recipes/<id>.json` | One recipe per file, in the `glint.recipe/1` format (Glint's `recipes/SPEC.md`). |
| `fixtures/<package>/*.json` | Synthetic or scrubbed sample notifications for one app, each with what its recipe should draw. |
| `categories.json` | App categories: one line per Android package, written by the intake's nightly classifier (below). |
| `categories-overrides.json` | Hand corrections to the categories; they always win. |
| `catalog/` | The cross-platform app catalog: `keys.json` (Xbox, Microsoft Store, Windows, Steam, Switch and web keys), `apps.json` (app slugs), `overrides.json`, `evidence/` (the reviewer agent's records) and `normalize-vectors.json` (see "App catalog"). |
| `revoked.txt` | Recipes withdrawn from the exchange: one `id` or `id@rev` per line. |
| `keys/exchange-1.pub`, `keys/exchange-2.pub` | The publishing public keys (base64 of the raw 32-byte Ed25519 key). |
| `checker/` | A standalone copy of Glint's recipe checker and shape learner (Kotlin), with its own Gradle build. |
| `tools/build_index.py` | Builds and signs the published site (Python, standard library only). |
| `tools/check_categories.py` | Checks the two categories files and merges them for publishing (and `tools/test_check_categories.py`). |
| `tools/check_catalog.py` | Checks the app catalog, computes each entry's state and builds its shards (and `tools/test_check_catalog.py`). |

Only recipe files and synthetic or scrubbed fixtures live here. There is no one's real notification
content, and there are no app icons, logos or other artwork: a recipe names an app by its package
and describes text, nothing more.

## How a recipe gets in

1. A pull request adds or changes files under `recipes/`, `fixtures/`, `revoked.txt` or the
   categories files (see "App categories").
2. `check.yml` builds the checker and checks every recipe against the schema and its fixtures. The
   catalog also refuses any recipe or fixture for a chat app or a health or payment app, the generic
   recipe (it is built into Glint), a recipe with no fixtures, and any fixture that isn't marked
   `"scrubbed": true` or `"synthetic": true` or that holds something that looks like an email address,
   phone number or link.
3. When the check passes and the pull request comes from a branch of this repo and touches nothing
   else, it is merged automatically (main fast-forwards to its commits, so the pull request must be
   on top of main), unless it is a drafted recipe or an edit shared from a phone (below), which
   waits for the owner's merge.
4. `publish.yml` then checks the catalog again, builds the index, signs it, verifies the signature
   against the committed public key and publishes the site.

Run the same steps locally (JDK 17 or newer, Python 3):

```sh
./checker/gradlew -p checker check                                     # the whole catalog
./checker/gradlew -p checker run --args="../recipes ../fixtures ../revoked.txt"
./checker/gradlew -p checker learn --args="fixtures/com.dd.doordash"   # the shape learner, no AI
./checker/gradlew -p checker installDist                              # both as jars, for the intake's drafting job
python tools/check_categories.py                                       # the categories files
python tools/check_catalog.py                                          # the app catalog (add --base <old tree> for the caps)
python -m unittest discover -s tools                                   # the checker's tests
python tools/build_index.py --out site                                 # unsigned dry run
python tools/build_index.py --out site --key test.pem --verify test.pub
```

Drafted recipes come from the private intake's nightly job as pull requests from `draft/<package>`
branches: a `learned` recipe and `fixtures/<package>/drafted.json`, a synthetic fixture the job
writes with `checker/src/catalog/.../DraftFixture.kt` from a few scrubbed shapes. They go through the
same check as any other pull request.

### Drafted recipes wait for the owner

A drafted recipe is never merged automatically. `check.yml` holds back any pull request from a
`draft/*` branch, and any that adds or changes a recipe whose `meta.status` is `learned` or whose
notes say a model touched it ("by AI", which the intake's drafting job writes), or a fixture whose
`source` is `learned`. The check still runs; the owner reads the recipe and merges it by hand, and
the merge publishes it as any push to `main` does. Every other pull request keeps the auto-merge.

Why: a drafted recipe's quality bar today is only "it draws a face", and the model may write the
tile's words from scrubbed text a phone sent. The human merge stays until two things exist: a check
that shows a draft beats the generic tile (it draws something the generic one wouldn't, on most of
its shapes), and model-written words restricted to a known vocabulary (the common-word list and the
learner's own words). Even merged, a `learned` recipe isn't dealt or turned on by Glint on its own
(see the index's `status`).

### Edits from phones wait for the owner

Glint's tile settings › "edit what it shows" lets a phone's owner pick which of a recipe's own fields
go on a tile's lines and the order of its faces (never free text), and optionally share that edit
through the tile exchange. The relay (the intake's `POST /recipe`, `glint.recipe-submit/1`) checks
it and opens a pull request here from an `edit/<id>-<hash>` branch, with one commit that changes only
`recipes/<id>.json`: one rev up, `meta.status` `unverified`, its notes starting "Edited on a phone
with Glint's tile editor" and saying what changed.

An edit is never merged automatically. `check.yml` holds back any pull request from an `edit/*`
branch, and any that changes a recipe whose notes start "Edited on a phone". The check still runs (an
edit of a recipe the catalog has no fixtures for, such as one learned on the phone, fails it until
fixtures come). The owner reads the diff, sets the status back (`verified` once it's checked on
samples) and merges by hand, or closes it.

`checker/sync-from-glint.sh <path-to-glint-checkout>` re-copies the checker's sources from Glint, and
the scrubber's common-word list (`checker/src/main/resources/glint/common-words.txt`);
`checker/VERSION` names the Glint commit they came from.

## App categories

`categories.json` says what kind of app a package is: `game`, `video`, `audio`, `image`, `social`,
`news`, `maps`, `productivity`, `accessibility` or `messaging` (Hearth HA's app categories), or
`unknown`. Glint uses it for an app's glyph when nothing else says what the app is, and passes it to
Hearth HA's screen time as `catalog_categories` (Hearth prefers the category the app declares itself).

```json
"com.roblox.client": {"category": "game", "name": "Roblox", "source": "play:GAME_ADVENTURE", "checked": "2026-11-05"},
"com.example.gallery": {"category": "image", "name": "Gallery", "glance": "photos", "source": "play:PHOTOGRAPHY", "checked": "2026-11-09"},
```

An entry may also say which of Glint's glances the app's tile borrows when nothing is waiting on it
(`glance`, optional):

| `glance` | The app's tile shows |
|---|---|
| `photos` | the photos glance (galleries and cameras) |
| `music` | the Music glance (players, podcasts) |
| `calendar` | the next event |
| `clock` | the Clock glance |
| `weather` | the forecast |
| `car` | reserved for a car glance Glint doesn't have yet (car makers' apps); Glint guesses as without one |
| `none` | the app's own glance, never a borrowed one |

Without one, Glint guesses on the phone (galleries it knows, what the app handles, its category). The
catalog's word wins over the guess while the tile exchange is on. Older Glint ignores the key, and Glint
skips a value it doesn't know, so new values can come later.

An entry may also say what kind of app it is (`kind`, optional), which Glint's kind recipes and Car
tile key on instead of hand lists of packages: `charging` (a charger network's app, such as
Electrify America or ChargePoint: its charging session joins the Car tile's when it matches the car's
own), `assistant` (an AI assistant or bot app, such as Claude or ChatGPT: its replies come as chat
notifications, but no person writes in it, so Glint never treats it as people talking), `car`,
`airline`, `delivery` or `ride`. An app has one kind at most. An app can have both a `glance` and a `kind` (Tesla's app
is a car app and a Supercharger app: `{"glance": "car", "kind": "charging"}`). Older Glint ignores the
key, and Glint skips a value it doesn't know.

The intake's nightly classifier writes it: Google Play's own genre for the app, mapped by a fixed rule;
an AI only for genres too broad to map (Entertainment, Lifestyle, Education...) and apps not on Play,
and `unknown` when it isn't sure. It writes a `glance` only where Play's genre says it cleanly
(Photography: `photos`, Music & Audio: `music`, Weather: `weather`, Auto & Vehicles: `car`). `name` is the app's Play title (left out if it might hold an email
address, link or phone number), `source` says where the category came from, `checked` when. About 40
new apps a night, plus a re-check of entries older than 90 days. Its pull requests from a
`categories/*` branch **merge on their own** when `tools/check_categories.py` passes: the schema,
categories, glances and kinds from the lists above, Android package names, no personal data (a name is one
short line; every other value has a fixed form), and at most 60 apps added, removed or changed
(category, glance or kind) per pull request.

### Changing a category

Never edit `categories.json` by hand (the classifier owns it). Put the fix in
**`categories-overrides.json`**, which always wins:

```json
{
  "format": "glint.categories-overrides/1",
  "apps": {
    "com.example.player": "video",
    "com.example.notagame": "unknown",
    "com.example.maps": {"category": "maps", "name": "Example Maps"},
    "com.example.gallery": {"glance": "photos"},
    "com.example.charger": {"kind": "charging"}
  }
}
```

A value is a category, `unknown` (publish no category for that app), or an object with a `category`, a
`name`, a `glance`, a `kind`, a `detail` and/or an `app` slug (each overrides only that key; see "App catalog"
for `detail` and `app`). The classifier leaves an app alone when its
override sets the category; one that only names the app or its glance still gets its category from
the classifier. Either commit to `main` (the push
publishes) or open a one-line pull request from a branch of this repo: it is checked and merged on its
own, and the merge publishes. Phones pick it up at their next update check with the tile exchange on.

## App catalog

The app categories above grow into a catalog of app facts across platforms, for Glint and for Hearth HA's
screen time (which fetches it itself). Glint's catalog owns, writes and signs it; Hearth only reads it. The
published URL, `glint.exchange/1` and `glint.catalog/1` are stable for both.

### Keys

Every key is `<namespace>:<id>`. One app has many keys; each key carries the app's **slug** (`app`), listed in
`catalog/apps.json`, so keys that share a slug are the same app.

| Namespace | Id | Example |
|---|---|---|
| `android` | package (in `categories.json`, without the prefix) | `android:com.example.player` |
| `msstore` | Microsoft Store product id, 12 characters, upper case: the Microsoft anchor | `msstore:9NBLGGH2JHXJ` |
| `xbox` | Xbox title id, decimal (the Store's `XboxTitleId`) | `xbox:1828326430` |
| `pfn` | Windows package family name, lower case | `pfn:microsoft.minecraftuwp_8wekyb3d8bbwe` |
| `steam` | Steam appid | `steam:1172470` |
| `switch` | Nintendo application id, 16 hex, upper case | `switch:0100152000022000` |
| `web` | registrable domain (curated only) | `web:example.com` |
| `name` | `name:<xbox\|msstore\|switch\|steam>:<normalized title>`, built from the entries' names | `name:switch:example kart` |
| `fs` | Family Safety's own id; **not accepted yet** (not known to be the same across accounts) | |

The name normalizer: drop the trademark, registered and copyright signs; Unicode NFKD without combining marks;
lower case; one trailing " for nintendo switch", " nintendo switch edition", " - nintendo switch", " standard
edition" or " edition" removed; every run of other characters than a-z and 0-9 becomes one space; trimmed.
`catalog/normalize-vectors.json` pins it, and Hearth's matches it. A name two apps share is left out.

### Fields

| Field | Values |
|---|---|
| `category` | The ten above, **frozen**: older Glint and Hearth drop a value they don't know. |
| `detail` | `youtube`, `streaming` (both under `video`), `education`, `companion`, `system` (under `productivity` or no category). Only added to, never changed. Educational games are `game` with no detail. |
| `app`, `name` | The slug and the app's title. |
| `state` | `confirmed` or `suggested`, **computed when publishing, never written in a source file**. |
| `source` | `override`, `play`, `store`, `steam`, `platform`, `ai`, `match` or `review`. |
| `checked` | When the facts were last read. |

**When an entry is confirmed:** an override (`categories-overrides.json`, `catalog/overrides.json`, by key or by
`app:<slug>`; a key's beats its app's); a store rule (Play's genre in the classifier's fixed map, the Microsoft
Store's `ProductKind` Game, Steam's type game with no software genre, every Switch title); or a review whose
evidence record passes the rule below. Anything else (an AI answer, a match by name, a Store application, Steam
software) is `suggested`. A `detail` shows on a confirmed entry only when an override or a passing review gave
it; on a suggested entry it shows as suggested. Glint and Hearth never let a suggested value loosen a limit.

**The evidence rule** (`catalog/evidence/<id>.json`, `glint.catalog-evidence/1`, written by the intake's reviewer
agent). A record confirms its key's proposed category and detail only when its verdict is `approve`, two judges
with different prompts both answered exactly that, at least one fact is a store or web page on
`displaycatalog.mp.microsoft.com`, `play.google.com`, `store.steampowered.com`, `www.nintendo.com`,
`ec.nintendo.com` or `en.wikipedia.org`, and no game signal stands against a value that isn't a game. The check
enforces it on every pull request and publish, so nothing loosens a limit without a recorded, passing review.

**Changes.** `check.yml` runs `tools/check_catalog.py --base` on pull requests: at most 60 keys added, removed or
changed, and no confirmed game stops being one without an override or a review. The classifier's pull requests
(`categories/*`) merge on their own. The reviewer agent's escalations come as a weekly `escalate/<date>` pull
request that waits for the owner: merging it accepts its overrides, closing it rejects them. A pull request from
a bot's branch (`categories/*`, `draft/*`, `edit/*`) that touches an overrides file waits for the owner too.

### Published (`glint.catalog/1`)

The index gains `"catalog": {"format": "glint.catalog/1", "shards": {"<platform>": {"path":
"k/<platform>.<first 16 hex of sha256>.json", "sha256": "...", "count": n}}}`, one shard per platform that has
keys. A shard is `{"format": "glint.catalog/1", "platform": "xbox", "keys": {"xbox:1828326430": {"app":
"minecraft", "name": "Minecraft", "category": "game", "state": "confirmed", "source": "store", "checked":
"2026-11-13"}}}`, trusted only when its SHA-256 equals the signed index's entry. The categories file
(`c/<hash>.json`) also gains each entry's `detail`, `state` and `source`; older Glint ignores them, and Glint
still reads only that file, never the shards. Readers ignore unknown keys and skip values and namespaces they
don't know; a change that can't be made that way gets a new format published beside this one for a year.

## Published format (`glint.exchange/1`)

Base URL (configurable in Glint; default `https://thesiegs.github.io/glint-recipes/`):

- `index.json`: UTF-8 JSON

  ```
  {"format":"glint.exchange/1","seq":<int, increases every publish>,"issued":"<ISO-8601 UTC>",
   "apps":{"<package>":{"id":"<recipe id>","rev":<int>,"sha256":"<lowercase hex of the recipe file bytes>",
                        "status":"<the recipe's meta.status>"}},
   "revoked":["<recipe id>" | "<recipe id>@<rev>", ...],
   "categories":{"path":"c/<first 16 hex of sha256>.json","sha256":"<lowercase hex of the file bytes>","count":<int>}}
  ```

  One entry per package: the catalog's best recipe for it (status `verified`, then `built-in`, then
  `unverified`, then the highest rev; drafts and revoked recipes are left out). `seq` is the Unix time
  of the publish run in seconds, so it only grows. Unknown keys are ignored. Glint deals a live
  tile, and turns an app's tile live by itself, only for a `verified` or `built-in` entry; an
  `unverified` or `learned` one is held off until it is checked against real notifications.
- `c/<hash>.json`: the app categories, `{"format":"glint.categories/1","apps":{"<package>":{"category":"game","name":"Roblox"}}}`,
  with the overrides applied and `unknown` left out (an app may have only a `name`, a `glance` or a `kind`); an
  app may carry a `"glance"` and a `"kind"` (above), which older Glint ignores. Named by its
  content, so it never changes under a cached index; trusted only if its SHA-256 equals the index's
  `categories` entry. An index without `categories` is fine (older Glint ignores the key).
- `index.json.sig`: base64 (standard, no line breaks) of the raw 64-byte **Ed25519** signature over
  the exact bytes of `index.json`.
- `r/<id>.<rev>.json`: the recipe file, an exact copy, immutable per rev. Glint trusts it only if its
  SHA-256 equals the index entry.

Glint ships one or two public keys and accepts an index signed by any of them. It rejects an index
whose `seq` is lower than the one it has cached (no rollback). A revoked id (or `id@rev`) is never
used, even from cache, and its cached file is deleted.

## Keys and rotation

The private keys exist only as the repository secrets `EXCHANGE_SIGNING_KEY` (signs today, public
half `keys/exchange-1.pub`) and `EXCHANGE_SIGNING_KEY_NEXT` (unused until a rotation, public half
`keys/exchange-2.pub`). Glint ships both public keys. To rotate:

1. Make sure the Glint release **and the Hearth release** in people's hands carry both public keys (Hearth HA
   pins them to verify the app catalog; see "App catalog").
2. Put the next key into `EXCHANGE_SIGNING_KEY` and set `EXCHANGE_PUBLIC_KEY` in `publish.yml` to
   `keys/exchange-2.pub`; publish.
3. Generate a new next key, commit its public half as `keys/exchange-3.pub`, set it as
   `EXCHANGE_SIGNING_KEY_NEXT`, and ship a Glint release that trusts keys 2 and 3. Then remove key 1.

A key that may have leaked is rotated the same way, at once. Never commit a private key.

## Privacy

The tile exchange is off in Glint by default. When it is **off**, Glint never contacts this catalog
or anything else for it. When it is **on**:

- Glint fetches this small signed index with its usual update check, and a recipe only when you place
  that app's tile or accept a dealt one. It fetches the app categories file with the index when that
  file changes; which apps are on the phone is never sent anywhere for it.
- For an app with no recipe (or one that broke), Glint may share that app's **scrubbed notification
  layouts**: which fields a notification uses and their shape, with words replaced by placeholders
  (`Xxxx`) and digits by `9`. Never the content of your notifications, and no account, user or device
  ids.
- Nothing is ever shared for health, payment or chat apps.

Hearth HA reads the same signed files and sends nothing to fetch them. With its own tile exchange setting turned on
by an HA administrator (asked once, off until then), it may send the store ids and store titles of public apps
the catalog lacks, and corrections an adult chooses to share: no household, person or device id, no usage and
no times. The intake only looks them up at their store; they never set a value here by themselves.

Shared layouts go to a private intake, where they may help draft a recipe; only finished recipes and
synthetic fixtures are ever published here. This notice describes how the software works; it is not
legal advice.

## License

Everything in this repository is dedicated to the public domain under
[CC0 1.0 Universal](LICENSE).
