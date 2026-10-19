#!/usr/bin/env bash
# Records docs/images/demo.gif from scripts/demo.sh, with asciinema and agg
# (brew install asciinema agg). Both have prebuilt packages for Intel Macs; vhs needs ffmpeg, which
# Homebrew compiles from source there. Needs a warm, seeded stack, ideally on fresh data
# (docs/demo.md): make warmup && scripts/seed-demo-data.sh. With assistant-service running, the GIF
# ends with a streamed answer.
# Pauses longer than 2s are shortened in the GIF; the times the demo prints are the real ones.
set -euo pipefail

cast="$(mktemp -d)/demo.cast"
asciinema rec --headless --overwrite --window-size 100x44 --idle-time-limit 2 \
  -c 'printf "\$ scripts/demo.sh\n"; DEMO_PAUSE=1 scripts/demo.sh' "$cast"
agg --theme monokai --font-size 16 --last-frame-duration 8 "$cast" docs/images/demo.gif
echo "wrote docs/images/demo.gif ($(du -h docs/images/demo.gif | cut -f1))"
