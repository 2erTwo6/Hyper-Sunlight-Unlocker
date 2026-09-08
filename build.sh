#!/bin/sh
# Build SunlightBoostLSP.apk. Needs: JDK 17 (javac/keytool), python3.
# r8.jar (D8 dexer, pure java, any arch) is fetched automatically.
set -e
cd "$(dirname "$0")"
if [ ! -s r8.jar ] || ! unzip -tq r8.jar >/dev/null 2>&1; then
    R8V=$(curl -s https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml \
          | grep -o '<latest>[^<]*' | head -1 | sed 's/<latest>//')
    [ -n "$R8V" ] || R8V=8.5.35
    echo "fetching r8 $R8V ..."
    curl -sL -o r8.jar "https://dl.google.com/android/maven2/com/android/tools/r8/$R8V/r8-$R8V.jar" \
        && unzip -tq r8.jar > /dev/null || { echo "r8 download failed"; exit 1; }
fi
mkdir -p classes dexout
javac --release 8 -d classes $(find src -name '*.java')
java -cp r8.jar com.android.tools.r8.D8 --release --min-api 29 --output dexout classes/sbo/*.class
python3 build_apk.py
