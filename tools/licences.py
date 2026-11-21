"""Every library the Android app ships (its release runtime classpath, from
Gradle) with its licence, as LICENSES.md. Gradle does not carry licences:
each publisher's group is mapped to the licence it publishes under, checked
by hand; a library from any other group fails until it is checked.

    python3 tools/licences.py > LICENSES.md
"""
import re, subprocess, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Group (or group prefix ending in ".") -> licence, as each publisher states.
GROUPS = {
    "androidx.": "Apache-2.0",            # Android Jetpack
    "org.jetbrains.kotlin": "Apache-2.0",  # Kotlin standard library
    "org.jetbrains.kotlinx": "Apache-2.0", # coroutines, serialization
    "org.jetbrains": "Apache-2.0",         # annotations
    "com.squareup.okhttp3": "Apache-2.0",
    "com.squareup.okio": "Apache-2.0",
    "com.google.guava": "Apache-2.0",      # listenablefuture only
    "org.jspecify": "Apache-2.0",
    "com.google.zxing": "Apache-2.0",      # QR codes (core only)
}


def licence(group):
    for g, l in GROUPS.items():
        if group == g or (g.endswith(".") and group.startswith(g)):
            return l
    return None


def main():
    out = subprocess.check_output(["./gradlew", "-q", ":app:dependencies", "--configuration", "releaseRuntimeClasspath"], cwd=ROOT, text=True)
    libs = set()
    for m in re.finditer(r"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\s]+)(?: -> ([^\s]+))?", out):
        version = m.group(4) or m.group(3)
        if version.startswith("{"):
            continue
        libs.add((m.group(1), m.group(2), version.rstrip("*").strip()))
    rows = sorted(libs)
    bad = [r for r in rows if licence(r[0]) is None]
    print("# Licences of what the Ortus Android app ships\n")
    print(f"The app is MIT OR Apache-2.0. These are the {len(rows)} libraries it carries (its release runtime classpath), with their licences.\n")
    print("| Group | Library | Version | Licence |\n| --- | --- | --- | --- |")
    for g, a, v in rows:
        print(f"| {g} | {a} | {v} | {licence(g) or '(not checked)'} |")
    for g, a, v in bad:
        print(f"licences: {g}:{a}:{v} is from a publisher whose licence is not checked", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
