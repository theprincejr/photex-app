#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <ndk dir> <tdlib output dir> <abi> <expected TDLib commit> <report file>" >&2
  exit 2
fi

ndk_dir=$1
tdlib_dir=$2
abi=$3
expected_commit=$4
report_file=$5

readelf="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
library="$tdlib_dir/libs/$abi/libtdjni.so"
page_size=$((0x4000))
failures=0

: > "$report_file"

report() {
  echo "$*" | tee -a "$report_file"
}

fail() {
  report "FAIL $*"
  failures=$((failures + 1))
}

report "abi=$abi"
report "library=libs/$abi/libtdjni.so"
report "expected_commit=$expected_commit"

if [ ! -x "$readelf" ]; then
  fail "llvm-readelf not found at $readelf"
fi
if [ ! -f "$library" ]; then
  fail "library missing: $library"
  report "RESULT FAIL"
  exit 1
fi

case "$abi" in
  arm64-v8a | x86_64) needs_16k=true ;;
  *) needs_16k=false ;;
esac

program_headers=$("$readelf" -lW "$library")
load_lines=$(printf '%s\n' "$program_headers" | awk '$1 == "LOAD" { print $3, $6, $NF }')
relro_lines=$(printf '%s\n' "$program_headers" | awk '$1 == "GNU_RELRO" { print $3, $6, $NF }')

if [ -z "$load_lines" ]; then
  fail "no LOAD segments found"
fi

load_index=0
while read -r vaddr memsz align; do
  [ -n "$vaddr" ] || continue
  load_index=$((load_index + 1))
  if [ "$needs_16k" = true ]; then
    if [ "$((align))" -eq "$page_size" ]; then
      report "PASS load_align segment=$load_index vaddr=$vaddr memsz=$memsz align=$align"
    else
      fail "load_align segment=$load_index vaddr=$vaddr memsz=$memsz align=$align (expected 0x4000)"
    fi
  else
    report "INFO load_align segment=$load_index vaddr=$vaddr memsz=$memsz align=$align (32-bit ABI, 16 KB not required)"
  fi
done <<< "$load_lines"

if [ "$needs_16k" = true ]; then
  if [ -z "$relro_lines" ]; then
    report "PASS relro no GNU_RELRO segment"
  else
    while read -r relro_vaddr relro_memsz _relro_align; do
      [ -n "$relro_vaddr" ] || continue
      relro_start=$((relro_vaddr))
      relro_end=$((relro_vaddr + relro_memsz))
      if [ $((relro_end % page_size)) -eq 0 ]; then
        report "PASS relro end=$(printf '0x%x' "$relro_end") is 16 KB-aligned"
        continue
      fi
      fills_segment=false
      while read -r vaddr memsz _align; do
        [ -n "$vaddr" ] || continue
        load_start=$((vaddr))
        load_end=$((vaddr + memsz))
        if [ "$relro_start" -ge "$load_start" ] && [ "$relro_end" -le "$load_end" ] && [ "$relro_end" -eq "$load_end" ]; then
          fills_segment=true
        fi
      done <<< "$load_lines"
      if [ "$fills_segment" = true ]; then
        report "PASS relro end=$(printf '0x%x' "$relro_end") fills its LOAD segment"
      else
        fail "relro end=$(printf '0x%x' "$relro_end") is neither 16 KB-aligned nor the end of its LOAD segment"
      fi
    done <<< "$relro_lines"
  fi
fi

commit_matches=$(strings -a "$library" | grep -cF "$expected_commit" || true)
if [ "$commit_matches" -ge 1 ]; then
  report "PASS commit_embedded $expected_commit"
else
  fail "commit_embedded $expected_commit not found in libtdjni.so"
fi

stripped_size_bytes=$(stat -c %s "$library")
report "INFO stripped_size_bytes=$stripped_size_bytes"

if [ "$failures" -eq 0 ]; then
  report "RESULT PASS"
else
  report "RESULT FAIL ($failures failed check(s))"
  exit 1
fi
