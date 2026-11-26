# .ai: skills for coding agents

Each folder in `skills/` is one skill: a `SKILL.md` with a `name`/`description` header (when to use it), the
project's rules for that area, good practices for the language, and how SOLID applies there (as far as it fits:
fully in the Kotlin code and the plugin contract, lightly in C++98 and the scripts). Some practices are not followed everywhere in
the code yet; new and changed code should follow them. `AGENTS.md` in the repository root is the general guide and
wins where the two disagree.

| Skill | Use it for |
|---|---|
| [kotlin-core](skills/kotlin-core/SKILL.md) | Plain Kotlin/JVM logic and its JUnit tests: `car-api/`, `core/`, `plugins/id6` parsing |
| [android-app](skills/android-app/SKILL.md) | The Android layer: `app/` and the plugin app (screens, service, widget, manifest) |
| [plugin-contract](skills/plugin-contract/SKILL.md) | The Drivelog ↔ plugin contract (AIDL, `CarValues`, bundle keys) and new car values end to end |
| [cpp98-headunit](skills/cpp98-headunit/SKILL.md) | The C++98 head-unit plugin in `headunit/` (runs inside the car's HMI) |
| [build-scripts](skills/build-scripts/SKILL.md) | Gradle, Makefile, Bash, PowerShell/batch, `make_db.py`, Docker toolchain |

Claude Code loads project skills from `.claude/skills/`. Each skill there is a short pointer with the same `name` and
`description` that sends the agent here (symlinks are not available on this Windows setup). When you add a skill or
change a description here, update its pointer too. Other agents can read the skills straight from here.
