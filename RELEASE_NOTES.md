## Roll v3.16: a silent death reports what the kernel said

**The low-memory kill behind light-reports#731 arrived as a report with no detail attached, even though the exit check had the kernel's own description in hand when it announced the death.**

**The symptom.** On a field phone, the previous run was killed by the low-memory killer. On the next start, Roll announced the death on the fault chip, the chip was read, and the report that came back carried only the tally line. Nothing in it said what the kernel saw.

**The cause.** When Roll starts, it reads the system's record of how the last run ended. For a native crash, a low-memory kill, an ANR or a signal, it announces the death. It did that in two steps. First, `recordFault` recorded the death into `Trouble` with no detail. Then a second `Trouble.record` call followed with the kernel's description attached. `Trouble` keeps the first failure it is given and does not overwrite it, so the second record, the one with the description, was always dropped.

**What changed.** The description now passes through `recordFault` as the fault's detail, so it goes in with the first record. The second record is gone.

Fixes [light-reports#731]: the fault chip was read, and the low-memory kill behind it arrived with none of the detail the exit check had in hand.

Nothing about which deaths are announced, the chip tally, or the report title changes. This is not verified on a phone.
