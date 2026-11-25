# Derived from tdlib/td example/android/Dockerfile at c8828d50f5eac2fc7089ce1089d7ce9095c61b5e
# and codaki-apps/tdlib-android (Boost Software License 1.0).

ARG BASE_IMAGE
FROM --platform=linux/amd64 ${BASE_IMAGE} AS build

ARG APT_SNAPSHOT
RUN test -n "${APT_SNAPSHOT}" \
 && apt-get update --error-on=any \
 && DEBIAN_FRONTEND=noninteractive apt-get install -yq --no-install-recommends ca-certificates \
 && echo "APT::Snapshot \"${APT_SNAPSHOT}\";" > /etc/apt/apt.conf.d/50snapshot \
 && apt-get update --error-on=any \
 && DEBIAN_FRONTEND=noninteractive apt-get install -yq default-jdk g++ git gperf make perl php-cli unzip wget \
 && rm -rf /var/lib/apt/lists/*

ARG TDLIB_COMMIT
WORKDIR /home
RUN test -n "${TDLIB_COMMIT}" \
 && git init -q td \
 && cd td \
 && git remote add origin https://github.com/tdlib/td.git \
 && git fetch -q --depth 1 origin "${TDLIB_COMMIT}" \
 && git checkout -q --detach FETCH_HEAD \
 && test "$(git rev-parse HEAD)" = "${TDLIB_COMMIT}"

WORKDIR /home/td/example/android
ARG ANDROID_NDK_VERSION
RUN test -n "${ANDROID_NDK_VERSION}" \
 && ./fetch-sdk.sh /home/SDK "${ANDROID_NDK_VERSION}"

ARG OPENSSL_URL
ARG OPENSSL_SHA256
ARG OPENSSL_DIR
RUN mkdir -p /home/licenses \
 && cp /home/td/LICENSE_1_0.txt /home/licenses/TDLib-LICENSE_1_0.txt \
 && wget -q -O /tmp/openssl.tar.gz "${OPENSSL_URL}" \
 && echo "${OPENSSL_SHA256}  /tmp/openssl.tar.gz" | sha256sum -c - \
 && tar -xzf /tmp/openssl.tar.gz -C /tmp --wildcards "${OPENSSL_DIR}/LICENSE*" \
 && cp /tmp/"${OPENSSL_DIR}"/LICENSE* /home/licenses/OpenSSL-LICENSE.txt \
 && rm -rf /tmp/openssl.tar.gz /tmp/"${OPENSSL_DIR}" \
 && sed -n '1,/\*\//p' "/home/SDK/ndk/${ANDROID_NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/zlib.h" \
      > /home/licenses/zlib-LICENSE.txt \
 && grep -q "Jean-loup Gailly" /home/licenses/zlib-LICENSE.txt

ARG BUILD_JOBS=4
ENV CMAKE_BUILD_PARALLEL_LEVEL=${BUILD_JOBS}
RUN export PATH="/home/SDK/cmake/3.22.1/bin:${PATH}" \
 && mkdir -p build-native-Java \
 && cd build-native-Java \
 && cmake "" -DTD_GENERATE_SOURCE_FILES=ON .. \
 && cmake --build .

COPY patches/build-openssl.patch /patches/build-openssl.patch
RUN cd /home/td && git apply /patches/build-openssl.patch

ARG ABI
ARG OPENSSL_VERSION
RUN case "${ABI}" in arm64-v8a|armeabi-v7a|x86_64) ;; *) echo "Unsupported ABI: '${ABI}'" >&2; exit 1 ;; esac \
 && OPENSSL_URL="${OPENSSL_URL}" OPENSSL_SHA256="${OPENSSL_SHA256}" OPENSSL_DIR="${OPENSSL_DIR}" \
    OPENSSL_ABIS="${ABI}" OPENSSL_JOBS="${BUILD_JOBS}" \
    ./build-openssl.sh /home/SDK "${ANDROID_NDK_VERSION}" /home/openssl "${OPENSSL_VERSION}" ""

ARG ANDROID_API_LEVEL
ARG ANDROID_STL
ARG TDLIB_INTERFACE
RUN test -n "${ANDROID_API_LEVEL}" && test -n "${ANDROID_STL}" && test -n "${TDLIB_INTERFACE}" \
 && ./build-tdlib.sh /home/SDK "${ANDROID_NDK_VERSION}" /home/openssl "${ANDROID_STL}" "${TDLIB_INTERFACE}" \
      "${ANDROID_API_LEVEL}" "${ABI}" \
 && rm -rf build-*

COPY scripts/gates.sh /usr/local/bin/gates.sh
RUN mkdir -p /home/out \
 && bash /usr/local/bin/gates.sh "/home/SDK/ndk/${ANDROID_NDK_VERSION}" tdlib "${ABI}" "${TDLIB_COMMIT}" \
      "/home/out/gates-${ABI}.txt" \
 && cp -r tdlib/java tdlib/libs tdlib/tdlib-debug.zip /home/out/ \
 && cp -r /home/licenses /home/out/licenses

FROM scratch
COPY --from=build /home/out/ /
