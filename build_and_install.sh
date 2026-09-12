#!/usr/bin/env bash
set -e

echo "🚀 Gemini Floating Companion Derleme ve Yükleme Başlatılıyor..."

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "📦 APK Derleniyor (Gradle Debug Build)..."
./gradlew assembleDebug

APK_PATH="$DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
  echo "❌ APK bulunamadı: $APK_PATH"
  exit 1
fi

echo "✅ APK başarıyla derlendi: $APK_PATH"

DEVICES=$(adb devices | grep -v "List of devices" | grep "device" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
  echo "⚠️ Bağlı adb cihazı bulunamadı. Telefonunuzu USB hata ayıklama moduyla bağlayıp tekrar deneyebilirsiniz."
  echo "📲 APK dosyasını manuel olarak telefona gönderebilirsiniz: $APK_PATH"
  exit 0
fi

echo "📲 Cihaza yükleniyor: $DEVICES"
adb install -r "$APK_PATH"

echo "🔑 Gerekli izinler tanımlanıyor..."
adb shell pm grant com.gemini.floatingcompanion android.permission.RECORD_AUDIO || true
adb shell pm grant com.gemini.floatingcompanion android.permission.CAMERA || true
adb shell pm grant com.gemini.floatingcompanion android.permission.POST_NOTIFICATIONS || true
adb shell appops set com.gemini.floatingcompanion SYSTEM_ALERT_WINDOW allow || true

echo "✨ Uygulama başlatılıyor..."
adb shell am start -n com.gemini.floatingcompanion/.ui.MainActivity

echo "🎉 Kurulum başarıyla tamamlandı!"
