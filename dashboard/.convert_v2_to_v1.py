#!/usr/bin/env python3
"""Convert Grafana schema-v2 dashboard (demo-dashboard.json) to a classic v1
export model suitable for the grafana.com dashboards catalog.

All panel/query/layout/variable data already exists in the v2 file; this is a
deterministic structural transform. Panels keep the existing `${datasource}`
template variable (no __inputs needed); __requires advertises the datasource and
panel plugins for the catalog.
"""
import json
import sys
from pathlib import Path

BASE = Path(__file__).resolve().parent
SRC = sys.argv[1] if len(sys.argv) > 1 else BASE / "demo-dashboard.json"
DST = sys.argv[2] if len(sys.argv) > 2 else BASE / "klag-grafana-com.json"
GVER = sys.argv[3] if len(sys.argv) > 3 else "11.3.0"

PANEL_NAMES = {
    "timeseries": "Time series",
    "stat": "Stat",
    "table": "Table",
    "bargauge": "Bar gauge",
    "barchart": "Bar chart",
}
CURSOR_SYNC = {"Off": 0, "Crosshair": 1, "Tooltip": 2, "Shared": 2}
HIDE = {"dontHide": 0, "hideLabel": 1, "hideVariable": 2}
DS = {"type": "prometheus", "uid": "${datasource}"}

with open(SRC) as f:
    d = json.load(f)

elements = d["elements"]


def build_panel(el, gridpos):
    spec = el["spec"]
    viz = spec["vizConfig"]
    ptype = viz["group"]
    vspec = viz.get("spec", {})
    p = {
        "datasource": dict(DS),
        "fieldConfig": vspec.get("fieldConfig", {"defaults": {}, "overrides": []}),
        "gridPos": gridpos,
        "id": spec["id"],
        "options": vspec.get("options", {}),
        "pluginVersion": GVER,
        "title": spec.get("title", ""),
        "type": ptype,
    }
    if spec.get("description"):
        p["description"] = spec["description"]
    # targets
    targets = []
    for q in spec["data"]["spec"]["queries"]:
        qspec = q["spec"]
        inner = dict(qspec["query"]["spec"])  # expr, legendFormat, editorMode, range, instant, format
        t = {"refId": qspec.get("refId", "A"), "datasource": dict(DS)}
        t.update(inner)
        targets.append(t)
    p["targets"] = targets
    # transformations
    trs = spec["data"]["spec"].get("transformations", []) or []
    if trs:
        p["transformations"] = [
            {"id": tr["group"], "options": tr["spec"].get("options", {})} for tr in trs
        ]
    return p, ptype


panels = []
panel_types = set()
y = 0
row_id = 1000

for row in d["layout"]["spec"]["rows"]:
    rspec = row["spec"]
    panels.append({
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "id": row_id,
        "panels": [],
        "title": rspec.get("title", ""),
        "type": "row",
    })
    row_id += 1
    y += 1
    row_h = 0
    for item in rspec["layout"]["spec"]["items"]:
        it = item["spec"]
        name = it["element"]["name"]
        gp = {"h": it["height"], "w": it["width"], "x": it["x"], "y": y + it["y"]}
        panel, ptype = build_panel(elements[name], gp)
        panels.append(panel)
        panel_types.add(ptype)
        row_h = max(row_h, it["y"] + it["height"])
    y += row_h

# templating
tmpl = []
for v in d["variables"]:
    s = v["spec"]
    if v["kind"] == "DatasourceVariable":
        tmpl.append({
            "current": s.get("current", {}),
            "hide": HIDE.get(s.get("hide"), 0),
            "includeAll": s.get("includeAll", False),
            "label": s.get("label", ""),
            "multi": s.get("multi", False),
            "name": s["name"],
            "options": [],
            "query": s.get("pluginId", "prometheus"),
            "refresh": 1,
            "regex": s.get("regex", ""),
            "skipUrlSync": s.get("skipUrlSync", False),
            "type": "datasource",
        })
    elif v["kind"] == "QueryVariable":
        qspec = s["query"]["spec"]
        tmpl.append({
            "allValue": s.get("allValue"),
            "current": s.get("current", {}),
            "datasource": dict(DS),
            "definition": s.get("definition", ""),
            "hide": HIDE.get(s.get("hide"), 0),
            "includeAll": s.get("includeAll", False),
            "label": s.get("label", ""),
            "multi": s.get("multi", False),
            "name": s["name"],
            "options": [],
            "query": {
                "qryType": 1,
                "query": qspec.get("query", ""),
                "refId": qspec.get("refId", "PrometheusVariableQueryEditor-VariableQuery"),
            },
            "refresh": 2,
            "regex": s.get("regex", ""),
            "sort": 1,
            "type": "query",
        })

requires = [
    {"type": "grafana", "id": "grafana", "name": "Grafana", "version": GVER},
    {"type": "datasource", "id": "prometheus", "name": "Prometheus", "version": "1.0.0"},
]
for pt in sorted(panel_types):
    requires.append({"type": "panel", "id": pt, "name": PANEL_NAMES.get(pt, pt), "version": ""})

ts = d.get("timeSettings", {})
out = {
    "__inputs": [],
    "__requires": requires,
    "annotations": {
        "list": [{
            "builtIn": 1,
            "datasource": {"type": "grafana", "uid": "-- Grafana --"},
            "enable": True,
            "hide": True,
            "iconColor": "rgba(0, 211, 255, 1)",
            "name": "Annotations & Alerts",
            "type": "dashboard",
        }]
    },
    "description": d.get("description", ""),
    "editable": d.get("editable", True),
    "fiscalYearStartMonth": ts.get("fiscalYearStartMonth", 0),
    "gnetId": None,
    "graphTooltip": CURSOR_SYNC.get(d.get("cursorSync", "Off"), 0),
    "id": None,
    "links": d.get("links", []),
    "liveNow": d.get("liveNow", False),
    "panels": panels,
    "refresh": ts.get("autoRefresh", ""),
    "schemaVersion": 39,
    "tags": d.get("tags", []),
    "templating": {"list": tmpl},
    "time": {"from": ts.get("from", "now-6h"), "to": ts.get("to", "now")},
    "timepicker": {"refresh_intervals": ts.get("autoRefreshIntervals", [])},
    "timezone": ts.get("timezone", "browser"),
    "title": d.get("title", ""),
    "uid": None,
    "version": 1,
    "weekStart": "",
}

with open(DST, "w") as f:
    json.dump(out, f, indent=2)
    f.write("\n")

print(f"wrote {DST}: {len(panels)} panels (incl rows), "
      f"{len(tmpl)} vars, types={sorted(panel_types)}")
