#!/usr/bin/env python3
"""Fail CI when tracked runtime credentials or unsafe archive members appear."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tarfile
import zipfile
from pathlib import Path, PurePosixPath

ARCHIVE_SUFFIXES = (".zip", ".tar", ".tar.gz", ".tgz", ".7z", ".rar")
SENSITIVE_SUFFIXES = {
    ".pid",
    ".dmp",
    ".dump",
    ".dumpstream",
    ".jks",
    ".keystore",
    ".p12",
    ".pfx",
    ".pem",
    ".key",
}
TEXT_SUFFIXES = {
    ".txt",
    ".md",
    ".json",
    ".jsonc",
    ".yaml",
    ".yml",
    ".properties",
    ".xml",
    ".env",
    ".js",
    ".ts",
    ".java",
    ".kt",
    ".kts",
    ".sh",
    ".ps1",
    ".cmd",
    ".conf",
    ".toml",
}
STRONG_SECRET_PATTERNS = {
    "private key": re.compile(
        rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
        rb"\s*[A-Za-z0-9+/=\r\n]{64,}\s*"
        rb"-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
    ),
    "GitHub token": re.compile(rb"\bgh[pousr]_[A-Za-z0-9]{36,}\b"),
    "AWS access key": re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "Slack token": re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
}
TOKEN_FILE = re.compile(
    r"(^|[._-])(?:access[._-]?token|refresh[._-]?token|token)([._-]|$)",
    re.IGNORECASE,
)


def tracked_files() -> list[str]:
    output = subprocess.check_output(["git", "ls-files", "-z"])
    return [
        item.decode("utf-8", errors="surrogateescape")
        for item in output.split(b"\0")
        if item
    ]


def archive_kind(path: str) -> str | None:
    lowered = path.lower()
    return next((suffix for suffix in ARCHIVE_SUFFIXES if lowered.endswith(suffix)), None)


def sensitive_path(path: str) -> str | None:
    normalized = path.replace("\\", "/")
    pure = PurePosixPath(normalized)
    name = pure.name
    lowered = name.lower()
    suffix = pure.suffix.lower()

    if suffix in SENSITIVE_SUFFIXES:
        return f"sensitive extension {suffix}"
    if TOKEN_FILE.search(lowered):
        return "token-like runtime file"
    if any(part.lower() in {"runtime", "dumps", "crash-dumps"} for part in pure.parts):
        return "runtime/dump directory"
    return None


def scan_bytes(label: str, data: bytes, findings: list[str]) -> None:
    for description, pattern in STRONG_SECRET_PATTERNS.items():
        if pattern.search(data):
            findings.append(f"{label}: contains a {description}")


def inspect_member(
    archive: Path,
    member_name: str,
    size: int,
    read_member,
    findings: list[str],
) -> None:
    reason = sensitive_path(member_name)
    if reason:
        findings.append(f"{archive.as_posix()}!{member_name}: {reason}")

    suffix = PurePosixPath(member_name).suffix.lower()
    if size <= 2 * 1024 * 1024 and suffix in TEXT_SUFFIXES:
        try:
            scan_bytes(f"{archive.as_posix()}!{member_name}", read_member(), findings)
        except (OSError, RuntimeError, zipfile.BadZipFile, tarfile.TarError) as exc:
            findings.append(f"{archive.as_posix()}!{member_name}: could not scan ({exc})")


def inspect_archive(path: Path, findings: list[str]) -> str:
    lowered = path.name.lower()
    if lowered.endswith(".zip"):
        try:
            with zipfile.ZipFile(path) as archive:
                for member in archive.infolist():
                    if member.is_dir():
                        continue
                    inspect_member(
                        path,
                        member.filename,
                        member.file_size,
                        lambda member=member: archive.read(member),
                        findings,
                    )
            return "inspected ZIP members"
        except (OSError, zipfile.BadZipFile) as exc:
            findings.append(f"{path.as_posix()}: invalid ZIP ({exc})")
            return "invalid ZIP"

    if lowered.endswith((".tar", ".tar.gz", ".tgz")):
        try:
            with tarfile.open(path, mode="r:*") as archive:
                for member in archive.getmembers():
                    if not member.isfile():
                        continue
                    inspect_member(
                        path,
                        member.name,
                        member.size,
                        lambda member=member: archive.extractfile(member).read(),  # type: ignore[union-attr]
                        findings,
                    )
            return "inspected TAR members"
        except (OSError, tarfile.TarError) as exc:
            findings.append(f"{path.as_posix()}: invalid TAR ({exc})")
            return "invalid TAR"

    findings.append(f"{path.as_posix()}: archive format cannot be inspected safely in standard CI")
    return "unsupported archive format"


def append_summary(archives: list[tuple[str, str]], findings: list[str]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write("## Tracked archive and runtime audit\n\n")
        for path, status in archives:
            summary.write(f"- `{path}` — {status}\n")
        if findings:
            summary.write("\n### Blocking findings\n\n")
            for finding in findings:
                summary.write(f"- {finding}\n")


def main() -> int:
    findings: list[str] = []
    archives: list[tuple[str, str]] = []

    for tracked in tracked_files():
        path = Path(tracked)
        reason = sensitive_path(tracked)
        if reason:
            findings.append(f"{path.as_posix()}: {reason}")

        if archive_kind(tracked):
            status = inspect_archive(path, findings)
            archives.append((path.as_posix(), status))

    print(f"Tracked archives: {len(archives)}")
    for archive, status in archives:
        print(f"  {archive} ({status})")
    append_summary(archives, findings)

    if findings:
        print("\nBlocking tracked-file findings:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        return 1

    print("No tracked PID, dump, token, keystore, private key, or strong archived secret was found.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
