"""Read-only orphan inventory. Requires a quiesced database key export; never deletes files."""
import argparse
import json
import os
from pathlib import Path
import re

UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
KEY = re.compile(r"documents/" + UUID + "/" + UUID + r"\.(png|jpg|jpeg|pdf|txt)")
MARKER = ".devspace-document-storage"


def inventory(root, database, writers_stopped):
    if not writers_stopped:
        raise ValueError("Stop all writers and confirm original transactions have terminated first")
    if not root.is_absolute() or root.resolve() != root or root.is_symlink():
        raise ValueError("An absolute unlinked root is required")
    marker = root / MARKER
    if marker.is_symlink() or marker.resolve() != marker or marker.read_text().strip() != "devspace-document-storage-v1":
        raise ValueError("Invalid storage marker")
    if set(database) != {"live", "pending"}:
        raise ValueError("Database export must contain live and pending key arrays")
    protected = set()
    for group in database.values():
        if not isinstance(group, list) or any(not isinstance(k, str) or not KEY.fullmatch(k) for k in group):
            raise ValueError("Invalid database key export")
        protected.update(group)
    found, candidates, rejected = set(), [], []
    for directory, dirs, files in os.walk(root, followlinks=False):
        base = Path(directory)
        for name in list(dirs):
            path = base / name
            if path.is_symlink() or path.resolve() != path:
                rejected.append(path.relative_to(root).as_posix())
                dirs.remove(name)
            elif name.endswith(".claim"):
                candidates.append({"key": path.relative_to(root).as_posix(), "kind": "abandonedClaim"})
                dirs.remove(name)
        for name in files:
            path = base / name
            key = path.relative_to(root).as_posix()
            if path.is_symlink() or path.resolve() != path or not path.is_file():
                rejected.append(key)
            elif key == MARKER:
                continue
            elif KEY.fullmatch(key):
                found.add(key)
                if key not in protected:
                    candidates.append({"key": key, "kind": "unreferencedFinal"})
            elif key.startswith("staging/") and key.endswith(".part"):
                candidates.append({"key": key, "kind": "abandonedStaging"})
            else:
                rejected.append(key)
    return {"mode": "dry-run", "protectedFiles": len(found & protected),
            "candidates": sorted(candidates, key=lambda c: c["key"]),
            "missingReferencedKeys": sorted(protected - found), "rejectedPaths": sorted(rejected)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--database-inventory", type=Path, required=True)
    parser.add_argument("--writers-stopped", action="store_true")
    args = parser.parse_args()
    try:
        result = inventory(args.root, json.loads(args.database_inventory.read_text(encoding="utf-8-sig")), args.writers_stopped)
    except (ValueError, OSError) as failure:
        parser.exit(2, "Inventory refused: " + str(failure) + "\n")
    print(json.dumps(result, indent=2))
