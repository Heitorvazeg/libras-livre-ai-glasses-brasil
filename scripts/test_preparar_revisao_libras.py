import csv
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import preparar_revisao_libras as review


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.audit = self.base / "triagem.csv"
        self.output = self.base / "revisao-consultor"
        self.rows = [dict(zip(review.FIELDS, ["nome", "abcdefghijk", "5", "canal", "Nome", "nenhuma",
                                            "candidato", "", ""]))]
        self.write_audit()
        folder = self.base / "nome"
        folder.mkdir()
        self.video = folder / "nome-abcdefghijk.mp4"
        self.video.write_bytes(b"fixture")
        probe = patch.object(review, "inspect_video", return_value={"duration": 5.02, "width": 640, "height": 480})
        probe.start()
        self.addCleanup(probe.stop)

    def write_audit(self):
        with self.audit.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=review.FIELDS)
            writer.writeheader()
            writer.writerows(self.rows)

    def build(self):
        return review.build_queue(self.base, self.audit, self.output)

    def test_selection_is_from_audit_not_duration(self):
        self.rows.append({**self.rows[0], "id": "12345678901", "dur_s": "1", "veredito": "descartar", "revisao_manual": "rótulo errado"})
        self.write_audit()
        queue = self.build()
        self.assertEqual(queue["summary"]["candidates"], 1)
        self.assertEqual(queue["exclusions"], [self.rows[1]])
        self.assertEqual(queue["candidates"][0]["permission_status"], "pendente")
        self.assertFalse(queue["training_ready"])

    def test_originals_unchanged_and_stable_fingerprint(self):
        before = self.audit.read_bytes(), self.video.read_bytes()
        first, second = self.build(), self.build()
        review.write_queue(first, self.output)
        self.assertEqual(first["queue_id"], second["queue_id"])
        self.assertEqual(before, (self.audit.read_bytes(), self.video.read_bytes()))
        self.assertEqual(first["candidates"][0]["sha256"], review.digest(self.video))
        self.assertEqual(json.loads((self.output / "fila.json").read_text())["queue_id"], first["queue_id"])
        self.video.write_bytes(b"other")
        self.assertNotEqual(first["queue_id"], self.build()["queue_id"])

    def test_missing_file_is_not_silently_removed(self):
        self.video.unlink()
        queue = self.build()
        self.assertEqual(queue["summary"]["candidates"], 1)
        self.assertEqual(queue["summary"]["files_ready"], 0)
        self.assertIsNotNone(queue["candidates"][0]["file_issue"])

    def test_ambiguous_file_is_blocked(self):
        self.video.with_suffix(".webm").write_bytes(b"other")
        self.assertIn("encontrados 2", self.build()["candidates"][0]["file_issue"])

    def test_duplicate_rows_and_path_traversal_rejected(self):
        self.rows.append(dict(self.rows[0]))
        self.write_audit()
        with self.assertRaises(ValueError):
            self.build()
        self.rows = [{**self.rows[0], "palavra": "../nome"}]
        self.write_audit()
        with self.assertRaises(ValueError):
            self.build()

    def test_content_duplicates_flagged_not_approved(self):
        self.rows.append({**self.rows[0], "id": "12345678901"})
        self.write_audit()
        (self.video.parent / "nome-12345678901.mp4").write_bytes(b"fixture")
        items = self.build()["candidates"]
        self.assertEqual(items[0]["identical_files"], [items[1]["key"]])
        self.assertEqual(items[0]["linguistic_status"], "pendente")

    def test_untrusted_title_cannot_escape_inline_json(self):
        self.rows[0]["titulo"] = '</script><script>alert("x")</script>'
        self.write_audit()
        review.write_queue(self.build(), self.output)
        html = (self.output / "index.html").read_text()
        self.assertNotIn(self.rows[0]["titulo"], html)
        self.assertIn("\\u003c/script>", html)
        self.assertNotIn("/* QUEUE_DATA */", html)

    def test_output_cannot_replace_source(self):
        with self.assertRaises(ValueError):
            review.build_queue(self.base, self.audit, self.base)


if __name__ == "__main__":
    unittest.main()