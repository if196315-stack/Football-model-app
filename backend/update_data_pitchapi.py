"""PitchAPI-backed replacement updater for docs/data.json.

Kept separate from the legacy production updater until explicitly activated.
The Odds API remains a separate provider and existing quotes are carried
forward by stable fixture identity (competition, teams, kickoff), not provider ID.
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import datetime, timezone

import requests
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from pitchapi_data_source import CACHE_PATH, model_history_rows, refresh_history
from update_odds import BASE_URL as ODDS_BASE_URL, LEAGUES as ODDS_LEAGUES, convert_event as convert_odds_event, similarity as odds_similarity
from update_data import (
    COMPS, MAP, WINDOW, HALF_LIFE, SHRINKAGE, _similarity, key,
    match_name, strengths, predict, update_prediction_archive,
)

OUT = ROOT / "docs" / "data.json"
# Cohorts with known fixture/season integrity defects are not model training data.
EXCLUDED_SEASONS = {
    ("ESP", "2023/24"), ("ESP", "2023/2024"),
    ("GER", "2024/25"), ("GER", "2024/2025"),
    ("ITA", "2022/23"), ("ITA", "2022/2023"),
}
EXCLUDED_MATCH_IDS = {"m_3ir3kE"}  # Spezia–Hellas Verona relegation playoff


def comp_for_code(code: str) -> str:
    return {"ENG": "eng", "ESP": "esp", "ITA": "ita", "GER": "ger", "FRA": "fra"}[code]


def build_model_frames(cache: dict):
    history = []
    results = []
    for row in model_history_rows(cache):
        if row.get("id") in EXCLUDED_MATCH_IDS:
            continue
        if (row.get("leagueCode"), row.get("season")) in EXCLUDED_SEASONS:
            continue
        try:
            date = pd.to_datetime(row.get("date"), utc=True).tz_convert(None)
            home_xg, away_xg = float(row["homeXg"]), float(row["awayXg"])
            home_score, away_score = int(row["homeScore"]), int(row["awayScore"])
        except (TypeError, ValueError, OverflowError):
            continue
        code = comp_for_code(row["leagueCode"])
        common = {"comp": code, "date": date, "match_id": row["id"]}
        history.append({**common, "team": row["home"], "side": "home",
                        "xg": home_xg, "xg_against": away_xg})
        history.append({**common, "team": row["away"], "side": "away",
                        "xg": away_xg, "xg_against": home_xg})
        results.append({**common, "home_score": home_score, "away_score": away_score})
    s = pd.DataFrame(history, columns=["comp", "date", "match_id", "team", "side", "xg", "xg_against"])
    matches = pd.DataFrame(results, columns=["comp", "date", "match_id", "home_score", "away_score"])
    if s.empty:
        base = {(c, side): None for c in MAP for side in ("home", "away")}
        return s, base, matches
    base = {}
    for code in MAP:
        for side in ("home", "away"):
            values = s.loc[(s.comp == code) & (s.side == side), "xg"]
            base[code, side] = float(values.mean()) if len(values) else None
    return s.sort_values("date"), base, matches.sort_values("date")


def fixture_identity(match: dict, competition: str) -> tuple | None:
    kickoff = str(match.get("utcDate") or "")
    if not kickoff:
        return None
    return (competition, key(match.get("home")), key(match.get("away")), kickoff[:16])


def preserve_odds_by_identity(payload: dict) -> int:
    # The preview checks out its feature branch, whose docs/data.json does not
    # receive the production Odds API refreshes. Prefer an explicitly supplied
    # read-only snapshot from main; fall back to the local output for tests/manual runs.
    previous_path = Path(os.environ.get("PITCHAPI_PREVIOUS_DATA_PATH", str(OUT)))
    if not previous_path.exists():
        return 0
    try:
        previous = json.loads(previous_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return 0
    old = {}
    candidates = {}
    for comp in previous.get("competitions", []):
        code = comp.get("code")
        candidates.setdefault(code, [])
        for match in comp.get("matches", []):
            quote = match.get("odds")
            if quote and match.get("id") is not None:
                old[str(match["id"])] = quote
                identity = fixture_identity(match, code)
                if identity:
                    old[identity] = quote
                candidates[code].append(match)

    retained = 0
    eligible_by_league = {}
    retained_by_league = {}
    for comp in payload.get("competitions", []):
        code = comp.get("code")
        for match in comp.get("matches", []):
            if match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                eligible_by_league[code] = eligible_by_league.get(code, 0) + 1
            quote = old.get(str(match.get("id")))
            if quote is None:
                identity = fixture_identity(match, code)
                quote = old.get(identity) if identity else None
            if quote is None and match.get("utcDate"):
                # Provider fixture IDs and some club spellings change across feeds.
                # Fall back only to a unique high-confidence team/time match.
                current_date = pd.to_datetime(match["utcDate"], utc=True, errors="coerce")
                ranked = []
                if not pd.isna(current_date):
                    for previous_match in candidates.get(code, []):
                        previous_date = pd.to_datetime(previous_match.get("utcDate"), utc=True, errors="coerce")
                        if pd.isna(previous_date):
                            continue
                        # PitchAPI fixture dates may be date-only. In that case
                        # compare calendar dates instead of assuming midnight kickoff.
                        if "T" not in str(match.get("utcDate") or ""):
                            if previous_date.date() != current_date.date():
                                continue
                        elif abs((previous_date-current_date).total_seconds()) > 6*3600:
                            continue
                        home_score = _similarity(key(match.get("home")), key(previous_match.get("home")))
                        away_score = _similarity(key(match.get("away")), key(previous_match.get("away")))
                        if home_score >= 0.88 and away_score >= 0.88:
                            ranked.append((home_score + away_score, previous_match.get("odds")))
                ranked.sort(key=lambda item: item[0], reverse=True)
                if ranked and (len(ranked) == 1 or ranked[0][0] - ranked[1][0] >= 0.10):
                    quote = ranked[0][1]
            if quote and match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                match["odds"] = quote
                retained += 1
                retained_by_league[code] = retained_by_league.get(code, 0) + 1
    for field in ("oddsUpdatedAt", "oddsCreditsRemaining", "oddsCreditsUsed",
                  "oddsErrors"):
        if field in previous:
            payload[field] = previous[field]
    eligible = sum(eligible_by_league.values())
    payload["oddsCoverage"] = {
        "eligible": eligible, "matched": retained, "updated": 0,
        "retained": retained, "notMatched": max(0, eligible - retained),
        "byLeague": {
            code: {
                "eligible": count, "matched": retained_by_league.get(code, 0),
                "updated": 0, "retained": retained_by_league.get(code, 0),
                "notMatched": max(0, count - retained_by_league.get(code, 0)),
            }
            for code, count in eligible_by_league.items()
        },
    }
    payload["oddsStatus"] = "ready" if retained else "no_quotes"
    payload["oddsNotice"] = (
        f"Перенесены прежние котировки The Odds API для {retained} из {eligible} предстоящих матчей; "
        "новые коэффициенты обновляются отдельным заданием."
        if retained else
        "Котировки из предыдущего снимка не сопоставлены; обновление The Odds API выполняется отдельным заданием."
    )
    return retained




def find_odds_event(match: dict, events: list[dict], league: str) -> dict | None:
    """Match The Odds API events to PitchAPI fixtures, including date-only kickoffs."""
    raw_date = str(match.get("utcDate") or "")
    if not raw_date:
        return None
    try:
        target = datetime.fromisoformat(raw_date.replace("Z", "+00:00"))
        if target.tzinfo is None:
            target = target.replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    date_only = "T" not in raw_date
    ranked = []
    for event in events:
        try:
            event_time = datetime.fromisoformat(str(event.get("commence_time") or "").replace("Z", "+00:00"))
            if event_time.tzinfo is None:
                event_time = event_time.replace(tzinfo=timezone.utc)
        except ValueError:
            continue
        if date_only:
            if event_time.date() != target.date():
                continue
        elif abs((event_time - target).total_seconds()) > 18 * 3600:
            continue
        home_score = odds_similarity(match.get("home"), event.get("home_team"), league)
        away_score = odds_similarity(match.get("away"), event.get("away_team"), league)
        if home_score >= 0.86 and away_score >= 0.86:
            ranked.append((home_score + away_score, event))
    ranked.sort(key=lambda item: item[0], reverse=True)
    if not ranked:
        return None
    if len(ranked) > 1 and ranked[0][0] - ranked[1][0] < 0.10:
        return None
    return ranked[0][1]


def refresh_odds_from_api(payload: dict, api_key: str) -> int:
    """Refresh bookmaker odds from The Odds API without changing the fixture source."""
    if not api_key:
        return 0
    competitions = {c.get("code"): c for c in payload.get("competitions", [])}
    updated = retained = matched = eligible = 0
    quota_remaining = quota_used = None
    errors = []
    coverage = {}
    for code, sport in ODDS_LEAGUES.items():
        competition = competitions.get(code)
        if not competition:
            continue
        league_eligible = league_matched = league_updated = league_retained = 0
        try:
            response = requests.get(
                f"{ODDS_BASE_URL}/{sport}/odds",
                params={"apiKey": api_key, "regions": "eu", "markets": "h2h,totals",
                        "oddsFormat": "decimal", "dateFormat": "iso"},
                timeout=35,
            )
            quota_remaining = response.headers.get("x-requests-remaining", quota_remaining)
            quota_used = response.headers.get("x-requests-used", quota_used)
            if response.status_code == 429:
                raise RuntimeError("The Odds API rate limit (429)")
            response.raise_for_status()
            events = response.json()
            for match in competition.get("matches", []):
                if match.get("status") not in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED"):
                    continue
                eligible += 1
                league_eligible += 1
                event = find_odds_event(match, events, code)
                if event:
                    matched += 1
                    league_matched += 1
                    quote = convert_odds_event(event)
                    if quote.get("best"):
                        match["odds"] = quote
                        updated += 1
                        league_updated += 1
                    elif match.get("odds"):
                        retained += 1
                        league_retained += 1
                elif match.get("odds"):
                    retained += 1
                    league_retained += 1
        except Exception as exc:
            errors.append(f"{code}: {str(exc)[:180]}")
            for match in competition.get("matches", []):
                if match.get("status") in ("SCHEDULED", "TIMED", "IN_PLAY", "PAUSED") and match.get("odds"):
                    retained += 1
                    league_retained += 1
        coverage[code] = {
            "eligible": league_eligible, "matched": league_matched,
            "updated": league_updated, "retained": league_retained,
            "notMatched": max(0, league_eligible - league_matched),
        }
        time.sleep(1)
    payload["oddsUpdatedAt"] = datetime.now(timezone.utc).isoformat()
    payload["oddsCoverage"] = {
        "eligible": eligible, "matched": matched, "updated": updated,
        "retained": retained, "notMatched": max(0, eligible - matched),
        "byLeague": coverage,
    }
    payload["oddsCreditsRemaining"] = int(quota_remaining) if str(quota_remaining or "").isdigit() else None
    payload["oddsCreditsUsed"] = int(quota_used) if str(quota_used or "").isdigit() else None
    payload["oddsStatus"] = "ready" if (updated or retained) else ("partial_error" if errors else "no_quotes")
    payload["oddsNotice"] = (
        f"Новые котировки The Odds API: {updated}; сохранены прежние: {retained}; без совпадения: {max(0, eligible - matched)}."
        if (updated or retained) else "The Odds API не вернул подходящих котировок для предстоящих матчей."
    )
    if errors:
        payload["oddsErrors"] = errors
    else:
        payload.pop("oddsErrors", None)
    return updated



def app_status(raw: str, match_date: str | None = None,
               today=None) -> str:
    value = str(raw or "").casefold()
    if value == "not_started" and match_date:
        kickoff = pd.to_datetime(match_date, utc=True, errors="coerce")
        current_day = today or datetime.now(timezone.utc).date()
        if not pd.isna(kickoff) and kickoff.date() < current_day:
            return "POSTPONED"
    return {
        "finished": "FINISHED", "scheduled": "SCHEDULED",
        "timed": "TIMED", "not_started": "SCHEDULED",
        "in_play": "IN_PLAY", "in play": "IN_PLAY",
        "paused": "PAUSED", "postponed": "POSTPONED",
        "canceled": "CANCELED", "cancelled": "CANCELED",
        "awarded": "AWARDED",
    }.get(value, value.upper())



def run() -> dict:
    api_key = os.environ.get("PITCHAPI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError("PITCHAPI_API_KEY is not configured")
    shot_limit = int(os.environ.get("PITCHAPI_MAX_SHOT_REQUESTS", "5000"))
    refreshed = refresh_history(api_key, max_shot_requests=shot_limit)
    cache = refreshed["cache"]
    s, base, results = build_model_frames(cache)
    st = strengths(s, base) if not s.empty else {}
    names = {code: sorted(s.loc[s.comp == code, "team"].dropna().unique().tolist())
             for code in MAP} if not s.empty else {}

    payload = {
        "updatedAt": datetime.now(timezone.utc).isoformat(),
        "source": "PitchAPI (fixtures, results and shot-level xG); The Odds API (bookmaker odds)",
        "modelVersion": "PitchAPI shot xG · incremental migration",
        "modelConfig": {"window": WINDOW, "halfLife": HALF_LIFE, "shrinkage": SHRINKAGE},
        "xgProvider": "PitchAPI",
        "xgHistoryMatches": int(len(results)),
        "xgHistoryPairedMatches": int(len(s) // 2),
        "xgHistoryPending": refreshed["summary"].get("pendingShotXg", 0),
        "xgUpdatedAt": cache.get("lastRefresh"),
        "status": "initializing",
        "notice": "Расписание и результаты загружены из PitchAPI; история xG заполняется постепенно.",
        "dataWarning": "Прогнозы строятся только на завершённых матчах с парным xG PitchAPI. Отсутствующий xG не заменяется нулём.",
        "competitions": [],
    }

    labels = {code: (label, league) for code, (label, league) in COMPS.items()}
    for provider_code in ("ENG", "ESP", "ITA", "GER", "FRA"):
        label, league = labels[{"ENG":"PL","ESP":"PD","ITA":"SA","GER":"BL1","FRA":"FL1"}[provider_code]]
        item = {"code": {"ENG":"PL","ESP":"PD","ITA":"SA","GER":"BL1","FRA":"FL1"}[provider_code],
                "league": league, "name": label, "matches": [], "error": None}
        fixtures = [row for row in cache.get("matches", {}).values()
                    if row.get("leagueCode") == provider_code]
        for row in sorted(fixtures, key=lambda x: str(x.get("date") or "")):
            date = str(row.get("date") or "")
            status = app_status(row.get("status"), date)
            home, away = row.get("home") or "TBD", row.get("away") or "TBD"
            pred, warning = None, None
            if status in ("SCHEDULED", "TIMED"):
                hn = match_name(home, names.get(league, []), league)
                an = match_name(away, names.get(league, []), league)
                if not hn or not an:
                    warning = "Не удалось надёжно сопоставить команды с историей PitchAPI"
                elif not st.get((league, hn)) or not st.get((league, an)):
                    warning = "Недостаточно парной истории xG PitchAPI"
                elif min(st[(league, hn)]["matches"], st[(league, an)]["matches"]) < 5:
                    warning = "Для прогноза требуется не менее 5 матчей истории каждой команды"
                else:
                    pred, warning = predict(league, home, away, st, base, names, results, date[:10])
            item["matches"].append({
                "id": row["id"], "utcDate": date, "status": status,
                "home": home, "away": away,
                "homeScore": row.get("homeScore"), "awayScore": row.get("awayScore"),
                "prediction": pred, "predictionWarning": warning,
            })
        item["matches"].sort(key=lambda x: x.get("utcDate") or "")
        payload["competitions"].append(item)

    upcoming = [m for c in payload["competitions"] for m in c["matches"]
                if m.get("status") in ("SCHEDULED", "TIMED") and m.get("prediction")]
    payload["status"] = "ready" if upcoming else "no_predictions"
    payload["notice"] = (
        f"Прогнозы доступны для {len(upcoming)} предстоящих матчей; "
        f"в истории {len(results)} матчей с парным xG, ожидают xG: {payload['xgHistoryPending']}."
    )
    update_prediction_archive(payload)
    retained = preserve_odds_by_identity(payload)
    odds_updated = 0
    if os.environ.get("PITCHAPI_REFRESH_ODDS_API", "").strip().lower() in ("1", "true", "yes"):
        odds_updated = refresh_odds_from_api(payload, os.environ.get("ODDS_API_KEY", "").strip())
    if odds_updated:
        payload["oddsNotice"] = f"Обновлены котировки The Odds API для {odds_updated} матчей."
    elif retained:
        payload["oddsNotice"] = f"Сохранены ранее полученные котировки для {retained} матчей; новые коэффициенты обновляются отдельно через The Odds API."
    OUT.parent.mkdir(parents=True, exist_ok=True)
    tmp = OUT.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(OUT)
    return {"competitions": len(payload["competitions"]), "matches": sum(len(c["matches"]) for c in payload["competitions"]),
            "pairedHistory": len(results), "predictions": len(upcoming),
            "pendingXg": payload["xgHistoryPending"], "retainedOdds": retained, "updatedOdds": odds_updated}


if __name__ == "__main__":
    print(json.dumps(run(), ensure_ascii=False))
