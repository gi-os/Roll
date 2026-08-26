## Roll v2.60 — the filter name now shows in Selfie mode

**The mode-picker band showed "SELFIE" even when the wheel had landed on a named filter — the one
piece of state you couldn't read off the picture.** Pro mode has always put the filter label in
that slot because the wheel cycles through filters and the active one is otherwise invisible. Selfie
has the same wheel and the same filters, but the condition that decided what to print only ever
checked for Photo. Now both modes show the filter name there, the same way Pro always did. Video and
Simple still keep their own labels: they have no dial.

Fixes [light-reports#118] — the mode band printed "SELFIE" over a selected filter.