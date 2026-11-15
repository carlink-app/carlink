"""Print a benchmark report (or several) as a table.

Usage: python3 summarize.py report.json [report2.json ...]
"""
import json
import sys


def cell(p, k):
    return f"{p[k]:.1f}" if p and p.get("n") else "-"


def main():
    for path in sys.argv[1:]:
        with open(path) as f:
            r = json.load(f)
        feat = r.get("features", {})
        print(f"\n== {path}")
        print(f"   {feat.get('chromiumVersion')}  secure={feat.get('isSecureContext')}  "
              f"WebTransport={feat.get('WebTransport')}  gpu={feat.get('webglRenderer')}")
        for k, v in feat.items():
            if k.startswith("decode "):
                print(f"   {k}: {v}")
        head = ["test", "fps", "dec50", "dec99", "draw99", "n2p50", "n2p99", "jit99", "jitmax", "rtt50", "rtt99", "err"]
        print("   " + " ".join(f"{h:>8}" if i else f"{h:<30}" for i, h in enumerate(head)))
        for run in r.get("runs", []):
            if run.get("skipped"):
                print(f"   {run['name']:<30} skipped: {run['skipped']}")
                continue
            err = str(run.get("errors") or "")
            if run.get("pingLoss"):
                err += f" loss {run['pingLoss'] * 100:.1f}%"
            if run.get("droppedFrames"):
                err += f" drop {run['droppedFrames']}"
            if run.get("failed"):
                err += f" FAILED {run['failed']}"
            row = [
                f"{run.get('fps', 0):.1f}" if run.get("fps") else "-",
                cell(run.get("decode"), "p50"), cell(run.get("decode"), "p99"),
                cell(run.get("draw"), "p99"),
                cell(run.get("netToPixel"), "p50"), cell(run.get("netToPixel"), "p99"),
                cell(run.get("jitter"), "p99"), cell(run.get("jitter"), "max"),
                cell(run.get("rtt"), "p50"), cell(run.get("rtt"), "p99"),
            ]
            print(f"   {run['name']:<30} " + " ".join(f"{c:>8}" for c in row) + f" {err}")


if __name__ == "__main__":
    main()
