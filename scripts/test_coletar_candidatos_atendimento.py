import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import coletar_candidatos_atendimento as collector


class CandidateTests(unittest.TestCase):
    def test_plan_never_searches(self):
        with patch.object(collector.yt_dlp, "YoutubeDL", side_effect=AssertionError("network")):
            self.assertEqual(collector.main([]), 0)

    def test_duration_boundaries(self):
        for value, band in [(None, "unknown"), (True, "unknown"), (float("nan"), "unknown"),
                            (0, "unknown"), (6, "short"), (6.01, "medium"),
                            (20, "medium"), (20.01, "long")]:
            self.assertEqual(collector.duration_band(value), band)

    def test_titles_are_not_approval(self):
        self.assertEqual(collector.title_gate("ruim", "Calmo/Nervoso em Libras")[0], "unmatched_title")
        self.assertEqual(collector.title_gate("ruim", "Bom e ruim em Libras")[0], "ambiguous_title_separate_review")
        self.assertEqual(collector.title_gate("dor", "Frases em Libras: dor")[0], "phrase_or_compilation")
        status, flags = collector.title_gate("senha", 'Sinal-termo "Senha" - Glossário Libras EaD')
        self.assertEqual(status, "eligible_metadata")
        self.assertIn("confirmar_sentido_de_atendimento", flags)
        self.assertIn("termo_auxiliar_nao_equivalencia_confirmada",
                      collector.title_gate("precisar", "Necessidade em Libras")[1])

    def test_inventory_keeps_exclusions_and_prior_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "triagem.csv").write_text("id,canal,veredito\nabcdefghijk,Channel,descartar\n")
            manifest = base / "candidatos" / "previous" / "manifest.jsonl"
            collector.append(manifest, {"id": "zyxwvutsrqp", "status": "failed"})
            ids, hashes, _ = collector.inventory(base)
            self.assertEqual(ids, {"abcdefghijk", "zyxwvutsrqp"})
            self.assertEqual(hashes, {})

    def test_compound_and_directional_titles_are_separate(self):
        for word, title in [("dor", "Sinalário da DAIN | Dor de Cabeça | #146"),
                            ("buscar", "Lupa buscar"), ("perguntar", "PERGUNTAR-ME (VERBO EM LIBRAS)")]:
            self.assertEqual(collector.title_gate(word, title)[0], "compound_or_other_sense_separate_review")
        self.assertEqual(collector.title_gate("repetir", "Sinal: REPETIR, DE NOVO, OUTRA VEZ.")[0],
                         "ambiguous_title_separate_review")
        for word, title in [("consulta", 'Sinal de "consulta" ou "curiosidade" em Libras'),
                            ("senha", "Segredo ou senha")]:
            self.assertEqual(collector.title_gate(word, title)[0], "ambiguous_title_separate_review")

    def test_correction_preserves_event_but_removes_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            collector.append(output / "manifest.jsonl", {"id": "abcdefghijk", "word": "dor",
                             "title": "Dor de cabeça", "status": "candidate_downloaded"})
            collector.refresh_selection(output)
            self.assertEqual(len(collector.rows(output / "manifest.jsonl")), 2)
            self.assertEqual(collector.completed(output), [])
            self.assertEqual(collector.latest_records(output)["abcdefghijk"]["status"], "metadata_rejected")

    def test_search_bounds_and_cache(self):
        class FakeDL:
            calls = 0
            def __init__(self, options):
                self.options = options
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass
            def extract_info(self, query, download):
                self.assert_query = query
                FakeDL.calls += 1
                assert query.startswith("ytsearch10:") and download is False
                return {"entries": [
                    {"id": "abcdefghijk", "title": "Ruim Libras", "duration": 5},
                    {"id": "zyxwvutsrqp", "title": "Ruim Libras", "duration": 15},
                    {"id": "ABCDEFGHIJK", "title": "Ruim Libras", "duration": 21},
                ]}
        with tempfile.TemporaryDirectory() as tmp, patch.object(collector.yt_dlp, "YoutubeDL", FakeDL):
            output = Path(tmp)
            result = collector.search("ruim", output, {"abcdefghijk"}, collector.Counter())
            self.assertEqual([r["id"] for r in result], ["zyxwvutsrqp"])
            self.assertEqual(FakeDL.calls, 4)
            collector.search("ruim", output, {"abcdefghijk"}, collector.Counter())
            self.assertEqual(FakeDL.calls, 4)

    def test_changed_media_blocks_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            collector.append(output / "manifest.jsonl", {"status": "candidate_downloaded", "id": "abcdefghijk",
                             "download_path": "missing.mp4", "sha256": "not_matching"})
            with self.assertRaises(ValueError):
                collector.completed(output)

    def test_unknown_metadata_never_downloads(self):
        from unittest.mock import MagicMock
        with tempfile.TemporaryDirectory() as tmp:
            dl = MagicMock()
            dl.__enter__.return_value = dl
            dl.extract_info.return_value = {"title": "Ruim Libras", "duration": None}
            with patch.object(collector.yt_dlp, "YoutubeDL", return_value=dl):
                result = collector.download({"word": "ruim", "id": "abcdefghijk", "source_url": "https://www.youtube.com/watch?v=abcdefghijk"}, Path(tmp), {})
            dl.process_info.assert_not_called()
            self.assertEqual(result["status"], "failed")
            self.assertFalse(result["training_ready"])
            self.assertEqual(result["permission_status"], "pendente")


if __name__ == "__main__":
    unittest.main()