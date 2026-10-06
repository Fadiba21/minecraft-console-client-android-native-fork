#!/usr/bin/env bash
# Membangun OpenSSL untuk Android arm64 (bionic). .NET membutuhkan libssl untuk koneksi TLS
# (login Microsoft/Xbox, Mojang session server, dll). Android tidak menyediakan libssl untuk aplikasi.
set -euo pipefail

OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
API="${ANDROID_API_LEVEL:-26}"
ROOT="$(pwd)"
OUT="$ROOT/build/openssl-out"
WORK="$ROOT/build/openssl-src"

NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "::error::Android NDK tidak ditemukan (ANDROID_NDK_ROOT/ANDROID_NDK_HOME)."
  exit 1
fi
export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

rm -rf "$OUT" "$WORK"
mkdir -p "$OUT" "$WORK"
cd "$WORK"

echo "== Unduh OpenSSL $OPENSSL_VERSION"
curl -fsSL "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" -o openssl.tar.gz
tar xf openssl.tar.gz --strip-components=1

echo "== Configure (android-arm64, API $API)"
./Configure android-arm64 -D__ANDROID_API__="$API" shared no-tests no-docs --prefix="$OUT"

echo "== Build"
make -j"$(nproc)" build_libs

echo "== Kumpulkan library"
# Nama file berbeda antar versi/target (libssl.so.3 atau libssl_3.so). Ambil apa pun yang ada.
shopt -s nullglob
for f in libssl*.so* libcrypto*.so*; do
  # lewati symlink; salin file asli saja
  if [ -L "$f" ]; then continue; fi
  cp "$f" "$OUT/"
done
llvm-strip --strip-unneeded "$OUT"/*.so* 2>/dev/null || true

echo "== Hasil:"
ls -la "$OUT"
if ! ls "$OUT"/libssl* >/dev/null 2>&1 || ! ls "$OUT"/libcrypto* >/dev/null 2>&1; then
  echo "::error::libssl/libcrypto tidak terbentuk."
  exit 1
fi
