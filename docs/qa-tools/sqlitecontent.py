#!/usr/bin/env python3
"""Print the *content* of a SQLite database copy, one line per row, sorted (product-agnostic).

usage:
  sqlitecontent.py DB_FILE [TABLE ...]   print "table|col1|col2|..." for every row of every
                                         user table (or only the named ones), rows sorted
  sqlitecontent.py selftest              build a throwaway db, print it, check the output

Opens the file read-only (mode=ro), so it never writes the copy (no WAL checkpoint).
Use it on a copy pulled with sqlitepull.sh (all three WAL files side by side).
Two dumps taken before/after an action and compared with `diff` answer
"did the stored content change" -- not "did the bytes change".
"""
import os
import sqlite3
import sys
import tempfile


def dump(path, tables=None):
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    names = [r[0] for r in con.execute(
        "select name from sqlite_master where type='table' "
        "and name not like 'sqlite_%' and name not in ('android_metadata','room_master_table') "
        "order by name")]
    out = []
    for t in names:
        if tables and t not in tables:
            continue
        rows = ["|".join(map(str, r)) for r in con.execute(f'select * from "{t}"')]
        out += [f"{t}|{r}" for r in sorted(rows)]
    con.close()
    return out


def selftest():
    d = tempfile.mkdtemp()
    p = os.path.join(d, "t.db")
    c = sqlite3.connect(p)
    c.execute("create table a(id text primary key, v text)")
    c.executemany("insert into a values(?,?)", [("2", "x\ny"), ("1", "  sp ")])
    c.commit(); c.close()
    lines = dump(p)
    assert lines == ["a|1|  sp ", "a|2|x\ny"], lines
    print(f"selftest OK: {len(lines)} rows")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "selftest":
        selftest()
    else:
        for line in dump(sys.argv[1], sys.argv[2:] or None):
            print(line)
