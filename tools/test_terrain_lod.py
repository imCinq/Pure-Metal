#!/usr/bin/env python3
"""Build and check terrain geometry and Fabric hooks without starting a game or opening a window."""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


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
            [str(root / "gradlew"), "--offline", "-q", "-I", str(init), "pmTerrainCheckClasspath",
             *(["-PsodiumVersion=" + env["SODIUM_VERSION"]] if env.get("SODIUM_VERSION") else [])],
            cwd=root, env=env, text=True, capture_output=True, check=True,
        )
        cp = next(line.removeprefix("PM_LOD_CP=") for line in result.stdout.splitlines() if line.startswith("PM_LOD_CP="))
        paths = cp.split(os.pathsep)
        sodium = [p for p in paths if "/maven.modrinth/sodium/" in p]
        if len(sodium) != 1:
            raise RuntimeError("Expected exactly one pinned Sodium dependency")
        vanilla_cp = os.pathsep.join(p for p in paths if p not in sodium)
        # Compile the typed regression fixture against the exact fluid API bundled with this Sodium.
        # The game/runtime dependency set is unchanged; Knot still loads the nested API normally.
        fluid_api = temp / "fluid-api.jar"
        with zipfile.ZipFile(sodium[0]) as archive:
            candidates = [name for name in archive.namelist() if name.startswith("META-INF/jars/")
                          and "fabric-rendering-fluids-v1" in name and name.endswith(".jar")]
            if len(candidates) != 1:
                raise RuntimeError("Expected one bundled fluid API")
            fluid_api.write_bytes(archive.read(candidates[0]))
            api_jars = []
            for index, name in enumerate(archive.namelist()):
                if name.startswith("META-INF/jars/fabric-") and name.endswith(".jar"):
                    jar = temp / ("bundled-api-" + str(index) + ".jar")
                    jar.write_bytes(archive.read(name))
                    api_jars.append(str(jar))
        compiled = temp / "classes"
        compiled.mkdir()
        subprocess.run([str(javac), "-d", str(compiled), "-cp", cp,
                        str(root / "tools/TestTerrainLod.java"), str(root / "tools/TestLodMixins.java"),
                        str(root / "tools/TestPaletteScratch.java"), str(root / "tools/TestTerrainDiagnostics.java"), str(root / "tools/TestMeshBounds.java"), str(root / "tools/TestBoundsProjection.java"),
                        str(root / "tools/TestSodiumBlockCallbacks.java"), str(root / "tools/TestFluidCallbacks.java")], check=True)
        subprocess.run([str(javac), "-d", str(compiled), "-cp", cp + os.pathsep + str(fluid_api),
                        str(root / "tools/TestFluidCarrierCost.java")], check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestTerrainLod"], cwd=temp, check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestMeshBounds"], cwd=temp, check=True)
        subprocess.run([str(java), "-cp", str(compiled) + os.pathsep + vanilla_cp, "TestBoundsProjection"], cwd=temp, check=True)
        game = temp / "fluids-absent"
        game.mkdir()
        groups = os.pathsep.join(str(root / p) for p in ["build/classes/java/main", "build/resources/main"])
        subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true",
                        "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                        "-Dfabric.classPathGroups=" + groups, "-cp", str(compiled) + os.pathsep + vanilla_cp,
                        "TestFluidCallbacks", str(game)], cwd=game, check=True)
        game = temp / "fluid-api-without-sodium"
        game.mkdir()
        subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true",
                        "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                        "-Dfabric.classPathGroups=" + groups, "-cp",
                        os.pathsep.join([str(compiled), vanilla_cp, *api_jars]),
                        "TestFluidCallbacks", str(game)], cwd=game, check=True)
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
        for disabled in (False, True):
            game = temp / ("callbacks-disabled" if disabled else "callbacks-enabled")
            game.mkdir()
            subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true",
                            "--sun-misc-unsafe-memory-access=allow",
                            "-Dpuremetal.disableSodiumBlockCallbacks=" + str(disabled).lower(),
                            "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                            "-Dfabric.classPathGroups=" + groups, "-cp", str(compiled) + os.pathsep + cp,
                            "TestSodiumBlockCallbacks", str(game)], cwd=game, check=True)
            game = temp / ("fluids-disabled" if disabled else "fluids-enabled")
            game.mkdir()
            subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true",
                            "-Dpuremetal.disableFluidCallbacks=" + str(disabled).lower(),
                            "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                            "-Dfabric.classPathGroups=" + groups, "-cp", str(compiled) + os.pathsep + cp,
                            "TestFluidCallbacks", str(game)], cwd=game, check=True)
            for fallback in (False, True):
                game = temp / ("fluid-cost-" + str(disabled) + "-" + str(fallback))
                game.mkdir()
                subprocess.run([str(java), "-Djava.awt.headless=true", "-Dfabric.development=true",
                                "-Dpuremetal.disableFluidCallbacks=" + str(disabled).lower(),
                                "-DfluidProbe.fallback=" + str(fallback).lower(),
                                "-Dfabric.defaultModDistributionNamespace=official", "-Dfabric.defaultMixinRemapType=static",
                                "-Dfabric.classPathGroups=" + groups, "-cp", str(compiled) + os.pathsep + cp,
                                "TestFluidCarrierCost", str(game)], cwd=game, check=True)


if __name__ == "__main__":
    main()
