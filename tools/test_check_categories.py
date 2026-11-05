"""Tests for tools/check_categories.py: the schema, the allowed set, package names, the name rule,
the change cap and what is published. python -m unittest discover -s tools"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_categories as cc  # noqa: E402


def doc(apps):
    return {"format": cc.FORMAT, "apps": apps}


def e(category, name=None):
    out = {"category": category, "source": "play:GAME_PUZZLE", "checked": "2026-11-05"}
    if name:
        out["name"] = name
    return out


class CheckTest(unittest.TestCase):
    def test_good_file(self):
        apps = cc.check_categories(doc({"com.example.game": e("game", "Puzzle Time"), "com.example.x": e("unknown"),
                                        "com.example.chat": e("messaging")}))
        self.assertEqual(len(apps), 3)

    def bad(self, data):
        with self.assertRaises(cc.Bad):
            cc.check_categories(data)

    def test_schema(self):
        self.bad({"apps": {}})
        self.bad(doc([]))
        self.bad({**doc({}), "extra": 1})
        self.bad(doc({"com.example.a": "game"}))
        self.bad(doc({"com.example.a": {**e("game"), "note": "x"}}))
        self.bad(doc({"com.example.a": {**e("game"), "checked": "yesterday"}}))
        self.bad(doc({"com.example.a": {**e("game"), "source": "a person said so"}}))

    def test_allowed_set_and_package(self):
        self.bad(doc({"com.example.a": e("shopping")}))
        self.bad(doc({"not a package": e("game")}))
        self.bad(doc({"example": e("game")}))

    def test_no_personal_data_in_names(self):
        for name in ["Write to a@b.co", "Booking.com", "https://x", "Call 555 123 4567", "two\nlines", "x" * 61]:
            self.bad(doc({"com.example.a": e("game", name)}))

    def test_overrides(self):
        o = cc.check_overrides({"format": cc.OVERRIDES_FORMAT, "apps": {"com.a.b": "video", "com.c.d": "unknown",
                                                                         "com.e.f": {"name": "Family Map"}}})
        self.assertEqual(len(o), 3)
        for bad in [{"com.a.b": "tv"}, {"com.a.b": {}}, {"com.a.b": {"category": "game", "why": "x"}}]:
            with self.assertRaises(cc.Bad):
                cc.check_overrides({"format": cc.OVERRIDES_FORMAT, "apps": bad})

    def test_changes_count_adds_removes_and_category_changes_not_dates(self):
        old = {"com.a.a": e("game"), "com.b.b": e("video"), "com.c.c": e("news")}
        new = {"com.a.a": {**e("game"), "checked": "2027-01-01"}, "com.b.b": e("audio"), "com.d.d": e("maps")}
        self.assertEqual(cc.changes(old, new), ["com.b.b", "com.c.c", "com.d.d"])

    def test_merged_overrides_win_and_unknown_is_left_out(self):
        cats = {"com.a.a": e("game", "A"), "com.b.b": e("unknown", "B"), "com.c.c": e("unknown"), "com.d.d": e("video")}
        over = {"com.d.d": "audio", "com.a.a": "unknown", "com.e.e": {"category": "maps", "name": "E"}}
        self.assertEqual(cc.merged(cats, over), {
            "com.a.a": {"name": "A"},
            "com.b.b": {"name": "B"},
            "com.d.d": {"category": "audio"},
            "com.e.e": {"category": "maps", "name": "E"},
        })


if __name__ == "__main__":
    unittest.main()
