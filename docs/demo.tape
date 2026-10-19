# Records docs/images/demo.gif from scripts/demo.sh. Needs vhs (brew install vhs) and a running
# stack, warm and seeded: `make warmup && scripts/seed-demo-data.sh` first, ideally on fresh data
# (docs/demo.md). Run from the repo root: vhs docs/demo.tape

Output docs/images/demo.gif

Set Shell "bash"
Set FontSize 15
Set Width 1200
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set TypingSpeed 40ms

Hide
Type "export DEMO_PAUSE=1 && clear"
Enter
Show

# make demo runs the same script after a warm-up and the seed, which the GIF leaves out.
Type "scripts/demo.sh"
Sleep 500ms
Enter

# The demo takes 20-60s on a laptop; wait for its last line, then hold the frame.
Wait+Screen@180s /dashboards:/
Sleep 4s
