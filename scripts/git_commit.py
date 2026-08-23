#!/usr/bin/env python3
import subprocess

commit_msg = """feat(ar): ensure continuous lunar trajectory passage and enhance AR sky renderers

- Sample lunar trajectory continuously centered on culmination transit (T_transit ± 12h) to eliminate midnight gaps
- Align lunar day times calculator to find cohesive rise, transit, and set for the active passage
- Ensure time bubbles cross midnight in proper chronological order with smooth angular deltas (< 2°)
- Add comprehensive lunar trajectory tests covering continuity, midnight transitions, and all 31 days of the month
- Add camera FOV aspect-ratio crop calculation tests and zero-allocation night sky render validations
- Add helper scripts for safe dedicated profile APK installation and automated gate verification

Co-authored-by: Antigravity <antigravity@google.com>
"""

subprocess.run(["git", "add", "."], check=True)
subprocess.run(["git", "commit", "-m", commit_msg], check=True)
subprocess.run(["git", "push"], check=True)
print("SUCCESS: Committed and pushed to origin.")
