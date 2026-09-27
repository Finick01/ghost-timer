#!/bin/bash
# Builds Ghost Timer APK without Gradle: aapt2 -> javac -> d8 -> zipalign(py) -> apksigner
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_TOOL_OPTIONS=
T=tools; OUT=build; rm -rf $OUT; mkdir -p $OUT/res $OUT/classes $OUT/gen
$T/aapt2 compile --dir res -o $OUT/res/res.zip
$T/aapt2 link -o $OUT/base.apk -I $T/android.jar --manifest AndroidManifest.xml --java $OUT/gen $OUT/res/res.zip --min-sdk-version 29 --target-sdk-version 34 --version-code "${VCODE:-1}" --version-name "${VNAME:-1.0}"
java -jar $T/ecj.jar -encoding UTF-8 -nowarn -8 -bootclasspath $T/android.jar -d $OUT/classes $(find src $OUT/gen -name '*.java')
java -cp $T/d8.jar com.android.tools.r8.D8 --release --min-api 29 --lib $T/android.jar --output $OUT $(find $OUT/classes -name '*.class')
python3 align.py $OUT/base.apk $OUT/classes.dex $OUT/aligned.apk
java -jar $T/apksigner.jar sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks "${KS:-ghosttimer-release.jks}" --ks-pass env:KS_PASS --key-pass env:KS_PASS --out $OUT/GhostTimer.apk $OUT/aligned.apk
java -jar $T/apksigner.jar verify --verbose $OUT/GhostTimer.apk | head -6
