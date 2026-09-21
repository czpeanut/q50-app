#!/usr/bin/env bash
# Render DashView's layout to PNG without a car or an emulator.
#
# tools/Preview.java is a desktop port of DashView's geometry. It exists because the head unit
# is the slowest possible place to discover that two labels overlap: catching that here takes
# seconds, and on the car it costs a trip. It caught five real collisions on the first run.
#
# Keep it in step with DashView by hand -- it mirrors the layout maths, not the code.
#
#   bash tools/preview.sh            # normal running state
#   bash tools/preview.sh warn       # redline, hot coolant, low tyre, hard braking
#   bash tools/preview.sh normal en  # English labels
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/preview
javac -encoding UTF-8 -d build/preview tools/Preview.java
( cd build/preview && java Preview "${1:-normal}" "${2:-zh}" )
echo "-> build/preview/*.png"
