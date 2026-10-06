#!/bin/sh
# Full-screen display for the HUD. Started from the desktop session's autostart, e.g. on
# Raspberry Pi OS Bookworm (labwc):  echo "/home/pi/hud/pi/kiosk.sh &" >> ~/.config/labwc/autostart
#
# FLIP: none | x | y | xy -- find it once in the car with HUD_URL_EXTRA="cal=1", then set it.
FLIP="${FLIP:-none}"
DIM="${DIM:-auto}"

# wait for the backend so the first paint is not an error page
i=0
until curl -s -o /dev/null http://127.0.0.1:8080/state || [ $i -ge 60 ]; do i=$((i + 1)); sleep 0.5; done

exec chromium --kiosk --noerrdialogs --disable-infobars --incognito --no-first-run \
  --check-for-update-interval=31536000 --disable-features=Translate \
  "http://127.0.0.1:8080/?flip=${FLIP}&dim=${DIM}${HUD_URL_EXTRA:+&$HUD_URL_EXTRA}"
