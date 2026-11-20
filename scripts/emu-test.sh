#!/bin/bash
set -u

AVD=qfold
KEEP=0
WINDOW=0
while [ $# -gt 0 ]; do
	case "$1" in
	--avd) AVD="$2"; shift ;;
	--keep) KEEP=1 ;;
	--window) WINDOW=1 ;;
	-h|--help) echo "usage: scripts/emu-test.sh [--avd qfold|qphone|qtab] [--keep] [--window]"; exit 0 ;;
	*) echo "unknown option $1"; exit 2 ;;
	esac
	shift
done

ROOT=$(cd "$(dirname "$0")/.." && pwd)
QUILL=${QUILL_SRC:-$ROOT/../quill}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
export JAVA_HOME=${JAVA_HOME:-$(ls -d "$HOME"/Android/jdk-21* 2>/dev/null | head -1)}
export PATH=$JAVA_HOME/bin:$HOME/.local/go/bin:$PATH
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator
AVDMGR=$SDK/cmdline-tools/latest/bin/avdmanager
PKG=io.github.katachinosekai.quillremote
OUT=$ROOT/build/emu-test/$(date +%Y%m%d-%H%M%S)-$AVD
mkdir -p "$OUT"
WORK=$(mktemp -d)
PASS=0
FAIL=0
SERIAL=""
EMU_PID=""
WEB_PID=""
FOLD_PID=""

log() { echo "$*" | tee -a "$OUT/results.txt"; }
ok() { PASS=$((PASS + 1)); log "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); log "FAIL  $1${2:+  ($2)}"; }
adb_() { timeout 30 "$ADB" -s "$SERIAL" "$@"; }

cleanup() {
	[ -n "$WEB_PID" ] && kill "$WEB_PID" 2>/dev/null
	[ -n "$FOLD_PID" ] && kill -INT "$FOLD_PID" 2>/dev/null
	if [ -n "$SERIAL" ]; then
		adb_ logcat -d > "$OUT/logcat.txt" 2>/dev/null
		adb_ reverse --remove-all 2>/dev/null
		[ "$KEEP" = 0 ] && [ -n "$EMU_PID" ] && adb_ emu kill >/dev/null 2>&1
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT

display() { adb_ shell dumpsys display 2>/dev/null | grep -F 'DisplayDeviceInfo{' | grep -F 'state ON' | head -1 | grep -o 'uniqueId="local:[0-9]*"' | grep -o '[0-9]*'; }
awake() {
	adb_ shell input keyevent KEYCODE_WAKEUP
	adb_ shell wm dismiss-keyguard >/dev/null 2>&1
	sleep 2
}
shot() {
	local d; d=$(display)
	adb_ exec-out screencap -p ${d:+-d "$d"} > "$OUT/$1.png" 2>/dev/null
}
dump() {
	adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
	adb_ exec-out cat /sdcard/ui.xml 2>/dev/null
}
has() { dump | grep -q -F "$1"; }
native() { dump | tr '>' '\n' | grep -F "$1" | grep -q -F 'class="android.widget.TextView"'; }
native_gone() {
	local i
	for i in $(seq 1 "${2:-10}"); do
		native "$1" || return 0
		sleep 1
	done
	return 1
}
wait_for() {
	local what=$1 secs=${2:-15} i
	for i in $(seq 1 "$secs"); do
		has "$what" && return 0
		sleep 1
	done
	return 1
}
gone() {
	local what=$1 secs=${2:-10} i
	for i in $(seq 1 "$secs"); do
		has "$what" || return 0
		sleep 1
	done
	return 1
}
tap_text() {
	local xml b
	xml=$(dump)
	b=$(printf '%s' "$xml" | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	[ -z "$b" ] && b=$(printf '%s' "$xml" | tr '>' '\n' | grep -F "content-desc=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	[ -z "$b" ] && return 1
	set -- $b
	adb_ shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}
tap_scroll() {
	local i size w h
	for i in 1 2 3 4; do
		tap_text "$1" && return 0
		size=$(adb_ shell wm size | tail -1 | awk '{print $3}')
		w=${size%x*}
		h=${size#*x}
		adb_ shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 4)) 300
		sleep 1
	done
	return 1
}
crashed() { adb_ logcat -d -b crash 2>/dev/null | grep -q -E "$PKG|FATAL"; }
width_dp() {
	local size dens w h
	size=$(adb_ shell wm size | tail -1 | awk '{print $3}')
	dens=$(adb_ shell wm density | tail -1 | awk '{print $3}')
	w=${size%x*}
	echo $((w * 160 / dens))
}
launch() { adb_ shell am start -W -n "$PKG/.MainActivity" "$@" >/dev/null 2>&1; }

log "Quill for Android emulator test: $AVD  ($(date))"

if ! "$EMU" -list-avds | grep -qx "$AVD"; then
	case "$AVD" in
	qphone) dev=pixel_8 ;;
	qfold) dev=pixel_fold ;;
	qtab) dev=pixel_tablet ;;
	*) dev=pixel_8 ;;
	esac
	echo no | "$AVDMGR" create avd -n "$AVD" -k "system-images;android-36;google_apis;x86_64" -d "$dev" >/dev/null || { log "cannot create AVD $AVD"; exit 2; }
fi

SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1}' | while read -r s; do
	[ "$(timeout 5 "$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] && echo "$s"
done | head -1)
if [ -z "$SERIAL" ]; then
	port=5554
	while "$ADB" devices | grep -q "emulator-$port"; do port=$((port + 2)); done
	SERIAL=emulator-$port
	flags=(-no-audio -no-snapshot -no-boot-anim -gpu host -feature -Vulkan -port "$port")
	[ "$WINDOW" = 0 ] && flags+=(-no-window)
	"$EMU" -avd "$AVD" "${flags[@]}" > "$OUT/emulator.log" 2>&1 &
	EMU_PID=$!
	for i in $(seq 1 120); do
		[ "$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
		sleep 2
	done
fi
[ "$(adb_ shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { log "emulator did not boot"; exit 2; }
log "device $SERIAL"

(cd "$ROOT" && ./gradlew -q assembleDebug) || { log "build failed"; exit 2; }
adb_ uninstall "$PKG" >/dev/null 2>&1
adb_ install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null || { log "install failed"; exit 2; }
adb_ shell settings put global window_animation_scale 0
adb_ shell settings put global transition_animation_scale 0
adb_ shell settings put global animator_duration_scale 0
adb_ shell settings put system fold_lock_behavior_setting stay_awake_on_fold_key
adb_ shell settings put system screen_off_timeout 1800000
adb_ shell locksettings set-disabled true >/dev/null 2>&1
adb_ logcat -c
adb_ logcat -b crash -c

mkdir -p "$WORK/www/code"
for s in A1:Fix-edits B2:Old-server-notes C3:Zomboid-launcher D4:Unsorted-idea; do
	id=session_${s%%:*}
	mkdir -p "$WORK/www/code/$id"
done
page='<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body><nav style="position:relative">'
for s in A1:Fix-edits B2:Old-server-notes C3:Zomboid-launcher D4:Unsorted-idea; do
	page="$page<a href=\"/code/session_${s%%:*}\" style=\"display:block;padding:8px\">$(echo "${s#*:}" | tr '-' ' ')</a>"
done
page="$page<a href=\"https://accounts.google.com/o/oauth2/v2/auth?client_id=test\" style=\"display:block;padding:8px\">Continue with Google test</a>"
page="$page</nav><main><p>Mock session page for emulator tests.</p><form><textarea></textarea></form></main></body></html>"
echo "$page" > "$WORK/www/code/index.html"
for d in "$WORK"/www/code/session_*; do cp "$WORK/www/code/index.html" "$d/index.html"; done
mkdir -p "$WORK/www/login"
echo '<!doctype html><html><body><p>Mock login page</p></body></html>' > "$WORK/www/login/index.html"
python3 -m http.server 8765 --bind 127.0.0.1 --directory "$WORK/www" > "$OUT/http.log" 2>&1 &
WEB_PID=$!
adb_ reverse tcp:8765 tcp:8765 >/dev/null

[ "$AVD" = qfold ] && adb_ emu unfold >/dev/null
awake
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
sleep 4
shot 01-start
crashed && bad "launch" "crash in logcat" || ok "launch"
has 'text="Quill"' && ok "top bar" || bad "top bar"

wide=$(width_dp)
log "screen width ${wide}dp"
if [ "$wide" -ge 600 ]; then
	wait_for 'text="Fix edits"' 20 && ok "vertical sidebar lists sessions" || bad "vertical sidebar lists sessions"
	has 'content-desc="Sidebar"' && ok "sidebar arrow shown" || bad "sidebar arrow shown"
	tap_text "Zomboid launcher" && sleep 3
	adb_ shell dumpsys activity top | grep -q "$PKG" && ok "open session from sidebar" || bad "open session from sidebar"
	tap_text "Sidebar" && sleep 2
	shot 02-sidebar-closed
	native_gone 'text="Sessions"' 5 && ok "arrow hides sidebar" || bad "arrow hides sidebar"
	tap_text "Sidebar" && sleep 2
	wait_for 'text="Sessions"' 5 && ok "arrow shows sidebar" || bad "arrow shows sidebar"
fi

if [ "$AVD" = qfold ]; then
	adb_ emu fold >/dev/null
	sleep 4
	awake
	shot 03-folded
	log "folded width $(width_dp)dp"
	crashed && bad "fold" "crash" || ok "fold without crash"
	native_gone 'text="Sessions"' 8 && ok "folded: sidebar gone" || bad "folded: sidebar gone"
	wait_for 'text="Other (4)"' 15 && ok "folded: folder bar" || bad "folded: folder bar"
	wait_for 'Close tab' 10 && ok "folded: tab bar" || bad "folded: tab bar"
	adb_ emu unfold >/dev/null
	sleep 4
	awake
	wait_for 'text="Sessions"' 10 && ok "unfold: sidebar back" || bad "unfold: sidebar back"
	adb_ emu fold >/dev/null
	sleep 4
	awake
fi

if [ "$(width_dp)" -lt 600 ]; then
	wait_for 'text="Other (4)"' 15 && ok "folder bar" || bad "folder bar"
	tap_text "Other (4)" && sleep 2
	shot 04-dropdown
	has 'text="Unsorted idea"' && ok "folder drop-down" || bad "folder drop-down"
	adb_ shell input keyevent KEYCODE_BACK
	sleep 1
	tap_text "Unsorted idea" >/dev/null 2>&1
	launch --es quill.testUrl http://127.0.0.1:8765/code/session_D4/
	sleep 3
	b=$(dump | tr '>' '\n' | grep -F 'content-desc="Close tab"' | wc -l)
	[ "$b" -ge 1 ] && ok "tabs recorded ($b)" || bad "tabs recorded"
	adb_ shell input tap 10 10 >/dev/null
fi

tap_text "Continue with Google test" && sleep 2
shot 04b-google-intercept
has 'text="Sign in with email"' && ok "Google sign-in intercepted with help dialog" || bad "Google sign-in intercepted with help dialog"
adb_ shell dumpsys activity top | grep -q "ACTIVITY $PKG" && ok "Google link stays in app" || bad "Google link stays in app"
tap_text "OK" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
sleep 1
launch --es quill.testUrl http://127.0.0.1:8765/login/
sleep 3
wait_for 'content-desc="Sign-in tip"' 8 && ok "sign-in tip on login page" || bad "sign-in tip on login page"
tap_text "Sign-in tip" && sleep 1
gone 'content-desc="Sign-in tip"' 5 && ok "sign-in tip dismissed" || bad "sign-in tip dismissed"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
sleep 3

tap_text "A+" && sleep 1
has 'text="125%"' && ok "text size A+" || bad "text size A+"
tap_text "A−" && sleep 1
has 'text="115%"' && ok "text size A−" || bad "text size A−"

tap_text "Settings" && sleep 2
shot 05-settings
has 'text="Folders from Quill"' && ok "settings dialog" || bad "settings dialog"
has 'text="Not paired. Scan the QR code in Quill → Settings → Pair phone."' && ok "settings: not paired" || bad "settings: not paired"
tap_scroll "Open claude.ai links in Quill"
for i in $(seq 1 10); do
	adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q -i settings && break
	sleep 1
done
adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q -i settings && ok "link settings open" || bad "link settings open" "$(adb_ shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity')"
adb_ shell input keyevent KEYCODE_BACK
sleep 2
adb_ shell dumpsys activity top | grep -E "^ *ACTIVITY" | tail -1 | grep -q "$PKG" || launch
sleep 1
tap_text "Done" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
sleep 1

if [ -d "$QUILL/internal/folders" ] && command -v go >/dev/null; then
	mkdir -p "$WORK/fd"
	cp "$ROOT/scripts/testdata/foldersd.go" "$WORK/fd/main.go"
	printf 'module github.com/katachinosekai/quill/emutest\n\ngo 1.26\n\nrequire github.com/katachinosekai/quill v0.0.0\n\nreplace github.com/katachinosekai/quill => %s\n' "$QUILL" > "$WORK/fd/go.mod"
	(cd "$WORK/fd" && GOFLAGS=-mod=mod go build -o foldersd . 2> "$OUT/foldersd-build.log")
	KEY=emutest_0123456789abcdef
	MAP='{"session_A1":"Active","session_B2":"Archive","session_C3":"Zomboid"}'
	"$WORK/fd/foldersd" "$WORK/fd/cert" 0 "$KEY" "$MAP" > "$WORK/fd/out" 2>"$OUT/foldersd.log" &
	FOLD_PID=$!
	for i in $(seq 1 20); do [ -s "$WORK/fd/out" ] && break; sleep 0.5; done
	read -r FPORT FP < "$WORK/fd/out"
	if [ -n "${FPORT:-}" ]; then
		LINK="quillpair://pair?h=10.0.2.2&p=$FPORT&k=$KEY&fp=$FP&f=quill-folders.json&n=emu-test"
		adb_ shell am start -W -a android.intent.action.VIEW -d "\"$LINK\"" "$PKG" >/dev/null 2>&1
		sleep 6
		shot 06-paired
		if [ "$(width_dp)" -lt 600 ]; then
			wait_for 'text="Active (1)"' 40 && ok "pairing: folders from local network" || bad "pairing: folders from local network"
			has 'text="Zomboid (1)"' && has 'text="Archive (1)"' && has 'text="Other (1)"' && ok "pairing: grouping" || bad "pairing: grouping"
		else
			wait_for 'text="Active  1"' 40 && ok "pairing: folders from local network" || bad "pairing: folders from local network"
			has 'text="Zomboid  1"' && ok "pairing: grouping" || bad "pairing: grouping"
		fi
		kill -INT "$FOLD_PID" 2>/dev/null
		FOLD_PID=""
		adb_ shell am force-stop "$PKG"
		launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
		sleep 5
		if [ "$(width_dp)" -lt 600 ]; then
			wait_for 'text="Active (1)"' 15 && ok "cached folders after restart (Quill offline)" || bad "cached folders after restart"
		else
			wait_for 'text="Active  1"' 15 && ok "cached folders after restart (Quill offline)" || bad "cached folders after restart"
		fi
		tap_text "Settings" && sleep 2
		has 'Paired with emu-test' && ok "settings: paired status" || bad "settings: paired status"
		tap_scroll "Unpair" && sleep 1
		tap_text "Done" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
		sleep 3
		if [ "$(width_dp)" -lt 600 ]; then
			wait_for 'text="Other (4)"' 10 && ok "unpair clears folders" || bad "unpair clears folders"
		else
			wait_for 'text="Other  4"' 10 && ok "unpair clears folders" || bad "unpair clears folders"
		fi
		adb_ shell am start -W -a android.intent.action.VIEW -d '"quillpair://pair?h=bad%20host&p=1&k=x&fp=zz"' "$PKG" >/dev/null 2>&1
		sleep 2
		crashed && bad "invalid pairing link" "crash" || ok "invalid pairing link rejected without crash"
	else
		bad "folder server" "did not start, see foldersd.log"
	fi
else
	log "SKIP  pairing (no Quill source or Go)"
fi

update_case() {
	local name=$1 apk=$2 digest=$3 ver=99.0.0 size
	mkdir -p "$WORK/www/u/$name"
	cp "$apk" "$WORK/www/u/$name/quill-android-$ver.apk"
	size=$(stat -c %s "$apk")
	[ -z "$digest" ] && digest=$(sha256sum "$apk" | cut -d' ' -f1)
	printf '{"tag_name":"v%s","draft":false,"prerelease":false,"assets":[{"name":"quill-android-%s.apk","browser_download_url":"http://127.0.0.1:8765/u/%s/quill-android-%s.apk","digest":"sha256:%s","size":%s}]}' \
		"$ver" "$ver" "$name" "$ver" "$digest" "$size" > "$WORK/www/u/$name/latest.json"
	adb_ shell am force-stop "$PKG"
	adb_ logcat -c
	launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/ --es quill.updateFeed "http://127.0.0.1:8765/u/$name/latest.json"
	wait_for 'content-desc="Update banner"' 20 || return 1
	tap_text "Update banner"
	sleep 8
	return 0
}
upd_log() { adb_ logcat -d -s QuillUpdate:I 2>/dev/null; }

adb_ shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
head -c 300000 /dev/urandom > "$WORK/junk.apk"
if update_case junk "$WORK/junk.apk" ""; then
	upd_log | grep -q "not a valid Quill APK" && ok "update: rejects invalid APK" || bad "update: rejects invalid APK" "$(upd_log | tail -1)"
else
	bad "update: banner for newer release"
fi
if update_case tamper "$WORK/junk.apk" "0000000000000000000000000000000000000000000000000000000000000000"; then
	upd_log | grep -q "checksum does not match" && ok "update: rejects checksum mismatch" || bad "update: rejects checksum mismatch" "$(upd_log | tail -1)"
fi
(cd "$ROOT" && ./gradlew -q assembleRelease -PqVersionCode=999 -PqVersionName=99.0.0 >/dev/null 2>&1) && cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$WORK/otherkey.apk"
if [ -f "$WORK/otherkey.apk" ] && update_case otherkey "$WORK/otherkey.apk" ""; then
	upd_log | grep -q "not signed by the Quill for Android key" && ok "update: rejects APK signed with another key" || bad "update: rejects APK signed with another key" "$(upd_log | tail -1)"
fi
(cd "$ROOT" && ./gradlew -q assembleDebug -PqVersionCode=999 -PqVersionName=99.0.0 >/dev/null 2>&1) && cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$WORK/good.apk"
(cd "$ROOT" && ./gradlew -q assembleDebug >/dev/null 2>&1)
if [ -f "$WORK/good.apk" ] && update_case good "$WORK/good.apk" ""; then
	shot 06b-install-confirm
	tap_text "Update" >/dev/null 2>&1 || tap_text "UPDATE" >/dev/null 2>&1 || tap_text "Install" >/dev/null 2>&1
	for i in $(seq 1 20); do
		v=$(adb_ shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')
		[ "$v" = "versionName=99.0.0" ] && break
		sleep 1
	done
	[ "$v" = "versionName=99.0.0" ] && ok "update: installs a genuine newer build" || bad "update: installs a genuine newer build" "$v; $(upd_log | tail -1)"
	adb_ uninstall "$PKG" >/dev/null 2>&1
	adb_ install "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
	sleep 3
	adb_ logcat -b crash -c
	launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
	sleep 3
fi

adb_ shell settings put system accelerometer_rotation 0
adb_ shell settings put system user_rotation 1
sleep 4
shot 07-rotated
crashed && bad "rotation" "crash" || ok "rotation"
has 'text="Quill"' && ok "rotation keeps UI" || bad "rotation keeps UI"
adb_ shell settings put system user_rotation 0
sleep 2

launch --es quill.testUrl chrome://crash
sleep 6
shot 08-after-renderer-crash
crashed && bad "renderer crash recovery" "app crashed" || ok "renderer crash recovery (no app crash)"
wait_for 'text="Quill"' 10 && ok "UI back after renderer crash" || bad "UI back after renderer crash"

adb_ shell input keyevent KEYCODE_BACK
sleep 2
crashed && bad "back" "crash" || ok "back"

adb_ logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
log ""
log "$PASS passed, $FAIL failed. Output: $OUT"
[ "$FAIL" = 0 ]
