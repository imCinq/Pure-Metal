#!/usr/bin/env python3
"""Check the DH experiment using an existing 3.3.4 jar, without launching Minecraft."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile


def java_home():
    configured = os.environ.get("JAVA_HOME")
    if configured:
        return Path(configured)
    result = subprocess.run(["/usr/libexec/java_home", "-v", "25"], text=True, capture_output=True)
    if result.returncode == 0 and result.stdout.strip():
        return Path(result.stdout.strip())
    candidates = [Path("/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home")]
    candidates.extend(Path.home().glob(".gradle/jdks/*/jdk-25*/Contents/Home"))
    for candidate in candidates:
        if (candidate / "bin/java").is_file() and (candidate / "bin/javac").is_file():
            return candidate
    raise SystemExit("Java 25 JDK not found; set JAVA_HOME to a Java 25 JDK.")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/test_distant_horizons.py /path/to/DistantHorizons-3.3.4.jar")
    root = Path(__file__).resolve().parent.parent
    jar = Path(sys.argv[1]).resolve(strict=True)
    env = dict(os.environ)
    home = java_home()
    env["JAVA_HOME"] = str(home)
    with tempfile.TemporaryDirectory(prefix="puremetal-dh-check-") as directory:
        temp = Path(directory)
        init = temp / "classpath.gradle"
        init.write_text("""allprojects { afterEvaluate { tasks.register('dhCheckClasspath') {
            dependsOn tasks.named('classes')
            doLast { println 'DH_CP=' + (sourceSets.main.output + configurations.compileClasspath + configurations.runtimeClasspath).asPath }
        } } }
""")
        result = subprocess.run([str(root / "gradlew"), "--offline", "-q", "-I", str(init), "dhCheckClasspath"], cwd=root, env=env, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError("Failed to prepare test classpath:\n" + result.stdout + result.stderr)
        cp = next(line[6:] for line in result.stdout.splitlines() if line.startswith("DH_CP="))
        cp = os.pathsep.join(p for p in cp.split(os.pathsep) if "/maven.modrinth/sodium/" not in p)
        compiled = temp / "classes"
        compiled.mkdir()
        subprocess.run([str(home / "bin/javac"), "-cp", cp, "-d", str(compiled), str(root / "tools/TestDistantHorizonsKnot.java"), str(root / "tools/TestDistantHorizonsExperiment.java")], check=True, timeout=25)
        full_cp = str(compiled) + os.pathsep + cp
        subprocess.run([str(home / "bin/java"), "-Djava.awt.headless=true", "-cp", full_cp, "io.github.imcinq.puremetal.backend.TestDistantHorizonsExperiment", str(jar)], cwd=temp, check=True, timeout=25)
        groups = os.pathsep.join(str(root / path) for path in ("build/classes/java/main", "build/resources/main"))
        cases = [("disabled", False, False, None, None), ("enabled", True, True, None, None),
                 ("auto-config", True, True, 'renderingEngine = "AUTO"\n', None),
                 ("opengl", True, False, 'renderingEngine = "OPEN_GL"\n', None),
                 ("repacked", True, True, None, "repacked"),
                 ("altered-code", True, False, None, "altered")]
        for name, flag, expected, config, variant in cases:
            game = temp / name
            (game / "mods").mkdir(parents=True)
            copy = game / "mods/dh.jar"
            if variant:
                target = "com/seibel/distanthorizons/common/wrappers/VersionConstants.class"
                with zipfile.ZipFile(jar) as source, zipfile.ZipFile(copy, "w") as out:
                    for entry in source.infolist():
                        data = source.read(entry)
                        if variant == "altered" and entry.filename == target:
                            data += b"\0"
                        if variant == "repacked":
                            entry.compress_type = zipfile.ZIP_DEFLATED
                        out.writestr(entry, data)
                    if variant == "repacked":
                        out.writestr("puremetal-fingerprint-test.txt", "additional harmless resource")
            else:
                shutil.copyfile(jar, copy)
            if config:
                (game / "config").mkdir()
                (game / "config/DistantHorizons.toml").write_text(config)
            command = [str(home / "bin/java"), "-Djava.awt.headless=true", "-Dfabric.development=true",
                       "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                       "-Dfabric.classPathGroups=" + groups, "-Dpuremetal.experimentalDistantHorizons=" + str(flag).lower(),
                       "-cp", full_cp, "TestDistantHorizonsKnot", str(game), str(expected).lower()]
            result = subprocess.run(command, cwd=game, text=True, capture_output=True, timeout=25)
            if result.returncode:
                raise RuntimeError(name + " failed:\n" + result.stdout + result.stderr)
            print(name + ": " + next(line for line in result.stdout.splitlines() if line.startswith("PASS DH Knot")))


if __name__ == "__main__":
    main()
