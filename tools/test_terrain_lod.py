#!/usr/bin/env python3
"""Build and check terrain geometry and Fabric hooks without starting a game or opening a window."""
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parent.parent
    env = dict(os.environ)
    java_home = Path(env.get("JAVA_HOME") or subprocess.run(["/usr/libexec/java_home", "-v", "25"], text=True, capture_output=True, check=True).stdout.strip())
    env["JAVA_HOME"] = str(java_home)
    java = java_home / "bin/java"
    javac = java_home / "bin/javac"
    with tempfile.TemporaryDirectory(prefix="pm-lod-check-") as directory:
        temp = Path(directory)
        init = temp / "classpath.gradle"
        init.write_text("""allprojects {
    afterEvaluate {
        tasks.register('pmTerrainCheckClasspath') {
            dependsOn tasks.named('classes')
            doLast {
                println 'PM_LOD_CP=' + (sourceSets.main.output + configurations.compileClasspath + configurations.runtimeClasspath).asPath
            }
        }
    }
}
""")
        result = subprocess.run(
            [str(root / "gradlew"), "--offline", "-q", "-I", str(init), "pmTerrainCheckClasspath"],
            cwd=root, env=env, text=True, capture_output=True, check=True,
        )
        cp = next(line.removeprefix("PM_LOD_CP=") for line in result.stdout.splitlines() if line.startswith("PM_LOD_CP="))
        paths = cp.split(os.pathsep)
        sodium = [p for p in paths if "/maven.modrinth/sodium/" in p]
        if len(sodium) != 1:
            raise RuntimeError("Expected exactly one pinned Sodium dependency")
        vanilla_cp = os.pathsep.join(p for p in paths if p not in sodium)
        compiled = temp / "classes"
        compiled.mkdir()
        subprocess.run([str(javac), "-d", str(compiled), "-cp", cp,
                        str(root / "tools/TestTerrainLod.java"), str(root / "tools/TestLodMixins.java"),
                        str(root / "tools/TestPaletteScratch.java"), str(root / "tools/TestTerrainDiagnostics.java"), str(root / "tools/TestMeshBounds.java"), str(root / "tools/TestBoundsProjection.java")], check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestTerrainLod"], cwd=temp, check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestMeshBounds"], cwd=temp, check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestBoundsProjection"], cwd=temp, check=True)
        groups = os.pathsep.join(str(root / p) for p in ["build/classes/java/main", "build/resources/main"])
        for mode, saved_off in [("vanilla", False), ("sodium", False), ("vanilla", True), ("baseline", False)]:
            game = temp / (mode + ("-saved-off" if saved_off else ""))
            game.mkdir()
            if saved_off:
                (game / "config").mkdir()
                (game / "config/puremetal.properties").write_text("terrainLod=false\nrenderScale=75\n")
            mode_cp = cp if mode == "sodium" else vanilla_cp
            subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true", "-Dmixin.debug.export=true",
                            "-Dpuremetal.profile=" + str(mode != "baseline").lower(),
                            "-Dpuremetal.terrainDiagnostics=" + str(mode != "baseline").lower(),
                            "-Dpuremetal.terrainOcclusionProbe=" + str(mode != "baseline").lower(),
                            *(["-Dpuremetal.experimentalLodTextures=false", "-Dpuremetal.rgssEarlyOut=false"] if mode == "baseline" else []),
                            "-Dpuremetal.disablePaletteScratch=" + str(mode == "baseline").lower(),
                            "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                            "-Dfabric.classPathGroups=" + groups, "-cp", str(compiled) + os.pathsep + mode_cp,
                            "TestLodMixins", str(game), mode, "off" if saved_off else "on"], cwd=game, check=True)


if __name__ == "__main__":
    main()
