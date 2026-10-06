#!/usr/bin/env bash
# Menyusun bundle runtime (MCC + .NET + OpenSSL + CA) menjadi assets/mcc-bundle.zip untuk APK.
set -euo pipefail

ROOT="$PWD"
PUB="$ROOT/build/mcc-out"
SSL="$ROOT/build/openssl-out"
B="$ROOT/build/bundle"
ASSETS="$ROOT/app/src/main/assets"

rm -rf "$B"
mkdir -p "$B" "$ASSETS"
cp -a "$PUB"/. "$B"/

echo "== Bersihkan file yang tidak dipakai"
find "$B" -name '*.pdb' -delete
# Native ImageMagick (glibc) tidak bisa dimuat di bionic; hanya dipakai fitur render peta gambar.
find "$B" -iname '*Magick.Native*' -delete 2>/dev/null || true

echo "== OpenSSL"
cp -a "$SSL"/. "$B"/
# Alias nama yang dicari .NET (libssl.so.3 / libssl.so). File asli tetap ada karena DT_NEEDED
# libssl menunjuk ke SONAME libcrypto aslinya.
shopt -s nullglob
ssl_real=$(ls "$SSL"/libssl* | head -n1)
cry_real=$(ls "$SSL"/libcrypto* | head -n1)
for n in libssl.so.3 libssl.so; do cp "$ssl_real" "$B/$n"; done
for n in libcrypto.so.3 libcrypto.so; do cp "$cry_real" "$B/$n"; done

echo "== Sertifikat CA"
cp /etc/ssl/certs/ca-certificates.crt "$B/cacert.pem"

echo "== libc++ (jaga-jaga)"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}}"
if [ -n "$NDK" ]; then
  cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$B/" 2>/dev/null || true
fi

echo "== Info bundle"
MCC_COMMIT=$(git -C "${MCC_SRC:-mcc-src}" rev-parse HEAD 2>/dev/null || echo unknown)
cat > "$B/bundle-info.json" <<EOF
{
  "mccRepo": "${MCC_REPO:-MCCTeam/Minecraft-Console-Client}",
  "mccRef": "${MCC_REF:-master}",
  "mccCommit": "$MCC_COMMIT",
  "dotnetSdk": "$(dotnet --version 2>/dev/null || echo unknown)",
  "openssl": "${OPENSSL_VERSION:-unknown}",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
cp "$B/bundle-info.json" "$ASSETS/bundle-info.json"

echo "== Pemeriksaan wajib"
need=(MinecraftClient MinecraftClient.dll libcoreclr.so libhostfxr.so libhostpolicy.so libSystem.Native.so cacert.pem libssl.so.3 libcrypto.so.3)
missing=0
for f in "${need[@]}"; do
  if [ ! -e "$B/$f" ]; then echo "::error::File wajib hilang: $f"; missing=1; fi
done
[ "$missing" = 0 ] || exit 1

if command -v file >/dev/null 2>&1; then
  echo "-- arsitektur:"
  file "$B/MinecraftClient" "$B/libcoreclr.so" "$B/libssl.so.3" | sed 's/, BuildID.*//'
  file "$B/MinecraftClient" | grep -qi 'aarch64' || { echo "::error::MinecraftClient bukan ELF aarch64"; exit 1; }
fi

echo "== Zip"
rm -f "$ASSETS/mcc-bundle.zip"
(cd "$B" && zip -r -q -9 "$ASSETS/mcc-bundle.zip" .)
ls -la "$ASSETS"
du -sh "$B" "$ASSETS/mcc-bundle.zip"
