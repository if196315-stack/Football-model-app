#!/usr/bin/env python3
"""Combined eight-competition audit using the Android-parity selector."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import audit_domestic_ai_signals as audit

DOMESTIC_CODES = {item[0] for item in audit.LEAGUES.values()}

UEFA = {
    "UCL": ("UCL", "Лига чемпионов УЕФА"),
    "UEL": ("UEL", "Лига Европы УЕФА"),
    "UECL": ("UECL", "Лига конференций УЕФА"),
}

def combine(domestic, uefa):
    comps = []
    seen = set()
    for source in (domestic, uefa):
        for original in source.get("competitions", []):
            if not isinstance(original, dict):
                continue
            comp = dict(original)
            code = str(comp.get("code") or comp.get("id") or "").upper()
            if code not in DOMESTIC_CODES and code not in UEFA:
                continue
            comp["code"] = code
            matches = []
            for match in comp.get("matches", []):
                if not isinstance(match, dict):
                    continue
                key = (code, str(match.get("id") or ""))
                if key in seen:
                    continue
                seen.add(key)
                matches.append(match)
            comp["matches"] = matches
            comps.append(comp)
    return {"competitions": comps}

def build_all(domestic, uefa, today=None):
    audit.LEAGUES.update(UEFA)
    combined = combine(domestic, uefa)
    report = audit.build(combined, today=today)
    today = today or datetime.now(timezone.utc).date()
    for code, league in report["leagues"].items():
        comp = next((x for x in combined["competitions"] if x["code"] == code), {})
        priced = 0
        for match in comp.get("matches", []):
            if match.get("status") not in ("SCHEDULED", "TIMED"):
                continue
            day = audit.match_day(match)
            if day is None or day < today:
                continue
            prediction = match.get("prediction")
            if not isinstance(prediction, dict) or not audit.is_melbet(match.get("odds")):
                continue
            for key, template in audit.RAW_MARKETS:
                probability = audit.number(prediction.get(key))
                side = template.format(home=audit.text(match.get("home")), away=audit.text(match.get("away")))
                if (0 <= probability <= 1 and probability == probability
                        and audit.display_label({"side": side}, match) is not None
                        and audit.odds_for({"side": side}, match) is not None):
                    priced += 1
        league["counts"]["priced_market_quotes"] = priced
    report["totals"]["priced_market_quotes"] = sum(x["counts"].get("priced_market_quotes", 0) for x in report["leagues"].values())
    report["scope"] = ["ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL"]
    report["sources"] = ["docs/data.json", "docs/uefa-data.json"]
    return report

def render(report):
    body = audit.markdown(report)
    rows = ["", "## Покрытие 14 рынков точной ценой MelBet", "",
            "| Турнир | Совпадений рынок + линия + команда + цена ≥ 1.50 |",
            "|---|---:|"]
    for code, league in report["leagues"].items():
        rows.append(f"| {code} | {league['counts'].get('priced_market_quotes', 0)} |")
    rows.append(f"| **Всего** | **{report['totals'].get('priced_market_quotes', 0)}** |")
    body = body.replace("## Матчи, прошедшие отбор", "\n".join(rows) + "\n\n## Матчи, прошедшие отбор")
    body = body.replace("текущий docs/data.json; будущие SCHEDULED/TIMED матчи национальных лиг",
                        "объединение docs/data.json и docs/uefa-data.json; все восемь турниров")
    return body

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="docs/data.json")
    parser.add_argument("--uefa-input", default="docs/uefa-data.json")
    parser.add_argument("--json-out", default="ai-all-competitions-audit.json")
    parser.add_argument("--markdown-out", default="ai-all-competitions-audit.md")
    args = parser.parse_args()
    domestic = json.loads(Path(args.input).read_text(encoding="utf-8"))
    uefa = json.loads(Path(args.uefa_input).read_text(encoding="utf-8"))
    report = build_all(domestic, uefa)
    rendered = render(report)
    Path(args.json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path(args.markdown_out).write_text(rendered, encoding="utf-8")
    print(rendered)

if __name__ == "__main__":
    main()
