#!/usr/bin/env python3
import subprocess

commit_msg = """feat(nightsky): add hardware-accelerated AGSL Sky Dome & Milky Way background

- Replace software rasterization with GPU AGSL RuntimeShader (API 33+)
- Integrate starless J2000.0 Milky Way equirectangular WebP texture (2048x1024)
- Add zero-allocation CelestialMatrixCalculator for real-time 3x3 view matrix construction
- Implement 4-layer NightSkyRenderer: Layer 0 Opaque Sky, Layer 1 AGSL Milky Way, Layer 2 3D Grid, Layer 3 FlatBuffers Stars & Constellations
- Add streamlined NightSkyActionDock with Material 3 Expressive circular toggle buttons
- Add instant Toast feedback on all action toggles displaying action name and state (On / Off)
- Persist night sky preferences in SettingsStore and update test suites (322/322 passing)

Co-authored-by: Antigravity <antigravity@google.com>
"""

subprocess.run(["git", "add", "."], check=True)
subprocess.run(["git", "commit", "-m", commit_msg], check=True)
