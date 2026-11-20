#!/usr/bin/env python3
import argparse
import json
import re
import subprocess
import sys
from collections import OrderedDict

# https://github.com/unjs/changelogen/blob/main/src/types.ts
SECTIONS = [
    ("feat", "Enhancements"),
    ("fix", "Fixes"),
    ("perf", "Performance"),
    ("refactor", "Refactors"),
    ("docs", "Documentation"),
    ("build", "Build"),
    ("types", "Types"),
    ("chore", "Chores"),
    ("examples", "Examples"),
    ("test", "Tests"),
    ("style", "Styles"),
    ("ci", "CI"),
]

OTHERS = "Others"
COMMIT = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?P<breaking>!)?:\s*(?P<summary>.+)$"
)


def git(*args):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit("git " + " ".join(args) + " failed: " + result.stderr.strip())
    return result.stdout


def repository():
    url = git("remote", "get-url", "origin").strip()
    for prefix in ("https://github.com/", "git@github.com:"):
        if url.startswith(prefix):
            return url[len(prefix) :].removesuffix(".git")
    return None


def is_prerelease(tag):
    return tag is not None and "-" in tag.removeprefix("v")


def base_tag(tag, end):
    tags = git("tag", "--merged", end, "--sort=-creatordate").split()
    stable = not is_prerelease(tag)
    for candidate in tags:
        if candidate == tag:
            continue
        if stable and is_prerelease(candidate):
            continue
        return candidate
    return None


def from_git(revision):
    out = git("log", "--no-merges", "--pretty=%h%x09%H%x09%an%x09%ae%x09%s", revision)
    for line in out.splitlines():
        parts = line.split("\t", 4)
        if len(parts) == 5:
            yield parts[0], parts[1], parts[2], parts[3], parts[4]


def from_github(repo, start, end):
    result = subprocess.run(
        [
            "gh",
            "api",
            "repos/" + repo + "/compare/" + start + "..." + end + "?per_page=250",
        ],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError:
        return None
    out = []
    for entry in data.get("commits", []):
        sha = entry.get("sha", "")
        message = entry.get("commit", {}).get("message", "").split("\n")[0]
        author = entry.get("author") or {}
        person = entry.get("commit", {}).get("author") or {}
        out.append(
            (
                sha[:7],
                sha,
                person.get("name") or "?",
                person.get("email") or "",
                message,
                author.get("login"),
            )
        )
    return out


def classify(subject):
    match = COMMIT.match(subject.strip())
    if not match:
        return OTHERS, subject.strip()
    kind = match.group("type").lower()
    if kind not in [key for key, _ in SECTIONS]:
        return OTHERS, subject.strip()
    summary = match.group("summary").strip()
    if match.group("scope"):
        summary = "**" + match.group("scope") + ":** " + summary
    if match.group("breaking"):
        summary = summary + " (BREAKING)"
    return kind, summary


def render(rows, repo):
    found = OrderedDict((key, []) for key, _ in SECTIONS)
    found[OTHERS] = []
    people = OrderedDict()
    for short, full, name, email, subject, login in rows:
        key, text = classify(subject)
        link = (
            "([" + short + "](https://github.com/" + repo + "/commit/" + full + "))"
            if repo
            else "(" + short + ")"
        )
        found[key].append("- " + text + " " + link)
        who = "@" + login if login else name
        seen = login or email or name
        people[seen] = who

    lines = ["## Changelog", ""]
    for key, title in SECTIONS:
        if found[key]:
            lines.append("### " + title + ":")
            lines.extend(found[key])
            lines.append("")
    if found[OTHERS]:
        lines.append("### " + OTHERS + ":")
        lines.extend(found[OTHERS])
        lines.append("")
    if people:
        lines.append("### Contributors:")
        for who in sorted(people.values(), key=lambda w: w.lower()):
            lines.append("- " + who)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main():
    parser = argparse.ArgumentParser(
        description="Generate a Markdown changelog for a GitHub release.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--tag",
        metavar="TAG",
        help="release tag used to select the target revision and base tag.",
    )
    parser.add_argument(
        "--from",
        dest="start",
        metavar="REVISION",
        help="starting revision (exclusive). Overrides automatic base tag detection.",
    )
    parser.add_argument(
        "--to",
        dest="end",
        metavar="REVISION",
        default="HEAD",
        help="ending revision (inclusive). Defaults to HEAD.",
    )
    parser.add_argument(
        "--repo",
        metavar="OWNER/NAME",
        help="GitHub repository for commit links and contributor attribution. Defaults to the origin remote.",
    )
    parser.add_argument(
        "--no-github",
        action="store_true",
        help="skip GitHub API requests and use local Git commit metadata.",
    )

    args = parser.parse_args()

    repo = args.repo or repository()
    end = args.tag if _exists(args.tag) else args.end
    start = args.start or base_tag(args.tag, end)
    revision = (start + ".." + end) if start else end

    rows = None
    if repo and start and not args.no_github:
        rows = from_github(repo, start, end)
    if rows is None:
        rows = [(s, f, n, e, sub, None) for s, f, n, e, sub in from_git(revision)]

    print(render(rows, repo), end="")


def _exists(tag):
    if tag is None:
        return False
    return (
        subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", "refs/tags/" + tag],
            capture_output=True,
        ).returncode
        == 0
    )


if __name__ == "__main__":
    main()