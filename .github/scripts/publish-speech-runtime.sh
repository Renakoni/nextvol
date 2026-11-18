#!/usr/bin/env bash
set -euo pipefail

archive=app/build/outputs/speech-runtimes/gpt-sovits-arm64.zip
catalog=app/build/speech-runtime-assets/speech-runtimes/sovits.json
sha=$(jq -er .sha256 "$catalog")
tag=$(jq -er '.url | split("/")[-2]' "$catalog")
repo=${GITHUB_REPOSITORY:?}
asset_digest() {
  gh api "repos/$repo/releases/tags/$tag" --jq \
    '.assets[] | select(.name == "gpt-sovits-arm64.zip") | .digest'
}

if ! actual=$(asset_digest); then
  gh release create "$tag" --repo "$repo" --target "${GITHUB_SHA:?}" \
    --prerelease --latest=false --title "GPT-SoVITS arm64 runtime ${sha:0:12}" \
    --notes "NextVol 听书设置按需下载的 GPT-SoVITS V2 Pro / V2 Pro Plus 运行库。" ||
    gh release view "$tag" --repo "$repo" >/dev/null
  actual=$(asset_digest)
fi
if [[ -z "$actual" ]]; then
  # A retry can finish an empty release. Concurrent publishers can only attach the same digest.
  gh release upload "$tag" "$archive" --repo "$repo" || [[ "$(asset_digest)" == "sha256:$sha" ]]
  actual=$(asset_digest)
fi
[[ "$actual" == "sha256:$sha" ]]
