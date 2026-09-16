#!/usr/bin/env python3
"""Validate exact COMMIT alignment and post-COMMIT foreign-key integrity."""

from __future__ import annotations

import csv
import sys
from pathlib import Path


RUN_KEY = ("profile", "workload", "variant", "repetition", "seed")
EVENT_REQUIRED = {
    *RUN_KEY,
    "event_id",
    "region_id",
    "event_operation_index",
    "action_operation_index",
    "trial_start_operation",
    "trial_end_operation",
    "commit_operation_index",
    "baseline_objective_nanos",
    "candidate_objective_nanos",
    "predicted_benefit_nanos",
    "region_exposure_fraction",
    "estimated_action_cost_nanos",
    "predicted_break_even_operations",
}
POST_REQUIRED = {
    *RUN_KEY,
    "phase",
    "event_id",
    "region_id",
    "commit_operation_index",
    "window_start_operation",
    "window_end_operation",
    "boundary",
}
RUN_REQUIRED = {*RUN_KEY, "operations", "trial_starts"}


def read_rows(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    if not path.is_file():
        raise SystemExit(f"missing required file: {path}")
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        return list(reader.fieldnames or []), list(reader)


def integer(row: dict[str, str], name: str) -> int:
    try:
        return int(row[name])
    except (KeyError, TypeError, ValueError) as exc:
        raise AssertionError(f"invalid integer {name}={row.get(name)!r}") from exc


def number(row: dict[str, str], name: str) -> float:
    try:
        return float(row[name])
    except (KeyError, TypeError, ValueError) as exc:
        raise AssertionError(f"invalid number {name}={row.get(name)!r}") from exc


def key(row: dict[str, str]) -> tuple[str, ...]:
    return tuple(row[name] for name in RUN_KEY) + (row["event_id"], row["region_id"])


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: validate_commit_instrumentation.py RESULT_DIRECTORY")
    root = Path(sys.argv[1])
    event_fields, events = read_rows(root / "adaptation_events.csv")
    post_fields, post_rows = read_rows(root / "post_commit_region_metrics.csv")
    run_fields, run_rows = read_rows(root / "run_metrics.csv")

    missing_events = EVENT_REQUIRED.difference(event_fields)
    missing_post = POST_REQUIRED.difference(post_fields)
    missing_runs = RUN_REQUIRED.difference(run_fields)
    assert not missing_events, f"missing adaptation fields: {sorted(missing_events)}"
    assert not missing_post, f"missing post-COMMIT fields: {sorted(missing_post)}"
    assert not missing_runs, f"missing run fields: {sorted(missing_runs)}"

    commits: dict[tuple[str, ...], dict[str, str]] = {}
    terminal_trials: dict[tuple[str, ...], int] = {}
    for row in events:
        if row["action"] == "GLOBAL_REBUILD":
            continue
        outcome = row["outcome"]
        if outcome in {"COMMIT", "ROLLBACK", "COMMIT_NO_TRIAL", "KEEP_FAILED_TRIAL"}:
            run_key = tuple(row[name] for name in RUN_KEY)
            terminal_trials[run_key] = terminal_trials.get(run_key, 0) + 1
            exposure = number(row, "region_exposure_fraction")
            assert 0.0 < exposure <= 1.0, row
        if outcome not in {"COMMIT", "COMMIT_NO_TRIAL"}:
            continue
        event_op = integer(row, "event_operation_index")
        action_op = integer(row, "action_operation_index")
        trial_start = integer(row, "trial_start_operation")
        trial_end = integer(row, "trial_end_operation")
        commit_op = integer(row, "commit_operation_index")
        if row["outcome"] == "COMMIT":
            assert integer(row, "trial_operations") > 0, row
            assert 0 < action_op < trial_start <= trial_end, row
            assert event_op == commit_op == trial_end, row
        else:
            assert integer(row, "trial_operations") == 0, row
            assert trial_start == trial_end == -1, row
            assert 0 < action_op == event_op == commit_op, row
        assert key(row) not in commits, f"duplicate COMMIT key: {key(row)}"
        commits[key(row)] = row

    for row in post_rows:
        parent = commits.get(key(row))
        assert parent is not None, f"orphan post-COMMIT row: {key(row)}"
        commit_op = integer(row, "commit_operation_index")
        assert commit_op == integer(parent, "commit_operation_index"), row
        start_op = integer(row, "window_start_operation")
        end_op = integer(row, "window_end_operation")
        assert commit_op < start_op <= end_op, row
        assert row["phase"] == parent["phase"], (
            f"post-COMMIT attribution crossed a phase boundary: parent={parent['phase']!r}, "
            f"observation={row['phase']!r}"
        )

    for row in run_rows:
        run_key = tuple(row[name] for name in RUN_KEY)
        starts = integer(row, "trial_starts")
        terminals = terminal_trials.get(run_key, 0)
        assert starts == terminals, (
            f"unresolved trial lifecycle for {run_key}: trial_starts={starts}, "
            f"terminal_events={terminals}"
        )

    print(f"INSTRUMENTATION_DATA_OK commits={len(commits)} "
          f"post_commit_rows={len(post_rows)} runs={len(run_rows)}")


if __name__ == "__main__":
    main()
