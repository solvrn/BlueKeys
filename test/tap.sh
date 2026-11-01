#!/bin/bash
# tap.sh <view-id> — taps a BlueKeys view at the centre of its current on-screen bounds.
adb shell uiautomator dump /sdcard/bk.xml >/dev/null
B=$(adb shell cat /sdcard/bk.xml | tr '>' '\n' | grep "id/$1\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+')
set -- $B
adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
