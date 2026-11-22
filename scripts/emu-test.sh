#!/bin/bash
set -u

AVD=qfold
KEEP=0
WINDOW=0
DEVICE=""
CLEANUP=0
FULL=0
SCALE=0.15
while [ $# -gt 0 ]; do
	case "$1" in
	--avd) AVD="$2"; shift ;;
	--keep) KEEP=1 ;;
	--window) WINDOW=1 ;;
	--device) DEVICE="$2"; shift ;;
	--cleanup) CLEANUP=1 ;;
	--full) FULL=1; SCALE=1 ;;
	-h|--help) echo "usage: scripts/emu-test.sh [--avd qfold|qphone|qtab] [--keep] [--window] | --device <ip:port> [--cleanup]"; exit 0 ;;
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
PKG=io.github.katachinosekai.quillremote.debug
ACT=io.github.katachinosekai.quillremote.MainActivity
[ -n "$DEVICE" ] && AVD=device
OUT=$ROOT/build/emu-test/$(date +%Y%m%d-%H%M%S)-$AVD
mkdir -p "$OUT"
WORK=$(mktemp -d)
PASS=0
FAIL=0
SERIAL=""
EMU_PID=""
WEB_PID=""
FOLD_PID=""
PHONE_PID=""

log() { echo "$*" | tee -a "$OUT/results.txt"; }
ok() { PASS=$((PASS + 1)); log "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); log "FAIL  $1${2:+  ($2)}"; }
DIRTY=1
CACHE=""
adb_() {
	case " $* " in *" input "*|*" am "*|*" install "*|*" uninstall "*|*" reverse "*) DIRTY=1 ;; esac
	timeout 30 "$ADB" -s "$SERIAL" "$@"
}
nap() { sleep "$(awk -v a="$1" -v b="$SCALE" 'BEGIN{printf "%.2f", a*b}')"; DIRTY=1; }
ANIM=""

restore_anim() {
	[ -z "$ANIM" ] && return
	set -- $ANIM
	for kv in "window_animation_scale:$1" "transition_animation_scale:$2" "animator_duration_scale:$3"; do
		k=${kv%%:*}; v=${kv#*:}
		if [ "$v" = null ] || [ -z "$v" ]; then
			timeout 15 "$ADB" -s "$SERIAL" shell settings delete global "$k" >/dev/null 2>&1
		else
			timeout 15 "$ADB" -s "$SERIAL" shell settings put global "$k" "$v" >/dev/null 2>&1
		fi
	done
	ANIM=""
	[ -n "${SAVED:-}" ] && rm -f "$SAVED"
}
cleanup() {
	restore_anim
	[ -n "$WEB_PID" ] && kill "$WEB_PID" 2>/dev/null
	[ -n "$FOLD_PID" ] && kill -INT "$FOLD_PID" 2>/dev/null
	[ -n "$PHONE_PID" ] && kill "$PHONE_PID" 2>/dev/null
	if [ -n "$SERIAL" ]; then
		adb_ logcat -d > "$OUT/logcat.txt" 2>/dev/null
		adb_ reverse --remove-all 2>/dev/null
		[ "$KEEP" = 0 ] && [ -n "$EMU_PID" ] && [ -z "$DEVICE" ] && adb_ emu kill >/dev/null 2>&1
	fi
	rm -rf "$WORK"
}
trap cleanup EXIT

display() { adb_ shell dumpsys display 2>/dev/null | grep -F 'DisplayDeviceInfo{' | grep -F 'state ON' | head -1 | grep -o 'uniqueId="local:[0-9]*"' | grep -o '[0-9]*'; }
awake() {
	adb_ shell input keyevent KEYCODE_WAKEUP
	adb_ shell wm dismiss-keyguard >/dev/null 2>&1
	nap 2
}
shot() {
	local d; d=$(display)
	adb_ exec-out screencap -p ${d:+-d "$d"} > "$OUT/$1.png" 2>/dev/null
}
dump() {
	if [ "$DIRTY" = 1 ]; then
		timeout 30 "$ADB" -s "$SERIAL" shell uiautomator dump --compressed /sdcard/ui.xml >/dev/null 2>&1
		CACHE=$(timeout 30 "$ADB" -s "$SERIAL" exec-out cat /sdcard/ui.xml 2>/dev/null)
		DIRTY=0
	fi
	printf '%s' "$CACHE"
}
has() { dump | grep -q -F "$1"; }
native() { dump | tr '>' '\n' | grep -F "$1" | grep -q -F 'class="android.widget.TextView"'; }
native_gone() {
	local i
	for i in $(seq 1 "${2:-10}"); do
		native "$1" || return 0
		nap 1
	done
	return 1
}
wait_for() {
	local what=$1 secs=${2:-15} end
	end=$(( $(date +%s) + secs ))
	while [ "$(date +%s)" -le "$end" ]; do
		DIRTY=1
		has "$what" && return 0
		sleep 0.15
	done
	return 1
}
gone() {
	local what=$1 secs=${2:-10} end
	end=$(( $(date +%s) + secs ))
	while [ "$(date +%s)" -le "$end" ]; do
		DIRTY=1
		has "$what" || return 0
		sleep 0.15
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
	local i b size w h
	for i in 1 2 3 4 5 6; do
		tap_text "$1" && return 0
		b=$(dump | tr '>' '\n' | grep -F 'scrollable="true"' | grep -v -F 'android.webkit.WebView' | tail -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
		if [ -n "$b" ]; then
			set -- "$1" $b
			adb_ shell input swipe $((($2 + $4) / 2)) $(($3 + ($5 - $3) * 3 / 4)) $((($2 + $4) / 2)) $(($3 + ($5 - $3) / 4)) 400
		else
			size=$(adb_ shell wm size | tail -1 | awk '{print $3}')
			w=${size%x*}
			h=${size#*x}
			case "$(adb_ shell dumpsys window displays | grep -m1 -o 'mCurrentRotation=ROTATION_[0-9]*')" in
			*_90|*_270) set -- "$1" "$h"; h=$w; w=$2 ;;
			esac
			adb_ shell input swipe $((w / 2)) $((h * 3 / 5)) $((w / 2)) $((h * 2 / 5)) 400
		fi
		sleep 0.7
		DIRTY=1
	done
	return 1
}
crashed() { adb_ logcat -d -b crash 2>/dev/null | grep -A2 "FATAL EXCEPTION" | grep -q "Process: $PKG"; }
tap_edit() {
	local b
	b=$(dump | tr '>' '\n' | grep -F 'class="android.widget.EditText"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	[ -z "$b" ] && return 1
	set -- $b
	adb_ shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}
pref_has_any() { adb_ shell run-as "$PKG" cat shared_prefs/quill.xml 2>/dev/null | grep -q "$1"; }
# Edge and anywhere follow one rule: hold ~0.45 s, then drag (x1 y x2)
hold_drag() { adb_ shell "input motionevent DOWN $1 $2; sleep 0.65; input motionevent MOVE $((($1 + $3) / 2)) $2; input motionevent MOVE $3 $2; input motionevent UP $3 $2"; nap 1; }
side_on() { native 'text="Sessions"' && return 0; tap_text "Session list edge"; nap 2; wait_for 'text="Sessions"' 5; }
side_off() { [ "$(width_dp)" -lt 600 ] || return 0; native 'text="Sessions"' || return 0; tap_text "Session list edge"; nap 2; }
bounds_of() { dump | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' '; }
lpress() {
	local b
	b=$(bounds_of "$1")
	[ -z "$b" ] && return 1
	set -- $b
	adb_ shell input swipe $((($1 + $3) / 2)) $((($2 + $4) / 2)) $((($1 + $3) / 2)) $((($2 + $4) / 2)) 900
}
# centered "<text>": the menu entry sits in the middle of the screen (a centered menu, not a popup at the finger)
centered() {
	local b size w h x y
	b=$(bounds_of "$1")
	[ -z "$b" ] && return 1
	size=$(adb_ shell wm size | tail -1 | awk '{print $3}')
	w=${size%x*}
	h=${size#*x}
	case "$(adb_ shell dumpsys window displays | grep -m1 -o 'mCurrentRotation=ROTATION_[0-9]*')" in
	*_90|*_270) x=$w; w=$h; h=$x ;;
	esac
	set -- $b
	x=$((($1 + $3) / 2))
	y=$((($2 + $4) / 2))
	[ $((x * 10)) -gt $((w * 4)) ] && [ $((x * 10)) -lt $((w * 6)) ] && [ $((y * 10)) -gt $((h * 2)) ] && [ $((y * 10)) -lt $((h * 8)) ]
}
width_dp() {
	local size dens w h
	size=$(adb_ shell wm size | tail -1 | awk '{print $3}')
	dens=$(adb_ shell wm density | tail -1 | awk '{print $3}')
	w=${size%x*}
	echo $((w * 160 / dens))
}
launch() { adb_ shell am start -W -n "$PKG/$ACT" "$@" >/dev/null 2>&1; }

log "Quill for Android emulator test: $AVD  ($(date))"

if [ -n "$DEVICE" ]; then
	"$ADB" connect "$DEVICE" >/dev/null 2>&1
	SERIAL=$DEVICE
elif ! "$EMU" -list-avds | grep -qx "$AVD"; then
	case "$AVD" in
	qphone) dev=pixel_8 ;;
	qfold) dev=pixel_fold ;;
	qtab) dev=pixel_tablet ;;
	qp90) dev=custom ;;
	*) dev=pixel_8 ;;
	esac
	if [ "$dev" = custom ]; then
		echo no | "$AVDMGR" create avd -n "$AVD" -k "system-images;android-36;google_apis;x86_64" >/dev/null || { log "cannot create AVD $AVD"; exit 2; }
		cfg="$HOME/.android/avd/$AVD.avd/config.ini"
		sed -i '/^hw.lcd.width=/d;/^hw.lcd.height=/d;/^hw.lcd.density=/d;/^hw.keyboard=/d;/^hw.ramSize=/d' "$cfg"
		printf 'hw.lcd.width=1200\nhw.lcd.height=1920\nhw.lcd.density=240\nhw.keyboard=yes\nhw.ramSize=3072\n' >> "$cfg"
	else
		echo no | "$AVDMGR" create avd -n "$AVD" -k "system-images;android-36;google_apis;x86_64" -d "$dev" >/dev/null || { log "cannot create AVD $AVD"; exit 2; }
	fi
fi

[ -z "$DEVICE" ] && SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1}' | while read -r s; do
	[ "$(timeout 5 "$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] && echo "$s"
done | head -1)
if [ -z "$SERIAL" ] && [ -z "$DEVICE" ]; then
	port=5554
	while "$ADB" devices | grep -q "emulator-$port"; do port=$((port + 2)); done
	SERIAL=emulator-$port
	flags=(-no-audio -no-snapshot -no-boot-anim -gpu host -feature -Vulkan -port "$port")
	[ "$WINDOW" = 0 ] && flags+=(-no-window)
	"$EMU" -avd "$AVD" "${flags[@]}" > "$OUT/emulator.log" 2>&1 &
	EMU_PID=$!
	for i in $(seq 1 120); do
		[ "$(timeout 5 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
		nap 2
	done
fi
[ "$(adb_ shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { log "emulator did not boot"; exit 2; }
log "device $SERIAL"

(cd "$ROOT" && ./gradlew -q assembleDebug) || { log "build failed"; exit 2; }
adb_ uninstall "$PKG" >/dev/null 2>&1
adb_ install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null || { log "install failed"; exit 2; }
if [ -z "$DEVICE" ]; then
	adb_ shell settings put global window_animation_scale 0
	adb_ shell settings put global transition_animation_scale 0
	adb_ shell settings put global animator_duration_scale 0
	adb_ shell settings put system fold_lock_behavior_setting stay_awake_on_fold_key
	adb_ shell settings put system screen_off_timeout 1800000
	adb_ shell locksettings set-disabled true >/dev/null 2>&1
fi
if [ -n "$DEVICE" ]; then
	SAVED="$HOME/.cache/quill-emu-anim-$(echo "$SERIAL" | tr -c 'A-Za-z0-9' _)"
	if [ -s "$SAVED" ]; then
		ANIM=$(cat "$SAVED")
		restore_anim
		log "restored animation settings left over from an interrupted run"
	fi
	ANIM="$(adb_ shell settings get global window_animation_scale | tr -d '\r') $(adb_ shell settings get global transition_animation_scale | tr -d '\r') $(adb_ shell settings get global animator_duration_scale | tr -d '\r')"
	mkdir -p "$HOME/.cache"
	echo "$ANIM" > "$SAVED"
	echo "$ANIM" > "$OUT/animations-before.txt"
	adb_ shell settings put global window_animation_scale 0
	adb_ shell settings put global transition_animation_scale 0
	adb_ shell settings put global animator_duration_scale 0
fi
HOSTIP=10.0.2.2
if [ -n "$DEVICE" ]; then
	DEVIP=$(adb_ shell ip -4 -o addr show wlan0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | tr -d '\r')
	HOSTIP=$(ip -4 route get "${DEVIP:-${DEVICE%%:*}}" 2>/dev/null | grep -o 'src [0-9.]*' | awk '{print $2}')
	log "device ip $DEVIP, this PC $HOSTIP"
fi
LOGQUIET=$(adb_ shell getprop log.tag | tr -d '\r' | grep -x -E '[EWAFS].*')
adb_ logcat -c
adb_ logcat -b crash -c
adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
launch
sleep 2
adb_ install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
for i in $(seq 1 40); do adb_ shell dumpsys notification --noredact 2>/dev/null | grep -q -F "Quill updated to" && break; sleep 0.25; done
adb_ shell dumpsys notification --noredact 2>/dev/null | grep -q -F "Quill updated to" && ok "update finished notification" || bad "update finished notification"
adb_ shell cmd notification cancel_all "$PKG" >/dev/null 2>&1 || true

mkdir -p "$WORK/www/code"
for s in A1:Fix-edits B2:Old-server-notes C3:Zomboid-launcher D4:Unsorted-idea; do
	id=session_${s%%:*}
	mkdir -p "$WORK/www/code/$id"
done
page='<!doctype html><html><head><meta name="viewport" content="width=device-width"></head><body><a href="/code/" style="position:absolute;top:4px;left:8px">Code</a><div role="dialog" id="rcdlg" style="margin-top:40px">Remote Control is on for this session. <a href="https://docs.claude.com/remote-control">Learn more</a> <button onclick="document.getElementById(&quot;rcdlg&quot;).remove()">Dismiss</button></div><div class="dframe-root" style="position:relative;--df-sidebar-width:220px"><aside class="dframe-sidebar" style="position:absolute;left:0;top:0;width:220px"><span>Claude Code header</span></aside><div class="dframe-content-inner" style="padding-left:var(--df-sidebar-width)"><span>Pane start</span></div></div><nav style="position:relative"><a href="/x/artifacts">Artifacts</a><a href="/x/customize">Customize</a><button>More</button><a href="/x/scheduled">Scheduled</a><button>Edit sidebar</button>'
for s in A1:Fix-edits B2:Old-server-notes C3:Zomboid-launcher D4:Unsorted-idea; do
	page="$page<a href=\"/code/session_${s%%:*}\" style=\"display:block;padding:8px\">$(echo "${s#*:}" | tr '-' ' ')</a>"
done
page="$page<a href=\"https://accounts.google.com/o/oauth2/v2/auth?client_id=test\" style=\"display:block;padding:8px\">Continue with Google test</a>"
page="$page<a href=\"https://github.com/login/oauth/authorize?client_id=quilltest\" style=\"display:block;padding:8px\">GitHub connect test</a>"
page="$page<a href=\"https://github.com/katachinosekai/quill\" style=\"display:block;padding:8px\">GitHub repo test</a>"
page="$page</nav><main><p>Mock session page for emulator tests.</p><p id=\"sent\"></p><form onsubmit=\"event.preventDefault();var t=this.querySelector('textarea');document.getElementById('sent').textContent='sent:'+t.value.replace(/\\n/g,'|');t.value=''\"><textarea aria-label=\"Message box\"></textarea><button type=\"submit\" aria-label=\"Send message\">Send</button></form></main></body></html>"
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
nap 4
shot 01-start
crashed && bad "launch" "crash in logcat" || ok "launch"
has 'content-desc="Settings"' && ok "top bar" || bad "top bar"
adb_ shell am force-stop "$PKG"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/ --es quill.testUpdateBlocked INSTALL_FAILED_BLOCKED
wait_for 'Update blocked by your phone' 8 && ok "blocked-update help dialog" || bad "blocked-update help dialog"
shot 01b-update-blocked
has 'Auto Blocker' && has 'Install anyway' && ok "blocked-update dialog explains Auto Blocker and Play Protect" || bad "blocked-update dialog explains Auto Blocker and Play Protect"
tap_text "LATER" >/dev/null 2>&1 || tap_text "Later" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
nap 1

wide=$(width_dp)
log "screen width ${wide}dp"
if [ "$wide" -ge 600 ]; then
	wait_for 'text="Fix edits"' 20 && ok "vertical sidebar lists sessions" || bad "vertical sidebar lists sessions"
	has 'content-desc="Sidebar"' && bad "no arrow button (the list edge replaces it)" || ok "no arrow button (the list edge replaces it)"
	has 'content-desc="Session list edge"' && ok "session list edge shown" || bad "session list edge shown"
	tap_text "Zomboid launcher" && nap 3
	adb_ shell dumpsys activity top | grep -q "$PKG" && ok "open session from sidebar" || bad "open session from sidebar"
	lpress "Fix edits" && nap 2
	centered "Open" && ! has 'text="Open in new tab"' && ! has 'text="Close tab"' && ok "sidebar long-press: centered session menu, no tab options" || bad "sidebar long-press: centered session menu, no tab options"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
	hb=$(dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	if [ -n "$hb" ]; then
		read -r hx1 hy1 hx2 hy2 <<< "$hb"
		hx=$(((hx1 + hx2) / 2)); hy=$(((hy1 + hy2) / 2))
		hold_drag $hx $hy $((hx - 200))
		nap 2
		DIRTY=1
		nb=$(dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="\[[0-9]*' | grep -o '[0-9]*$')
		[ -n "$nb" ] && [ "$nb" -lt $((hx1 - 100)) ] && pref_has_any 'sideW.portrait' && ok "portrait: hold and drag the edge resizes the session list" || bad "portrait: hold and drag the edge resizes the session list" "$hx1 -> $nb"
		hpos() { DIRTY=1; dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="\[[0-9]*' | grep -o '[0-9]*$'; }
		hold_drag $((nb + 5)) $hy 2
		nap 2
		cb=$(hpos)
		[ -n "$cb" ] && [ "$cb" -lt 40 ] && pref_has_any 'name="sideOpen" value="false"' && ok "portrait: drag to the left edge closes the list (edge stays)" || bad "portrait: drag to the left edge closes the list (edge stays)" "edge at $cb"
		sw=$(adb_ shell wm size | tail -1 | awk '{print $3}'); sw=${sw%x*}
		hold_drag $((cb + 10)) $hy $((sw - 2))
		nap 2
		fb=$(hpos)
		[ -n "$fb" ] && [ "$fb" -gt $((sw - 80)) ] && pref_has_any 'name="sideOpen" value="true"' && ok "portrait: drag to the right edge opens the list fully" || bad "portrait: drag to the right edge opens the list fully" "edge at $fb of $sw"
		hold_drag $((fb + 5)) $hy $hx
		nap 2
		tb=$(hpos)
		adb_ shell input tap $((tb + 10)) $hy
		nap 2
		cb=$(hpos)
		[ -n "$cb" ] && [ "$cb" -lt 40 ] && ok "portrait: tapping the edge closes the list" || bad "portrait: tapping the edge closes the list" "edge at $cb"
		adb_ shell input tap 15 $hy
		nap 2
		ob=$(hpos)
		[ -n "$ob" ] && [ "$ob" -gt 200 ] && ok "portrait: tapping the closed edge opens it again" || bad "portrait: tapping the closed edge opens it again" "edge at $ob"
	else
		bad "portrait: hold and drag the edge resizes the session list" "no handle"
	fi
	# Landscape: the edge drags too, from any height (user: grabs at 1/4 and 3/4 height failed)
	adb_ shell settings put system accelerometer_rotation 0
	adb_ shell settings put system user_rotation 1
	nap 4
	DIRTY=1
	lb=$(dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	if [ -n "$lb" ]; then
		read -r lx1 ly1 lx2 ly2 <<< "$lb"
		lcx=$(((lx1 + lx2) / 2))
		for q in 1 3; do
			ly=$((ly1 + (ly2 - ly1) * q / 4))
			hold_drag $lcx $ly $((lcx + 150))
			nap 2
			nx=$(hpos)
			[ -n "$nx" ] && [ "$nx" -gt $((lx1 + 80)) ] && ok "landscape: drag the edge at $q/4 height" || bad "landscape: drag the edge at $q/4 height" "$lx1 -> $nx"
			hold_drag $((nx + 9)) $ly $lcx
			nap 2
		done
	else
		bad "landscape: drag the edge at 1/4 height" "no edge"
	fi
	adb_ shell settings put system user_rotation 0
	nap 4
	# Hold anywhere ~0.45 s, then move sideways: the list edge follows the finger
	hb2=$(dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	if [ -n "$hb2" ]; then
		read -r gx1 gy1 gx2 gy2 <<< "$hb2"
		read -r GW GH <<< "$(scr)"
		gy=$(((gy1 + gy2) / 2))
		gx=$((gx2 + 150))
		hold_drag $gx $gy $((gx + 160))
		nap 2
		DIRTY=1
		hx=$(hpos)
		[ -n "$hx" ] && [ "$hx" -gt $((gx + 100)) ] && [ "$hx" -lt $((gx + 200)) ] && ok "hold anywhere then drag pulls the list edge to the finger" || bad "hold anywhere then drag pulls the list edge to the finger" "$gx1 -> $hx (finger at $((gx + 160)))"
		hold_drag $((hx + 9)) $gy $(((gx1 + gx2) / 2))
		nap 2
	fi
	# Slow grab just beside the strip (chat side): a held, slow drag resizes the list and opens no row menu
	sb=$(dump | tr '>' '\n' | grep -F 'content-desc="Session list edge"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	if [ -n "$sb" ]; then
		read -r sx1 sy1 sx2 sy2 <<< "$sb"
		sy=$((sy1 + (sy2 - sy1) / 3))
		hold_drag $((sx2 + 20)) $sy $((sx2 + 220))
		nap 2
		DIRTY=1
		nx=$(hpos)
		[ -n "$nx" ] && [ "$nx" -gt $((sx1 + 120)) ] && ! has 'text="Rename..."' && ! has 'text="Expand all folders"' && ok "hold beside the edge then drag (no row menu)" || bad "hold beside the edge then drag (no row menu)" "$sx1 -> $nx"
		hold_drag $((nx + 9)) $sy $(((sx1 + sx2) / 2))
		nap 2
	fi
	tap_text "Session list edge" && nap 2
	shot 02-sidebar-closed
	native_gone 'text="Sessions"' 5 && ok "tapping the edge hides the list" || bad "tapping the edge hides the list"
	has 'content-desc="Session list edge"' && ok "closed list keeps its edge" || bad "closed list keeps its edge"
	tap_text "Session list edge" && nap 2
	wait_for 'text="Sessions"' 5 && ok "tapping the edge shows the list" || bad "tapping the edge shows the list"
fi

if [ "$AVD" = qfold ] && [ -z "$DEVICE" ]; then
	adb_ emu fold >/dev/null
	nap 4
	awake
	shot 03-folded
	log "folded width $(width_dp)dp"
	crashed && bad "fold" "crash" || ok "fold without crash"
	has 'Close tab' && bad "folded: no tabs" || ok "folded: no tabs"
	adb_ emu unfold >/dev/null
	nap 4
	awake
	wait_for 'text="Sessions"' 10 && ok "unfold: sidebar back" || bad "unfold: sidebar back"
	adb_ emu fold >/dev/null
	nap 4
	awake
fi

if [ "$(width_dp)" -lt 600 ]; then
	has 'content-desc="Session list edge"' && ok "phone: list edge instead of the arrow" || bad "phone: list edge instead of the arrow"
	native 'text="Sessions"' || { tap_text "Session list edge"; nap 2; }
	wait_for 'text="Unsorted idea"' 15 && ok "phone: folder list with its sessions" || bad "phone: folder list with its sessions"
	shot 04-folder-list
	has 'Close tab' && bad "phone: no tabs" || ok "phone: no tabs"
	has 'content-desc="Switch screen orientation"' && bad "phone: no Landscape/Portrait button" || ok "phone: no Landscape/Portrait button"
	lpress "Unsorted idea" && nap 2
	centered "Open" && ! has 'text="Open in new tab"' && ok "phone: long-press opens a centered session menu" || bad "phone: long-press opens a centered session menu"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
	lpress "$(dump | tr '>' '\n' | grep -o 'text="Other  [0-9]*"' | head -1 | sed 's/^text="//; s/"$//')" && nap 2
	centered "Collapse all folders" && ! has 'text="Open all in tabs"' && ok "phone: folder menu (expand/collapse only)" || bad "phone: folder menu (expand/collapse only)"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
	tap_text "Unsorted idea" && nap 2
	native_gone 'text="Sessions"' 8 && ok "phone: picking a session closes the list" || bad "phone: picking a session closes the list"
	wait_for 'content-desc="Open session: ' 8 && has 'text="Quill"' && ok "phone: session name bar under the top bar while the list is closed" || bad "phone: session name bar under the top bar while the list is closed"
	adb_ shell input tap 10 10 >/dev/null
fi

scr() { local size w h; size=$(adb_ shell wm size | tail -1 | awk '{print $3}'); w=${size%x*}; h=${size#*x}; case "$(adb_ shell dumpsys window displays | grep -m1 -o 'mCurrentRotation=ROTATION_[0-9]*')" in *_90|*_270) echo "$h $w" ;; *) echo "$w $h" ;; esac; }
read -r SW SH <<< "$(scr)"
swipe_l() { adb_ shell input swipe $((SW * 4 / 5)) $((SH / 2)) $((SW / 4)) $((SH / 2)) 250; nap 2; }
swipe_r() { adb_ shell input swipe $((SW / 4)) $((SH / 2)) $((SW * 4 / 5)) $((SH / 2)) 250; nap 2; }
if native 'text="Sessions"'; then
	swipe_l
	native_gone 'text="Sessions"' 5 && ok "swipe left anywhere closes the list" || bad "swipe left anywhere closes the list"
	swipe_r
	wait_for 'text="Sessions"' 5 && ok "swipe right anywhere opens the list" || bad "swipe right anywhere opens the list"
else
	swipe_r
	wait_for 'text="Sessions"' 5 && ok "swipe right anywhere opens the list" || bad "swipe right anywhere opens the list"
	swipe_l
	native_gone 'text="Sessions"' 5 && ok "swipe left anywhere closes the list" || bad "swipe left anywhere closes the list"
fi

for j in Artifacts Customize More Scheduled "Edit sidebar"; do has "text=\"$j\"" && bad "hidden: $j" || ok "hidden: $j"; done
has 'text="Code"' && bad "hidden: Code label" || ok "hidden: Code label"
has 'Claude Code header' && bad "hidden: claude.ai sidebar (dframe)" || ok "hidden: claude.ai sidebar (dframe)"
wl=$(dump | tr '>' '\n' | grep -F 'class="android.webkit.WebView"' | head -1 | grep -o 'bounds="\[[0-9]*' | grep -o '[0-9]*$')
pl=$(dump | tr '>' '\n' | grep -F 'text="Pane start"' | head -1 | grep -o 'bounds="\[[0-9]*' | grep -o '[0-9]*$')
[ -n "$wl" ] && [ -n "$pl" ] && [ $((pl - wl)) -lt 60 ] && ok "claude.ai pane starts at the left edge" || bad "claude.ai pane starts at the left edge" "webview=$wl pane=$pl"
has 'Remote Control is on' && bad "Remote Control Learn-more prompt closed" || ok "Remote Control Learn-more prompt closed"
tap_edit && nap 1
adb_ shell input text hello
adb_ shell input keyevent KEYCODE_ENTER
nap 2
dump | grep -q -i 'text="sent:hello"' && ok "Enter sends" || bad "Enter sends" "$(dump | grep -o 'sent:[^"]*' | head -1)"
tap_edit && nap 1
adb_ shell input text one
adb_ shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_ENTER
adb_ shell input text two
nap 1
dump | grep -q -i 'text="sent:hello"' && ok "Shift+Enter makes a line break (not sent)" || bad "Shift+Enter makes a line break (not sent)"
adb_ shell input keyevent KEYCODE_ENTER
nap 2
dump | grep -q -i 'sent:one|two' && ok "multi-line message sent with Enter" || bad "multi-line message sent with Enter" "$(dump | grep -o 'sent:[^"]*' | head -1)"
adb_ shell am force-stop "$PKG"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
nap 4
bars0=$(adb_ shell run-as "$PKG" cat shared_prefs/quill.xml 2>/dev/null | grep -o -E 'name="(barsHidden|sideOpen)" value="[a-z]*"' | tr '\n' ' ')
adb_ shell input keyevent KEYCODE_ENTER
adb_ shell input keyevent KEYCODE_ENTER
nap 1
bars1=$(adb_ shell run-as "$PKG" cat shared_prefs/quill.xml 2>/dev/null | grep -o -E 'name="(barsHidden|sideOpen)" value="[a-z]*"' | tr '\n' ' ')
[ "$bars0" = "$bars1" ] && ! dump | tr '>' '\n' | grep -F 'focused="true"' | grep -q -F 'class="android.widget.TextView"' && ok "Enter after a cold start stays in the page (top bar not focused)" || bad "Enter after a cold start stays in the page (top bar not focused)" "$bars0 -> $bars1"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
nap 3
tap_text "Continue with Google test" && nap 2
shot 04b-google-intercept
has 'text="Sign in with email"' && ok "Google sign-in intercepted with help dialog" || bad "Google sign-in intercepted with help dialog"
top_resumed() { adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity"; }
for i in $(seq 1 20); do top_resumed | grep -q "$PKG/" && break; sleep 0.25; done
top_resumed | grep -q "$PKG/" && ok "Google link stays in app" || bad "Google link stays in app" "$(top_resumed)"
tap_text "OK" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
nap 1
tap_text "GitHub connect test" && nap 5
adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q "$PKG" && ok "GitHub sign-in stays in Quill" || bad "GitHub sign-in stays in Quill" "$(adb_ shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity')"
gone 'Mock session page' 15 && ok "GitHub page loaded in the app" || bad "GitHub page replaced the mock (skin check skipped)"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
nap 3
tap_text "GitHub repo test" && nap 4
adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q "$PKG" && bad "GitHub repo link opens outside" || ok "GitHub repo link opens outside"
adb_ shell am force-stop com.android.chrome >/dev/null 2>&1
launch --es quill.testUrl http://127.0.0.1:8765/login/
nap 3
wait_for 'content-desc="Sign-in tip"' 8 && ok "sign-in tip on login page" || bad "sign-in tip on login page"
tap_text "Sign-in tip" && nap 1
gone 'content-desc="Sign-in tip"' 5 && ok "sign-in tip dismissed" || bad "sign-in tip dismissed"
launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
nap 3

if has 'text="A+"' || has 'text="A−"'; then bad "A+/A− removed from top bar"; else ok "A+/A− removed from top bar"; fi
pinch() {
	adb_ shell am force-stop "$PKG"
	launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/ --ef quill.testPinch "$1"
	nap 3
	tap_text "Settings" && nap 2
	has "Text size  $2" && ok "pinch x$1 -> $2" || bad "pinch x$1 -> $2" "$(dump | grep -o 'Text size  [0-9]*%' | head -1)"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
}
pinch 1.5 175%
pinch 0.5 90%
pinch 10 200%
pinch 0.1 80%
pinch 1.4375 115%
pinch 1.5 175%
tap_text "Settings" && nap 2
tap_text "Reset" && nap 1
has 'Text size  115%' && ok "settings: Reset text size" || bad "settings: Reset text size"
shot 05c-settings-top
tap_scroll "Check for updates" >/dev/null 2>&1
shot 05d-settings-bottom
has 'text="About"' && has 'text="Sign-in"' && ok "settings sections: Sign-in, About" || bad "settings sections: Sign-in, About"
adb_ shell input keyevent KEYCODE_BACK
nap 1

tap_text "Settings" && nap 2
shot 05-settings
has 'text="Folders from Quill"' && ok "settings dialog" || bad "settings dialog"
if has 'Chat width' || has 'Message box width'; then bad "width sliders removed"; else ok "width sliders removed"; fi
has 'text="Not paired  ·  pair from the Devices button in the Quill Desktop top bar"' && ok "settings: not paired" || bad "settings: not paired"
for sec in Text "Folders from Quill"; do has "text=\"$sec\"" && ok "settings section: $sec" || bad "settings section: $sec"; done
has 'Text size  115%' && ok "settings: text size row" || bad "settings: text size row"
tap_scroll "Open claude.ai links in Quill Android"
for i in $(seq 1 10); do
	adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q -i settings && break
	nap 1
done
adb_ shell dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" | grep -q -i settings && ok "link settings open" || bad "link settings open" "$(adb_ shell dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity')"
adb_ shell input keyevent KEYCODE_BACK
nap 2
adb_ shell dumpsys activity top | grep -E "^ *ACTIVITY" | tail -1 | grep -q "$PKG" || launch
nap 1
tap_text "Done" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
nap 1

if [ -d "$QUILL/internal/folders" ] && command -v go >/dev/null; then
	mkdir -p "$WORK/fd"
	cp "$ROOT/scripts/testdata/foldersd.go" "$WORK/fd/main.go"
	printf 'module github.com/katachinosekai/quill/emutest\n\ngo 1.26\n\nrequire github.com/katachinosekai/quill v0.0.0\n\nreplace github.com/katachinosekai/quill => %s\n' "$QUILL" > "$WORK/fd/go.mod"
	(cd "$WORK/fd" && GOFLAGS=-mod=mod go build -o foldersd . 2> "$OUT/foldersd-build.log")
	KEY=emutest_0123456789abcdef
	MAP='{"session_A1":"Active","session_B2":"Archive","session_C3":"Zomboid"}'
	echo '[]' > "$WORK/fd/events.json"
	adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
	"$WORK/fd/foldersd" "$WORK/fd/cert" 0 "$KEY" "$MAP" "$WORK/fd/events.json" > "$WORK/fd/out" 2>"$OUT/foldersd.log" &
	FOLD_PID=$!
	for i in $(seq 1 20); do [ -s "$WORK/fd/out" ] && break; sleep 0.5; done
	read -r FPORT FP < "$WORK/fd/out"
	if [ -n "${FPORT:-}" ]; then
		LINK="quillpair://pair?h=$HOSTIP&p=$FPORT&k=$KEY&fp=$FP&f=quill-folders.json&n=emu-test"
		adb_ shell am start -W -a android.intent.action.VIEW -d "\"$LINK\"" "$PKG" >/dev/null 2>&1
		nap 6
		shot 06-paired
		side_on
		wait_for 'text="Active  1"' 40 && ok "pairing: folders from local network" || bad "pairing: folders from local network"
		has 'text="Zomboid  1"' && has 'text="Archive  1"' && ok "pairing: grouping" || bad "pairing: grouping"
		wait_for '64%' 40 && has '5-hour session' && ok "usage from Quill" || bad "usage from Quill"
		wait_for '12 msgs  ·  Zomboid Tester' 10 && ok "session row shows the kmem name (age · msgs · kmem)" || bad "session row shows the kmem name (age · msgs · kmem)"
		notif() { adb_ shell dumpsys notification --noredact 2>/dev/null | grep -c -F "$1"; }
		now() { echo $(( $(date +%s) * 1000 )); }
		nap 4
		EVS=""
		addev() { EVS="$EVS${EVS:+,}{\"time\":$1,\"type\":\"$2\",\"rc\":\"$3\",\"quill\":\"q\",\"title\":\"$4\",\"folder\":\"$5\"}"; echo "[$EVS]" > "$WORK/fd/events.json"; }
		addev "$(now)" approval session_C3 "Zomboid launcher" Zomboid
		addev "$(now)" finished session_A1 "Fix edits" Active
		for i in $(seq 1 20); do [ "$(notif 'Approval needed: Zomboid launcher')" -ge 1 ] && break; sleep 1; done
		[ "$(notif 'Approval needed: Zomboid launcher')" -ge 1 ] && ok "notify: approval for another session" || bad "notify: approval for another session"
		[ "$(notif 'Finished: Fix edits')" = 0 ] && ok "notify: silent for the session on screen" || bad "notify: silent for the session on screen"
		addev 1000 error session_B2 "Stale event" Archive
		nap 4
		[ "$(notif 'Stale event')" = 0 ] && ok "notify: ignores stale events" || bad "notify: ignores stale events"
		adb_ shell cmd statusbar collapse >/dev/null 2>&1
		sleep 4
		tap_text "Settings" && wait_for 'text="Notifications"' 8
		shot 06e-settings-before-bg
		tap_scroll "Notify in background"; log "bg tap rc=$?"
		nap 3
		shot 06f-settings-after-bg
		log "bg pref: $(adb_ shell run-as "$PKG" cat shared_prefs/quill.xml 2>/dev/null | tr '<' '\n' | grep notifyBackground)"
		adb_ shell input keyevent KEYCODE_BACK
		nap 1
		for i in $(seq 1 40); do adb_ shell dumpsys activity services "$PKG" | grep -q NotifyService && break; sleep 0.25; done
		adb_ shell dumpsys activity services "$PKG" | grep -q NotifyService && ok "background service starts" || bad "background service starts"
		for i in $(seq 1 40); do [ "$(notif 'Quill is watching for approvals')" -ge 1 ] && break; sleep 0.25; done
		[ "$(notif 'Quill is watching for approvals')" -ge 1 ] && ok "background service notification shown" || bad "background service notification shown"
		adb_ shell input keyevent KEYCODE_HOME
		nap 2
		addev "$(now)" question session_B2 "Old server notes" Archive
		for i in $(seq 1 30); do [ "$(notif 'Question: Old server notes')" -ge 1 ] && break; sleep 1; done
		[ "$(notif 'Question: Old server notes')" -ge 1 ] && ok "notify in background (app closed)" || bad "notify in background (app closed)"
		shot 06c-notifications
		adb_ logcat -c
		adb_ shell am start -W -n "$PKG/$ACT" --es quill.openSession session_C3 >/dev/null 2>&1
		for i in $(seq 1 40); do adb_ logcat -d -s QuillOpen:I | grep -q session_C3 && break; sleep 0.25; done
		if adb_ logcat -d -s QuillOpen:I | grep -q session_C3; then
			ok "notification opens its session"
		elif [ -n "$LOGQUIET" ]; then
			log "SKIP  notification opens its session  (device hides info logs: log.tag=$LOGQUIET)"
		else
			bad "notification opens its session"
		fi
		launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
		nap 3
		tap_text "Settings" && wait_for 'text="Notifications"' 8
		tap_scroll "Notify in background" && nap 2
		adb_ shell input keyevent KEYCODE_BACK
		nap 2
		adb_ shell dumpsys activity services "$PKG" | grep -q NotifyService && bad "background service stops" || ok "background service stops"
		kill -INT "$FOLD_PID" 2>/dev/null
		FOLD_PID=""
		adb_ shell am force-stop "$PKG"
		launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
		nap 5
		side_on
		wait_for 'text="Active  1"' 15 && ok "cached folders after restart (Quill offline)" || bad "cached folders after restart"
		tap_text "Settings" && nap 2
		has 'Paired with emu-test' && ok "settings: paired status" || bad "settings: paired status"
		tap_scroll "Unpair" && nap 1
		tap_text "Done" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
		nap 3
		side_on
		wait_for 'text="Other  4"' 10 && ok "unpair clears folders" || bad "unpair clears folders"
		adb_ shell am start -W -a android.intent.action.VIEW -d '"quillpair://pair?h=bad%20host&p=1&k=x&fp=zz"' "$PKG" >/dev/null 2>&1
		nap 2
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
	nap 8
	return 0
}
upd_log() { adb_ logcat -d -s QuillUpdate:I 2>/dev/null; }

if [ "$FULL" = 1 ]; then
adb_ shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
head -c 300000 /dev/urandom > "$WORK/junk.apk"
if update_case junk "$WORK/junk.apk" ""; then
	upd_log | grep -q "not a valid Quill Android APK" && ok "update: rejects invalid APK" || bad "update: rejects invalid APK" "$(upd_log | tail -1)"
else
	bad "update: banner for newer release"
fi
if update_case tamper "$WORK/junk.apk" "0000000000000000000000000000000000000000000000000000000000000000"; then
	upd_log | grep -q "checksum does not match" && ok "update: rejects checksum mismatch" || bad "update: rejects checksum mismatch" "$(upd_log | tail -1)"
fi
(cd "$ROOT" && ./gradlew -q assembleRelease -PqVersionCode=999 -PqVersionName=99.0.0 >/dev/null 2>&1) && cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$WORK/otherkey.apk"
(cd "$ROOT" && ./gradlew -q assembleRelease >/dev/null 2>&1)
if [ -f "$WORK/otherkey.apk" ] && update_case otherkey "$WORK/otherkey.apk" ""; then
	upd_log | grep -q -E "not signed by the Quill Android key|for a different app" && ok "update: rejects APK signed with another key" || bad "update: rejects APK signed with another key" "$(upd_log | tail -1)"
fi
(cd "$ROOT" && ./gradlew -q assembleDebug -PqVersionCode=999 -PqVersionName=99.0.0 >/dev/null 2>&1) && cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$WORK/good.apk"
(cd "$ROOT" && ./gradlew -q assembleDebug >/dev/null 2>&1)
if [ -f "$WORK/good.apk" ] && update_case good "$WORK/good.apk" ""; then
	shot 06b-install-confirm
	tap_text "Update" >/dev/null 2>&1 || tap_text "UPDATE" >/dev/null 2>&1 || tap_text "Install" >/dev/null 2>&1
	for i in $(seq 1 20); do
		v=$(adb_ shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')
		[ "$v" = "versionName=99.0.0" ] && break
		nap 1
	done
	[ "$v" = "versionName=99.0.0" ] && ok "update: installs a genuine newer build" || bad "update: installs a genuine newer build" "$v; $(upd_log | tail -1)"
	adb_ uninstall "$PKG" >/dev/null 2>&1
	adb_ install "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
	nap 3
	adb_ logcat -b crash -c
	launch --es quill.testUrl http://127.0.0.1:8765/code/session_A1/
	nap 3
fi

fi
if [ -z "$DEVICE" ]; then
adb_ shell settings put system accelerometer_rotation 0
adb_ shell settings put system user_rotation 1
nap 4
shot 07-rotated
crashed && bad "rotation" "crash" || ok "rotation"
has 'content-desc="Settings"' && ok "rotation keeps UI" || bad "rotation keeps UI"
adb_ shell settings put system user_rotation 0
fi
nap 2

if [ "$FULL" = 1 ]; then
launch --es quill.testUrl chrome://crash
nap 6
shot 08-after-renderer-crash
crashed && bad "renderer crash recovery" "app crashed" || ok "renderer crash recovery (no app crash)"
wait_for 'content-desc="Settings"' 10 && ok "UI back after renderer crash" || bad "UI back after renderer crash"

fi
adb_ shell input keyevent KEYCODE_BACK
nap 2
crashed && bad "back" "crash" || ok "back"

if command -v go >/dev/null; then
	mkdir -p "$WORK/ph"
	cp "$ROOT/scripts/testdata/phoned.go" "$WORK/ph/main.go"
	printf 'module phoned\n\ngo 1.26\n' > "$WORK/ph/go.mod"
	(cd "$WORK/ph" && go build -o phoned . 2> "$OUT/phoned-build.log")
	PKEY=phonetest_0123456789abcdef
	"$WORK/ph/phoned" 0 "$PKEY" > "$WORK/ph/out" 2> "$OUT/phoned.log" &
	PHONE_PID=$!
	for i in $(seq 1 20); do [ -s "$WORK/ph/out" ] && break; sleep 0.5; done
	read -r PPORT PFP < "$WORK/ph/out"
fi
if [ -n "${PPORT:-}" ]; then
	pcurl() { curl -sk -H "X-Quill-Key: $PKEY" "https://127.0.0.1:$PPORT$1"; }
	plog() { pcurl /debug/log; }
	# A1 open natively: the app's last opened session is A1 and the native message box is on screen (A1's first message
	# may be scrolled away once its history grows, so don't rely on it)
	a1_open() { local i; for i in $(seq 1 $((${1:-10} * 4))); do DIRTY=1; pref_has 'name="remote.last">session_A1<' && has 'content-desc="Message box' && return 0; sleep 0.25; done; return 1; }
	wait_log() { local i; for i in $(seq 1 40); do plog | grep -q "$1" && return 0; sleep 0.25; done; return 1; }
	ime_off() { adb_ shell dumpsys input_method | grep -q 'mInputShown=true' && adb_ shell input keyevent KEYCODE_BACK && nap 1; }
	popen() { adb_ shell am start -W -f 0x24000000 -n "$PKG/$ACT" --es quill.openQuill "$1" >/dev/null 2>&1; }
	pnotif() { adb_ shell dumpsys notification --noredact 2>/dev/null | grep -c -F "$1"; }
	pref_has() { adb_ shell run-as "$PKG" cat shared_prefs/quill.xml 2>/dev/null | grep -q "$1"; }
	adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
	PLINK="quillpair://pair?h=$HOSTIP&p=$PPORT&k=$PKEY&fp=$PFP&n=emu-phone&i=a1b2c3d4&v=2"
	adb_ shell am start -W -a android.intent.action.VIEW -d "\"$PLINK\"" "$PKG" >/dev/null 2>&1
	wait_for 'text="Sessions"' 20 && side_off
	wait_for 'text="Rename the greeting"' 30 && ok "remote: Quill chat shown natively after a v2 pairing" || bad "remote: Quill chat shown natively after a v2 pairing"
	shot 09-remote
	side_on
	U_SEEN=""
	for i in 1 2 3; do
		DIRTY=1
		U_SEEN="$U_SEEN $(dump | grep -o 'text="1.2M tok · ctx 23%"\|text="Today 5.6M · 7d 42.0M"\|text="available"' | tr '\n' ' ')"
		ub=$(dump | tr '>' '\n' | grep -F 'content-desc="Usage"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
		[ -z "$ub" ] && break
		read -r x1 y1 x2 y2 <<< "$ub"
		adb_ shell input swipe $((x1 + (x2 - x1) * 4 / 5)) $(((y1 + y2) / 2)) $((x1 + (x2 - x1) / 5)) $(((y1 + y2) / 2)) 300
		nap 1
	done
	echo "$U_SEEN" | grep -q 'tok · ctx 23%' && echo "$U_SEEN" | grep -q 'Today 5.6M · 7d 42.0M' && echo "$U_SEEN" | grep -q '"available"' && ok "remote: usage shows This session, All sessions, credits" || bad "remote: usage shows This session, All sessions, credits" "$U_SEEN"
	adb_ shell settings put system time_12_24 12
	adb_ shell input keyevent KEYCODE_HOME
	nap 1
	launch
	side_on
	for i in $(seq 1 20); do DIRTY=1; dump | grep -q -E 'resets [A-Za-z]* ?[0-9]+:[0-9]{2} ?(AM|PM|am|pm)' && break; sleep 0.5; done
	DIRTY=1
	dump | grep -q -E 'resets [A-Za-z]* ?[0-9]+:[0-9]{2} ?(AM|PM|am|pm)' && ok "remote: reset times follow the device's 12-hour clock" || bad "remote: reset times follow the device's 12-hour clock" "$(dump | grep -o 'resets [^"]*' | head -1)"
	adb_ shell settings put system time_12_24 24
	adb_ shell input keyevent KEYCODE_HOME
	nap 1
	launch
	side_on
	for i in $(seq 1 20); do DIRTY=1; dump | grep -q -E 'resets [A-Za-z]* ?[0-9]{1,2}:[0-9]{2}["· ]' && ! dump | grep -q -E 'resets [A-Za-z]* ?[0-9]+:[0-9]{2} ?(AM|PM)' && break; sleep 0.5; done
	DIRTY=1
	! dump | grep -q -E 'resets [A-Za-z]* ?[0-9]+:[0-9]{2} ?(AM|PM|am|pm)' && dump | grep -q -E 'resets [A-Za-z]* ?[0-9]{1,2}:[0-9]{2}' && ok "remote: reset times follow the device's 24-hour clock" || bad "remote: reset times follow the device's 24-hour clock" "$(dump | grep -o 'resets [^"]*' | head -1)"
	side_off
	has 'content-desc="New session"' && ok "remote: + New session in the top bar" || bad "remote: + New session in the top bar"
	popen session_A1
	wait_for 'text="Rename the greeting"' 10
	pcurl "/debug/suggest?id=session_A1&text=Run%20the%20tests" >/dev/null
	wait_for 'suggestion: Run the tests' 10 && ok "remote: prompt suggestion shown in the empty box" || bad "remote: prompt suggestion shown in the empty box"
	tap_edit && nap 1
	adb_ shell input keyevent KEYCODE_TAB
	nap 1
	adb_ shell input keyevent KEYCODE_ENTER
	wait_log 'session_A1/send[^,]*Run the tests' && ok "remote: Tab fills in the suggestion" || bad "remote: Tab fills in the suggestion"
	nap 2
	eb=$(dump | tr '>' '\n' | grep -F 'class="android.widget.EditText"' | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
	if [ -n "$eb" ]; then
		read -r ex1 ey1 ex2 ey2 <<< "$eb"
		adb_ shell "input tap $(((ex1 + ex2) / 2)) $(((ey1 + ey2) / 2)); input tap $(((ex1 + ex2) / 2)) $(((ey1 + ey2) / 2))"
		nap 1
		adb_ shell input keyevent KEYCODE_ENTER
	fi
	for i in $(seq 1 40); do [ "$(plog | grep -o 'session_A1/send[^,]*Run the tests' | wc -l)" -ge 2 ] && break; sleep 0.25; done
	[ "$(plog | grep -o 'session_A1/send[^,]*Run the tests' | wc -l)" -ge 2 ] && ok "remote: double-tap fills in the suggestion" || bad "remote: double-tap fills in the suggestion"
	pcurl "/debug/suggest?id=session_A1&text=" >/dev/null
	ime_off
	popen session_B2
	pcurl "/debug/long?id=session_B2" >/dev/null
	wait_for 'text="Newest filler message."' 15
	gone 'content-desc="Go to latest message"' 5 && ok "remote: no ↓ button at the newest message" || bad "remote: no ↓ button at the newest message"
	read -r CW CH <<< "$(scr)"
	for i in 1 2 3; do adb_ shell input swipe $((CW * 2 / 3)) $((CH * 2 / 5)) $((CW * 2 / 3)) $((CH * 4 / 5)) 200; done
	nap 2
	wait_for 'content-desc="Go to latest message"' 5 && ok "remote: ↓ button appears when scrolled up" || bad "remote: ↓ button appears when scrolled up"
	tap_text "Go to latest message" && nap 2
	wait_for 'text="Newest filler message."' 5 && gone 'content-desc="Go to latest message"' 5 && ok "remote: ↓ jumps to the newest message" || bad "remote: ↓ jumps to the newest message"
	pcurl "/debug/blank?id=session_B2" >/dev/null
	if wait_for 'text="Healed reply after a lost update."' 20; then
		ok "remote: an empty reply heals itself (full reload)"
	elif tap_text "Go to latest message" && wait_for 'text="Healed reply after a lost update."' 5; then
		bad "remote: an empty reply heals itself (full reload)" "healed, but the chat didn't stay at the newest message"
	else
		bad "remote: an empty reply heals itself (full reload)"
	fi
	pcurl "/debug/plan?id=session_B2&rec=0&n=1" >/dev/null
	if wait_for 'text="Approve: Ship as v0.1.18"' 15; then
		has 'text="Other answers…"' && ! has 'text="Approve: Replace the v0.1.17 files"' && ok "remote: plan card: recommended option is the main button" || bad "remote: plan card: recommended option is the main button"
		tap_text "Other answers…" && nap 1
		wait_for 'text="Approve: Replace the v0.1.17 files"' 5 && ok "remote: plan card: Other answers shows the rest" || bad "remote: plan card: Other answers shows the rest"
		tap_text "Approve: Ship as v0.1.18" && nap 1
		wait_log 'req_plan1[^]]*choice[^0-9]*0' && ok "remote: plan card approves with the chosen option" || bad "remote: plan card approves with the chosen option" "$(plog | grep -o 'req_plan1[^]]*' | tail -1)"
	else
		bad "remote: plan card: recommended option is the main button" "no option buttons"
	fi
	pcurl "/debug/planq?id=session_B2" >/dev/null
	if wait_for 'text="Approve plan &amp; send answers"' 15; then
		has 'Unanswered questions are sent as' && ok "remote: plan questions card with its controls" || bad "remote: plan questions card with its controls"
		# The card can be taller than a phone screen: scroll up to its first controls when needed
		tap_up() { local i; read -r UW UH <<< "$(scr)"; for i in 1 2 3 4 5; do tap_text "$1" && return 0; adb_ shell input swipe $((UW * 2 / 3)) $((UH * 2 / 5)) $((UW * 2 / 3)) $((UH * 3 / 5)) 300; nap 1; DIRTY=1; done; return 1; }
		tap_up "Yes" && nap 1
		tap_up "a: Plan-first" && nap 1
		tap_scroll "Approve plan &amp; send answers" && nap 1
		wait_log 'req_planq[^]]*planAnswers' && plog | grep -o 'req_planq[^]]*' | tail -1 | grep -q 'yes' && plog | grep -o 'req_planq[^]]*' | tail -1 | grep -q '"2\\":1' && ok "remote: plan answers go with the approval (yes/no, recommended pick, unanswered left out)" || bad "remote: plan answers go with the approval (yes/no, recommended pick, unanswered left out)" "$(plog | grep -o 'req_planq[^]]*' | tail -1)"
		plog | grep -o 'req_planq[^}]*}' | tail -1 | grep -q '"3\\":\[0,1\]' && ok "remote: plan multi question pre-ticks recommendedPicks" || bad "remote: plan multi question pre-ticks recommendedPicks" "$(plog | grep -o 'req_planq[^}]*}' | tail -1)"
		plog | grep -o 'req_planq[^}]*}' | tail -1 | grep -q '"5\\":\\"no' && ok "remote: plan yes/no question pre-picks the recommended answer" || bad "remote: plan yes/no question pre-picks the recommended answer" "$(plog | grep -o 'req_planq[^}]*}' | tail -1)"
	else
		bad "remote: plan questions card with its controls" "no card"
	fi
	pcurl "/debug/plan?id=session_B2&n=2" >/dev/null
	if wait_for 'text="Approve: Replace the v0.1.17 files"' 15; then
		ok "remote: plan card without a recommendation: one button per option"
		tap_text "Approve: Replace the v0.1.17 files" && nap 1
		wait_log 'req_plan2[^]]*choice[^0-9]*1' && ok "remote: plan card sends the second option" || bad "remote: plan card sends the second option"
	else
		bad "remote: plan card without a recommendation: one button per option"
	fi
	popen session_A1
	wait_for 'text="Rename the greeting"' 10
	has 'Edit main.go' && ok "remote: tool row" || bad "remote: tool row"
	adb_ shell cmd statusbar collapse >/dev/null 2>&1
	tap_edit && nap 1
	adb_ shell input text remote_hello
	adb_ shell input keyevent KEYCODE_ENTER
	wait_for 'text="Push now"' 15 && ok "remote: send, streamed reply with choices" || bad "remote: send, streamed reply with choices"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
	DIRTY=1
	tap_text "Push now"
	for i in $(seq 1 20); do plog | grep -q 'choice' && break; sleep 0.25; done
	plog | grep -q 'choice' && ok "remote: choice button answers by seq/index" || bad "remote: choice button answers by seq/index" "$(plog | tail -c 300)"
	pcurl "/debug/busy?id=session_A1&on=1" >/dev/null
	nap 2
	tap_edit && nap 1
	adb_ shell input text queued_one
	adb_ shell input keyevent KEYCODE_ENTER
	nap 1
	adb_ shell input text queued_two
	adb_ shell input keyevent KEYCODE_ENTER
	nap 1
	adb_ shell input keyevent KEYCODE_BACK
	wait_for 'text="2 queued messages"' 10 && ok "remote: busy session queues both messages" || bad "remote: busy session queues both messages" "$(dump | grep -o 'queued[^"]*' | head -2 | tr '\n' ' ')"
	shot 09b-queue
	tap_text "Send now"
	for i in $(seq 1 20); do plog | grep -q 'queue/send-now' && break; sleep 0.25; done
	wait_for 'text="1 queued message"' 8
	tap_text "Remove from queue"
	for i in $(seq 1 20); do plog | grep -q 'queue/delete' && break; sleep 0.25; done
	plog | grep -q 'queue/send-now' && plog | grep -q 'queue/delete' && ok "remote: queue Send now and remove" || bad "remote: queue Send now and remove"
	gone 'queued message' 8 && ok "remote: queue empty afterwards" || bad "remote: queue empty afterwards"
	tap_edit && nap 1
	adb_ shell input text queued_four
	adb_ shell input keyevent KEYCODE_ENTER
	wait_for 'text="1 queued message"' 8
	before=$(plog | grep -o 'queue/send-now' | wc -l)
	nb=$(bounds_of "Send now")
	if [ -n "$nb" ]; then
		read -r n1 n2 n3 n4 <<< "$nb"
		adb_ shell "input tap $(((n1 + n3) / 2)) $(((n2 + n4) / 2)); input tap $(((n1 + n3) / 2)) $(((n2 + n4) / 2)); input tap $(((n1 + n3) / 2)) $(((n2 + n4) / 2))"
	fi
	for i in $(seq 1 20); do [ "$(plog | grep -o 'queue/send-now' | wc -l)" -gt "$before" ] && break; sleep 0.25; done
	nap 2
	after=$(plog | grep -o 'queue/send-now' | wc -l)
	[ "$after" = $((before + 1)) ] && gone 'queued message' 5 && ok "remote: repeated taps on Send now send once and hide it at once" || bad "remote: repeated taps on Send now send once and hide it at once" "$before -> $after"
	tap_edit && nap 1
	adb_ shell input text queued_three
	adb_ shell input keyevent KEYCODE_ENTER
	wait_for 'text="1 queued message"' 8
	tap_text "Edit queued message: queued_three" && nap 1
	for i in $(seq 1 20); do [ "$(plog | grep -o 'queue/delete' | wc -l)" -ge 2 ] && break; sleep 0.25; done
	DIRTY=1
	[ "$(plog | grep -o 'queue/delete' | wc -l)" -ge 2 ] && dump | tr '>' '\n' | grep -F 'class="android.widget.EditText"' | grep -q 'text="queued_three"' && ok "remote: tapping a queued message puts it in the box to edit" || bad "remote: tapping a queued message puts it in the box to edit"
	adb_ shell input keyevent KEYCODE_MOVE_END
	for i in $(seq 1 14); do adb_ shell input keyevent KEYCODE_DEL; done
	ime_off
	pcurl "/debug/busy?id=session_A1&on=0" >/dev/null
	pcurl "/debug/pause?on=1" >/dev/null
	wait_for 'text="Resume now"' 10 && ok "remote: usage pause banner" || bad "remote: usage pause banner"
	tap_text "Resume now" && nap 1
	gone 'text="Resume now"' 8 && plog | grep -q 'usage/resume' && ok "remote: Resume now" || bad "remote: Resume now"
	pcurl "/debug/control?on=0" >/dev/null
	wait_for 'Read only  ·  turn on device control in Quill Desktop' 10 && ok "remote: read-only hint in the message box" || bad "remote: read-only hint in the message box"
	dump | grep -o '<node[^>]*class="android.widget.EditText"[^>]*>' | grep -q 'enabled="false"' && ok "remote: message box disabled while read only" || bad "remote: message box disabled while read only"
	pcurl "/debug/control?on=1" >/dev/null
	wait_for 'Message Quill' 10 && ok "remote: message box back after control on" || bad "remote: message box back after control on"
	tap_text "Settings" && nap 2
	for i in 1 2 3 4 5 6; do
		DIRTY=1
		has '·  Quill Desktop 9.9.9' && break
		b=$(dump | tr '>' '\n' | grep -F 'scrollable="true"' | grep -v -F 'android.webkit.WebView' | tail -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
		[ -z "$b" ] && break
		read -r x1 y1 x2 y2 <<< "$b"
		adb_ shell input swipe $(((x1 + x2) / 2)) $((y1 + (y2 - y1) * 3 / 4)) $(((x1 + x2) / 2)) $((y1 + (y2 - y1) / 4)) 400
		nap 1
	done
	has '·  Quill Desktop 9.9.9' && ok "remote: About shows the desktop Quill version" || bad "remote: About shows the desktop Quill version" "$(dump | grep -o 'Quill Android[^"]*' | head -1)"
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	popen session_C3
	wait_for 'text="Allow Bash?"' 15 && ok "remote: permission card" || bad "remote: permission card"
	tap_text "Allow" && nap 1
	wait_for 'text="Bash: allowed"' 10 && ok "remote: Allow answers and the card resolves" || bad "remote: Allow answers and the card resolves"
	tap_part() {
		local b
		b=$(dump | tr '>' '\n' | grep -F "text=\"$1" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
		[ -z "$b" ] && return 1
		set -- $b
		adb_ shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
	}
	tap_desc() {
		local b
		b=$(dump | tr '>' '\n' | grep -F "content-desc=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' ')
		[ -z "$b" ] && return 1
		set -- $b
		adb_ shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
	}
	pstart() { tap_text "START" >/dev/null 2>&1 || tap_text "Start" >/dev/null 2>&1; }
	tap_desc "New session" && nap 2
	wait_for 'content-desc="Session name"' 10 && ok "remote: new session dialog has a name field" || bad "remote: new session dialog has a name field"
	has 'text="zomboid-fps · saved' && has 'open in Fix edits' && ok "remote: new session dialog lists kmem handoffs, open ones point to their session" || bad "remote: new session dialog lists kmem handoffs, open ones point to their session"
	pstart
	wait_for 'Started with online · opus · default' 15 && ok "remote: new session started and opened" || bad "remote: new session started and opened"
	plog | grep -q -F 'new session start=map[kmem:new title:]' && ok "remote: empty name starts with kmem new" || bad "remote: empty name starts with kmem new" "$(plog | grep -o 'new session start=[^"]*' | tail -1)"
	tap_desc "New session" && nap 2
	tap_edit && nap 1
	adb_ shell input text "My%sTest"
	wait_for 'kmem name: my-test' 10 && ok "remote: name shows the kmem name preview" || bad "remote: name shows the kmem name preview"
	adb_ shell dumpsys input_method | grep -q 'mInputShown=true' && adb_ shell input keyevent KEYCODE_BACK && nap 1
	pstart
	wait_for 'text="kmem name: my-test"' 15 && wait_log 'My Test' && ok "remote: named session starts with that kmem name" || bad "remote: named session starts with that kmem name"
	tap_desc "New session" && nap 2
	tap_part "zomboid-fps · saved" && nap 1
	pstart
	wait_for 'Resumed kmem handoff zomboid-fps' 15 && ok "remote: resume a kmem handoff from the phone" || bad "remote: resume a kmem handoff from the phone"
	tap_desc "New session" && nap 2
	tap_part "quill-tester · saved" && nap 2
	a1_open 10 && ok "remote: handoff open elsewhere opens that session" || bad "remote: handoff open elsewhere opens that session"
	pcurl "/debug/nohandoffs?on=1" >/dev/null
	tap_desc "New session" && nap 2
	wait_for 'text="Folder"' 10 && ! has 'content-desc="Session name"' && ok "remote: older Quill Desktop: dialog without the kmem part" || bad "remote: older Quill Desktop: dialog without the kmem part"
	tap_text "CANCEL" >/dev/null 2>&1 || tap_text "Cancel" >/dev/null 2>&1
	nap 1
	pcurl "/debug/nohandoffs?on=0" >/dev/null
	tap_any() { tap_text "$(echo "$1" | tr '[:lower:]' '[:upper:]')" >/dev/null 2>&1 || tap_text "$1" >/dev/null 2>&1; }
	side_on
	lpress "Zomboid launcher" && nap 2
	wait_for 'text="Rename..."' 5 && has 'text="End session"' && has 'text="Delete session..."' && ! has 'text="Set archive"' && ! has 'text="Move to folder..."' && ! has 'kmem name...' \
		&& ok "remote: long-press menu has Quill Desktop's session actions" || bad "remote: long-press menu has Quill Desktop's session actions"
	centered "End session" && ok "remote: session menu is centered" || bad "remote: session menu is centered"
	has 'text="Add to home screen"' && ok "home: long-press menu offers Add to home screen" || bad "home: long-press menu offers Add to home screen"
	if ! tap_text "Rename..."; then bad "remote: rename from the menu" "menu did not open"; fi
	nap 2
	wait_for 'text="Rename session"' 5 && tap_edit && nap 1
	adb_ shell input keyevent KEYCODE_MOVE_END
	for i in $(seq 1 20); do adb_ shell input keyevent KEYCODE_DEL; done
	adb_ shell input text "Launcher%s2"
	ime_off
	tap_any "Save" && nap 2
	wait_for 'text="Launcher 2"' 10 && ok "remote: rename from the menu" || bad "remote: rename from the menu" "$(plog | grep -o '/rename [^"]*' | tail -1)"
	side_on
	lpress "Launcher 2" && nap 2
	if wait_for 'text="Restore original name"' 5; then
		tap_text "Restore original name" && nap 2
		wait_for 'text="Zomboid launcher"' 10 && ok "remote: Restore original name" || bad "remote: Restore original name"
	else
		bad "remote: Restore original name" "not in the menu"
		adb_ shell input keyevent KEYCODE_BACK
	fi
	side_on
	lpress "My Test" && nap 2
	tap_text "Delete session..." && nap 2
	tap_any "Delete" && nap 2
	gone 'text="My Test"' 10 && ok "remote: Delete session after confirming" || bad "remote: Delete session after confirming"
	side_on
	lpress "Zomboid launcher" && nap 2
	tap_text "Project..." && nap 2
	tap_text "New project..." && nap 2
	tap_edit && nap 1
	adb_ shell input text "Games"
	ime_off
	tap_any "Save" && nap 2
	wait_log 'session_C3/project.*Games' && wait_for 'Games' 10 && ok "remote: Project... moves the session to a new project" || bad "remote: Project... moves the session to a new project"
	side_on
	lpress "zomboid-fps" && nap 2
	tap_text "End session" && nap 2
	wait_log '/end ' && ok "remote: End session" || bad "remote: End session"
	tap_desc "New session" && nap 2
	b=$(bounds_of "$(dump | tr '>' '\n' | grep -o 'text="old-notes · saved[^"]*"' | head -1 | sed 's/^text="//; s/"$//')")
	if [ -n "$b" ]; then
		set -- $b
		adb_ shell input swipe $((($1 + $3) / 2)) $((($2 + $4) / 2)) $((($1 + $3) / 2)) $((($2 + $4) / 2)) 900
		nap 2
	fi
	if wait_for 'text="Delete handoff..."' 5; then
		ok "remote: long-press on a handoff opens its menu"
		tap_text "Delete handoff..." && nap 2
		tap_any "Delete" && nap 2
		wait_log 'handoffs/old-notes/delete' && ok "remote: Delete handoff" || bad "remote: Delete handoff"
	else
		bad "remote: long-press on a handoff opens its menu"
		adb_ shell input keyevent KEYCODE_BACK
	fi
	tap_text "CANCEL" >/dev/null 2>&1 || tap_text "Cancel" >/dev/null 2>&1
	nap 1
	tap_desc "New session" && nap 2
	b=$(bounds_of "$(dump | tr '>' '\n' | grep -o 'text="zomboid-fps · saved[^"]*"' | head -1 | sed 's/^text="//; s/"$//')")
	if [ -n "$b" ]; then
		set -- $b
		adb_ shell input swipe $((($1 + $3) / 2)) $((($2 + $4) / 2)) $((($1 + $3) / 2)) $((($2 + $4) / 2)) 900
		nap 2
	fi
	tap_text "Resume" && nap 2
	wait_for 'Resumed fresh from handoff zomboid-fps' 10 && ok "remote: Resume a handoff from its menu" || bad "remote: Resume a handoff from its menu"

	pcurl "/debug/lock?on=1" >/dev/null
	tap_desc "Reconnect" && nap 2
	wait_for 'text="Unlock Quill Desktop"' 20 && ok "remote: password prompt when Quill Desktop locks this device" || bad "remote: password prompt when Quill Desktop locks this device"
	tap_edit && nap 1
	adb_ shell input text "wrong-pass"
	ime_off
	DIRTY=1
	has 'wrong-pass' && bad "remote: password hidden while typing" || ok "remote: password hidden while typing"
	has 'text="Show password"' && ok "remote: Show password toggle" || bad "remote: Show password toggle"
	tap_any "Unlock" && nap 1
	wait_for 'Wrong password (1 of 5 tries)' 10 && ok "remote: wrong password says how many tries" || bad "remote: wrong password says how many tries"
	tap_edit && nap 1
	adb_ shell input keyevent KEYCODE_MOVE_END
	for i in $(seq 1 12); do adb_ shell input keyevent KEYCODE_DEL; done
	adb_ shell input text "test-password-1"
	ime_off
	tap_any "Unlock" && nap 1
	gone 'text="Unlock Quill Desktop"' 10 && wait_log '/phone/unlock ' && ok "remote: right password unlocks" || bad "remote: right password unlocks"
	popen session_A1
	a1_open 20 && ok "remote: sessions load again after unlocking" || bad "remote: sessions load again after unlocking"
	# Home-network unlock (user 2026-11-22, Quill Desktop b12fad1). The emulator has no screen lock, so with
	# "Require this phone's screen lock" on (the default) no key is made; with it off, the password session registers it.
	sleep 3
	plog | grep -q 'lan key registered' && bad "home: no key without the phone's screen lock" || ok "home: no key without the phone's screen lock"
	tap_text "Settings" && nap 2
	tap_scroll "Require this phone's screen lock" && nap 1
	wait_log 'lan key registered' && ok "home: key registered from the password session" || bad "home: key registered from the password session"
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	pcurl "/debug/lan?on=1" >/dev/null
	pcurl "/debug/relock" >/dev/null
	tap_desc "Reconnect" && nap 4
	DIRTY=1
	wait_log 'lan unlock ok' && ! has 'text="Unlock Quill Desktop"' && ok "home: unlocks with the phone's key, no password, on the home network" || bad "home: unlocks with the phone's key, no password, on the home network" "$(plog | grep -o 'lan unlock[^"]*' | tail -1)"
	popen session_A1
	a1_open 20 && ok "home: sessions load after the key unlock" || bad "home: sessions load after the key unlock"
	# Turning "Require this phone's screen lock" back on in a key-unlocked session drops the old key at once
	KU=$(plog | grep -c 'lan unlock ok')
	tap_text "Settings" && nap 2
	tap_scroll "Require this phone's screen lock" && nap 2
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	pcurl "/debug/relock" >/dev/null
	tap_desc "Reconnect" && nap 4
	wait_for 'text="Unlock Quill Desktop"' 20 && [ "$(plog | grep -c 'lan unlock ok')" = "$KU" ] && ok "home: screen-lock option on drops the old key at once" || bad "home: screen-lock option on drops the old key at once"
	tap_edit && nap 1
	adb_ shell input text "test-password-1"
	ime_off
	tap_any "Unlock" && nap 2
	popen session_A1
	a1_open 20
	pcurl "/debug/lan?on=0" >/dev/null
	pcurl "/debug/relock" >/dev/null
	tap_desc "Reconnect" && nap 2
	wait_for 'text="Unlock Quill Desktop"' 20 && ok "home: password again off the home network" || bad "home: password again off the home network"
	tap_any "Cancel" >/dev/null 2>&1; nap 1
	pcurl "/debug/relock" >/dev/null
	tap_desc "Reconnect" && nap 2
	wait_for 'text="Unlock Quill Desktop"' 20 && ok "remote: asks again once Quill Desktop drops the unlock" || bad "remote: asks again once Quill Desktop drops the unlock"
	pcurl "/debug/lock?on=0" >/dev/null
	tap_any "Cancel" && nap 1
	tap_desc "Reconnect" && nap 2
	popen session_A1
	wait_for 'text="Rename the greeting"' 10
	nap 2
	pcurl "/debug/event?id=session_C3&type=approval" >/dev/null
	pcurl "/debug/event?id=session_A1&type=finished" >/dev/null
	for i in $(seq 1 40); do [ "$(pnotif 'Approval needed: Zomboid launcher')" -ge 1 ] && break; sleep 0.5; done
	[ "$(pnotif 'Approval needed: Zomboid launcher')" -ge 1 ] && ok "remote: notification for another session" || bad "remote: notification for another session"
	[ "$(pnotif 'Finished: Fix edits')" = 0 ] && ok "remote: silent for the session on screen" || bad "remote: silent for the session on screen"
	# Home screen: launcher shortcuts (New session + recent sessions) from Quill Desktop's list
	sc=$(adb_ shell dumpsys shortcut 2>/dev/null | grep -A400 "Package: $PKG" | grep -o 'id=[^, ]*' | tr '\n' ' ')
	echo "$sc" | grep -q 'id=new' && echo "$sc" | grep -q 'id=s:session_' && ok "home: launcher shortcuts (New session + recent sessions)" || bad "home: launcher shortcuts (New session + recent sessions)" "$sc"
	pref_has 'home.sessions' && ok "home: session list cached for the widget" || bad "home: session list cached for the widget"
	# Approval notification with Allow / Deny that answers from the shade
	pcurl "/debug/perm?id=session_C3&req=req_notif" >/dev/null
	nap 1
	pcurl "/debug/event?id=session_C3&type=approval" >/dev/null
	for i in $(seq 1 40); do [ "$(pnotif 'rm -rf build/')" -ge 1 ] && break; sleep 0.5; done
	ndump=$(adb_ shell dumpsys notification --noredact 2>/dev/null)
	echo "$ndump" | grep -q 'Bash: rm -rf build/' && echo "$ndump" | grep -q '"Allow"' && echo "$ndump" | grep -q '"Deny"' && ok "notify: approval shows the command with Allow / Deny" || bad "notify: approval shows the command with Allow / Deny"
	adb_ shell cmd statusbar expand-notifications >/dev/null 2>&1
	nap 2
	DIRTY=1
	tap_text "Allow" >/dev/null 2>&1 || { tap_text "Approval needed: Zomboid launcher" >/dev/null 2>&1; }
	wait_log 'req_notif[^]]*allow' && ok "notify: Allow answers from the notification" || bad "notify: Allow answers from the notification" "$(plog | grep -o 'req_notif[^]]*' | tail -1)"
	for i in $(seq 1 20); do [ "$(pnotif 'rm -rf build/')" = 0 ] && break; sleep 0.5; done
	[ "$(pnotif 'rm -rf build/')" = 0 ] && ok "notify: answered notification goes away" || bad "notify: answered notification goes away"
	adb_ shell cmd statusbar collapse >/dev/null 2>&1
	nap 1
	# Share to Quill: shared text goes into the picked session's message box
	adb_ shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Shared from the emulator'" -n "$PKG/$ACT" >/dev/null 2>&1
	wait_for 'text="Send to…"' 15 && ok "share: Send to… picker" || bad "share: Send to… picker"
	tap_part "Zomboid launcher" && nap 2
	DIRTY=1
	dump | grep -q 'text="Shared from the emulator"' && pref_has 'name="remote.last">session_C3<' && ok "share: text lands in the chosen session's message box" || bad "share: text lands in the chosen session's message box"
	adb_ shell input keyevent KEYCODE_BACK
	nap 1
	# Launcher shortcut "New session" opens the new session dialog
	adb_ shell am start -W -a android.intent.action.VIEW -n "$PKG/$ACT" --ez quill.newSession true >/dev/null 2>&1
	wait_for 'text="New session"' 15 && ok "home: New session shortcut opens the dialog" || bad "home: New session shortcut opens the dialog"
	tap_any "Cancel" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	# Message times under messages, and the ⚙ switch
	popen session_A1
	a1_open 15
	wait_for 'content-desc="Sent ' 10 && ok "times: messages show when they were sent" || bad "times: messages show when they were sent"
	tap_text "Settings" && nap 2
	tap_text "Message times" && nap 1
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	gone 'content-desc="Sent ' 8 && ok "times: Message times off hides them" || bad "times: Message times off hides them"
	tap_text "Settings" && nap 2
	tap_text "Message times" && nap 1
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 1
	wait_for 'content-desc="Sent ' 8 && ok "times: back on" || bad "times: back on"
	# Streaming replies are formatted while they arrive (open **bold** closed for now), finished text as before
	pcurl "/debug/conns?reset=1" >/dev/null
	pcurl "/debug/stream?id=session_A1" >/dev/null
	wait_for 'Still typing half bo' 15 && ! dump | grep -q 'text="[^"]*\*\*' && has 'item two' && ok "stream: formatted while streaming, no raw **" || bad "stream: formatted while streaming, no raw **" "$(dump | grep -o 'text="[^"]*\(Still\|Intro\)[^"]*"' | head -3 | tr '\n' ' ')"
	DS=$(pcurl "/debug/conns")
	echo "$DS" | grep -q '"DELTA sent":[1-9]' && ok "delta: streaming reply fetched as deltas" || bad "delta: streaming reply fetched as deltas" "$DS"
	log "INFO  stream deltas: $(echo "$DS" | grep -o '"DELTA[^,}]*' | tr '\n' ' ')"
	pcurl "/debug/streamend?id=session_A1" >/dev/null
	wait_for 'Still typing half bold done.' 15 && ! dump | grep -q 'text="[^"]*\*\*' && ok "stream: finished reply rendered" || bad "stream: finished reply rendered"
	# Connection reuse (0.9.22): with a session open and nothing happening, few new TLS connections and no
	# folders.json/usage.json polling in paired mode
	popen session_A1
	a1_open 15
	nap 3
	pcurl "/debug/conns?reset=1" >/dev/null
	sleep 40
	CONNS=$(pcurl "/debug/conns")
	NC=$(echo "$CONNS" | python3 -I -c 'import json,sys;print(json.load(sys.stdin)["conns"])')
	log "INFO  idle 40 s: $CONNS"
	[ "$NC" -le 6 ] && ok "net: connections reused while idle ($NC new in 40 s)" || bad "net: connections reused while idle" "$NC new in 40 s"
	echo "$CONNS" | grep -q 'folders.json\|usage.json' && bad "net: no folders/usage polling in paired mode" "$CONNS" || ok "net: no folders/usage polling in paired mode"
	# Centered columns (0.9.24, Quill Desktop 1be563f + user rule f5c0dd9): a centered list keeps its markers lined up
	# and sits centered as one column; with Chat text centering off it is left-aligned
	pcurl "/debug/layout?id=session_A1" >/dev/null
	if wait_for 'second choice' 15; then
		nap 1
		DIRTY=1
		b1=$(bounds_of "• one"); b2=$(bounds_of "• two items")
		set -- $b1; L1=$1; R1=$3; set -- $b2; L2=$1
		[ -n "$b1" ] && [ -n "$b2" ] && [ "$L1" = "$L2" ] && ok "layout: list markers line up when centered" || bad "layout: list markers line up when centered" "$b1 | $b2"
		# centered in the chat area (on a tablet the session sidebar takes the left side): compare with the
		# full-width paragraph below the list
		b4=$(bounds_of "Short end."); set -- $b4; CC=$((($1 + $3) / 2)); CW=$(($3 - $1)); LC=$(((L1 + R1) / 2))
		[ -n "$b4" ] && [ $(( (LC - CC) * (LC - CC) * 400 )) -le $(( CW * CW )) ] && [ $((R1 - L1)) -lt "$CW" ] && ok "layout: the list is one centered column" || bad "layout: the list is one centered column" "list $b1, chat $b4"
		ba=$(bounds_of "a. first choice"); bb=$(bounds_of "b) second choice")
		set -- $ba; LA=$1; set -- $bb; LB=$1
		[ -n "$ba" ] && [ -n "$bb" ] && [ "$LA" = "$LB" ] && ok "layout: lettered items with blank lines between are one list" || bad "layout: lettered items with blank lines between are one list" "$ba | $bb"
		shot 20-centered-layout
		CL=$L1
		tap_text "Settings" && nap 2
		tap_text "Chat text" && nap 1
		tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
		nap 2
		DIRTY=1
		b3=$(bounds_of "• one"); set -- $b3
		[ -n "$b3" ] && [ "$1" -lt "$CL" ] && ok "layout: left-aligned list with centering off" || bad "layout: left-aligned list with centering off" "$b3 (centered left $CL)"
		shot 21-left-layout
		tap_text "Settings" && nap 2
		tap_text "Chat text" && nap 1
		tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
		nap 2
	else
		bad "layout: list markers line up when centered" "no layout message"
	fi
	# One notification watcher at a time (0.9.25): pause/resume the app a few times, then an idle session makes at
	# most one /events long-poll at a time (25 s each: <= 3 in 50 s; leftover watchers made several at once)
	for k in 1 2 3; do adb_ shell input keyevent KEYCODE_HOME; sleep 2; launch; sleep 3; done
	a1_open 15
	pcurl "/debug/conns?reset=1" >/dev/null
	sleep 50
	EV=$(pcurl "/debug/conns" | python3 -I -c 'import json,sys;print(json.load(sys.stdin)["paths"].get("GET /events",0))')
	log "INFO  /events in 50 s idle after 3 pause/resume cycles: $EV"
	[ "$EV" -le 3 ] && ok "net: one notification watcher after pause/resume ($EV /events in 50 s)" || bad "net: one notification watcher after pause/resume" "$EV /events in 50 s"
	# Quill Desktop's session switches in ⚙ (phone API settings, Quill Desktop 5c19d7b)
	checked_of() { dump | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | grep -o 'checked="[a-z]*"'; }
	tap_text "Settings" && nap 2
	tap_scroll "Ask before building" >/dev/null 2>&1; nap 1
	DIRTY=1
	if has 'text="Ask before building"'; then
		ok "settings: Quill Desktop's session switches shown"
		[ "$(checked_of 'Ask before building')" = 'checked="false"' ] && wait_log 'settings map\[buildGate:false\]' && ok "settings: switching sends POST /phone/settings" || bad "settings: switching sends POST /phone/settings" "$(checked_of 'Ask before building') $(plog | grep -o 'settings map[^"]*' | tail -1)"
		pcurl "/debug/settings?coordinator=0" >/dev/null
		for k in $(seq 1 20); do DIRTY=1; [ "$(checked_of 'Coordinate with subagents')" = 'checked="false"' ] && break; sleep 0.5; done
		[ "$(checked_of 'Coordinate with subagents')" = 'checked="false"' ] && ok "settings: a change on the PC updates the switch" || bad "settings: a change on the PC updates the switch" "$(checked_of 'Coordinate with subagents')"
		pcurl "/debug/control?on=0" >/dev/null
		nap 2
		tap_text "Coordinate with subagents" && nap 3
		DIRTY=1
		[ "$(checked_of 'Coordinate with subagents')" = 'checked="false"' ] && ok "settings: switch flips back when control is off" || bad "settings: switch flips back when control is off" "$(checked_of 'Coordinate with subagents')"
		pcurl "/debug/control?on=1" >/dev/null
		pcurl "/debug/settings?buildGate=1&coordinator=1" >/dev/null
	else
		bad "settings: Quill Desktop's session switches shown"
	fi
	tap_text "DONE" >/dev/null 2>&1 || adb_ shell input keyevent KEYCODE_BACK
	nap 2
	# Portrait session rows show tokens and context, like Quill Desktop's portrait list (941a3cf)
	if [ "$(width_dp)" -lt 600 ]; then
		side_on
		DIRTY=1
		[ "$(dump | tr '>' '\n' | grep -c ' tok · ctx ')" -ge 2 ] && ok "rows: tokens and context in portrait session rows" || bad "rows: tokens and context in portrait session rows"
		side_off
	fi
	# Plan card overlay (Quill Desktop 596036f): the plan's numbered questions get their controls in the reply
	popen session_A1
	a1_open 15
	pcurl "/debug/planover?id=session_A1" >/dev/null
	if wait_for 'Show as written' 15; then
		has 'Which channel for the overlay build' && has 'Yes  (recommended)' && ! has 'text="1. Should the overlay tag go out too? Yes or no?"' && ok "overlay: questions answered in the reply, no separate card" || bad "overlay: questions answered in the reply, no separate card"
		tap_toggle() { local k; read -r UW UH <<< "$(scr)"; for k in 1 2 3 4; do DIRTY=1; tap_part "$1" && return 0; adb_ shell input swipe $((UW / 2)) $((UH * 2 / 5)) $((UW / 2)) $((UH * 3 / 5)) 300; nap 1; done; return 1; }
		tap_toggle "▤  Show as written"; nap 2
		DIRTY=1
		AIP=no
		for k in 1 2 3 4 5; do DIRTY=1; has 'Answer in place' && AIP=yes && break; read -r UW UH <<< "$(scr)"; adb_ shell input swipe $((UW / 2)) $((UH * 2 / 5)) $((UW / 2)) $((UH * 3 / 5)) 300; nap 1; done
		CARD=no
		for k in 1 2 3 4 5 6; do DIRTY=1; has 'text="1. Should the overlay tag go out too? Yes or no?"' && CARD=yes && break; read -r UW UH <<< "$(scr)"; adb_ shell input swipe $((UW / 2)) $((UH * 3 / 5)) $((UW / 2)) $((UH * 2 / 5)) 300; nap 1; done
		[ "$AIP" = yes ] && [ "$CARD" = yes ] && ok "overlay: Show as written brings back the card" || bad "overlay: Show as written brings back the card" "toggle=$AIP card=$CARD"
		tap_toggle "☰  Answer in place"; nap 2
		DIRTY=1
		tap_scroll "Approve plan &amp; send answers" && nap 1
		wait_log 'req_planover[^}]*planAnswers' && plog | grep -o 'req_planover[^}]*}' | tail -1 | grep -q '"1\\":\\"yes' && plog | grep -o 'req_planover[^}]*}' | tail -1 | grep -q '"2\\":1' && ok "overlay: approve sends the in-place answers (recommended picks)" || bad "overlay: approve sends the in-place answers (recommended picks)" "$(plog | grep -o 'req_planover[^}]*}' | tail -1)"
	else
		bad "overlay: questions answered in the reply, no separate card" "no overlay toggle"
	fi
	# Unreachable Quill Desktop: after 2 failed tries the line suggests the VPN, with a help link
	pcurl "/debug/down?s=45" >/dev/null
	wait_for 'Not at home? Turn on your VPN' 45 && has 'VPN help' && ok "vpn: hint when Quill Desktop can't be reached" || bad "vpn: hint when Quill Desktop can't be reached"
	gone 'Not at home? Turn on your VPN' 60 && ok "vpn: hint goes away once reachable" || bad "vpn: hint goes away once reachable"
	adb_ shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
	adb_ shell cmd appops set "$PKG" POST_NOTIFICATION ignore >/dev/null 2>&1
	nap 1
	launch
	wait_for 'content-desc="Notifications off banner"' 20 && ok "remote: banner when notifications are off" || bad "remote: banner when notifications are off"
	adb_ shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
	adb_ shell cmd appops set "$PKG" POST_NOTIFICATION allow >/dev/null 2>&1
	adb_ shell input keyevent KEYCODE_HOME
	nap 1
	launch
	gone 'content-desc="Notifications off banner"' 20 && ok "remote: banner goes away once notifications are on" || bad "remote: banner goes away once notifications are on"
	pcurl "/debug/noapi?on=1" >/dev/null
	adb_ shell cmd statusbar collapse >/dev/null 2>&1
	sleep 4
	DIRTY=1
	tap_text "Reconnect" >/dev/null 2>&1
	for i in $(seq 1 40); do pref_has 'phone.unsupported" value="true"' && break; sleep 0.5; done
	pref_has 'phone.unsupported" value="true"' && ok "remote: falls back to claude.ai without a phone API" || bad "remote: falls back to claude.ai without a phone API"
	pcurl "/debug/noapi?on=0" >/dev/null
	adb_ shell input keyevent KEYCODE_HOME
	nap 1
	launch
	a1_open 30 && ok "remote: switches back once the API answers" || bad "remote: switches back once the API answers"
	crashed && bad "remote: no crash" "$(adb_ logcat -d -b crash | grep -m1 -A2 'FATAL')" || ok "remote: no crash"
	plog > "$OUT/phoned-requests.json" 2>/dev/null
	kill "$PHONE_PID" 2>/dev/null
	PHONE_PID=""
else
	log "SKIP  remote mode (go or the mock phone server unavailable)"
fi

adb_ logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
[ "$CLEANUP" = 1 ] && adb_ uninstall "$PKG" >/dev/null 2>&1
log ""
log "$PASS passed, $FAIL failed. Output: $OUT"
[ "$FAIL" = 0 ]
