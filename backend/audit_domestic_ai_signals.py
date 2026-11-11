#!/usr/bin/env python3
"""Read-only audit that mirrors MainActivity.collectAiCandidates / strongestDisplaySignal."""
import argparse
import json
import math
import re
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path

LEAGUES = {
    "ENG": ("PL", "Англия · Премьер-лига"),
    "ESP": ("PD", "Испания · Ла Лига"),
    "ITA": ("SA", "Италия · Серия A"),
    "GER": ("BL1", "Германия · Бундеслига"),
    "FRA": ("FL1", "Франция · Лига 1"),
}
MIN_ODDS = 1.50
MIN_PROBABILITY = 0.60
MIN_EV = 0.05
MIN_EDGE_PP = 5.0
RAW_MARKETS = (
    ("1x", "1X"), ("x2", "X2"), ("bttsYes", "ОЗ ДА"),
    ("over15", "ТБ 1.5"), ("over25", "ТБ 2.5"), ("over35", "ТБ 3.5"),
    ("homeOver05", "ИТБ 0.5 · {home}"), ("homeOver15", "ИТБ 1.5 · {home}"),
    ("homeOver25", "ИТБ 2.5 · {home}"), ("homeOver35", "ИТБ 3.5 · {home}"),
    ("awayOver05", "ИТБ 0.5 · {away}"), ("awayOver15", "ИТБ 1.5 · {away}"),
    ("awayOver25", "ИТБ 2.5 · {away}"), ("awayOver35", "ИТБ 3.5 · {away}"),
)

def number(value, default=float("nan")):
    try:
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default

def text(value):
    return "" if value is None else str(value).strip()

def norm_side(value):
    return re.sub(r"\s+", " ", text(value)).lower()

def is_melbet(board):
    if not isinstance(board, dict):
        return False
    bookmaker = text(board.get("bookmaker"))
    return bookmaker.lower() == "melbet" if bookmaker else "melbet" in text(board.get("source")).lower()

def odds_for(signal, match, min_odds=MIN_ODDS):
    """Port of Android topSignalOdds: exact market, team and line; MelBet only."""
    board = match.get("odds")
    if not is_melbet(board):
        return None
    best = board.get("best")
    if not isinstance(best, dict):
        return None

    def acceptable(value):
        price = number(value)
        return price if math.isfinite(price) and price >= min_odds else None

    side = text(signal.get("side"))
    if side.upper() in ("1X", "X2"):
        return acceptable((best.get("doubleChance") or {}).get("1x" if side.upper() == "1X" else "x2"))
    if side.upper() == "ОЗ ДА":
        return acceptable((best.get("btts") or {}).get("yes"))
    if side.upper() == "ОЗ НЕТ":
        return acceptable((best.get("btts") or {}).get("no"))
    if side.upper().startswith("ИТБ "):
        found = re.search(r"ИТБ\s+([0-9]+(?:\.[0-9]+)?)", side, re.I)
        if not found:
            return None
        point = number(found.group(1))
        team_name = re.sub(r"\s+", " ", side.split("·", 1)[1].strip()) if "·" in side else ""
        home = re.sub(r"\s+", " ", text(match.get("home")))
        away = re.sub(r"\s+", " ", text(match.get("away")))
        team = "home" if team_name.lower() == home.lower() else "away" if team_name.lower() == away.lower() else ""
        rows = best.get("teamTotals")
        if not team or not isinstance(rows, list):
            return None
        for row in rows:
            if not isinstance(row, dict):
                continue
            raw_team = text(row.get("team"))
            row_team = ("home" if raw_team.lower() in ("home", "хозяева", home.lower()) or raw_team == "1"
                        else "away" if raw_team.lower() in ("away", "гости", away.lower()) or raw_team == "2" else "")
            line = number(row.get("point"))
            price = acceptable(row.get("price"))
            if row_team == team and text(row.get("side")).lower() == "over" and math.isfinite(line) and abs(line-point) < .001 and price is not None:
                return price
        return None
    if side.startswith("ТБ "):
        point = number(side[3:].strip())
        rows = best.get("totals")
        if not isinstance(rows, list):
            return None
        for row in rows:
            if not isinstance(row, dict):
                continue
            line = number(row.get("point"))
            price = acceptable(row.get("price"))
            if text(row.get("side")) == "over" and math.isfinite(point) and math.isfinite(line) and abs(line-point) < .001 and price is not None:
                return price
    return None

def display_label(signal, match):
    side = re.sub(r"\s+", " ", text(signal.get("side")))
    upper = side.upper()
    if not side or "ИТМ" in upper or upper.startswith("ТМ ") or "UNDER" in upper or upper == "НЕ X2":
        return None
    if upper in ("1X", "X2", "ОЗ ДА"):
        return side
    if upper in ("ТБ 1.5", "ТБ 2.5", "ТБ 3.5"):
        return side
    if upper.startswith("ИТБ "):
        found = re.search(r"ИТБ\s+([0-9]+(?:\.[0-9]+)?)", side, re.I)
        point = number(found.group(1)) if found else float("nan")
        team = re.sub(r"\s+", " ", side.split("·", 1)[1].strip()) if "·" in side else ""
        if point in (.5, 1.5, 2.5, 3.5) and team.lower() in (text(match.get("home")).lower(), text(match.get("away")).lower()):
            return side
    return None

def strongest_display_signal(prediction, match):
    """Mirror Android strongestDisplaySignal including UEFA probability fallbacks."""
    candidates = []
    strongest = prediction.get("strongestSignal")
    if isinstance(strongest, dict):
        candidates.append(strongest)
    signals = prediction.get("signals")
    if isinstance(signals, list):
        for candidate in signals:
            if not isinstance(candidate, dict):
                continue
            key = norm_side(candidate.get("side"))
            if not any(norm_side(item.get("side")) == key for item in candidates):
                candidates.append(candidate)

    for field, template in RAW_MARKETS:
        probability = number(prediction.get(field))
        if not math.isfinite(probability) or not 0 <= probability <= 1:
            continue
        side = template.format(home=text(match.get("home")), away=text(match.get("away")))
        if not any(norm_side(item.get("side")) == norm_side(side) for item in candidates):
            candidates.append({"side": side, "modelProbability": probability})

    # Android falls back to paperSignals only when no full-model candidate exists.
    if not candidates and isinstance(match.get("paperSignals"), list):
        candidates.extend(legacy_paper_signals(match))

    available = []
    for candidate in candidates:
        if display_label(candidate, match) is None:
            continue
        probability = number(candidate.get("modelProbability"))
        odds = odds_for(candidate, match)
        if not math.isfinite(probability) or not 0 <= probability <= 1 or odds is None:
            continue
        ev = probability * odds - 1.0
        kelly = ev / (odds - 1.0) if odds > 1 else float("-inf")
        available.append((candidate, probability, odds, ev, kelly))
    positive = [item for item in available if item[3] > 0]
    if positive:
        return max(positive, key=lambda item: item[4])
    return max(available, key=lambda item: item[1]) if available else None

def legacy_paper_signals(match):
    # Mirrors the Android legacy UEFA path; only used if no model signal objects exist.
    result = []
    for row in match.get("paperSignals", []):
        if not isinstance(row, dict):
            continue
        market = text(row.get("market"))
        outcome = text(row.get("outcome"))
        if market == "double_chance":
            side = {"1x": "1X", "x2": "X2"}.get(outcome, "")
        elif market == "match_total_over":
            side = "ТБ " + str(number(row.get("point")))
        elif market == "team_total_over":
            team = text(match.get("home")) if row.get("team") == "home" else text(match.get("away")) if row.get("team") == "away" else ""
            side = "ИТБ " + str(number(row.get("point"))) + " · " + team
        elif market == "btts" and outcome == "yes":
            side = "ОЗ ДА"
        else:
            continue
        probability = number(row.get("model_probability"))
        archived_price = number(row.get("price"))
        edge = number(row.get("edge"))
        signal = {"side": side, "modelProbability": probability, "edgePp": edge * 100, "paperOnly": True}
        price = odds_for(signal, match)
        ev = probability * price - 1 if price is not None and math.isfinite(probability) else float("-inf")
        if (price is not None and math.isfinite(archived_price) and abs(price-archived_price) < .005
                and probability >= MIN_PROBABILITY and edge >= .05 and ev >= MIN_EV):
            result.append(signal)
    if not result:
        return []
    # Android uefaPaperSignal selects its legacy paper candidate by EV before Line ranking.
    return [max(result, key=lambda signal: number(signal.get("modelProbability")) * (odds_for(signal, match) or 0.0) - 1.0)]

def ai_eligibility(signal, match, odds, probability, ev):
    """Exact collectAiCandidates gates; returns a reason or the admitted row."""
    side = text(signal.get("side"))
    if probability < MIN_PROBABILITY:
        return None, "probability_below_60pct"
    if signal.get("noPositiveSignal") is True:
        return None, "no_positive_signal"
    if side.lower() == "оз нет":
        return None, "excluded_btts_no"
    if not math.isfinite(ev) or ev < MIN_EV:
        return None, "ev_below_5pct"
    if signal.get("paperOnly") is True:
        edge_pp = number(signal.get("edgePp"))
    else:
        edge_pp = (probability - 1.0 / odds) * 100.0 + number(signal.get("formAdjustmentPp"), 0.0)
    if not math.isfinite(edge_pp) or edge_pp < MIN_EDGE_PP:
        return None, "edge_below_5pp"
    return {
        "side": side, "odds": round(odds, 3), "probability": round(probability, 4),
        "edge_pp": round(edge_pp, 3), "ev_pct": round(ev * 100, 2),
        "kelly_fraction": round(ev / (odds - 1.0), 6),
        "paper_only": signal.get("paperOnly") is True,
    }, None

def match_day(match):
    raw = text(match.get("utcDate"))[:10] or text(match.get("dateEvent"))[:10]
    try:
        return date.fromisoformat(raw)
    except ValueError:
        stamp = number(match.get("startTimestamp"), 0)
        if stamp > 0:
            if stamp < 10_000_000_000:
                stamp *= 1000
            return datetime.fromtimestamp(stamp / 1000, tz=timezone.utc).date()
        return None

def build(data, today=None):
    today = today or datetime.now(timezone.utc).date()
    out = {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "as_of_utc_date": today.isoformat(),
        "android_rules": {"min_odds": MIN_ODDS, "min_probability": MIN_PROBABILITY,
                          "min_ev_pct": MIN_EV * 100, "min_edge_pp": MIN_EDGE_PP,
                          "line_selection": "max full-Kelly among positive-EV exact MelBet prices; otherwise max probability",
                          "ai_candidate": "only the single Line leader; no second-best fallback"},
        "leagues": {},
    }
    comps = data.get("competitions", [])
    for code, (feed_code, name) in LEAGUES.items():
        comp = next((item for item in comps if isinstance(item, dict) and text(item.get("code")).upper() == feed_code), {})
        counts, qualifying, candidate_rows = Counter(), [], []
        for match in comp.get("matches", []) if isinstance(comp, dict) else []:
            if not isinstance(match, dict) or text(match.get("status")) not in ("SCHEDULED", "TIMED"):
                continue
            day = match_day(match)
            if day is None or day < today:
                continue
            counts["scheduled_future"] += 1
            prediction = match.get("prediction")
            if not isinstance(prediction, dict):
                counts["without_prediction"] += 1
                continue
            counts["with_prediction"] += 1
            if not is_melbet(match.get("odds")):
                counts["without_melbet_board"] += 1
                continue
            counts["with_melbet_board"] += 1
            leader = strongest_display_signal(prediction, match)
            if leader is None:
                counts["without_line_leader"] += 1
                continue
            signal, probability, odds, ev, kelly = leader
            counts["with_line_leader"] += 1
            result, reason = ai_eligibility(signal, match, odds, probability, ev)
            row = {"date": day.isoformat(), "home": text(match.get("home")), "away": text(match.get("away")),
                   "line_leader": text(signal.get("side")), "line_odds": round(odds, 3),
                   "line_probability": round(probability, 4), "line_ev_pct": round(ev * 100, 2),
                   "line_kelly_fraction": round(kelly, 6)}
            candidate_rows.append(row)
            if result:
                counts["qualifying_matches"] += 1
                counts["qualifying_signals"] += 1
                qualifying.append({**row, "ai_signal": result})
            else:
                counts["rejected:" + reason] += 1
        out["leagues"][code] = {"name": name, "counts": dict(sorted(counts.items())),
                                "qualifying_matches": qualifying, "matches_with_line_leader": candidate_rows}
    total = Counter()
    for league in out["leagues"].values():
        total.update(league["counts"])
    out["totals"] = dict(sorted(total.items()))
    return out

def markdown(report):
    lines = ["# Аудит AI-сигналов: точное соответствие Android", "",
             f"- Дата среза UTC: **{report['as_of_utc_date']}**",
             "- Источник: текущий docs/data.json; будущие SCHEDULED/TIMED матчи национальных лиг.",
             "- Для каждого матча сначала выбирается один лидер Линии; AI проверяет только его.",
             "- Лидер: максимальный full-Kelly среди сигналов с положительным EV и точной ценой MelBet ≥ 1.50; если положительного EV нет — максимальная вероятность.",
             "- AI: P ≥ 60%, EV ≥ 5%, edge ≥ 5 п.п.; BTTS NO исключён.", "",
             "| Лига | Будущих матчей | С прогнозом | MelBet board | Лидер Line | Прошли AI |",
             "|---|---:|---:|---:|---:|---:|"]
    for code, league in report["leagues"].items():
        c = league["counts"]
        lines.append(f"| {code} | {c.get('scheduled_future',0)} | {c.get('with_prediction',0)} | {c.get('with_melbet_board',0)} | {c.get('with_line_leader',0)} | **{c.get('qualifying_matches',0)}** |")
    t = report["totals"]
    lines.append(f"| **Всего** | **{t.get('scheduled_future',0)}** | **{t.get('with_prediction',0)}** | **{t.get('with_melbet_board',0)}** | **{t.get('with_line_leader',0)}** | **{t.get('qualifying_matches',0)}** |")
    lines += ["", "## Матчи, прошедшие отбор", ""]
    found = False
    for code, league in report["leagues"].items():
        for row in league["qualifying_matches"]:
            found = True
            ai = row["ai_signal"]
            lines.append(f"- **{code} · {row['date']} · {row['home']} — {row['away']}**: {ai['side']} @ {ai['odds']:.2f}, P={ai['probability']:.1%}, EV={ai['ev_pct']:.1f}%, edge={ai['edge_pp']:.1f} п.п.")
    if not found:
        lines.append("В этом срезе матчей, прошедших все фильтры, нет.")
    lines += ["", "## Причины отказа", ""]
    for code, league in report["leagues"].items():
        reasons = {key.split(":", 1)[1]: value for key, value in league["counts"].items() if key.startswith("rejected:")}
        if reasons:
            lines.append("- **" + code + ":** " + "; ".join(f"{key.replace('_',' ')} — {value}" for key, value in sorted(reasons.items())))
    lines += ["", "## Ограничения", "",
              "- Это аудит текущего среза, не бэктест и не оценка доходности.",
              "- В одном матче учитывается ровно один Line-лидер; второй рынок не подставляется, если лидер не проходит AI.",
              "- Скрипт только читает feed и не меняет прогнозы, коэффициенты, пороги или ставки.", ""]
    return "\n".join(lines)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="docs/data.json")
    parser.add_argument("--json-out", default="ai-domestic-audit.json")
    parser.add_argument("--markdown-out", default="ai-domestic-audit.md")
    args = parser.parse_args()
    report = build(json.loads(Path(args.input).read_text(encoding="utf-8")))
    Path(args.json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rendered = markdown(report)
    Path(args.markdown_out).write_text(rendered, encoding="utf-8")
    print(rendered)

if __name__ == "__main__":
    main()
