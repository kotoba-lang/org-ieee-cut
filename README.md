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

## Measured against the system utility

Fifteen cases, all identical on stdout, stderr and exit status: fields 1, 2
and 3; past the last field, twice; no delimiter; an empty file; a leading and
a trailing delimiter (the empty field on each side **is** a field); a
multi-byte field; the tab default with and without a tab present; and a
missing operand.

Verified to fail as well as pass — each control fails **exactly two** cases
and no others: the field-overrun bug fails `-f4` and `-f9`, treating an
undelimited line as an empty field fails `nosep` and the tab-default case,
and making the default a space fails both `tabs` cases.

## Capabilities

`:cli/args` (38), `:fs/app-data` (35), `:io/write` (37), `:io/write-error`
(39). A missing operand is reported byte-for-byte using wire 35's `EXISTS`
form.

## What this is not

One field. No lists (`-f1,3`), no ranges (`-f2-`), no `-c`, `-b`, `-s`, `-n`,
no multiple operands, no reading standard input. `-d` must be joined to its
argument (`-d:`), and `-f` to its number (`-f2`).
