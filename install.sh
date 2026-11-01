#!/data/data/com.termux/files/usr/bin/bash
# Install the wireless mic inside Termux.
#   pkg install git && git clone https://github.com/partha-cybersec/android-wireless-mic && cd android-wireless-mic && bash install.sh
set -e
cd "$(dirname "$0")"

echo "== Packages =="
pkg install -y python python-numpy pulseaudio openssl-tool termux-api curl
pip install --no-cache-dir websockets qrcode

echo "== Files =="
mkdir -p "$HOME/wireless-mic" "$HOME/.shortcuts/tasks" "$HOME/.shortcuts/icons"
cp transmitter/server.py transmitter/receiver.html transmitter/tx.html "$HOME/wireless-mic/"
cp transmitter/wmic "$PREFIX/bin/wmic" && chmod 755 "$PREFIX/bin/wmic"
cp "shortcuts/Wireless Mic" "shortcuts/Stop Mic" "$HOME/.shortcuts/tasks/"
chmod 700 "$HOME/.shortcuts/tasks/Wireless Mic" "$HOME/.shortcuts/tasks/Stop Mic"
cp shortcuts/icons/*.png "$HOME/.shortcuts/icons/"
chmod 700 "$HOME/.shortcuts" "$HOME/.shortcuts/tasks" "$HOME/.shortcuts/icons"

echo "== Storage access (for backup recordings in Music/WirelessMic) =="
[ -d "$HOME/storage" ] || termux-setup-storage

cat <<'EOF'

Done.
 1. Give Termux:API the Microphone permission (Android Settings > Apps > Termux:API > Permissions),
    or run once:  termux-microphone-record -d -l 1   (accept the prompt)
 2. Add the Termux:Widget "Termux shortcut" widget to your home screen and pick
    "Wireless Mic" and "Stop Mic".  Or just type:  wmic
EOF
