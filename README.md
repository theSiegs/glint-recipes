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
| `revoked.txt` | Recipes withdrawn from the exchange: one `id` or `id@rev` per line. |
| `keys/exchange-1.pub`, `keys/exchange-2.pub` | The publishing public keys (base64 of the raw 32-byte Ed25519 key). |
| `checker/` | A standalone copy of Glint's recipe checker and shape learner (Kotlin), with its own Gradle build. |
| `tools/build_index.py` | Builds and signs the published site (Python, standard library only). |
| `tools/check_categories.py` | Checks the two categories files and merges them for publishing (and `tools/test_check_categories.py`). |

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

The intake's nightly classifier writes it: Google Play's own genre for the app, mapped by a fixed rule;
an AI only for genres too broad to map (Entertainment, Lifestyle, Education...) and apps not on Play,
and `unknown` when it isn't sure. It writes a `glance` only where Play's genre says it cleanly
(Photography: `photos`, Music & Audio: `music`, Weather: `weather`, Auto & Vehicles: `car`). `name` is the app's Play title (left out if it might hold an email
address, link or phone number), `source` says where the category came from, `checked` when. About 40
new apps a night, plus a re-check of entries older than 90 days. Its pull requests from a
`categories/*` branch **merge on their own** when `tools/check_categories.py` passes: the schema,
categories and glances from the lists above, Android package names, no personal data (a name is one
short line; every other value has a fixed form), and at most 60 apps added, removed or changed
(category or glance) per pull request.

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
    "com.example.gallery": {"glance": "photos"}
  }
}
```

A value is a category, `unknown` (publish no category for that app), or an object with a `category`, a
`name` and/or a `glance` (each overrides only that key). The classifier leaves an app alone when its
override sets the category; one that only names the app or its glance still gets its category from
the classifier. Either commit to `main` (the push
publishes) or open a one-line pull request from a branch of this repo: it is checked and merged on its
own, and the merge publishes. Phones pick it up at their next update check with the tile exchange on.

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
  with the overrides applied and `unknown` left out (an app may have only a `name` or a `glance`); an
  app may carry a `"glance"` (above), which older Glint ignores. Named by its
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

1. Make sure the Glint release in people's hands carries both public keys.
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

Shared layouts go to a private intake, where they may help draft a recipe; only finished recipes and
synthetic fixtures are ever published here. This notice describes how the software works; it is not
legal advice.

## License

Everything in this repository is dedicated to the public domain under
[CC0 1.0 Universal](LICENSE).
