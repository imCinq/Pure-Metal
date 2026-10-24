"""Runs one dev client per graphics API, strictly one at a time, and prints each BENCH line.
Usage: python3 -I tools/bench.py <tag> <api> [<api> ...]   (api: default | vulkan | opengl; default = Metal)
Env: BENCH_WALK=1 (autopilot: fly forward), BENCH_COMMAND (run once on join, e.g. "gamemode creative"), BENCH_SECONDS / BENCH_WARMUP (default 25 / 10), BENCH_COOLDOWN (idle seconds before each run), NO_SODIUM=1 runs without Sodium; BENCH_ARGS adds extra gradle args (e.g. "-Pvalidate").
BENCH_REPEAT=N runs the API list N times (default 1) and prints a per-API summary (median avgFps, p98Ms, p99.5Ms).
BENCH_PREWARM=1 does one unmeasured run with the first API before the measured runs, so autopilot chunks are generated and saved.
The game window must stay focused and in-world: frames only count with no screen open."""
import os
import re, signal, statistics, subprocess, sys, time

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OPTIONS = os.path.join(PROJECT, "run", "options.txt")
ENV = dict(os.environ)
METRICS = [re.compile(rf'{k}=([\d.]+)') for k in ("avgFps", "p98Ms", r"p99\.5Ms")]


def clients_running():
    return subprocess.run(["pgrep", "-f", "KnotClient"], capture_output=True).returncode == 0


def set_option(key, value):
    with open(OPTIONS) as f:
        text = f.read()
    text = re.sub(rf'^{key}:.*$', f'{key}:{value}', text, flags=re.M)
    with open(OPTIONS, "w") as f:
        f.write(text)


def set_api(api):
    set_option("preferredGraphicsBackend", f'"{api}"')


def run_client(api, log, extra):
    while clients_running():
        time.sleep(2)
    set_api(api)
    with open(log, "w") as out:
        sodium = [] if os.environ.get("NO_SODIUM") else ["-Psodium"]
        subprocess.run(["./gradlew", "runClient", *sodium, "-Pworld=New World", "-Pbench=" + os.environ.get("BENCH_SECONDS", "25"), "-PbenchWarmup=" + os.environ.get("BENCH_WARMUP", "10"), *extra],
                       cwd=PROJECT, env=ENV, stdout=out, stderr=subprocess.STDOUT)
    with open(log) as f:
        return [l.split("BENCH", 1)[1].strip() for l in f if "BENCH backend" in l or "BENCH encoders" in l]


def metrics(lines):
    for l in lines:
        found = [p.search(l) for p in METRICS] if l.startswith("backend=") else None
        if found and all(found):
            return tuple(float(m.group(1)) for m in found)
    return None


tag, apis = sys.argv[1], sys.argv[2:]
extra = os.environ.get("BENCH_ARGS", "").split()
if os.environ.get("BENCH_WALK"):
    extra.append("-PbenchWalk")
if os.environ.get("BENCH_COMMAND"):
    extra.append("-PbenchCommand=" + os.environ["BENCH_COMMAND"])
repeat = int(os.environ.get("BENCH_REPEAT", "1"))
with open(OPTIONS) as f:
    text = f.read()
vsync = re.search(r'^enableVsync:(.*)$', text, flags=re.M).group(1)
fullscreen = re.search(r'^fullscreen:(.*)$', text, flags=re.M).group(1)
pause = re.search(r'^pauseOnLostFocus:(.*)$', text, flags=re.M).group(1)
backend = re.search(r'^preferredGraphicsBackend:(.*)$', text, flags=re.M).group(1)
signal.signal(signal.SIGTERM, lambda *_: sys.exit(1))  # run the finally block when stopped
set_option("enableVsync", "false")  # measure uncapped; the user's own settings are restored below
set_option("fullscreen", "true")  # every run at the full display size
set_option("pauseOnLostFocus", "false")  # a focus change must not open the pause menu mid-run
try:
    if os.environ.get("BENCH_PREWARM") == "1":
        run_client(apis[0], os.path.join(PROJECT, "build", f"bench-{tag}-prewarm.log"), extra)  # result ignored
        print("prewarm done", flush=True)
    # An api may be listed more than once to set a balanced order (e.g. "default vulkan opengl opengl vulkan default").
    # BENCH_COOLDOWN seconds of idle before every run keep heat build-up (fanless Macs) from favouring early runs.
    cooldown = float(os.environ.get("BENCH_COOLDOWN", "0"))
    results = {api: [] for api in apis}
    multi = repeat > 1 or len(set(apis)) < len(apis)
    run = 0
    for i in range(repeat):
        for api in apis:
            run += 1
            if cooldown > 0:
                time.sleep(cooldown)
            name = f"bench-{tag}-{api}-{run}.log" if multi else f"bench-{tag}-{api}.log"
            log = os.path.join(PROJECT, "build", name)
            lines = run_client(api, log, extra)
            print(api, "->", " || ".join(lines) if lines else "NO RESULT (see " + log + ")", flush=True)
            m = metrics(lines)
            if m:
                results[api].append(m)
    if multi:
        print("SUMMARY", flush=True)
        for api, runs in results.items():
            if not runs:
                print(api, "-> NO RESULT", flush=True)
                continue
            med = [statistics.median(r[k] for r in runs) for k in range(3)]
            print(f"{api} median avgFps={med[0]:.2f} p98Ms={med[1]:.2f} p99.5Ms={med[2]:.2f} runs={len(runs)} "
                  f"avgFps per run={', '.join(f'{r[0]:.2f}' for r in runs)}", flush=True)
finally:
    set_option("preferredGraphicsBackend", backend)
    set_option("enableVsync", vsync)
    set_option("fullscreen", fullscreen)
    set_option("pauseOnLostFocus", pause)
