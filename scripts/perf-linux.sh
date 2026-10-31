#!/usr/bin/env bash
# Measures the release build: start-up to "UI ready", memory (PSS/RSS) and idle CPU.
# Usage: scripts/perf-linux.sh [runs]   (after `pnpm tauri build`; close other Luncher windows first)
cd "$(dirname "$0")/.."
source scripts/env.sh
BIN=target/release/luncher
LOG=$(ls -t ~/.local/share/luncher/logs/luncher*.log | head -1)
tree_pids() { local p=$1; echo $p; for c in $(pgrep -P $p); do tree_pids $c; done; }
ticks() { local t=0; for p in "$@"; do [ -r /proc/$p/stat ] && t=$((t + $(awk '{print $14+$15}' /proc/$p/stat))); done; echo $t; }
for run in $(seq 1 ${1:-3}); do
  before=$(wc -l < "$LOG")
  t0=$(date +%s.%N)
  $BIN > /dev/null 2>&1 &
  pid=$!
  for i in $(seq 1 200); do
    sleep 0.05
    LOG=$(ls -t ~/.local/share/luncher/logs/luncher*.log | head -1)
    tail -n +$((before+1)) "$LOG" | grep -q "UI ready" && break
  done
  t1=$(date +%s.%N)
  ready=$(echo "$t1 - $t0" | bc)
  sleep 10
  pids=$(tree_pids $pid)
  pss=0; rss=0
  for p in $pids; do
    v=$(awk '/^Pss:/{print $2}' /proc/$p/smaps_rollup 2>/dev/null); pss=$((pss + ${v:-0}))
    v=$(awk '/^VmRSS:/{print $2}' /proc/$p/status 2>/dev/null); rss=$((rss + ${v:-0}))
  done
  c0=$(ticks $pids); sleep 10; c1=$(ticks $pids)
  hz=$(getconf CLK_TCK)
  cpu=$(echo "scale=1; ($c1 - $c0) * 100 / $hz / 10" | bc)
  printf "run %d: ui_ready=%.2fs procs=%d pss=%dMB rss=%dMB idle_cpu=%s%%\n" $run $ready $(echo $pids | wc -w) $((pss/1024)) $((rss/1024)) $cpu
  for p in $pids; do printf "   %-18s pss=%dMB\n" "$(cat /proc/$p/comm 2>/dev/null)" $(( $(awk '/^Pss:/{print $2}' /proc/$p/smaps_rollup 2>/dev/null || echo 0) / 1024 )); done
  kill $pid; wait $pid 2>/dev/null; sleep 1
done
