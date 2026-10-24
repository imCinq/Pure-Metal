#!/usr/bin/env python3
"""Actual shader compilation and UV prototype checks, with no game/window launch."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
env = dict(os.environ)
if not env.get("JAVA_HOME"):
    env["JAVA_HOME"] = subprocess.run(["/usr/libexec/java_home", "-v", "25"], text=True, capture_output=True, check=True).stdout.strip()
with tempfile.TemporaryDirectory(prefix="pm-texture-check-") as directory:
    temp = Path(directory)
    init = temp / "classpath.gradle"
    init.write_text("""allprojects { afterEvaluate {
tasks.register('pmTextureCheckClasspath') {
dependsOn tasks.named('classes')
doLast { println 'PM_CP=' + (sourceSets.main.output + configurations.compileClasspath + configurations.runtimeClasspath).asPath }
}
} }
""")
    result = subprocess.run([str(root / "gradlew"), "--offline", "-q", "-I", str(init), "pmTextureCheckClasspath"], cwd=root, env=env, text=True, capture_output=True, check=True)
    cp = next(line[6:] for line in result.stdout.splitlines() if line.startswith("PM_CP="))
    subprocess.run([env["JAVA_HOME"]+"/bin/javac", "-cp", cp, "-d", str(temp), str(root/"tools/TestLodTextureShaders.java")], check=True)
    reference = env.get("PM_LOD_SHADER_REFERENCE")
    subprocess.run([env["JAVA_HOME"]+"/bin/java", "-Djava.awt.headless=true", *(["-Dpuremetal.lodShaderReference="+reference] if reference else []), "-cp", str(temp)+os.pathsep+cp, "io.github.imcinq.puremetal.backend.TestLodTextureShaders", str(root/"build/lod-texture-shaders")], cwd=root, check=True)
