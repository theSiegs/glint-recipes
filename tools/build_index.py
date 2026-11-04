#!/usr/bin/env python3
"""Builds the tile exchange's published site from the catalog (stdlib only).

    python tools/build_index.py --out site [--key signing.pem] [--verify keys/exchange-1.pub] [--seq N]

Writes, per the glint.exchange/1 contract (README.md):
  site/index.json        one entry per package: its best recipe (status verified > built-in >
                         unverified > learned, then the highest rev), the SHA-256 of the exact
                         file bytes, seq (Unix seconds of the run), issued, and the revoked list
  site/r/<id>.<rev>.json exact copies of the recipe files
  site/index.json.sig    with --key: base64 of the raw 64-byte Ed25519 signature over index.json
                         (signed with `openssl pkeyutl -sign -rawin`)
With --verify, the signature is checked with openssl against each raw base64 public key given
(the run fails unless one of them verifies it). Without --key nothing is signed (a dry run).
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

FORMAT = "glint.exchange/1"
RANK = {"verified": 3, "built-in": 2, "unverified": 1, "learned": 0}  # "draft" is never published
ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
REVOKED = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}(@[1-9][0-9]*)?$")
# DER SubjectPublicKeyInfo prefix of an Ed25519 key; the raw 32 key bytes follow it.
SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")


def fail(msg):
    print("build_index: " + msg, file=sys.stderr)
    sys.exit(1)


def read_revoked(path):
    out = []
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as f:
        for n, raw in enumerate(f, 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            if not REVOKED.match(line):
                fail(f"{path} line {n}: {line!r} is not an id or id@rev")
            if line not in out:
                out.append(line)
    return out


def read_recipes(folder):
    recipes = []
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(folder, name)
        with open(path, "rb") as f:
            data = f.read()
        try:
            r = json.loads(data.decode("utf-8"))
        except ValueError as e:
            fail(f"{name}: not strict JSON ({e}); catalog files are plain JSON")
        rid, rev = r.get("id"), r.get("rev")
        pkg = (r.get("app") or {}).get("package")
        status = (r.get("meta") or {}).get("status", "unverified")
        if not isinstance(rid, str) or not ID.match(rid):
            fail(f"{name}: bad id {rid!r}")
        if not isinstance(rev, int) or isinstance(rev, bool) or rev < 1:
            fail(f"{name}: bad rev {rev!r}")
        if not isinstance(pkg, str) or not pkg or pkg == "*":
            fail(f"{name}: bad app.package {pkg!r}")
        recipes.append({"file": name, "bytes": data, "id": rid, "rev": rev, "package": pkg, "status": status})
    return recipes


def best_per_package(recipes, revoked):
    best = {}
    for r in recipes:
        if r["status"] not in RANK:
            continue  # drafts and unknown statuses stay out
        if r["id"] in revoked or f'{r["id"]}@{r["rev"]}' in revoked:
            continue
        key = (RANK[r["status"]], r["rev"], r["id"])
        cur = best.get(r["package"])
        if cur is None or key > (RANK[cur["status"]], cur["rev"], cur["id"]):
            best[r["package"]] = r
    return best


def openssl(*args, data=None):
    p = subprocess.run(["openssl", *args], input=data, capture_output=True)
    return p


def sign(index_path, key_path):
    p = openssl("pkeyutl", "-sign", "-rawin", "-inkey", key_path, "-in", index_path)
    if p.returncode != 0 or len(p.stdout) != 64:
        fail("signing failed: " + p.stderr.decode(errors="replace").strip())
    return p.stdout


def verify(index_path, sig, raw_pub_b64):
    raw = base64.b64decode(raw_pub_b64.strip(), validate=True)
    if len(raw) != 32:
        fail("a public key file must hold the base64 of a raw 32-byte Ed25519 key")
    with tempfile.TemporaryDirectory() as tmp:
        der, sigf = os.path.join(tmp, "pub.der"), os.path.join(tmp, "index.sig")
        with open(der, "wb") as f:
            f.write(SPKI_PREFIX + raw)
        with open(sigf, "wb") as f:
            f.write(sig)
        p = openssl("pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER", "-inkey", der,
                    "-in", index_path, "-sigfile", sigf)
        return p.returncode == 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="site")
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    help="the catalog checkout (default: this script's repo)")
    ap.add_argument("--key", help="Ed25519 private key (PEM) to sign index.json with")
    ap.add_argument("--verify", action="append", default=[],
                    help="raw base64 public key file the signature must verify against (repeatable: any one)")
    ap.add_argument("--seq", type=int, help="override seq (default: Unix seconds now)")
    a = ap.parse_args()

    now = int(time.time())
    seq = a.seq if a.seq is not None else now
    revoked = read_revoked(os.path.join(a.root, "revoked.txt"))
    recipes = read_recipes(os.path.join(a.root, "recipes"))
    best = best_per_package(recipes, set(revoked))

    if os.path.isdir(a.out):
        shutil.rmtree(a.out)
    os.makedirs(os.path.join(a.out, "r"))
    apps = {}
    for pkg in sorted(best):
        r = best[pkg]
        with open(os.path.join(a.out, "r", f'{r["id"]}.{r["rev"]}.json'), "wb") as f:
            f.write(r["bytes"])
        apps[pkg] = {"id": r["id"], "rev": r["rev"], "sha256": hashlib.sha256(r["bytes"]).hexdigest()}
    index = {
        "format": FORMAT,
        "seq": seq,
        "issued": datetime.datetime.fromtimestamp(now, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": apps,
        "revoked": revoked,
    }
    index_bytes = json.dumps(index, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    index_path = os.path.join(a.out, "index.json")
    with open(index_path, "wb") as f:
        f.write(index_bytes)
    print(f"index.json: seq {seq}, {len(apps)} apps, {len(revoked)} revoked")

    if a.key:
        sig = sign(index_path, a.key)
        with open(index_path + ".sig", "w", encoding="ascii", newline="") as f:
            f.write(base64.b64encode(sig).decode("ascii"))
        print("index.json.sig written")
        if a.verify:
            for pub in a.verify:
                with open(pub, encoding="ascii") as f:
                    if verify(index_path, sig, f.read()):
                        print(f"signature verifies against {os.path.basename(pub)}")
                        break
            else:
                fail("the signature verifies against none of: " + ", ".join(a.verify))
    elif a.verify:
        fail("--verify needs --key")


if __name__ == "__main__":
    main()
