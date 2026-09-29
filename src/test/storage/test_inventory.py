import importlib.util
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
sys.dont_write_bytecode = True

spec = importlib.util.spec_from_file_location("inventory", Path(__file__).resolve().parents[3] / "tools/document-storage-inventory.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class InventoryTests(unittest.TestCase):
    def test_protects_metadata_and_jobs_and_never_deletes_candidates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            (root / module.MARKER).write_text("devspace-document-storage-v1")
            prefix = "documents/00000000-0000-4000-8000-000000000001/"
            keys = [prefix + f"00000000-0000-4000-8000-00000000000{n}.txt" for n in (1, 2, 3)]
            for key in keys:
                path = root / key
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("keep bytes")
            result = module.inventory(root, {"live": [keys[0]], "pending": [keys[1]]}, True)
            self.assertEqual(2, result["protectedFiles"])
            self.assertEqual([{"key": keys[2], "kind": "unreferencedFinal"}], result["candidates"])
            self.assertTrue(all((root / key).read_text() == "keep bytes" for key in keys))
            with self.assertRaises(ValueError):
                module.inventory(root, {"live": [], "pending": []}, False)
            with self.assertRaises(ValueError):
                module.inventory(root, {"live": ["../outside"], "pending": []}, True)

    def test_does_not_follow_junction_or_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary).resolve()
            root, outside = base / "root", base / "outside"
            root.mkdir()
            outside.mkdir()
            (root / module.MARKER).write_text("devspace-document-storage-v1")
            (outside / "secret.txt").write_text("unchanged")
            link = root / "escape"
            if os.name == "nt":
                subprocess.run(["cmd.exe", "/c", "mklink", "/J", str(link), str(outside)], check=True, capture_output=True)
            else:
                link.symlink_to(outside, target_is_directory=True)
            try:
                result = module.inventory(root, {"live": [], "pending": []}, True)
                self.assertEqual(["escape"], result["rejectedPaths"])
                self.assertEqual([], result["candidates"])
                self.assertEqual("unchanged", (outside / "secret.txt").read_text())
            finally:
                os.rmdir(link) if os.name == "nt" else link.unlink()


if __name__ == "__main__":
    unittest.main()
