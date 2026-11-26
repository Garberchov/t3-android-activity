#!/usr/bin/env python3
"""Measure companion CPU time and UID network usage on a connected Android device."""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def call(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs)


def stats(adb, package):
    uid_text = call(adb + ['shell', 'cmd', 'package', 'list', 'packages', '-U', package])
    uid = int(re.search(r'uid:(\d+)', uid_text).group(1))
    pid = call(adb + ['shell', 'pidof', package]).strip().split()[0]
    fields = call(adb + ['shell', 'cat', f'/proc/{pid}/stat']).rsplit(')', 1)[1].split()
    cpu_ticks = int(fields[11]) + int(fields[12])
    # Ask Android to refresh its usage accounting; do not reset system counters.
    call(adb + ['shell','dumpsys','netstats','--poll'])
    dump = call(adb + ['shell','dumpsys','netstats','detail'])
    total_bytes = total_packets = 0
    match_uid = False
    for line in dump.splitlines():
        if 'ident=' in line and ' uid=' in line:
            match_uid = bool(re.search(rf'\buid={uid} set=(?:DEFAULT|FOREGROUND) tag=0x0\b', line))
        elif match_uid:
            found = re.search(r'\brb=(\d+) rp=(\d+) tb=(\d+) tp=(\d+)', line)
            if found:
                rb, rp, tb, tp = map(int, found.groups())
                total_bytes += rb + tb
                total_packets += rp + tp
    return {'pid':pid,'cpu_ticks':cpu_ticks,'bytes':total_bytes,'packets':total_packets,
            'clk_tck':int(call(adb+['shell','getconf','CLK_TCK']).strip())}


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--adb', default='adb')
    p.add_argument('--serial', default='-d')
    p.add_argument('--package',default='org.t3activity.companion')
    p.add_argument('--seconds',type=int,default=60)
    p.add_argument('--label',required=True)
    p.add_argument('--output',type=Path)
    args=p.parse_args()
    adb=[args.adb]+([args.serial] if args.serial in ('-d','-e') else ['-s',args.serial])
    before=stats(adb,args.package)
    start_stamp=call(adb+["shell", "date '+%m-%d %H:%M:%S.%3N'"]).strip()
    start=time.monotonic()
    time.sleep(args.seconds)
    after=stats(adb,args.package)
    logs=call(adb+['logcat','-d','-T',start_stamp,'-s','T3Activity:D','*:S'])
    elapsed=time.monotonic()-start
    if before['pid']!=after['pid']:
        raise SystemExit('App process restarted; rerun benchmark.')
    result={'label':args.label,'seconds':round(elapsed,2),
            'cpu_ms':round(1000*(after['cpu_ticks']-before['cpu_ticks'])/after['clk_tck'],1),
            **{key:after[key]-before[key] for key in ('bytes','packets')},
            'requests':len(re.findall(r'T3Activity: (?:update|heartbeat) \d+ms',logs)),
            'errors':len(re.findall(r'T3Activity: Reconnecting:',logs))}
    text=json.dumps(result,indent=2)
    print(text)
    if args.output: args.output.write_text(text+'\n')


if __name__=='__main__':main()
