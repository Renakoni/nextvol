"""Mirror published NextVol app releases; publish the stable pointer only after artifacts."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

REPOSITORY = "Renakoni/nextvol"
BUCKET = "nextvol-downloads"
PUBLIC_BASE = "https://download.nextvolcom.com"
ACCOUNT = "15407376b9dad9f8856a1961ecd9083a"
PACKAGE = "indi.renakoni.nextvol"
CERTIFICATE = "87b61dcf3eecca2b93acb8aae035ce016260508e09e205266912703523efacfc"
MANIFEST_KEY = "updates/stable.json"


def gh_api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True, encoding="utf-8", timeout=60))


def validate_release(release, tag):
    if not re.fullmatch(r"v[0-9]+[.][0-9]+[.][0-9]+", tag):
        raise ValueError("Expected a formal app tag vX.Y.Z")
    if release["draft"] or release["prerelease"] or release["tag_name"] != tag:
        raise ValueError("Only a published stable app release can be synchronized")
    apk_name = f"NextVol-{tag[1:]}-release.apk"
    apks = [asset["name"] for asset in release["assets"] if asset["name"].lower().endswith(".apk")]
    if apks != [apk_name] or not any(asset["name"] == "SHA256SUMS" for asset in release["assets"]):
        raise ValueError("Expected one universal NextVol APK and SHA256SUMS")
    return apk_name


def build_manifest(release, gradle, apk, checksums, tools):
    tag = release["tag_name"]
    validate_release(release, tag)
    version = re.search(r'^\s*versionName\s*=\s*"([^\"]+)"', gradle, re.M)[1]
    code = int(re.search(r"^\s*versionCode\s*=\s*([0-9_]+)", gradle, re.M)[1].replace("_", ""))
    if f"v{version}" != tag or not 0 < code <= 2100000000:
        raise ValueError("Release tag and Android version do not match")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    entries = checksums.read_text(encoding="utf-8").splitlines()
    if entries != [f"{digest}  {apk.name}"]:
        raise ValueError("APK does not match SHA256SUMS")
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    signature = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True)
    if f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}" not in signature.splitlines():
        raise ValueError("Unexpected APK signing certificate")
    aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8")
    identity = f"package: name='{PACKAGE}' versionCode='{code}' versionName='{version}'"
    if not any(line.startswith(identity + " ") or line == identity for line in badging.splitlines()):
        raise ValueError("APK package or version differs from the release")
    if "application-debuggable" in badging:
        raise ValueError("Debug APKs cannot be synchronized")
    return dict(schemaVersion=1, tag=tag, versionCode=code, versionName=version,
                packageName=PACKAGE, releaseNotes=release.get("body") or "",
                downloadUrl=f"{PUBLIC_BASE}/releases/{tag}/{apk.name}", sha256=digest)


def read_existing(store):
    from botocore.exceptions import ClientError
    try:
        return json.loads(store.get_object(Bucket=BUCKET, Key=MANIFEST_KEY)["Body"].read())
    except ClientError as error:
        if error.response["Error"]["Code"] in {"NoSuchKey", "404"}:
            return None
        raise


def publish(store, manifest, apk, checksums):
    existing = read_existing(store)
    if existing:
        if existing["versionCode"] > manifest["versionCode"]:
            print("A newer app version is already synchronized; stable metadata was preserved.")
            return
        if existing["versionCode"] == manifest["versionCode"] and (
                existing["tag"] != manifest["tag"] or existing["sha256"] != manifest["sha256"]):
            raise ValueError("Refusing to replace a published Android version with different content")
    # Immutable names and long cache lifetimes; metadata is always written last.
    from botocore.exceptions import ClientError
    for path, content_type in ((apk, "application/vnd.android.package-archive"), (checksums, "text/plain")):
        key = f"releases/{manifest['tag']}/{path.name}"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        try:
            previous = store.head_object(Bucket=BUCKET, Key=key)
        except ClientError as error:
            if error.response["Error"]["Code"] not in {"NoSuchKey", "404"}:
                raise
            previous = None
        if previous:
            if previous.get("Metadata", {}).get("sha256") != digest:
                raise ValueError("Refusing to overwrite an immutable release artifact")
        else:
            store.upload_file(str(path), BUCKET, key, ExtraArgs={
                "ContentType": content_type, "CacheControl": "public, max-age=31536000, immutable",
                "Metadata": {"sha256": digest},
            })
        stored = store.head_object(Bucket=BUCKET, Key=key)
        if stored["ContentLength"] != path.stat().st_size or stored.get("Metadata", {}).get("sha256") != digest:
            raise ValueError("Uploaded artifact verification failed")
    store.put_object(Bucket=BUCKET, Key=MANIFEST_KEY,
                     Body=(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode(),
                     ContentType="application/json; charset=utf-8", CacheControl="no-store")
    print(f"Synchronized {manifest['tag']}: {PUBLIC_BASE}/{MANIFEST_KEY}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--android-tools", type=Path, required=True)
    args = parser.parse_args()
    # Validate before using the tag in API paths or invoking a download.
    if not re.fullmatch(r"v[0-9]+[.][0-9]+[.][0-9]+", args.tag):
        parser.error("Expected vX.Y.Z")
    release = gh_api(f"repos/{REPOSITORY}/releases/tags/{args.tag}")
    name = validate_release(release, args.tag)
    encoded = gh_api(f"repos/{REPOSITORY}/contents/app/build.gradle.kts?ref={args.tag}")["content"]
    gradle = base64.b64decode(encoded).decode()
    with tempfile.TemporaryDirectory(prefix="nextvol-release-") as temporary:
        subprocess.run(["gh", "release", "download", args.tag, "--repo", REPOSITORY,
                        "--pattern", name, "--pattern", "SHA256SUMS", "--dir", temporary], check=True, timeout=300)
        apk, checksums = Path(temporary) / name, Path(temporary) / "SHA256SUMS"
        manifest = build_manifest(release, gradle, apk, checksums, args.android_tools)
        import boto3
        from botocore.config import Config
        # R2 uses the S3 protocol without AWS optional streaming checksums.
        # The release SHA-256 is verified independently before upload.
        config = Config(request_checksum_calculation="when_required", response_checksum_validation="when_required")
        store = boto3.client("s3", endpoint_url=f"https://{ACCOUNT}.r2.cloudflarestorage.com",
                             region_name="auto", config=config)
        publish(store, manifest, apk, checksums)


if __name__ == "__main__":
    main()
