import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from zipfile import ZipFile

from botocore.exceptions import ClientError
import sync_release as sync


def missing():
    return ClientError({"Error": {"Code": "404"}}, "GetObject")


class ReleaseSyncTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "NextVol-0.1.1-release.apk"
        with ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
        self.digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.checksums = self.apk.with_name("SHA256SUMS")
        self.checksums.write_text(f"{self.digest}  {self.apk.name}\n")
        self.release = dict(draft=False, prerelease=False, tag_name="v0.1.1", body="## Notes",
                            assets=[dict(name=self.apk.name), dict(name="SHA256SUMS")])
        self.manifest = dict(versionCode=10300010, tag="v0.1.1", sha256=self.digest)
        self.store = Mock()
        self.store.get_object.side_effect = missing()
        self.heads = {}
        self.store.head_object.side_effect = self.head
        self.store.upload_file.side_effect = self.upload

    def head(self, **args):
        if args["Key"] not in self.heads:
            raise missing()
        return self.heads[args["Key"]]

    def upload(self, path, bucket, key, ExtraArgs):
        self.heads[key] = dict(ContentLength=Path(path).stat().st_size, Metadata=ExtraArgs["Metadata"])

    def publish(self):
        sync.publish(self.store, self.manifest, self.apk, self.checksums)

    def test_only_published_matching_app_releases_are_eligible(self):
        self.assertEqual(self.apk.name, sync.validate_release(self.release, "v0.1.1"))
        for updates in (dict(tag_name="v0.1.0"), dict(draft=True), dict(prerelease=True),
                        dict(assets=[dict(name="other.apk")])):
            release = self.release | updates
            with self.assertRaises(ValueError):
                sync.validate_release(release, "v0.1.1")

    def test_manifest_uses_verified_apk_identity_and_checksum(self):
        output = [f"Signer #1 certificate SHA-256 digest: {sync.CERTIFICATE}\n",
                  "package: name='indi.renakoni.nextvol' versionCode='10300010' versionName='0.1.1' platformBuildVersionName='x'\n"]
        with patch.object(sync.subprocess, "check_output", side_effect=output):
            manifest = sync.build_manifest(self.release, 'versionCode = 10_300_010\nversionName = "0.1.1"',
                                           self.apk, self.checksums, Path("tools"))
        self.assertEqual(self.digest, manifest["sha256"])
        self.assertEqual("## Notes", manifest["releaseNotes"])
        self.checksums.write_text("wrong hash")
        with self.assertRaises(ValueError):
            sync.build_manifest(self.release, 'versionCode = 10300010\nversionName = "0.1.1"',
                                self.apk, self.checksums, Path("tools"))

    def test_artifacts_finish_before_stable_pointer_and_retry_reuses_objects(self):
        self.publish()
        self.assertEqual("put_object", self.store.mock_calls[-1][0])
        args = self.store.put_object.call_args.kwargs
        self.assertEqual("updates/stable.json", args["Key"])
        self.assertEqual("no-store", args["CacheControl"])
        self.assertEqual(self.manifest, json.loads(args["Body"]))
        self.assertEqual(2, self.store.upload_file.call_count)
        self.publish()
        self.assertEqual(2, self.store.upload_file.call_count)

    def test_upload_failure_preserves_previous_stable_pointer(self):
        self.store.upload_file.side_effect = OSError("upload failed")
        with self.assertRaises(OSError):
            self.publish()
        self.store.put_object.assert_not_called()

    def test_runtime_bytes_match_signed_catalog_before_publish_and_retry(self):
        name, tag = "vendor.zip", "offline-runtime-5"
        digest = hashlib.sha256(b"component").hexdigest()
        spec = dict(url=f"https://github.com/{sync.REPOSITORY}/releases/download/{tag}/{name}", sha256=digest)
        with ZipFile(self.apk, "a") as archive:
            archive.writestr("assets/speech-runtimes/vendor.json", json.dumps(spec))
        self.manifest["sha256"] = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        release = dict(draft=False, assets=[dict(name=name, size=9, digest=f"sha256:{digest}")])
        path = self.apk.with_name(name)
        with patch.object(sync, "gh_api", return_value=release), patch.object(sync.subprocess, "run") as fetch:
            fetch.side_effect = lambda *args, **kwargs: path.write_bytes(b"incorrect")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                self.publish()
            self.store.put_object.assert_not_called()
            fetch.side_effect = lambda *args, **kwargs: path.write_bytes(b"component")
            self.publish()
            self.assertEqual(digest, self.heads[f"releases/{tag}/{name}"]["Metadata"]["sha256"])
            self.publish()
            self.assertEqual(2, fetch.call_count)

    def test_old_versions_and_changed_content_never_overwrite_current_release(self):
        for previous in (self.manifest | dict(versionCode=10300011), self.manifest | dict(sha256="different")):
            self.store.get_object.side_effect = None
            self.store.get_object.return_value = {"Body": io.BytesIO(json.dumps(previous).encode())}
            if previous["versionCode"] > self.manifest["versionCode"]:
                self.publish()
            else:
                with self.assertRaises(ValueError):
                    self.publish()
            self.store.upload_file.assert_not_called()
            self.store.put_object.assert_not_called()

    def test_immutable_artifact_mismatch_does_not_publish_metadata(self):
        self.heads[f"releases/v0.1.1/{self.apk.name}"] = dict(ContentLength=1, Metadata={"sha256": "wrong"})
        with self.assertRaises(ValueError):
            self.publish()
        self.store.put_object.assert_not_called()


if __name__ == "__main__":
    unittest.main()
