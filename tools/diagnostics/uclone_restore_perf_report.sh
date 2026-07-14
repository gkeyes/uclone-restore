#!/bin/sh
set -eu

usage() {
  cat <<'EOF'
Usage: uclone_restore_perf_report.sh <task-log> [task-log ...]

Read UClone task logs and summarize restore phase timings. The input files are
never modified. Logs created before UCLONE_PERF markers were introduced remain
valid input and are reported with PERF_MARKERS=0.
EOF
}

if [ "$#" -eq 0 ]; then
  usage >&2
  exit 2
fi

case "$1" in
  -h|--help)
    usage
    exit 0
    ;;
esac

status=0
for log_path do
  if [ ! -f "$log_path" ]; then
    printf 'ERROR: log not found: %s\n' "$log_path" >&2
    status=1
    continue
  fi

  printf 'FILE=%s\n' "$log_path"
  awk '
    BEGIN {
      task = "UNKNOWN"
      request_id = "UNKNOWN"
      duration_ms = "UNKNOWN"
      exit_code = "UNKNOWN"
      scanned_files = 0
      copied_files = 0
      copied_bytes = 0
      peak_temporary_bytes = 0
      target_downtime_ms = 0
      marker_count = 0
      order_count = 0
    }

    {
      sub(/\r$/, "")
    }

    /^TASK=/ {
      task = substr($0, 6)
      next
    }

    /^REQUEST_ID=/ {
      request_id = substr($0, 12)
      next
    }

    /^DURATION_MS=/ {
      duration_ms = substr($0, 13)
      next
    }

    /^EXIT=/ {
      exit_code = substr($0, 6)
      next
    }

    /^UCLONE_METRIC:scanned_files=/ {
      for (field_index = 1; field_index <= NF; field_index++) {
        split($field_index, field, "=")
        key = field[1]
        sub(/^UCLONE_METRIC:/, "", key)
        value = field[2]
        if (key == "scanned_files") scanned_files += value
        else if (key == "copied_files") copied_files += value
        else if (key == "copied_bytes") copied_bytes += value
        else if (key == "peak_temporary_bytes" && value > peak_temporary_bytes) peak_temporary_bytes = value
        else if (key == "target_downtime_ms" && value > target_downtime_ms) target_downtime_ms = value
      }
      next
    }

    /^UCLONE_PERF:phase=/ {
      phase = "UNKNOWN"
      part = "UNKNOWN"
      duration = ""
      for (field_index = 1; field_index <= NF; field_index++) {
        split($field_index, field, "=")
        key = field[1]
        sub(/^UCLONE_PERF:/, "", key)
        value = field[2]
        if (key == "phase") phase = value
        else if (key == "part") part = value
        else if (key == "duration_ms") duration = value
      }
      if (duration !~ /^[0-9]+$/) next
      group = phase SUBSEP part
      if (!(group in seen)) {
        seen[group] = 1
        order[++order_count] = group
      }
      count[group]++
      total[group] += duration
      if (duration > maximum[group]) maximum[group] = duration
      marker_count++
    }

    END {
      printf "TASK=%s\n", task
      printf "REQUEST_ID=%s\n", request_id
      printf "DURATION_MS=%s\n", duration_ms
      printf "EXIT=%s\n", exit_code
      printf "METRICS scanned_files=%s copied_files=%s copied_bytes=%s peak_temporary_bytes=%s target_downtime_ms=%s\n", \
        scanned_files, copied_files, copied_bytes, peak_temporary_bytes, target_downtime_ms
      printf "PERF_MARKERS=%d\n", marker_count
      if (marker_count > 0) {
        print "PHASE PART COUNT TOTAL_MS MAX_MS"
        for (position = 1; position <= order_count; position++) {
          group = order[position]
          split(group, fields, SUBSEP)
          printf "%s %s %d %.0f %.0f\n", fields[1], fields[2], count[group], total[group], maximum[group]
        }
      }
    }
  ' "$log_path"
  printf '\n'
done

exit "$status"
