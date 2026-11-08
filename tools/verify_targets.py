#!/usr/bin/env python3
"""FGM Plus 守卫闸（本地与 CI 同一份）。

校验 versions/targets.json 的矩阵规则与仓库结构互相对得上：
  1. 矩阵合法性：目标键不带 '_' 前缀的条目必须有 project/minecraft/loader/mappings/buildable；
     buildable: true 不许留 unverified，也不许引用 layers.json 里没有的层。
  2. 目录与矩阵对得上：工程目录存在 = 矩阵里有条目（否则是一份没人验证的源码）；
     矩阵里的 project = platforms/ 下真实存在的目录。
  3. 身份唯一：平台 gradle.properties 不许定义仓库级身份键（mod_version 等）。
  4. shared/ 与各层里不许引用平台侧包（net.fabricmc / net.minecraftforge / net.neoforged）。

用法：python tools/verify_targets.py [--matrix-out _matrix.json] [--list-buildable]
退出码非 0 = 有问题；--matrix-out 顺带产出 CI 矩阵（buildable 目标的 project 列表）；
--list-buildable 在守卫通过后把 buildable 的 project 逐行打到 stdout（给 build_all.sh /
collect_jars.sh 消费 —— 在脚本里算路径而不是把 POSIX 路径传给 Windows python）。
"""

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

IDENTITY_KEYS = [
    "mod_id", "mod_name", "mod_license", "mod_group_id",
    "mod_authors", "mod_description", "mod_version",
]
LOADER_DIRS = {"forge", "fabric", "neoforge"}
PLATFORM_PACKAGE_RE = re.compile(
    r"^\s*import\s+(net\.fabricmc|net\.minecraftforge|net\.neoforged)\."
)
AXES = ("since", "loader", "mappings")
AXIS_DIR = {"since": "version", "loader": "loader", "mappings": "mapping"}


def fail(msg: str) -> None:
    print(f"[verify] FAIL {msg}")
    global _failed
    _failed = True


_failed = False


def load_json(path: Path):
    with path.open(encoding="utf-8") as fh:
        return json.load(fh)


def version_at_least(have: str, want: str) -> bool:
    a = [int(x) if x.isdigit() else 0 for x in have.split(".")]
    b = [int(x) if x.isdigit() else 0 for x in want.split(".")]
    for i in range(max(len(a), len(b))):
        x = a[i] if i < len(a) else 0
        y = b[i] if i < len(b) else 0
        if x != y:
            return x > y
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix-out", type=Path, default=None)
    parser.add_argument("--list-buildable", action="store_true",
                        help="after the guard passes, print buildable target project paths, one per line")
    args = parser.parse_args()

    props_path = ROOT / "gradle.properties"
    if not props_path.is_file():
        fail(f"missing repo-root identity file {props_path}")
        return 1
    identity = {}
    for line in props_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            identity[k.strip()] = v.strip()
    for key in IDENTITY_KEYS:
        if key not in identity:
            fail(f"repo-root gradle.properties missing identity key {key}")

    targets = load_json(ROOT / "versions" / "targets.json")
    layers = load_json(ROOT / "versions" / "layers.json")
    layer_names = {k for k in layers if not k.startswith("_")}

    seen_projects = set()
    buildable = []
    declared_projects = set()

    for name, t in targets.items():
        if name.startswith("_"):
            continue
        if not isinstance(t, dict):
            fail(f"target {name}: entry is not an object")
            continue
        for field in ("project", "minecraft", "loader", "mappings", "buildable"):
            if field not in t:
                fail(f"target {name}: missing required field {field}")
        if "project" in t:
            if t["project"] in declared_projects:
                fail(f"target {name}: project {t['project']} already claimed by another entry")
            declared_projects.add(t["project"])
        # MC 26.x 去混淆后 loom 不再声明任何映射层，"none" 表示直连官方名
        if t.get("mappings") not in ("official", "yarn", "none"):
            fail(f"target {name}: mappings must be 'official', 'yarn' or 'none', got {t.get('mappings')!r}")
        if t.get("loader") not in LOADER_DIRS:
            fail(f"target {name}: loader must be one of {sorted(LOADER_DIRS)}")
        for lname in t.get("layers", []):
            if lname not in layer_names:
                fail(f"target {name}: references layer {lname!r} missing from versions/layers.json")
        if t.get("buildable") is True:
            buildable.append({"name": name, "project": t.get("project")})
            if t.get("unverified"):
                fail(f"target {name}: buildable targets must not carry 'unverified' fields")
        elif t.get("buildable") is not False:
            fail(f"target {name}: buildable must be true or false")

        # layer predicate spot-check (config-time red in gradle, here as a lint)
        lname_list = t.get("layers", [])
        for lname in lname_list:
            d = layers.get(lname, {})
            if "since" in d and "minecraft" in t:
                if not version_at_least(str(t["minecraft"]), str(d["since"])):
                    fail(f"target {name} (mc {t['minecraft']}) mounts version layer {lname} (since {d['since']}) — out of reach")
            for axis in AXES:
                if axis != "since" and axis in d:
                    have = str(t.get("mappings") if axis == "mappings" else t.get("loader"))
                    if have != str(d[axis]):
                        fail(f"target {name}: {axis}={have} but layer {lname} requires {axis}={d[axis]}")

    # platform directories vs matrix
    platforms_dir = ROOT / "platforms"
    if platforms_dir.is_dir():
        for project_dir in sorted(platforms_dir.iterdir()):
            if not project_dir.is_dir():
                continue
            rel = project_dir.relative_to(ROOT).as_posix()
            if rel not in declared_projects:
                fail(f"platform directory {rel} has no entry in versions/targets.json — "
                     f"unverified source nobody builds")
            for log in project_dir.rglob("*.log"):
                pass  # build logs allowed inside platform dirs

    # identity uniqueness: platform gradle.properties must not define identity keys
    if platforms_dir.is_dir():
        for props in sorted(platforms_dir.glob("*/gradle.properties")):
            for line in props.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith("#") or "=" not in line:
                    continue
                k = line.split("=", 1)[0].strip()
                if k in IDENTITY_KEYS:
                    fail(f"{props.relative_to(ROOT)} defines identity key {k} — identity lives only at repo root")

    # shared/ and layers must not import platform packages
    for base in [ROOT / "shared", ROOT / "layers"]:
        if not base.is_dir():
            continue
        for java in base.rglob("*.java"):
            for i, line in enumerate(java.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                if PLATFORM_PACKAGE_RE.match(line):
                    fail(f"{java.relative_to(ROOT)}:{i} shared/layers code imports a platform package")

    if args.matrix_out:
        args.matrix_out.write_text(json.dumps({"include": buildable}, indent=2), encoding="utf-8")
        print(f"[verify] matrix written to {args.matrix_out}", file=sys.stderr)

    if _failed:
        print("[verify] FAILED", file=sys.stderr)
        return 1
    print(f"[verify] OK ({len(buildable)} buildable target(s))", file=sys.stderr)
    if args.list_buildable:
        for entry in buildable:
            print(entry["project"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
