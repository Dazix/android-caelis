#!/usr/bin/env python3
import subprocess

commit_msg = """fix(solar): refine optical FOV tracking and remove Red Vision from Sun AR

- Add OnLayoutChangeListener to PreviewView for dynamic optical FOV calculation matching actual viewport dimensions
- Filter multi-camera focal lengths to select primary standard wide lens (3.5mm - 8.0mm)
- Remove Red Vision mode from Sun AR in favor of high-contrast daytime solar palette
- Add instant Toast feedback for Keep Screen Awake and Reset Calibration in Sun AR
- Verified with 100% test pass (37 test suites)

Co-authored-by: Antigravity <antigravity@google.com>
"""

subprocess.run(["git", "add", "."], check=True)
subprocess.run(["git", "commit", "-m", commit_msg], check=True)
subprocess.run(["git", "push"], check=True)
print("SUCCESS: Committed and pushed to origin.")
