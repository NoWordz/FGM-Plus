#!/usr/bin/env python3
"""Derive the store body from README.md (**not stored in the repo**).

Same rule as the rest of the E33EPUS project group: the store page is edited in the
platform's own editor, so a committed "description document" would silently drift from
what is actually live. The repository README is the one description you already keep
current, so the store body is a derived view: printed on demand, pasted, never committed.

    README.md  --(this script)-->  Modrinth body  (English Markdown)
                                    CurseForge body (byte-identical, pasted by hand)

Usage:
    python tools/store_copy.py --platform modrinth
    python tools/store_copy.py --platform curseforge --out %TEMP%\\cf.md

Exit code: 0 = produced; 1 = the README is missing a section the map needs.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"

# README section name -> store section name. Every name here MUST exist in README.md,
# otherwise this fails loudly: a renamed README heading would otherwise drop a section
# from the store body without anyone noticing.
MAP = [
    ("What it is", "Overview"),
    ("Features", "Features"),
    ("Compatibility", "Compatibility"),
    ("Configuration", "Configuration"),
    ("Known limitations", "Known Limitations"),
    ("FAQ", "FAQ"),
]
FEEDBACK_TITLE = "Feedback"

# The Modrinth/CurseForge gallery cover. Set to a user-uploaded attachment URL (not a
# raw.githubusercontent.com URL, which does not load) once a cover exists; empty = no
# header image is emitted and the platform's own project icon is used.
BANNER_URL = ""

HEADING = re.compile(r"^##\s+(.+?)\s*$")
TAGLINE = re.compile(r"<em>(.+?)</em>")

LOADER_LABEL = {"forge": "Forge", "fabric": "Fabric", "neoforge": "NeoForge"}


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    if not path.is_file():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def readme_sections() -> tuple[str, dict[str, list[list[str]]]]:
    """Return (tagline, {section title: [block, ...]}); blocks keep list/table/paragraph shape."""
    if not README.is_file():
        raise SystemExit("README.md not found")

    text = README.read_text(encoding="utf-8")
    match = TAGLINE.search(text)
    tagline = match.group(1).strip() if match else ""

    sections: dict[str, list[list[str]]] = {}
    title: str | None = None
    block: list[str] = []

    def shape(line: str) -> str:
        if line.startswith("- "):
            return "list"
        if line.startswith("|"):
            return "table"
        return "text"

    def flush() -> None:
        nonlocal block
        if block and title is not None:
            sections[title].append(block)
        block = []

    for raw in text.splitlines():
        line = raw.rstrip()
        heading = HEADING.match(line)
        if heading:
            flush()
            title = heading.group(1)
            sections[title] = []
            continue
        if title is None:            # banner / title / tagline / badges above the first section
            continue
        if not line.strip():
            flush()
            continue
        if block and shape(block[0]) != shape(line):
            flush()
        block.append(line)
    flush()
    return tagline, sections


def header_block() -> list[str]:
    """Cover + name + badges, all generated so they cannot go stale by hand."""
    root = read_properties(ROOT / "gradle.properties")
    name = root.get("mod_name", "")
    license_name = root.get("mod_license", "")
    repo = root.get("mod_repo_url", "").rstrip("/")
    if not (name and repo):
        raise SystemExit("gradle.properties is missing mod_name / mod_repo_url")

    targets = json.loads((ROOT / "versions" / "targets.json").read_text(encoding="utf-8"))
    live = [t for k, t in targets.items() if not k.startswith("_") and t.get("buildable")]

    mc = " / ".join(sorted({t.get("minecraft", "") for t in live}, key=lambda v: [int(x) for x in v.split(".")]))
    loaders = " | ".join(LOADER_LABEL.get(t.get("loader", ""), t.get("loader", "")) for t in live)
    loaders = " | ".join(dict.fromkeys(loaders.split(" | ")))   # de-duplicate, keep order
    java = " / ".join(sorted({str(t.get("java", "")) for t in live}, key=int))
    slug = repo.split("github.com/")[-1]

    def badge(label: str, message: str, color: str) -> str:
        return f"![{label}](https://img.shields.io/badge/{quote(label, safe='')}-{quote(message, safe='')}-{color})"

    out: list[str] = []
    if BANNER_URL:
        out += [f"![{name}]({BANNER_URL})", ""]
    out += [f"# {name}", ""]
    out += [
        " ".join([
            badge("Minecraft", mc, "green"),
            badge("Loaders", loaders, "red"),
            badge("Side", "Client", "blue"),
            badge("Java", java + "+", "yellow"),
            f"![Version](https://img.shields.io/github/v/release/{slug}?sort=semver)",
            badge("License", license_name, "brightgreen"),
        ]),
    ]
    return out


def render(platform: str) -> str:
    tagline, sections = readme_sections()
    root = read_properties(ROOT / "gradle.properties")
    issues = f"{root.get('mod_repo_url', '').rstrip('/')}/issues"

    picked: list[tuple[str, list[list[str]]]] = []
    for readme_title, store_title in MAP:
        if readme_title not in sections:
            raise SystemExit(
                f"README.md has no '{readme_title}' section — update MAP in tools/store_copy.py "
                f"when a README heading is renamed (otherwise the store body silently loses a section)")
        picked.append((store_title, sections[readme_title]))
    picked.append((FEEDBACK_TITLE, [[issues]]))

    out: list[str] = header_block()
    out += ["", f"_{tagline}_", ""]
    for title, blocks in picked:
        out += [f"## {title}", ""]
        for block in blocks:
            out += block
            out.append("")
    while out and not out[-1]:
        out.pop()
    return "\n".join(out) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", required=True, choices=["modrinth", "curseforge"])
    parser.add_argument("--out", type=Path, help="write to a file (default: stdout)")
    args = parser.parse_args()

    text = render(args.platform)
    if args.out:
        args.out.write_text(text, encoding="utf-8", newline="\n")
        print(f"store_copy: {args.platform} body -> {args.out} ({len(text.splitlines())} lines)",
              file=sys.stderr)
    else:
        sys.stdout.buffer.write(text.encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
