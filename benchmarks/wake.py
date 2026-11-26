#!/usr/bin/env python3
"""Measure screen-wake-to-fresh-response time on the Android companion."""
import argparse
import datetime
import json
import re
import subprocess
import time
from pathlib import Path


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--adb', default='adb')
    p.add_argument('--serial', default='-d')
    p.add_argument('--trials',type=int,default=4)
    p.add_argument('--sleep-seconds',type=int,default=20)
    p.add_argument('--output',type=Path)
    args=p.parse_args()
    adb=[args.adb]+([args.serial] if args.serial in ('-d','-e') else ['-s',args.serial])
    def call(*commands):
        return subprocess.check_output(adb+list(commands),text=True)
    def stamp(line):
        return datetime.datetime.strptime(line[:18],'%m-%d %H:%M:%S.%f')
    results=[]
    for i in range(args.trials):
        call('shell','input','keyevent','KEYCODE_SLEEP')
        time.sleep(args.sleep_seconds)
        start=call('shell',"date '+%m-%d %H:%M:%S.%3N'").strip()
        call('shell','input','keyevent','KEYCODE_WAKEUP')
        deadline=time.monotonic()+18
        first=None
        fresh=None
        while time.monotonic()<deadline:
            lines=call('logcat','-d','-T',start,'-s','T3Activity:D','*:S').splitlines()
            wake=next((k for k,l in enumerate(lines) if 'T3Activity: screen wake' in l),None)
            if wake is not None:
                for line in lines[wake+1:]:
                    match=re.search(r'T3Activity: (?:update|heartbeat) (\d+)ms, age (\d+)ms',line)
                    if match:
                        result={'latency_ms':round(1000*(stamp(line)-stamp(lines[wake])).total_seconds()),
                                'request_ms':int(match[1]),'source_age_ms':int(match[2])}
                        if first is None:
                            first=result
                        if result['source_age_ms'] <= 2500:
                            fresh=result
                            break
            if fresh is not None:
                break
            time.sleep(.35)
        result={'first':first, 'fresh':fresh}
        results.append(result)
        print('wake',i+1,result,flush=True)
    if args.output:
        args.output.write_text(json.dumps(results,indent=2)+'\n')


if __name__=='__main__':main()
