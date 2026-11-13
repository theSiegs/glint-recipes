#!/usr/bin/env python3
"""Checks the cross-platform app catalog and builds what is published from it (README.md, "App catalog");
standard library only.

    python tools/check_catalog.py [--root .] [--base <a checkout or export of the base tree>]

Sources (all checked):
  categories.json, categories-overrides.json  Android packages (tools/check_categories.py), which may also
                                              carry "detail", "app" and "review"
  catalog/keys.json        every other platform's keys, written by the intake's classifier
  catalog/apps.json        the app slugs every key points to
  catalog/overrides.json   hand corrections by key or by "app:<slug>"; they always win
  catalog/evidence/*.json  the reviewer agent's evidence records
  catalog/normalize-vectors.json  the name normalizer's test vectors (Hearth's matches them)

The safety rule: an entry is "confirmed" only by an override, a deterministic store rule, or a review whose
evidence record passes (evidence_passes). A "detail" (youtube, streaming, education, companion, system) is
published on a confirmed entry only when an override or a passing review gave it. The state is computed
here, never written in a source file.

With --base: at most CAP keys added, removed or changed (Android and other platforms together), and a key
that was a confirmed game may stop being one only through an override or a passing review.
"""
import argparse
import json
import os
import re
import sys
import unicodedata
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_categories as cc  # noqa: E402

FORMAT = "glint.catalog/1"
KEYS_FORMAT = "glint.catalog-keys/1"
APPS_FORMAT = "glint.catalog-apps/1"
OVERRIDES_FORMAT = "glint.catalog-overrides/1"
EVIDENCE_FORMAT = "glint.catalog-evidence/1"
VECTORS_FORMAT = "glint.catalog-normalize/1"

CATEGORIES = cc.CATEGORIES
UNKNOWN = cc.UNKNOWN
DETAILS = ("youtube", "streaming", "education", "companion", "system")
# Which categories a detail may sit under (None: no category)
DETAIL_CATEGORIES = {
    "youtube": ("video",),
    "streaming": ("video",),
    "education": ("productivity", None),
    "companion": ("productivity", None),
    "system": ("productivity", None),
}
LOOSE = ("education", "companion", "system")
CAP = cc.CAP

SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{1,39}$")
NAMESPACES = {
    "android": cc.PACKAGE,
    "msstore": re.compile(r"^[0-9A-Z]{12}$"),
    "xbox": re.compile(r"^[1-9][0-9]{0,9}$"),
    "pfn": re.compile(r"^[a-z0-9][a-z0-9.\-]{0,100}_[a-z0-9]{13}$"),
    "fs": re.compile(r"^(xboxplt|windowsplt):[0-9a-f]{16}$"),
    "steam": re.compile(r"^[1-9][0-9]{0,9}$"),
    "switch": re.compile(r"^01[0-9A-F]{14}$"),
    "web": re.compile(r"^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$"),
}
# fs: Family Safety's own ids, accepted only once they are known to be the same across accounts
# (design phase 0); until then they stay in each household.
FS_ACCEPTED = False
NAME_PLATFORMS = ("xbox", "msstore", "switch", "steam")
SHARDS = ("android", "msstore", "xbox", "pfn", "fs", "steam", "switch", "web", "name")

# Source tags in catalog/keys.json and what a confirmed entry's coarse source is
KEY_SOURCE = re.compile(r"^(store:(Game|Application)|steam:(game|software)|platform:switch|ai(:[A-Za-z0-9_]{1,40})?|ai-search|match)$")
# Play genres the classifier maps by a fixed rule (the intake's classifying.GENRES)
PLAY_RULE = re.compile(r"^play:(GAME_[A-Z_]+|SOCIAL|DATING|COMMUNICATION|VIDEO_PLAYERS|MUSIC_AND_AUDIO|NEWS_AND_MAGAZINES|"
                       r"MAPS_AND_NAVIGATION|PHOTOGRAPHY|ART_AND_DESIGN|PRODUCTIVITY|TOOLS|BUSINESS)$")

EVIDENCE_ID = re.compile(r"^\d{4}-\d{2}-\d{2}-[a-z]+-[A-Za-z0-9-]{1,120}$")
FACT_HOSTS = ("displaycatalog.mp.microsoft.com", "play.google.com", "store.steampowered.com", "www.nintendo.com",
              "ec.nintendo.com", "en.wikipedia.org")
VERDICTS = ("approve", "reject", "escalate")
WHY = (None, "disagree", "abuse")
SAYS_MAX = 300
REASON_MAX = 200

Bad = cc.Bad


# ---- keys and names -----------------------------------------------------------------------------------

def split_key(key):
    if not isinstance(key, str) or len(key) > 200 or ":" not in key or any(ch.isspace() for ch in key):
        raise Bad(f"{key!r} is not a <namespace>:<id> key")
    ns, ident = key.split(":", 1)
    if ns not in NAMESPACES:
        raise Bad(f"{key!r}: unknown namespace {ns!r}")
    if not NAMESPACES[ns].match(ident):
        raise Bad(f"{key!r}: not a well-formed {ns} id")
    if ns == "xbox" and int(ident) >= 2 ** 32:
        raise Bad(f"{key!r}: an Xbox title id is 32-bit")
    return ns, ident


_SUFFIXES = (" for nintendo switch", " nintendo switch edition", " - nintendo switch", " standard edition", " edition")


def normalize(name):
    """The shared name normalizer (README.md, "Name keys"); Hearth's matches catalog/normalize-vectors.json."""
    s = name.replace("™", "").replace("®", "").replace("©", "")
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower()
    s = " ".join(s.split())
    for suffix in _SUFFIXES:
        if s.endswith(suffix):
            s = s[: -len(suffix)]
            break
    s = re.sub(r"[^0-9a-z]+", " ", s)
    return " ".join(s.split())


# ---- sources -----------------------------------------------------------------------------------------

def check_detail(where, category, detail):
    if detail is None:
        return
    if detail not in DETAILS:
        raise Bad(f"{where}: detail {detail!r} is not one of {DETAILS}")
    cat = None if category in (None, UNKNOWN) else category
    if cat not in DETAIL_CATEGORIES[detail]:
        raise Bad(f"{where}: detail {detail!r} needs category {DETAIL_CATEGORIES[detail]}, not {category!r}")


def check_slug(where, slug, apps=None):
    if not isinstance(slug, str) or not SLUG.match(slug):
        raise Bad(f"{where}: app {slug!r} is not a slug ([a-z0-9][a-z0-9-]{{1,39}})")
    if apps is not None and slug not in apps:
        raise Bad(f"{where}: app {slug!r} is not in catalog/apps.json")


def check_apps(data):
    if not isinstance(data, dict) or data.get("format") != APPS_FORMAT or not isinstance(data.get("apps"), dict):
        raise Bad(f'catalog/apps.json: must be {{"format": "{APPS_FORMAT}", "apps": {{...}}}}')
    for slug, v in data["apps"].items():
        where = f"catalog/apps.json {slug}"
        check_slug(where, slug)
        if not isinstance(v, dict) or set(v) - {"name", "replaces"} or "name" not in v:
            raise Bad(f'{where}: {{"name": ..., "replaces"?: [...]}}')
        cc.check_name(where, v["name"])
        for old in v.get("replaces", []):
            check_slug(where, old)
            if old in data["apps"]:
                raise Bad(f"{where}: replaces {old!r}, which is still listed")
    return data["apps"]


KEY_FIELDS = {"app", "name", "category", "detail", "source", "checked", "review"}


def check_keys(data, apps):
    if not isinstance(data, dict) or data.get("format") != KEYS_FORMAT or not isinstance(data.get("keys"), dict):
        raise Bad(f'catalog/keys.json: must be {{"format": "{KEYS_FORMAT}", "keys": {{...}}}}')
    for key, e in data["keys"].items():
        where = f"catalog/keys.json {key}"
        ns, _ = split_key(key)
        if ns == "android":
            raise Bad(f"{where}: Android packages live in categories.json")
        if ns == "fs" and not FS_ACCEPTED:
            raise Bad(f"{where}: fs keys aren't accepted yet (they stay in each household)")
        if not isinstance(e, dict) or set(e) - KEY_FIELDS:
            raise Bad(f"{where}: an entry is an object with only {sorted(KEY_FIELDS)}")
        check_slug(where, e.get("app"), apps)
        if "name" in e:
            cc.check_name(where, e["name"])
        if "category" in e and e["category"] not in CATEGORIES + (UNKNOWN,):
            raise Bad(f"{where}: category {e['category']!r} is not one of {CATEGORIES + (UNKNOWN,)}")
        check_detail(where, e.get("category"), e.get("detail"))
        if not isinstance(e.get("source"), str) or not KEY_SOURCE.match(e["source"]):
            raise Bad(f"{where}: source {e.get('source')!r} is not a known tag")
        if not isinstance(e.get("checked"), str) or not cc.DATE.match(e["checked"]):
            raise Bad(f"{where}: checked must be a yyyy-mm-dd date")
        if "review" in e and (not isinstance(e["review"], str) or not EVIDENCE_ID.match(e["review"])):
            raise Bad(f"{where}: review {e['review']!r} is not an evidence id")
    return data["keys"]


OVERRIDE_FIELDS = {"category", "detail", "app", "name"}


def check_overrides(data, apps):
    if not isinstance(data, dict) or data.get("format") != OVERRIDES_FORMAT or not isinstance(data.get("keys"), dict):
        raise Bad(f'catalog/overrides.json: must be {{"format": "{OVERRIDES_FORMAT}", "keys": {{...}}}}')
    for key, v in data["keys"].items():
        where = f"catalog/overrides.json {key}"
        if key.startswith("app:"):
            check_slug(where, key[4:], apps)
            if isinstance(v, dict) and ("app" in v or "name" in v):
                raise Bad(f"{where}: an app override sets only category and detail")
        else:
            ns, _ = split_key(key)
            if ns == "android":
                raise Bad(f"{where}: Android overrides live in categories-overrides.json")
        if not isinstance(v, dict) or not v or set(v) - OVERRIDE_FIELDS:
            raise Bad(f"{where}: an object with {sorted(OVERRIDE_FIELDS)}")
        if "category" in v and v["category"] not in CATEGORIES + (UNKNOWN,):
            raise Bad(f"{where}: category {v['category']!r} is not one of {CATEGORIES + (UNKNOWN,)}")
        if "detail" in v and v["detail"] not in DETAILS:
            raise Bad(f"{where}: detail {v['detail']!r} is not one of {DETAILS}")
        if "app" in v:
            check_slug(where, v["app"], apps)
        if "name" in v:
            cc.check_name(where, v["name"])
    return data["keys"]


def _check_text(where, text, limit):
    if not isinstance(text, str) or len(text) > limit or any(ord(ch) < 32 for ch in text):
        raise Bad(f"{where}: at most {limit} characters of one-line text")
    if cc.EMAIL.search(text) or cc.PHONE.search(text):
        raise Bad(f"{where}: looks like it holds an email address or phone number")


def _answer(where, a):
    if not isinstance(a, dict) or set(a) - {"category", "detail"}:
        raise Bad(f"{where}: {{\"category\"?, \"detail\"?}}")
    if a.get("category") is not None and a["category"] not in CATEGORIES + (UNKNOWN,):
        raise Bad(f"{where}: category {a['category']!r}")
    if a.get("detail") is not None and a["detail"] not in DETAILS:
        raise Bad(f"{where}: detail {a['detail']!r}")
    return {"category": a.get("category"), "detail": a.get("detail")}


def check_evidence(rec, name):
    """One evidence record, checked for form (evidence_passes says whether it confirms anything)."""
    where = f"catalog/evidence/{name}"
    want = {"format", "id", "key", "previous", "proposed", "verdict", "facts", "game_signals", "judges", "why", "reviewed"}
    if not isinstance(rec, dict) or rec.get("format") != EVIDENCE_FORMAT or set(rec) != want:
        raise Bad(f"{where}: an evidence record has exactly {sorted(want)} and format {EVIDENCE_FORMAT}")
    if not isinstance(rec["id"], str) or not EVIDENCE_ID.match(rec["id"]) or name != rec["id"] + ".json":
        raise Bad(f"{where}: id must be <yyyy-mm-dd>-<namespace>-<id> and match the file name")
    split_key(rec["key"])
    if rec["previous"] is not None:
        _answer(where + " previous", rec["previous"])
    _answer(where + " proposed", rec["proposed"])
    if rec["verdict"] not in VERDICTS or rec["why"] not in WHY:
        raise Bad(f"{where}: verdict in {VERDICTS}, why in {WHY}")
    if not isinstance(rec["facts"], list) or len(rec["facts"]) > 12:
        raise Bad(f"{where}: facts is a list of at most 12")
    for i, f in enumerate(rec["facts"]):
        if not isinstance(f, dict) or set(f) != {"kind", "url", "says"} or f["kind"] not in ("store", "web", "proposal"):
            raise Bad(f"{where} fact {i}: {{kind: store|web|proposal, url, says}}")
        if f["kind"] == "proposal":
            if f["url"] is not None:
                raise Bad(f"{where} fact {i}: a proposal fact has no url")
        elif not isinstance(f["url"], str) or not f["url"].startswith("https://"):
            raise Bad(f"{where} fact {i}: an https url")
        _check_text(f"{where} fact {i}", f["says"], SAYS_MAX)
    if not isinstance(rec["game_signals"], list) or not all(isinstance(s, str) and len(s) <= 80 for s in rec["game_signals"]):
        raise Bad(f"{where}: game_signals is a list of short strings")
    if not isinstance(rec["judges"], list) or len(rec["judges"]) > 4:
        raise Bad(f"{where}: judges is a list of at most 4")
    for i, j in enumerate(rec["judges"]):
        if not isinstance(j, dict) or set(j) != {"model", "prompt", "answer", "reason"}:
            raise Bad(f"{where} judge {i}: {{model, prompt, answer, reason}}")
        if not all(isinstance(j[k], str) and 0 < len(j[k]) <= 60 for k in ("model", "prompt")):
            raise Bad(f"{where} judge {i}: model and prompt are short strings")
        _answer(f"{where} judge {i}", j["answer"])
        _check_text(f"{where} judge {i} reason", j["reason"], REASON_MAX)
    if not isinstance(rec["reviewed"], str) or not cc.DATE.match(rec["reviewed"]):
        raise Bad(f"{where}: reviewed must be a yyyy-mm-dd date")
    return rec


def evidence_passes(rec, key, category, detail):
    """Whether [rec] confirms [key] as [category]/[detail] (README.md, "The evidence rule"): (ok, why not)."""
    if rec is None:
        return False, "no evidence record"
    if rec["verdict"] != "approve":
        return False, f"verdict is {rec['verdict']}"
    want = {"category": None if category in (None, UNKNOWN) else category, "detail": detail}
    def norm(a):
        a = _answer("", a)
        return {"category": None if a["category"] == UNKNOWN else a["category"], "detail": a["detail"]}
    if rec["key"] != key or norm(rec["proposed"]) != want:
        return False, "the record is for another key or value"
    agree = [j for j in rec["judges"] if norm(j["answer"]) == want]
    if len({j["prompt"] for j in agree}) < 2:
        return False, "fewer than two judges with different prompts agree"
    if not any(f["kind"] in ("store", "web") and urlparse(f["url"]).hostname in FACT_HOSTS for f in rec["facts"]):
        return False, "no store or web fact from an allowed host"
    if rec["game_signals"] and (detail in LOOSE or want["category"] != "game"):
        return False, "game signals stand against a value that isn't a game"
    return True, ""


def load_json(path):
    return cc.load_json(path)


def load(root):
    """Everything at [root], checked: a dict of the sources."""
    cats, android_overrides = cc.load(root)
    for pkg, e in cats.items():
        where = f"categories.json {pkg}"
        check_detail(where, e.get("category"), e.get("detail"))
        if "review" in e and (not isinstance(e["review"], str) or not EVIDENCE_ID.match(e["review"])):
            raise Bad(f"{where}: review {e['review']!r} is not an evidence id")
    cat_dir = os.path.join(root, "catalog")
    apps_doc = load_json(os.path.join(cat_dir, "apps.json"))
    apps = check_apps(apps_doc) if apps_doc is not None else {}
    for pkg, e in cats.items():
        if "app" in e:
            check_slug(f"categories.json {pkg}", e["app"], apps)
    for pkg, v in android_overrides.items():
        if isinstance(v, dict):
            if "app" in v:
                check_slug(f"categories-overrides.json {pkg}", v["app"], apps)
            if "detail" in v and v["detail"] not in DETAILS:
                raise Bad(f"categories-overrides.json {pkg}: detail {v['detail']!r} is not one of {DETAILS}")
    keys_doc = load_json(os.path.join(cat_dir, "keys.json"))
    keys = check_keys(keys_doc, apps) if keys_doc is not None else {}
    ov_doc = load_json(os.path.join(cat_dir, "overrides.json"))
    overrides = check_overrides(ov_doc, apps) if ov_doc is not None else {}
    evidence = {}
    ev_dir = os.path.join(cat_dir, "evidence")
    if os.path.isdir(ev_dir):
        for name in sorted(os.listdir(ev_dir)):
            if not name.endswith(".json"):
                continue
            rec = check_evidence(load_json(os.path.join(ev_dir, name)), name)
            evidence[rec["id"]] = rec
    vectors = load_json(os.path.join(cat_dir, "normalize-vectors.json"))
    if vectors is not None:
        check_vectors(vectors)
    return {"categories": cats, "android_overrides": android_overrides, "apps": apps, "keys": keys,
            "overrides": overrides, "evidence": evidence}


def check_vectors(data):
    if not isinstance(data, dict) or data.get("format") != VECTORS_FORMAT or not isinstance(data.get("vectors"), list):
        raise Bad(f'catalog/normalize-vectors.json: {{"format": "{VECTORS_FORMAT}", "vectors": [[in, out], ...]}}')
    for pair in data["vectors"]:
        if not (isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) for x in pair)):
            raise Bad("catalog/normalize-vectors.json: each vector is [input, output]")
        if normalize(pair[0]) != pair[1]:
            raise Bad(f"catalog/normalize-vectors.json: normalize({pair[0]!r}) is {normalize(pair[0])!r}, not {pair[1]!r}")


# ---- what is published ------------------------------------------------------------------------------

def _resolve(key, entry, overrides, app_override, evidence):
    """One key's published facts: {app?, name?, category?, detail?, state?, source?, checked?} or None."""
    e = dict(entry or {})
    tag = e.get("source", "")
    review = e.get("review")
    over = {}
    if app_override:
        over.update(app_override)
    if overrides:
        over.update(overrides)
    category = over.get("category", e.get("category"))
    if category == UNKNOWN:
        category = None
    detail = over["detail"] if "detail" in over else e.get("detail")
    out = {}
    app = over.get("app", e.get("app"))
    if app:
        out["app"] = app
    name = over.get("name", e.get("name"))
    if name:
        out["name"] = name
    if category is None and detail is None:
        if "checked" in e:
            out["checked"] = e["checked"]
        return out or None
    by_override = ("category" in over or "detail" in over)
    reviewed = review is not None and evidence_passes(evidence.get(review), key, category, detail)[0]
    if by_override:
        state, source = "confirmed", "override"
    elif reviewed:
        state, source = "confirmed", "review"
    else:
        rule = None
        if PLAY_RULE.match(tag):
            rule = "play"
        elif tag == "store:Game":
            rule = "store"
        elif tag == "steam:game":
            rule = "steam"
        elif tag == "platform:switch":
            rule = "platform"
        if rule is not None:
            state, source = "confirmed", rule
            # A detail on a rule-confirmed entry needs an override or a review: it is held back, not shown
            # under the store rule's confirmation.
            detail = None
        else:
            state = "suggested"
            source = {"store": "store", "steam": "steam", "platform": "platform", "match": "match"}.get(tag.split(":")[0], "ai")
    if category is not None:
        out["category"] = category
    if detail is not None:
        out["detail"] = detail
    out["state"] = state
    out["source"] = source
    if "checked" in e:
        out["checked"] = e["checked"]
    return out


def published(src):
    """{shard: {key: entry}} for every shard with entries, and the name shard."""
    shards = {}
    app_over = {k[4:]: v for k, v in src["overrides"].items() if k.startswith("app:")}
    for pkg in sorted(set(src["categories"]) | set(src["android_overrides"])):
        o = src["android_overrides"].get(pkg)
        o = {"category": o} if isinstance(o, str) else (o or {})
        o = {k: v for k, v in o.items() if k in ("category", "detail", "app", "name")}
        e = src["categories"].get(pkg) or {}
        app = o.get("app", e.get("app"))
        out = _resolve(f"android:{pkg}", e, o, app_over.get(app), src["evidence"])
        if out:
            shards.setdefault("android", {})[f"android:{pkg}"] = out
    for key in sorted(set(src["keys"]) | {k for k in src["overrides"] if not k.startswith("app:")}):
        e = src["keys"].get(key) or {}
        o = src["overrides"].get(key) or {}
        app = o.get("app", e.get("app"))
        out = _resolve(key, e, o, app_over.get(app), src["evidence"])
        if out:
            shards.setdefault(key.split(":", 1)[0], {})[key] = out
    names, clash = {}, set()
    for ns in NAME_PLATFORMS:
        for key, e in (shards.get(ns) or {}).items():
            if not e.get("name") or not e.get("app") or ("category" not in e and "detail" not in e):
                continue
            n = normalize(e["name"])
            if not n:
                continue
            nk = f"name:{ns}:{n}"
            entry = {k: e[k] for k in ("app", "name", "category", "detail", "state", "source") if k in e}
            if nk in names and names[nk]["app"] != entry["app"]:
                clash.add(nk)
            names.setdefault(nk, entry)
    for nk in clash:
        del names[nk]
    if names:
        shards["name"] = dict(sorted(names.items()))
    return shards


def v1_apps(src, shards=None):
    """What the v1 categories file (c/<hash>.json) publishes: check_categories.merged plus each Android entry's
    detail, state and source, which older Glint ignores."""
    shards = shards if shards is not None else published(src)
    out = cc.merged(src["categories"], src["android_overrides"])
    android = shards.get("android", {})
    for pkg, v in out.items():
        e = android.get(f"android:{pkg}") or {}
        for k in ("detail", "state", "source"):
            if k in e:
                v[k] = e[k]
    return out


def changes(old, new):
    """Keys added, removed or with another category or detail, between two published() results."""
    def flat(shards):
        return {k: (e.get("category"), e.get("detail"), e.get("app")) for ns, ks in shards.items() if ns != "name" for k, e in ks.items()}
    a, b = flat(old), flat(new)
    return sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))


def unreviewed_ungaming(old, new):
    """Keys that were a confirmed game in [old] and aren't a game in [new] without an override or review."""
    out = []
    for ns, ks in old.items():
        if ns == "name":
            continue
        for k, e in ks.items():
            if e.get("category") == "game" and e.get("state") == "confirmed":
                n = (new.get(ns) or {}).get(k)
                if n is not None and n.get("category") != "game" and n.get("source") not in ("override", "review"):
                    out.append(k)
    return sorted(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--base", help="the base tree (a pull request's base): caps the changes and guards confirmed games")
    a = ap.parse_args()
    try:
        src = load(a.root)
        shards = published(src)
        if a.base:
            old = published(load(a.base))
            diff = changes(old, shards)
            print(f"catalog: {len(diff)} keys added, removed or changed against the base")
            if len(diff) > CAP:
                raise Bad(f"{len(diff)} changes in one go, more than {CAP}; split it or merge by hand")
            gone = unreviewed_ungaming(old, shards)
            if gone:
                raise Bad(f"confirmed games stop being games without an override or review: {', '.join(gone[:10])}")
    except Bad as e:
        print(f"check_catalog: {e}", file=sys.stderr)
        return 1
    counts = ", ".join(f"{ns} {len(ks)}" for ns, ks in sorted(shards.items()))
    confirmed = sum(1 for ks in shards.values() for e in ks.values() if e.get("state") == "confirmed")
    print(f"catalog: {counts}; {confirmed} confirmed, {len(src['evidence'])} evidence records")
    return 0


if __name__ == "__main__":
    sys.exit(main())
