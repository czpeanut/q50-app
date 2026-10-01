#!/usr/bin/env bash
# Build the throwaway system survey as two .epk variants. Not part of V.T.D. itself: it lives
# here, in the development repository, and is left out of the published one.
#
#   A  vtd.survey.a   lower screen   WITH  IVI_CAN_READ
#   B  vtd.survey.b   UPPER screen   WITHOUT IVI_CAN_READ
#
# B carries two experiments at once, and they do not interfere: whether ivi.defaultDisplay=UPPER
# puts an app on the navigation screen, and whether the sensor list is filtered by permission
# (compare its sensor count with A's).
#
# Uses the same toolchain variables as ../../build.sh and the same keystore.ks.
set -euo pipefail
cd "$(dirname "$0")/../.."

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot}"
SDK="${ANDROID_SDK:-/c/Users/raid2/scoop/apps/android-clt/current}"
BT="$SDK/build-tools/34.0.0"
ANDJAR="$SDK/platforms/android-34/android.jar"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) X=.exe; B=.bat;; *) X=""; B="";; esac
AAPT="$BT/aapt$X"; ZIPALIGN="$BT/zipalign$X"; APKSIGNER="$BT/apksigner$B"; D8="$BT/d8$B"
JAVAC="$JAVA_HOME/bin/javac$X"; KEYTOOL="$JAVA_HOME/bin/keytool$X"
VC=$(date +%s)
OUT=build/survey

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVAC" --release 8 -encoding UTF-8 -g -d "$OUT/classes" -classpath "$ANDJAR" \
    tools/survey/src/vtd/survey/*.java
"$D8" --min-api 10 --lib "$ANDJAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

if [ ! -f keystore.ks ]; then
  "$KEYTOOL" -genkeypair -keystore keystore.ks -alias vtd -keyalg RSA \
    -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=VTD" >/dev/null 2>&1
fi

variant() {   # tag package label display canread
  local tag=$1 pkg=$2 label=$3 disp=$4 canread=$5 name="vtd-survey-$1"
  local perm=""
  mkdir -p "$OUT/$tag"            # aapt insists the manifest file is literally named AndroidManifest.xml
  [ "$canread" = yes ] && perm='<uses-permission android:name="com.ygomi.permission.IVI_CAN_READ" />'
  sed -e "s|@PKG@|$pkg|" -e "s|@LABEL@|$label|g" -e "s|@DISPLAY@|$disp|g" -e "s|@CANREAD@|$perm|" \
      -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VC\"/" \
      tools/survey/AndroidManifest.xml > "$OUT/$tag/AndroidManifest.xml"
  "$AAPT" package -f -M "$OUT/$tag/AndroidManifest.xml" -S res -I "$ANDJAR" -F "$OUT/$name.unsigned.apk"
  ( cd "$OUT/dex" && "$AAPT" add "../$name.unsigned.apk" classes.dex >/dev/null )
  "$ZIPALIGN" -f -p 4 "$OUT/$name.unsigned.apk" "$OUT/$name.aligned.apk"
  "$APKSIGNER" sign --ks keystore.ks --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled true \
    --out "$OUT/$name.apk" "$OUT/$name.aligned.apk"
  python tools/epk_tool.py build "$OUT/$name.apk" "$OUT/$name.epk" --cert keys/obu_cert.pem
  "$AAPT" dump badging "$OUT/$name.apk" 2>/dev/null | grep -E "^package:|launchable|uses-permission" || true
}

variant a vtd.survey.a "VTD 檢測 A" LOWER yes
variant b vtd.survey.b "VTD 檢測 B" UPPER no
ls -l "$OUT"/*.epk
