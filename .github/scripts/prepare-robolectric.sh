#!/usr/bin/env bash
set -euo pipefail

# Robolectric 4.16.1 DefaultSdkProvider, instrumentation version 7.
# APIs selected by app/src/test: 24, 27, 28, 30, 33, 34, 35.
# API 36 supplies Robolectric's compile-time Android resources as well.
versions=(
  7.0.0_r1-robolectric-r1-i7
  8.1.0-robolectric-4611349-i7
  9-robolectric-4913185-2-i7
  11-robolectric-6757853-i7
  13-robolectric-9030017-i7
  14-robolectric-10818077-i7
  15-robolectric-13954326-i7
  16-robolectric-13921718-i7
)
repository=https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented
destination="$HOME/.m2/repository/org/robolectric/android-all-instrumented"
staging=$(mktemp -d "${RUNNER_TEMP:?}/robolectric.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT

for version in "${versions[@]}"; do
  name="android-all-instrumented-$version"
  for extension in pom jar; do
    file="$name.$extension"
    curl --fail --location --silent --show-error --retry 2 --connect-timeout 20 --max-time 300 \
      --output "$staging/$file" "$repository/$version/$file"
    curl --fail --location --silent --show-error --retry 2 --connect-timeout 20 --max-time 60 \
      --output "$staging/$file.sha512" "$repository/$version/$file.sha512"
    printf '%s  %s\n' "$(cat "$staging/$file.sha512")" "$staging/$file" | sha512sum --check
  done
  mkdir -p "$destination/$version"
  mv -- "$staging/$name".* "$destination/$version/"
done
