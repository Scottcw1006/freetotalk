#!/usr/bin/env python3
"""Print every UI text node with its bounds and exact code points.

selftest: `python3 uitext.py selftest` -> dumps once and prints 'selftest OK: N nodes'.
Usage:   `python3 uitext.py [substring]` -> rows 'y0,y1<TAB>repr(text)<TAB>U+xxxx ...'
Only prints nodes whose text contains the substring (default: all non-empty).
"""
import re,subprocess,sys,html

def dump():
    raw=subprocess.run(['adb','exec-out','uiautomator','dump','/dev/tty'],
                       capture_output=True).stdout.decode('utf-8','surrogatepass')
    i=raw.find('<?xml'); j=raw.rfind('>')
    if i<0 or j<0: sys.exit('ABORT: dump failed: '+raw.strip()[:200])
    return raw[i:j+1]

def nodes(xml):
    for m in re.finditer(r'<node[^>]*>',xml):
        tag=m.group(0)
        t=re.search(r'\btext="([^"]*)"',tag)
        b=re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',tag)
        if not t or not b: continue
        txt=html.unescape(t.group(1))
        if txt: yield (int(b.group(2)),int(b.group(4)),txt)

if __name__=='__main__':
    xml=dump()
    ns=list(nodes(xml))
    if len(sys.argv)>1 and sys.argv[1]=='selftest':
        print('selftest OK: %d nodes'%len(ns)); sys.exit(0)
    needle=sys.argv[1] if len(sys.argv)>1 else ''
    for y0,y1,txt in ns:
        if needle and needle not in txt: continue
        cps=' '.join('U+%04X'%ord(c) for c in txt)
        print('%d,%d\t%s\t%s'%(y0,y1,repr(txt),cps))
