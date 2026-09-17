import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import download_libras_gap_videos as downloader


def reviewed_entry(index=1, **changes):
    # Fictional fixtures only, never shipped as approved acquisition sources.
    entry = dict(
        id=f"fixture-{index}", word="por favor", source_name="Fictional dictionary",
        source_label="POR FAVOR", source_url=f"https://example.org/{index}.mp4",
        catalog_url="https://example.org/catalog", label_evidence_url="https://example.org/labels",
        permission_evidence_url="https://example.org/permission", format_evidence_url="https://example.org/protocol",
        license="test permission", conditions="fictional fixture, no real authorization",
        reviewed_by="test reviewer", reviewed_at="2026-09-11", review_status="approved",
        language="Libras", clip_type="isolated_sign", performer_type="human", label_verified=True,
        format_verified=True, download_permitted=True, training_permitted=True,
        allowed_usage_scopes=["research_noncommercial"],
    )
    return {**entry, **changes}


class DownloaderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / "sources.json"
        for name, value in (("OUT_BASE", self.root / "curated"),
                            ("MANIFEST", self.root / "curated" / "manifest.jsonl"),
                            ("CATALOG", self.catalog)):
            override = patch.object(downloader, name, value)
            override.start()
            self.addCleanup(override.stop)
        self.write_catalog([])
        tools = patch.object(downloader.shutil, "which", return_value="/bin/tool")
        tools.start()
        self.addCleanup(tools.stop)

    def write_catalog(self, entries):
        self.catalog.write_text(json.dumps({"schema_version": 1, "entries": entries}), encoding="utf-8")

    def run_main(self, *args):
        return downloader.main(["--words", "por favor", *args])

    def fake_download(self, word, url, out_dir, max_duration):
        out_dir.mkdir(parents=True, exist_ok=True)
        video = out_dir / "clip.mp4"
        video.write_bytes(url.encode())
        return str(video)

    def test_empty_catalog_never_contacts_network_and_reports_gap(self):
        with patch.object(downloader.yt_dlp, "YoutubeDL") as network:
            self.assertEqual(self.run_main(), 1)
            self.assertEqual(self.run_main("--download"), 1)
            network.assert_not_called()
        report = json.loads((downloader.OUT_BASE / "plan.json").read_text())
        self.assertEqual(report["words"][0]["missing"], 5)
        self.assertFalse(report["training_ready"])

    def test_plan_is_offline_even_with_eligible_sources(self):
        self.write_catalog([reviewed_entry()])
        with patch.object(downloader.yt_dlp, "YoutubeDL") as network:
            self.assertEqual(self.run_main("--per-word", "1"), 0)
            network.assert_not_called()
        self.assertFalse(downloader.MANIFEST.exists())

    def test_each_required_attestation_is_fail_closed(self):
        for field in reviewed_entry():
            with self.subTest(field=field):
                entry = reviewed_entry()
                del entry[field]
                self.assertTrue(downloader.eligibility_errors(entry, "research_noncommercial"))

    def test_ineligible_sources_never_reach_downloader(self):
        cases = [dict(review_status="pending"), dict(language="ASL"), dict(clip_type="sentence"),
                 dict(performer_type="avatar"), dict(training_permitted="true"),
                 dict(label_verified=False), dict(format_verified=False), dict(download_permitted=False),
                 dict(permission_evidence_url=""), dict(source_url="file:///tmp/video.mp4")]
        for changes in cases:
            with self.subTest(changes=changes):
                self.write_catalog([reviewed_entry(**changes)])
                with patch.object(downloader, "download_best_video") as download:
                    self.assertEqual(self.run_main("--download"), 1)
                    download.assert_not_called()

    def test_research_permission_does_not_allow_product(self):
        self.write_catalog([reviewed_entry()])
        eligible, rejected = downloader.load_catalog(self.catalog, "product")
        self.assertFalse(eligible)
        self.assertIn("permission does not cover product", rejected[0]["reasons"])

    def test_duplicate_urls_and_youtube_aliases_count_once(self):
        self.write_catalog([reviewed_entry(source_url="https://youtu.be/abcdefghijk"),
                            reviewed_entry(2, source_url="https://www.youtube.com/watch?v=abcdefghijk")])
        eligible, rejected = downloader.load_catalog(self.catalog, "research_noncommercial")
        self.assertEqual(len(eligible), 1)
        self.assertEqual(rejected[0]["reasons"], ["duplicate media URL"])

    def test_conflicting_labels_and_duplicate_ids_abort(self):
        for other in (reviewed_entry(2, word="ajudar", source_url=reviewed_entry()["source_url"]),
                      reviewed_entry(2, id="fixture-1")):
            self.write_catalog([reviewed_entry(), other])
            with self.assertRaises(ValueError):
                downloader.load_catalog(self.catalog, "research_noncommercial")

    def test_malformed_catalog_aborts_without_network(self):
        self.catalog.write_text("[]")
        with patch.object(downloader.yt_dlp, "YoutubeDL") as network:
            with self.assertRaises(SystemExit) as error:
                self.run_main("--download")
            self.assertEqual(error.exception.code, 2)
            network.assert_not_called()

    def test_five_target_and_resume_preserve_provenance(self):
        self.write_catalog([reviewed_entry(i) for i in range(6)])
        with patch.object(downloader, "download_best_video", side_effect=self.fake_download) as download, \
                patch.object(downloader, "valid_video", return_value=True):
            self.assertEqual(self.run_main("--download"), 0)
            self.assertEqual(download.call_count, 5)
            download.reset_mock()
            self.assertEqual(self.run_main("--download"), 0)
            download.assert_not_called()
        rows = [json.loads(line) for line in downloader.MANIFEST.read_text().splitlines()]
        self.assertEqual(len(rows), 5)
        self.assertIn("permission_evidence_url", rows[0]["catalog_entry"])
        self.assertEqual(rows[0]["sha256"], downloader.file_sha256(Path(rows[0]["download_path"])))
        self.assertFalse(rows[0]["training_ready"])

    def test_failure_continues_and_attempt_limit_holds(self):
        self.write_catalog([reviewed_entry(i) for i in range(3)])
        with patch.object(downloader, "download_best_video", return_value="") as download:
            self.assertEqual(self.run_main("--download", "--max-attempts", "2"), 1)
            self.assertEqual(download.call_count, 2)
        def fail_first(word, url, out_dir, max_duration):
            return "" if url.endswith("0.mp4") else self.fake_download(word, url, out_dir, max_duration)
        with patch.object(downloader, "download_best_video", side_effect=fail_first) as download:
            self.assertEqual(self.run_main("--download", "--per-word", "1"), 0)
            self.assertEqual(download.call_count, 2)

    def test_changed_bytes_or_attestations_are_not_reused(self):
        entry = reviewed_entry()
        self.write_catalog([entry])
        with patch.object(downloader, "download_best_video", side_effect=self.fake_download), \
                patch.object(downloader, "valid_video", return_value=True):
            self.assertEqual(self.run_main("--download", "--per-word", "1"), 0)
            row = json.loads(downloader.MANIFEST.read_text().splitlines()[0])
            self.assertFalse(downloader.completed_downloads([{**entry, "conditions": "changed"}], "research_noncommercial", 180))
            Path(row["download_path"]).write_bytes(b"changed")
            self.assertFalse(downloader.completed_downloads([entry], "research_noncommercial", 180))

    def test_legacy_downloads_do_not_count(self):
        legacy = self.root / "manifest.csv"
        legacy.write_text("word,status,download_path\npor favor,downloaded,/old.mp4\n")
        before = legacy.read_bytes()
        with patch.object(downloader, "download_best_video") as download:
            self.assertEqual(self.run_main("--download"), 1)
            download.assert_not_called()
        self.assertEqual(legacy.read_bytes(), before)

    def test_partial_and_empty_files_are_not_success(self):
        partial = self.root / "video.mp4.part"
        partial.write_bytes(b"unfinished")
        empty = self.root / "video.mp4"
        empty.touch()
        self.assertFalse(downloader.valid_video(partial))
        self.assertFalse(downloader.valid_video(empty))

    def test_ffprobe_requires_video_and_bounded_duration(self):
        video = self.root / "video.mp4"
        video.write_bytes(b"mock")
        with patch.object(downloader.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, '{"streams": [], "format": {"duration": "10"}}')
            self.assertFalse(downloader.valid_video(video))
            run.return_value.stdout = '{"streams": [{"codec_type": "video"}], "format": {"duration": "10"}}'
            self.assertTrue(downloader.valid_video(video))
            self.assertFalse(downloader.valid_video(video, 5))

    def test_failed_extraction_does_not_reuse_old_file(self):
        (self.root / "por-favor.mp4").write_bytes(b"old")
        with patch.object(downloader.yt_dlp, "YoutubeDL") as factory:
            factory.return_value.__enter__.return_value.extract_info.return_value = None
            self.assertEqual(downloader.download_best_video("por favor", "https://example.org/clip.mp4", self.root), "")

    def test_direct_media_and_split_streams_supported(self):
        with patch.object(downloader.yt_dlp, "YoutubeDL") as factory, \
                patch.object(downloader, "valid_video", return_value=True) as validate:
            ydl = factory.return_value.__enter__.return_value
            info = {"id": "clip", "duration": None}
            ydl.extract_info.return_value = info
            ydl.prepare_filename.return_value = str(self.root / "clip.webm")
            self.assertEqual(downloader.download_best_video("por favor", "https://example.org/clip.mp4", self.root), str(self.root / "clip.mp4"))
            self.assertIn("+ba", factory.call_args.args[0]["format"])
            ydl.process_info.assert_called_once_with(info)
            validate.assert_called_once_with(self.root / "clip.mp4", 180)


if __name__ == "__main__":
    unittest.main()