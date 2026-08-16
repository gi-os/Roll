## Roll v2.50 — the dial lock is a setting, and it is off

**v2.49 could lock your filter dial with nothing on the phone able to open it. This turns that
off and gives it a switch.** If the dial has been stuck since yesterday's update, installing this
unsticks it — the lock is off unless you ask for it.

The fault was in the escape hatch rather than the lock. v2.49 made a click on the wheel the only
way to wake the dial, and reasoned that as long as *something* was pointed at the lock there was
always a way out. That check was about the binding, not about the key: it never asked whether a
wheel click actually reaches this app. On a phone running LightControl — which binds the wheel
system-wide, and is the ordinary state of affairs here — it may not. A turn still arrived, so the
dial reported itself locked; the click did not, so nothing could unlock it. And the way back into
Settings is the mode picker, which is reached with the wheel. A feature whose only escape is the
control it disables is not a feature, and no amount of care inside `Controls` was going to fix
that, because the assumption was outside it.

So the master switch is now **Settings › Keys › Dial lock**, it is **off by default**, and it is a
row you tap. Nothing about it depends on the wheel working. Turning it off wakes the dial
immediately rather than at the next launch, because a switch that appears to do nothing is how
this went wrong the first time.

With it on, the behaviour is what was asked for and what v2.49 described: the dial starts asleep
every time the app opens, a click on the wheel wakes it, a second click puts it back, and a bare
turn while it is asleep says so on the panel instead of moving anything. Press-and-turn and an
open strip still ignore the lock — neither is a gesture a pocket can make. The notice now names
both ways out, the click and the settings row, rather than only the quick one.

**The torch is back on the wheel click.** v2.49 moved that binding's default to the lock, which
took the torch away from everybody as a side effect of a feature nobody had switched on. The lock
now *claims* the click for as long as the setting is on, without touching the binding underneath —
the same mechanism that already lends the volume keys to a playing clip — so turning the lock off
hands the click straight back to the torch on the very next press. The lock is no longer offered
in the key picker at all, which is what stops it being put on your last shutter or taken off the
click and stranding a locked dial again.

Fixes [light-reports#28] — the dial lock could not be turned off, and on this phone could not be
unlocked either.
