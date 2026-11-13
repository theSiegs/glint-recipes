"""Tests for tools/check_catalog.py: keys, details, the computed state, the evidence rule, name keys, what the
v1 categories file gains, and the base guards. python -m unittest discover -s tools"""
import copy
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_catalog as ck  # noqa: E402

DAY = "2026-11-14"
_DIRS = []


def tearDownModule():
    for d in _DIRS:
        d.cleanup()


def evidence(key="xbox:1234567", proposed=None, verdict="approve", judges=None, facts=None, signals=None, rid=None):
    proposed = proposed or {"category": "productivity", "detail": "companion"}
    return {
        "format": ck.EVIDENCE_FORMAT,
        "id": rid or f"{DAY}-{key.split(':')[0]}-{key.split(':', 1)[1]}",
        "key": key,
        "previous": {"category": "game"},
        "proposed": proposed,
        "verdict": verdict,
        "facts": facts if facts is not None else [
            {"kind": "store", "url": "https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=9XXXXXXXXXXX",
             "says": "ProductKind=Application; Category=Utilities & tools"}],
        "game_signals": signals or [],
        "judges": judges if judges is not None else [
            {"model": "m", "prompt": "judge-a/1", "answer": dict(proposed), "reason": "a hub app"},
            {"model": "m", "prompt": "judge-b/1", "answer": dict(proposed), "reason": "launches games"}],
        "why": None,
        "reviewed": DAY,
    }


class Tree:
    """A catalog checkout in a temp dir."""

    def __init__(self):
        self.dir = tempfile.TemporaryDirectory()
        _DIRS.append(self.dir)
        self.root = self.dir.name
        self.categories = {"com.example.game": {"category": "game", "name": "Example Game", "source": "play:GAME_PUZZLE", "checked": DAY},
                           "com.example.tube": {"category": "video", "name": "Example Tube", "source": "ai:ENTERTAINMENT", "checked": DAY}}
        self.android_overrides = {}
        self.apps = {"example-game": {"name": "Example Game"}, "example-hub": {"name": "Example Hub"}}
        self.keys = {"xbox:1234567": {"app": "example-hub", "name": "Example Hub", "category": "game", "source": "store:Game", "checked": DAY}}
        self.overrides = {}
        self.evidence = []

    def write(self):
        os.makedirs(os.path.join(self.root, "catalog", "evidence"), exist_ok=True)
        for f in os.listdir(os.path.join(self.root, "catalog", "evidence")):
            os.remove(os.path.join(self.root, "catalog", "evidence", f))

        def dump(path, data):
            with open(os.path.join(self.root, path), "w", encoding="utf-8") as f:
                json.dump(data, f)
        dump("categories.json", {"format": "glint.categories/1", "apps": self.categories})
        dump("categories-overrides.json", {"format": "glint.categories-overrides/1", "apps": self.android_overrides})
        dump("catalog/apps.json", {"format": ck.APPS_FORMAT, "apps": self.apps})
        dump("catalog/keys.json", {"format": ck.KEYS_FORMAT, "keys": self.keys})
        dump("catalog/overrides.json", {"format": ck.OVERRIDES_FORMAT, "keys": self.overrides})
        for rec in self.evidence:
            dump(f"catalog/evidence/{rec['id']}.json", rec)
        return self.root

    def shards(self):
        return ck.published(ck.load(self.write()))


class KeyTest(unittest.TestCase):
    def test_good_keys(self):
        for key in ("android:com.example.app", "msstore:9NBLGGH2JHXJ", "xbox:1828326430", "pfn:example.app_8wekyb3d8bbwe",
                    "steam:1172470", "switch:0100152000022000", "web:example.com", "fs:xboxplt:0123456789abcdef"):
            ck.split_key(key)

    def test_bad_keys(self):
        for key in ("xbox:0x1234", "xbox:99999999999", "msstore:9nblggh2jhxj", "switch:0100152000022", "pfn:Example_8wekyb3d8bbwe",
                    "steam:abc", "web:https://example.com", "web:example", "ios:com.example", "android:example", "nocolon", "xbox: 12"):
            with self.assertRaises(ck.Bad, msg=key):
                ck.split_key(key)

    def test_fs_not_accepted_yet(self):
        t = Tree()
        t.keys["fs:xboxplt:0123456789abcdef"] = {"app": "example-hub", "source": "match", "checked": DAY}
        with self.assertRaises(ck.Bad):
            ck.load(t.write())

    def test_slug_must_be_listed(self):
        t = Tree()
        t.keys["xbox:7654321"] = {"app": "nowhere", "category": "game", "source": "store:Game", "checked": DAY}
        with self.assertRaises(ck.Bad):
            ck.load(t.write())

    def test_android_not_in_keys(self):
        t = Tree()
        t.keys["android:com.example.x"] = {"app": "example-hub", "source": "ai", "checked": DAY}
        with self.assertRaises(ck.Bad):
            ck.load(t.write())


class DetailTest(unittest.TestCase):
    def test_detail_needs_its_category(self):
        ck.check_detail("x", "video", "youtube")
        ck.check_detail("x", "productivity", "education")
        ck.check_detail("x", None, "companion")
        for cat, det in (("game", "education"), ("productivity", "youtube"), ("video", "companion"), ("video", "shorts")):
            with self.assertRaises(ck.Bad):
                ck.check_detail("x", cat, det)


class StateTest(unittest.TestCase):
    def test_rules(self):
        t = Tree()
        t.keys["msstore:9XXXXXXXXXX1"] = {"app": "example-hub", "category": "video", "source": "store:Application", "checked": DAY}
        t.keys["steam:10"] = {"app": "example-game", "category": "game", "source": "steam:game", "checked": DAY}
        t.keys["steam:11"] = {"app": "example-game", "category": "game", "source": "steam:software", "checked": DAY}
        t.keys["switch:0100000000010000"] = {"app": "example-game", "category": "game", "source": "platform:switch", "checked": DAY}
        s = t.shards()
        self.assertEqual(s["android"]["android:com.example.game"]["state"], "confirmed")
        self.assertEqual(s["android"]["android:com.example.game"]["source"], "play")
        self.assertEqual(s["android"]["android:com.example.tube"]["state"], "suggested")
        self.assertEqual(s["android"]["android:com.example.tube"]["source"], "ai")
        self.assertEqual(s["xbox"]["xbox:1234567"]["state"], "confirmed")
        self.assertEqual(s["msstore"]["msstore:9XXXXXXXXXX1"]["state"], "suggested")
        self.assertEqual(s["steam"]["steam:10"]["state"], "confirmed")
        self.assertEqual(s["steam"]["steam:11"]["state"], "suggested")
        self.assertEqual(s["switch"]["switch:0100000000010000"]["source"], "platform")

    def test_rule_confirmed_entry_holds_back_an_unreviewed_detail(self):
        t = Tree()
        t.categories["com.example.game2"] = {"category": "video", "detail": "youtube", "source": "play:VIDEO_PLAYERS", "checked": DAY}
        e = t.shards()["android"]["android:com.example.game2"]
        self.assertEqual(e["state"], "confirmed")
        self.assertNotIn("detail", e)

    def test_suggested_entry_shows_its_detail(self):
        t = Tree()
        t.categories["com.example.tube"]["detail"] = "youtube"
        e = t.shards()["android"]["android:com.example.tube"]
        self.assertEqual((e["state"], e["detail"]), ("suggested", "youtube"))

    def test_overrides_confirm_and_key_beats_app(self):
        t = Tree()
        t.overrides["app:example-hub"] = {"category": "productivity", "detail": "companion"}
        t.keys["xbox:7654321"] = {"app": "example-hub", "category": "game", "source": "store:Game", "checked": DAY}
        t.overrides["xbox:7654321"] = {"category": "game"}
        s = t.shards()["xbox"]
        self.assertEqual(s["xbox:1234567"], {"app": "example-hub", "name": "Example Hub", "category": "productivity",
                                             "detail": "companion", "state": "confirmed", "source": "override", "checked": DAY})
        self.assertEqual((s["xbox:7654321"]["category"], s["xbox:7654321"].get("detail")), ("game", "companion"))

    def test_android_override_with_app_only_does_not_confirm(self):
        t = Tree()
        t.android_overrides["com.example.tube"] = {"app": "example-hub"}
        e = t.shards()["android"]["android:com.example.tube"]
        self.assertEqual((e["app"], e["state"]), ("example-hub", "suggested"))


class EvidenceTest(unittest.TestCase):
    def reviewed(self, rec, value=("productivity", "companion")):
        t = Tree()
        t.keys["xbox:1234567"] = {"app": "example-hub", "category": value[0], "detail": value[1], "source": "ai",
                                  "checked": DAY, "review": rec["id"]}
        t.evidence.append(rec)
        return t.shards()["xbox"]["xbox:1234567"]

    def test_passing_record_confirms(self):
        e = self.reviewed(evidence())
        self.assertEqual((e["state"], e["source"], e["detail"]), ("confirmed", "review", "companion"))

    def assert_suggested(self, rec):
        self.assertEqual(self.reviewed(rec)["state"], "suggested")

    def test_one_judge_is_not_enough(self):
        rec = evidence()
        rec["judges"] = rec["judges"][:1]
        self.assert_suggested(rec)

    def test_same_prompt_twice_is_one_judge(self):
        rec = evidence()
        rec["judges"][1]["prompt"] = "judge-a/1"
        self.assert_suggested(rec)

    def test_judges_disagree(self):
        rec = evidence()
        rec["judges"][1]["answer"] = {"category": "game", "detail": None}
        self.assert_suggested(rec)

    def test_needs_a_store_or_web_fact_from_an_allowed_host(self):
        self.assert_suggested(evidence(facts=[{"kind": "web", "url": "https://example.com/about", "says": "a hub"}]))
        self.assert_suggested(evidence(facts=[{"kind": "proposal", "url": None, "says": "suggested by households"}]))

    def test_game_signals_block_loosening(self):
        self.assert_suggested(evidence(signals=["ProductKind=Game"]))

    def test_game_signals_block_leaving_game(self):
        rec = evidence(proposed={"category": "video", "detail": None}, signals=["play:GAME_ARCADE"])
        self.assertEqual(self.reviewed(rec, ("video", None))["state"], "suggested")

    def test_not_approved(self):
        self.assert_suggested(evidence(verdict="escalate"))

    def test_record_for_another_value(self):
        rec = evidence(proposed={"category": "productivity", "detail": "education"})
        self.assert_suggested(rec)

    def test_record_form(self):
        rec = evidence()
        rec["facts"][0]["says"] = "write to help@example.com"
        with self.assertRaises(ck.Bad):
            ck.check_evidence(rec, rec["id"] + ".json")
        rec = evidence()
        with self.assertRaises(ck.Bad):
            ck.check_evidence(rec, "other-name.json")
        rec = evidence()
        del rec["why"]
        with self.assertRaises(ck.Bad):
            ck.check_evidence(rec, rec["id"] + ".json")


class NameTest(unittest.TestCase):
    def test_vectors_in_repo(self):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        path = os.path.join(root, "catalog", "normalize-vectors.json")
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as f:
                ck.check_vectors(json.load(f))

    def test_normalize(self):
        self.assertEqual(ck.normalize("Pokémon™ Violet for Nintendo Switch"), "pokemon violet")
        self.assertEqual(ck.normalize("A: B - C"), "a b c")

    def test_name_keys_and_clashes(self):
        t = Tree()
        t.keys["xbox:7654321"] = {"app": "example-game", "name": "Example Hub", "category": "game", "source": "store:Game", "checked": DAY}
        t.keys["msstore:9XXXXXXXXXX2"] = {"app": "example-game", "name": "Example Game™", "category": "game", "source": "store:Game", "checked": DAY}
        names = t.shards()["name"]
        self.assertNotIn("name:xbox:example hub", names)  # two apps claim it
        self.assertEqual(names["name:msstore:example game"]["app"], "example-game")


class PublishTest(unittest.TestCase):
    def test_v1_gains_detail_state_source_only(self):
        t = Tree()
        t.android_overrides["com.example.tube"] = {"detail": "youtube"}
        src = ck.load(t.write())
        v1 = ck.v1_apps(src)
        self.assertEqual(v1["com.example.tube"], {"category": "video", "name": "Example Tube", "detail": "youtube",
                                                  "state": "confirmed", "source": "override"})
        self.assertEqual(set(v1["com.example.game"]), {"category", "name", "state", "source"})

    def test_base_guard(self):
        base = Tree()
        old = ck.published(ck.load(base.write()))
        t = Tree()
        t.keys["xbox:1234567"] = {**t.keys["xbox:1234567"], "category": "productivity", "source": "ai"}
        new = ck.published(ck.load(t.write()))
        self.assertEqual(ck.unreviewed_ungaming(old, new), ["xbox:1234567"])
        t.overrides["xbox:1234567"] = {"category": "productivity"}
        new = ck.published(ck.load(t.write()))
        self.assertEqual(ck.unreviewed_ungaming(old, new), [])
        self.assertEqual(ck.changes(old, new), ["xbox:1234567"])

    def test_repo_catalog_passes(self):
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        if os.path.isfile(os.path.join(root, "catalog", "keys.json")):
            src = ck.load(root)
            shards = ck.published(src)
            self.assertIn("android", shards)


if __name__ == "__main__":
    unittest.main()
