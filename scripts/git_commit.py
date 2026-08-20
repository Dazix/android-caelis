#!/usr/bin/env python3
import subprocess

commit_msg = """feat(ephemeris): add Moon position, lunar trajectory, and rename menu to Position

- Rename navigation destination to 'Poloha' (CS) / 'Position' (EN) with SunAndMoon vector icon
- Implement high-precision Lunar ephemeris (Jean Meeus Ch. 47) with topocentric parallax and Saemundsson atmospheric refraction
- Add 24-hour lunar trajectory planner with configurable time bubbles and Moonrise/Moonset/Transit markers
- Render Moon in cool ice-blue/silver-cyan palette alongside warm golden Sun in AR overlay
- Add Moonrise, Moon transit, Moonset, and phase/illumination % to Day Summary card
- Add comprehensive LunarPositionCalculatorTest and LunarTrajectoryPlannerTest (39 suites passing)

Co-authored-by: Antigravity <antigravity@google.com>
"""

subprocess.run(["git", "add", "."], check=True)
subprocess.run(["git", "commit", "-m", commit_msg], check=True)
subprocess.run(["git", "push"], check=True)
print("SUCCESS: Committed and pushed to origin.")
