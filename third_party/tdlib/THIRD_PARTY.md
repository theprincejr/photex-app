# Third-party components in PhotX TDLib release

| Component | License | Text in release | Source |
|---|---|---|---|
| TDLib (`tdlib/td` at `TDLIB_COMMIT`) | Boost Software License 1.0 | `LICENSES/TDLib-LICENSE_1_0.txt` | `LICENSE_1_0.txt` in pinned TDLib checkout |
| OpenSSL 3.5 LTS (`OPENSSL_VERSION`), statically linked | Apache License 2.0 | `LICENSES/OpenSSL-LICENSE.txt` | `LICENSE.txt` in sha256-checked OpenSSL tarball |
| zlib (Android system `libz.so`, linked dynamically) | zlib license | `LICENSES/zlib-LICENSE.txt` | `zlib.h` header in pinned NDK sysroot |

Build scripts in `third_party/tdlib/` are derived from `tdlib/td` (`example/android`) and `codaki-apps/tdlib-android` under the Boost Software License 1.0.
