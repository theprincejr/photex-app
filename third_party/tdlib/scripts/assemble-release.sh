#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <parts dir> <dist dir>" >&2
  exit 2
fi

parts_dir=$(cd "$1" && pwd)
dist_dir=$2
script_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(dirname "$script_dir")

set -a
# shellcheck source=/dev/null
source "$repo_dir/tdlib.lock"
set +a

die() {
  echo "assemble-release: $*" >&2
  exit 1
}

if [ -e "$dist_dir" ] && [ -n "$(ls -A "$dist_dir")" ]; then
  die "$dist_dir exists and is not empty"
fi
mkdir -p "$dist_dir"
dist_dir=$(cd "$dist_dir" && pwd)

work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
release_dir="$work_dir/release"
symbols_dir="$work_dir/symbols"
java_rel="java/org/drinkless/tdlib"
mkdir -p "$release_dir/$java_rel" "$release_dir/jniLibs" "$release_dir/LICENSES" "$symbols_dir"

reference_abi=""
for abi in $ABIS; do
  part="$parts_dir/tdlib-$abi"
  [ -d "$part" ] || die "missing part for $abi: $part"

  for required in "libs/$abi/libtdjni.so" "$java_rel/Client.java" "$java_rel/TdApi.java" tdlib-debug.zip \
      "gates-$abi.txt" licenses/TDLib-LICENSE_1_0.txt licenses/OpenSSL-LICENSE.txt licenses/zlib-LICENSE.txt; do
    [ -f "$part/$required" ] || die "$abi: missing $required"
  done

  libs_entries=$(ls -A "$part/libs")
  [ "$libs_entries" = "$abi" ] || die "$abi: libs/ holds unexpected entries: $(echo "$libs_entries" | tr '\n' ' ')"
  abi_entries=$(ls -A "$part/libs/$abi")
  [ "$abi_entries" = "libtdjni.so" ] || die "$abi: libs/$abi holds unexpected files: $(echo "$abi_entries" | tr '\n' ' ')"

  grep -qx "RESULT PASS" "$part/gates-$abi.txt" || die "$abi: gates did not pass (see gates-$abi.txt)"
  if grep -q "^FAIL" "$part/gates-$abi.txt"; then
    die "$abi: gates file contains a FAIL line"
  fi

  if [ -z "$reference_abi" ]; then
    reference_abi=$abi
    cp "$part/$java_rel/Client.java" "$part/$java_rel/TdApi.java" "$release_dir/$java_rel/"
    cp "$part/licenses/TDLib-LICENSE_1_0.txt" "$part/licenses/OpenSSL-LICENSE.txt" \
      "$part/licenses/zlib-LICENSE.txt" "$release_dir/LICENSES/"
  else
    reference="$parts_dir/tdlib-$reference_abi"
    for same in "$java_rel/Client.java" "$java_rel/TdApi.java" licenses/TDLib-LICENSE_1_0.txt \
        licenses/OpenSSL-LICENSE.txt licenses/zlib-LICENSE.txt; do
      expected=$(sha256sum "$reference/$same" | cut -d' ' -f1)
      actual=$(sha256sum "$part/$same" | cut -d' ' -f1)
      [ "$expected" = "$actual" ] || die "$same differs between $reference_abi ($expected) and $abi ($actual)"
    done
  fi

  mkdir -p "$release_dir/jniLibs/$abi"
  cp "$part/libs/$abi/libtdjni.so" "$release_dir/jniLibs/$abi/libtdjni.so"
  cp "$part/gates-$abi.txt" "$release_dir/gates-$abi.txt"

  debug_dir="$work_dir/debug-$abi"
  python3 -m zipfile -e "$part/tdlib-debug.zip" "$debug_dir"
  [ -f "$debug_dir/tdlib/libs/$abi/libtdjni.so.debug" ] || die "$abi: tdlib-debug.zip has no libtdjni.so.debug"
  mkdir -p "$symbols_dir/$abi"
  cp "$debug_dir/tdlib/libs/$abi/libtdjni.so.debug" "$symbols_dir/$abi/libtdjni.so.debug"
done

[ -n "$reference_abi" ] || die "ABIS in tdlib.lock is empty"

cp "$repo_dir/THIRD_PARTY.md" "$release_dir/LICENSES/README.md"
cp "$repo_dir/tdlib.lock" "$release_dir/tdlib.lock"

python3 - "$release_dir" <<'PY'
import hashlib
import json
import os
import pathlib
import sys

release = pathlib.Path(sys.argv[1])
abis = os.environ["ABIS"].split()
java = release / "java/org/drinkless/tdlib"


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


info = {
    "tdlib_version": os.environ["TDLIB_VERSION"],
    "tdlib_commit": os.environ["TDLIB_COMMIT"],
    "release_tag": os.environ["RELEASE_TAG"],
    "android_ndk_version": os.environ["ANDROID_NDK_VERSION"],
    "android_api_level": int(os.environ["ANDROID_API_LEVEL"]),
    "android_stl": os.environ["ANDROID_STL"],
    "tdlib_interface": os.environ["TDLIB_INTERFACE"],
    "abis": abis,
    "openssl": {
        "version": os.environ["OPENSSL_VERSION"],
        "url": os.environ["OPENSSL_URL"],
        "sha256": os.environ["OPENSSL_SHA256"],
    },
    "base_image": os.environ["BASE_IMAGE"],
    "apt_snapshot": os.environ["APT_SNAPSHOT"],
    "assemble_image": os.environ["ASSEMBLE_IMAGE"],
    "stripped_size_bytes": {abi: (release / "jniLibs" / abi / "libtdjni.so").stat().st_size for abi in abis},
    "sha256": {
        "java/org/drinkless/tdlib/Client.java": sha256(java / "Client.java"),
        "java/org/drinkless/tdlib/TdApi.java": sha256(java / "TdApi.java"),
        **{f"jniLibs/{abi}/libtdjni.so": sha256(release / "jniLibs" / abi / "libtdjni.so") for abi in abis},
    },
}
(release / "BUILD-INFO.json").write_text(json.dumps(info, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

release_zip="tdlib-android-${RELEASE_TAG}.zip"
symbols_zip="tdlib-android-symbols-${RELEASE_TAG}.zip"
python3 "$script_dir/make-zip.py" "$dist_dir/$release_zip" "$release_dir"
python3 "$script_dir/make-zip.py" "$dist_dir/$symbols_zip" "$symbols_dir"

(
  cd "$dist_dir"
  sha256sum "$release_zip" "$symbols_zip" > SHA256SUMS
  sha256sum -c SHA256SUMS
)

REPO_SLUG="${GITHUB_REPOSITORY:-theprincejr/photex-app}"
{
  echo "# ${RELEASE_TAG}"
  echo
  echo "First-party PhotX build of TDLib ${TDLIB_VERSION} from tdlib/td commit \`${TDLIB_COMMIT}\` for Android."
  echo
  echo "- ABIs: ${ABIS}"
  echo "- Android API level ${ANDROID_API_LEVEL}, NDK ${ANDROID_NDK_VERSION}, ${ANDROID_STL}, ${TDLIB_INTERFACE} interface"
  echo "- ${OPENSSL_VERSION} (release tarball sha256 \`${OPENSSL_SHA256}\`)"
  echo "- Base image \`${BASE_IMAGE}\`, apt snapshot \`${APT_SNAPSHOT}\`"
  echo "- 16 KB page alignment verified on 64-bit libraries; TDLib commit embedded (\`gates-*.txt\`)"
  echo
  echo "## Assets"
  echo
  echo "- \`${release_zip}\`: java/, jniLibs/<abi>/libtdjni.so, LICENSES/, tdlib.lock, BUILD-INFO.json, gates-*.txt"
  echo "- \`${symbols_zip}\`: <abi>/libtdjni.so.debug (native debug symbols)"
  echo
  echo "## SHA256SUMS"
  echo
  echo '```'
  cat "$dist_dir/SHA256SUMS"
  echo '```'
  echo
  echo "## Verify"
  echo
  echo '```'
  echo "sha256sum -c SHA256SUMS"
  echo "gh attestation verify ${release_zip} --repo ${REPO_SLUG}"
  echo '```'
} > "$dist_dir/NOTES.md"

echo "assemble-release: wrote $dist_dir/{$release_zip,$symbols_zip,SHA256SUMS,NOTES.md}"
