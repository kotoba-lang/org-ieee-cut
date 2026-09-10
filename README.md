# kotoba-lang/org-ieee-cut — POSIX `cut`, as a Kotoba command binary

`cut` from IEEE Std 1003.1, the single-field form, written in `.kotoba` and
compiled to a standalone native executable.

```sh
./cut -f 2 FILE      # field 2, delimited by TAB
./cut -d: -f2 FILE   # field 2, delimited by :
```

## Two behaviours that carry most of the weight

**A line with no delimiter is printed whole.** `cut -d: -f2` over a line
reading `nofield` answers `nofield`, not the empty string. That is the
behaviour, not an edge case — `-s` suppresses such lines, and this does not
implement `-s`.

**The default delimiter is a TAB, not a space.** `cut -f2` over `a:b:c`
answers the whole line, because there is no tab in it; over
`one<TAB>two<TAB>three` it answers `two`.

A tab cannot be written in Kotoba source (no control characters, no
char-to-string builtin), so it is cut from a literal that contains one — the
same trick a newline needs.

## The case that caught the real bug

Asking for a field **past the last one**. `-f4` over a three-field line must
answer nothing; before it was fixed this answered `c`, and `-f1`, `-f2` and
`-f3` all agreed.

The mistake was conflating two different questions. Running out of
delimiters *at the top level* means the line has none, and the whole line is
the answer. Running out *mid-descent* means the line has fewer fields than
were asked for, and the answer is empty. `field-of` asks the first question
before the walk starts.

## The list is a set, not a sequence

`-f` takes a list — `-f2,4`, `-f2-4`, `-f2-`, `-f-3`, and mixtures. Measured
against `/usr/bin/cut` 2026-09-10, it behaves as a **set tested per field**
rather than a sequence to walk:

```
-f3,1  -> a:c    output is ASCENDING, not the order written
-f1,1  -> a      a repeat selects the field once
-f2,9  -> b      a field past the end is skipped, not an empty field
-f3-1  -> ""     a descending range selects nothing, and still prints a line
```

So this asks "is field n selected" for n = 1, 2, … in order. Ascending order,
de-duplication and out-of-range skipping all fall out of that — none of them
needs a sort, which is fortunate, because there is no ordering primitive to
sort with.

An absent low bound is 1 and an absent high bound is unbounded, but an absent
**item** is zero: `-f1,`, `-f,1`, `-f` and `-f-` all report *values may not
include zero*, while `-f1-` and `-f-1` are well formed. Both diagnostics are
compared byte for byte.

## Measured against the system utility

Thirty-six cases, all identical on stdout, stderr and exit status: fields 1,
2 and 3; past the last field, twice; no delimiter; an empty file; a leading
and a trailing delimiter (the empty field on each side **is** a field); a
multi-byte field; the tab default with and without a tab present; a missing
operand; the ten list and range cases above; and seven malformed lists across
both diagnostics.

Verified to fail as well as pass. The single-field controls each fail exactly
two cases: the field-overrun bug fails `-f4` and `-f9`, treating an
undelimited line as an empty field fails `nosep` and the tab-default case,
and making the default a space fails both `tabs` cases. The list controls are
just as narrow — writing the delimiter before every selected field instead of
between them fails 18 cases with a leading `:`, and bounding the walk by a
constant instead of the line's field count fails exactly 4, of which
`-f2,9 -> b:` is precisely the skipped-field claim.

## Five parameters

The selected fields are joined by the delimiter, so the separator goes
between them. Carrying a "written anything yet" flag alongside line,
delimiter, spec, index and total is six parameters, and the compiler admits
five (`kotoba.compiler.frontend/max-parameters` — an ABI arity limit, not a
language decision; if it moves, this collapses back into one function). So
the first selected field is found first and written bare, and every later one
is written with a leading delimiter. No flag needed.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37), `:io/write-error`
(39). A missing operand is reported byte-for-byte using wire 35's `EXISTS`
form.

## Several operands, each cut on its own

There are no headers; each operand is cut in turn and the outputs follow one
another. What makes two operands *look* like a single byte stream is a
per-line rule, not concatenation:

**A last line with no trailing newline gets one only if a field was
extracted.** Measured 2026-09-10 — `cut -d: -f1` over `nonl-x:nonl-y` answers
`nonl-x\n` (7 bytes), and over `g` answers `g` (**1 byte, no newline**),
because an undelimited line is echoed whole and its termination is preserved.

```
cut -d: -f1 bare colon   ->  ga / d            bare had no newline, so `a` follows it
cut -d: -f1 nonl colon   ->  nonl-x / a / d    a field was cut, so it did not
```

The first of those was misread as *"cut concatenates its operands"* while this
was being written. It does not — the previous operand's output was simply
unterminated. Those two cases together are what distinguish the models, and
both are in the suite.

### The single-operand suite could not see it

Every undelimited fixture ended in a newline, so this rule was never
exercised and the implementation terminated the last line unconditionally.
The control shows the reach: making it unconditional again fails three cases,
one of which is **single-operand** (`-f1 bare`) — a latent bug that only two
operands made visible.

An unreadable operand is reported and the readable ones are still cut and
printed, with exit 1 — where `sort` refuses to print anything at all.

## `-s` suppresses exactly the lines cut would echo whole

A line with no delimiter is printed whole — that is the behaviour this README
opens with — and `-s` is the flag that drops those lines instead. An **empty**
line has no delimiter either, so `-s` drops it too.

`-s` is about the **delimiter, not the field**: a delimited line that lacks
the requested field still prints an empty line under `-s`.

Removing the suppression fails **5** cases — exactly those containing an
undelimited line, while a fully-delimited fixture correctly survives, since
there is nothing there to suppress. Letting a suppressed line still emit its
newline fails **3**: otherwise `-s` replaces each dropped line with a blank
one.

## The flags are scanned, not indexed

`-d: -s -f1`, `-s -d: -f1` and `-d: -f2 -s` are one request. This used to ask
whether argument 0 began with `-d` and take the operand from a fixed offset —
a positional assumption that `-s` breaks in three places at once. The
arguments are now scanned, and a scan has no position to be wrong about.

Restoring fixed offsets fails **11** cases: every `-s` one. That is the
measure of how load-bearing the change is — it is not tidying.

## What this is not

No `-c`, `-b`, `-s`, `-n`, no reading standard input — with no file operand
this exits 1 rather than pretending to have read an empty one. `-d` must be
joined to its argument (`-d:`), and `-f` to its list (`-f2`).

One malformed spec is classified differently from BSD `cut`: a list with
whitespace in it (`-f'1 ,2'`) is reported here as *illegal list value* where
`/usr/bin/cut` reports *values may not include zero*. That case is left out
of the comparison rather than papered over — the other seven malformed
shapes agree byte for byte.
