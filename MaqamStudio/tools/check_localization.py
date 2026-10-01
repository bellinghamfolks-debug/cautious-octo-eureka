#!/usr/bin/env python3
"""Checks Maqam Studio's Arabic and English text tables against each other and the code.

Fails when:
  * a key used in Swift is missing from either language,
  * the two tables have different keys,
  * a value is empty,
  * a key's placeholders differ between languages (count, or positional order),
  * a key is defined twice in one table.

Runs anywhere Python 3 does, so CI checks it before a Mac is involved.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "App"
TABLES = {lang: APP / "Resources" / f"{lang}.lproj" / "Localizable.strings" for lang in ("ar", "en")}

ENTRY = re.compile(r'^\s*"((?:[^"\\]|\\.)+)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;\s*$')
PLACEHOLDER = re.compile(r"%(?:(\d+)\$)?([@dfs])")
# Keys reach l10n either directly, or as a stored key (titleKey, actionKey).
USES = [
    re.compile(r'l10n\(\s*"([a-z0-9_.]+)"'),
    re.compile(r'actionKey:\s*"([a-z0-9_.]+)"'),
    re.compile(r'return\s+"((?:error|action|loudness)\.[a-z0-9_.]+)"'),
]
# Keys built at run time; each prefix maps to the identifiers the code can produce.
JINS = re.compile(r'"(jins [a-z ]+)"')


def parse(path: pathlib.Path) -> tuple[dict[str, str], list[str]]:
    entries: dict[str, str] = {}
    problems: list[str] = []
    text = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        match = ENTRY.match(line)
        if not match:
            problems.append(f"{path.name}:{number}: unparsable line: {line.strip()}")
            continue
        key, value = match.groups()
        if key in entries:
            problems.append(f"{path.parent.name}: duplicate key {key}")
        entries[key] = value
    return entries, problems


def placeholders(value: str) -> list[str]:
    found = PLACEHOLDER.findall(value)
    if any(position for position, _ in found):
        return sorted(f"{position}{kind}" for position, kind in found)
    return [f"{index + 1}{kind}" for index, (_, kind) in enumerate(found)]


def used_keys() -> set[str]:
    keys: set[str] = set()
    for swift in APP.rglob("*.swift"):
        source = swift.read_text(encoding="utf-8")
        for pattern in USES:
            keys.update(pattern.findall(source))
    core = (ROOT / "Core" / "src" / "maqam.cpp").read_text(encoding="utf-8")
    for jins in set(JINS.findall(core)):
        keys.add("jins." + jins.removeprefix("jins ").replace(" ", "_"))
    return keys


def main() -> int:
    problems: list[str] = []
    tables = {}
    for lang, path in TABLES.items():
        entries, issues = parse(path)
        tables[lang] = entries
        problems += issues
    ar, en = tables["ar"], tables["en"]
    for key in sorted(set(ar) - set(en)):
        problems.append(f"en: missing {key}")
    for key in sorted(set(en) - set(ar)):
        problems.append(f"ar: missing {key}")
    for lang, entries in tables.items():
        for key, value in entries.items():
            if not value.strip():
                problems.append(f"{lang}: empty value for {key}")
    for key in sorted(set(ar) & set(en)):
        if placeholders(ar[key]) != placeholders(en[key]):
            problems.append(f"placeholders differ for {key}: ar={ar[key]!r} en={en[key]!r}")
    for key in sorted(used_keys()):
        for lang, entries in tables.items():
            if key not in entries:
                problems.append(f"{lang}: key used in code but not defined: {key}")
    if problems:
        print("\n".join(problems))
        return 1
    print(f"Localization OK: {len(ar)} keys in each language, {len(used_keys())} referenced from code.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
