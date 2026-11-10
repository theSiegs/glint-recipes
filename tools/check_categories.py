#!/usr/bin/env python3
"""Checks the catalog's app categories (README.md, "App categories"); standard library only.

    python tools/check_categories.py [--root .] [--base <old categories.json>]

categories.json (written by the intake's nightly classifier) and categories-overrides.json (written by
hand; it always wins) must pass:
  - schema: their format, an "apps" object, and per app only the known keys with the right types
  - categories in the allowed set (Hearth HA's APP_CATEGORIES plus messaging, or "unknown")
  - a glance, when there is one, in the allowed set (which of Glint's glances an app's tile borrows)
  - a kind, when there is one, in the allowed set (what Glint's kind recipes and Car tile key on)
  - package names in Android's format
  - no personal data: a name is one short line with no email address, link or phone number, and
    every other value has a fixed form (a source tag, a date)
  - with --base: at most CAP apps added, removed or changed category, glance or kind against the old file
Also merges the two into what is published (merged()), used by tools/build_index.py.
"""
import argparse
import json
import os
import re
import sys

FORMAT = "glint.categories/1"
OVERRIDES_FORMAT = "glint.categories-overrides/1"
CATEGORIES = ("game", "video", "audio", "image", "social", "news", "maps", "productivity", "accessibility", "messaging")
UNKNOWN = "unknown"
# Which of Glint's glances an app's tile borrows when it has nothing waiting: Glint's own photos, music,
# calendar, clock and weather glances; "car", reserved for a car glance Glint doesn't have yet (Glint
# falls back to its own guess); "calls", a calling app (Google Voice and the like) whose voicemails,
# missed calls and texts get the calling-app faces (Glint 2026-11-10); "none", the app's own glance,
# never a borrowed one.
GLANCES = ("photos", "music", "calendar", "clock", "weather", "car", "calls", "none")
# What kind of app it is, which Glint's kind recipes and Car tile key on instead of hand lists of
# packages (Glint's AppCategories.KINDS, owner 2026-11-10): "charging", a charger network's app whose
# session fuses into the Car tile's; "car", "airline", "delivery", "ride".
KINDS = ("car", "charging", "airline", "delivery", "ride")
CAP = 60
PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
SOURCE = re.compile(r"^(play|ai|ai-search)(:[A-Z0-9_]{1,40})?$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
NAME_MAX = 60
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
LINK = re.compile(r"(https?://|www\.|\b[\w-]+\.(com|net|org|io|app)\b)", re.I)
PHONE = re.compile(r"\+?\d[\d\s().-]{6,}\d")
ENTRY_KEYS = {"category", "name", "source", "checked", "glance", "kind"}
PUBLISHED = ("category", "name", "glance", "kind")


class Bad(Exception):
    pass


def check_name(where, name):
    if not isinstance(name, str) or not name.strip() or len(name) > NAME_MAX or name != " ".join(name.split()):
        raise Bad(f"{where}: name must be one line of 1..{NAME_MAX} characters")
    if any(ord(ch) < 32 for ch in name):
        raise Bad(f"{where}: name has a control character")
    if EMAIL.search(name) or LINK.search(name) or PHONE.search(name):
        raise Bad(f"{where}: name looks like it holds an email address, link or phone number")


def check_package(where, pkg):
    if not isinstance(pkg, str) or len(pkg) > 255 or not PACKAGE.match(pkg):
        raise Bad(f"{where}: {pkg!r} is not an Android package name")


def check_glance(where, glance):
    if not isinstance(glance, str) or glance not in GLANCES:
        raise Bad(f"{where}: glance {glance!r} is not one of {GLANCES}")


def check_kind(where, kind):
    if not isinstance(kind, str) or kind not in KINDS:
        raise Bad(f"{where}: kind {kind!r} is not one of {KINDS}")


def load_json(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        try:
            return json.load(f)
        except ValueError as e:
            raise Bad(f"{os.path.basename(path)}: not JSON ({e})") from e


def check_categories(data):
    """The apps of a categories.json document, checked."""
    if not isinstance(data, dict) or data.get("format") != FORMAT or not isinstance(data.get("apps"), dict):
        raise Bad(f'categories.json: must be {{"format": "{FORMAT}", "apps": {{...}}}}')
    if set(data) - {"format", "apps"}:
        raise Bad(f"categories.json: unknown keys {sorted(set(data) - {'format', 'apps'})}")
    for pkg, e in data["apps"].items():
        where = f"categories.json {pkg}"
        check_package(where, pkg)
        if not isinstance(e, dict) or set(e) - ENTRY_KEYS:
            raise Bad(f"{where}: an entry is an object with only {sorted(ENTRY_KEYS)}")
        if e.get("category") not in CATEGORIES + (UNKNOWN,):
            raise Bad(f"{where}: category {e.get('category')!r} is not one of {CATEGORIES + (UNKNOWN,)}")
        if not isinstance(e.get("source"), str) or not SOURCE.match(e["source"]):
            raise Bad(f"{where}: source {e.get('source')!r} is not play[:GENRE], ai[:GENRE] or ai-search")
        if not isinstance(e.get("checked"), str) or not DATE.match(e["checked"]):
            raise Bad(f"{where}: checked must be a yyyy-mm-dd date")
        if "name" in e:
            check_name(where, e["name"])
        if "glance" in e:
            check_glance(where, e["glance"])
        if "kind" in e:
            check_kind(where, e["kind"])
    return data["apps"]


def check_overrides(data):
    """The apps of a categories-overrides.json document, checked: each a category (or "unknown"), or
    an object with a category, a name, a glance and/or a kind."""
    if not isinstance(data, dict) or data.get("format") != OVERRIDES_FORMAT or not isinstance(data.get("apps"), dict):
        raise Bad(f'categories-overrides.json: must be {{"format": "{OVERRIDES_FORMAT}", "apps": {{...}}}}')
    if set(data) - {"format", "apps"}:
        raise Bad("categories-overrides.json: only format and apps")
    for pkg, v in data["apps"].items():
        where = f"categories-overrides.json {pkg}"
        check_package(where, pkg)
        if isinstance(v, str):
            v = {"category": v}
        if not isinstance(v, dict) or not v or set(v) - set(PUBLISHED):
            raise Bad(f'{where}: a category, or {{"category": ..., "name": ..., "glance": ..., "kind": ...}}')
        if "category" in v and v["category"] not in CATEGORIES + (UNKNOWN,):
            raise Bad(f"{where}: category {v['category']!r} is not one of {CATEGORIES + (UNKNOWN,)}")
        if "name" in v:
            check_name(where, v["name"])
        if "glance" in v:
            check_glance(where, v["glance"])
        if "kind" in v:
            check_kind(where, v["kind"])
    return data["apps"]


def changes(old, new):
    """Apps added, removed or with another category, glance or kind in [new] than in [old]."""
    def published(apps, p):
        e = apps.get(p) or {}
        return e.get("category"), e.get("glance"), e.get("kind")
    return sorted(p for p in set(old) | set(new) if published(old, p) != published(new, p) or (p in old) != (p in new))


def load(root):
    """(categories, overrides) of the catalog at [root], checked; empty when a file is missing."""
    c = load_json(os.path.join(root, "categories.json"))
    o = load_json(os.path.join(root, "categories-overrides.json"))
    return (check_categories(c) if c is not None else {}), (check_overrides(o) if o is not None else {})


def merged(categories, overrides):
    """What phones get: {package: {"category"?, "name"?, "glance"?, "kind"?}}, overrides winning; "unknown"
    categories and the classifier's bookkeeping (source, checked) left out; an app with none is left out."""
    out = {}
    for pkg in sorted(set(categories) | set(overrides)):
        e = {k: v for k, v in (categories.get(pkg) or {}).items() if k in PUBLISHED}
        o = overrides.get(pkg)
        if o is not None:
            e.update({"category": o} if isinstance(o, str) else o)
        if e.get("category") == UNKNOWN:
            del e["category"]
        if e:
            out[pkg] = {k: e[k] for k in PUBLISHED if k in e}
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--base", help="the categories.json being replaced (a pull request's base); caps the changes")
    a = ap.parse_args()
    try:
        cats, overrides = load(a.root)
        if a.base:
            old = load_json(a.base)
            old = check_categories(old) if old is not None else {}
            diff = changes(old, cats)
            print(f"categories.json: {len(diff)} apps added, removed or changed against the base")
            if len(diff) > CAP:
                raise Bad(f"categories.json: {len(diff)} changes in one go, more than {CAP}; split it or merge by hand")
    except Bad as e:
        print(f"check_categories: {e}", file=sys.stderr)
        return 1
    out = merged(cats, overrides)
    print(f"categories: {len(cats)} classified, {len(overrides)} overridden, {sum('category' in v for v in out.values())} published with a category, "
          f"{sum('glance' in v for v in out.values())} with a glance, {sum('kind' in v for v in out.values())} with a kind")
    return 0


if __name__ == "__main__":
    sys.exit(main())
