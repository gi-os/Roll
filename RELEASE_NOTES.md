## Roll v2.59 — multi-select can delete again

**Long-press several photographs in the roll and there was no way to bin them.** The selection bar
offered a close and a share, and nothing else. Trash lived only in the single-photo viewer, so a
set of photographs could be sent together but had to be deleted one at a time — each one opened,
each one binned through its own system dialog. The README already promised "send or delete several
at once"; the delete half of that was never wired up.

The selection bar now has a trash button next to share. It hands every selected photograph to the
system's trash dialog in one request — the same `MediaStore.createTrashRequest` the viewer uses,
just with a list of Uris instead of one. The dialog still asks, because the roll shows photographs
this app did not create, and trashing still means trashing rather than quietly deleting: nothing
about the safety model changed, only that the button now sits where the count of selected
photographs is. The roll refreshes itself on the way back in, and the selection clears as the
binned photographs leave the roll.

Fixes [light-reports#52] — no delete option when multiple photos were selected in the roll.
