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
| `revoked.txt` | Recipes withdrawn from the exchange: one `id` or `id@rev` per line. |
| `keys/exchange-1.pub`, `keys/exchange-2.pub` | The publishing public keys (base64 of the raw 32-byte Ed25519 key). |
| `checker/` | A standalone copy of Glint's recipe checker and shape learner (Kotlin), with its own Gradle build. |
| `tools/build_index.py` | Builds and signs the published site (Python, standard library only). |

Only recipe files and synthetic or scrubbed fixtures live here. There is no one's real notification
content, and there are no app icons, logos or other artwork: a recipe names an app by its package
and describes text, nothing more.

## How a recipe gets in

1. A pull request adds or changes files under `recipes/`, `fixtures/` or `revoked.txt`.
2. `check.yml` builds the checker and checks every recipe against the schema and its fixtures. The
   catalog also refuses any recipe or fixture for a chat app or a health or payment app, the generic
   recipe (it is built into Glint), a recipe with no fixtures, and any fixture that isn't marked
   `"scrubbed": true` or `"synthetic": true` or that holds something that looks like an email address,
   phone number or link.
3. When the check passes and the pull request comes from a branch of this repo and touches nothing
   else, it is squash-merged automatically, unless it is a drafted recipe (below), which waits for
   the owner's merge.
4. `publish.yml` then checks the catalog again, builds the index, signs it, verifies the signature
   against the committed public key and publishes the site.

Run the same steps locally (JDK 17 or newer, Python 3):

```sh
./checker/gradlew -p checker check                                     # the whole catalog
./checker/gradlew -p checker run --args="../recipes ../fixtures ../revoked.txt"
./checker/gradlew -p checker learn --args="fixtures/com.dd.doordash"   # the shape learner, no AI
./checker/gradlew -p checker installDist                              # both as jars, for the intake's drafting job
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

`checker/sync-from-glint.sh <path-to-glint-checkout>` re-copies the checker's sources from Glint, and
the scrubber's common-word list (`checker/src/main/resources/glint/common-words.txt`);
`checker/VERSION` names the Glint commit they came from.

## Published format (`glint.exchange/1`)

Base URL (configurable in Glint; default `https://thesiegs.github.io/glint-recipes/`):

- `index.json`: UTF-8 JSON

  ```
  {"format":"glint.exchange/1","seq":<int, increases every publish>,"issued":"<ISO-8601 UTC>",
   "apps":{"<package>":{"id":"<recipe id>","rev":<int>,"sha256":"<lowercase hex of the recipe file bytes>",
                        "status":"<the recipe's meta.status>"}},
   "revoked":["<recipe id>" | "<recipe id>@<rev>", ...]}
  ```

  One entry per package: the catalog's best recipe for it (status `verified`, then `built-in`, then
  `unverified`, then the highest rev; drafts and revoked recipes are left out). `seq` is the Unix time
  of the publish run in seconds, so it only grows. Unknown keys are ignored. Glint deals a live
  tile, and turns an app's tile live by itself, only for a `verified` or `built-in` entry; an
  `unverified` or `learned` one is held off until it is checked against real notifications.
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
  that app's tile or accept a dealt one.
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
