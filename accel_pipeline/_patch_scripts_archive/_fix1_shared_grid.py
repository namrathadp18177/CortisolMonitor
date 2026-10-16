import re

def patch_file(path, expected_block_count, expected_bound_count):
    src = open(path, encoding="utf-8").read()

    old_block = (
        '        session_start_ms = g["timestamp_ms"].iloc[0]\n'
        '        participant_email = g["participant_email"].iloc[0]\n'
        '        window_idx = ((g["timestamp_ms"] - session_start_ms) // window_ms).astype(int)\n'
    )
    n_block = src.count(old_block)
    print(f"{path}: block match count = {n_block} (expected {expected_block_count})")
    assert n_block == expected_block_count

    new_block = (
        '        participant_email = g["participant_email"].iloc[0]\n'
        '        # Window boundaries are anchored to ABSOLUTE clock time (every window_ms since\n'
        '        # epoch), not to this session\'s own first sample -- so HRV/EDA/skin-temp/accel\n'
        '        # windows covering the same real moment land on IDENTICAL window_start_ms values\n'
        '        across every sensor and every windowing script, instead of each sensor drifting by\n'
        '        # however many ms its own first sample happened to differ by (audit finding:\n'
        '        # "union grid still combines independently anchored window starts"). The session\'s\n'
        '        # first/last window can be shorter than window_sec as a result (it rarely starts\n'
        '        # exactly on a grid boundary) -- n_samples/coverage/low_confidence already handle a\n'
        '        # partial window correctly, same as any other low-coverage window.\n'
        '        window_idx = (g["timestamp_ms"] // window_ms).astype(int)\n'
    )
    src = src.replace(old_block, new_block)

    old_bounds = (
        '                "window_start_ms": session_start_ms + idx * window_ms,\n'
        '                "window_end_ms": session_start_ms + (idx + 1) * window_ms,\n'
    )
    n_bounds = src.count(old_bounds)
    print(f"{path}: bounds match count = {n_bounds} (expected {expected_bound_count})")
    assert n_bounds == expected_bound_count

    new_bounds = (
        '                "window_start_ms": idx * window_ms,\n'
        '                "window_end_ms": (idx + 1) * window_ms,\n'
    )
    src = src.replace(old_bounds, new_bounds)

    open(path, "w", encoding="utf-8").write(src)
    print(f"{path}: patched, size {len(src.encode())}")

patch_file("hrv_eda_windowing.py", 2, 2)
patch_file("accel_windowing.py", 1, 1)
patch_file("skin_temp_windowing.py", 1, 1)
