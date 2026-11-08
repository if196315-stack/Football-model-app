import json
import os
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "data.json"
COMPETITIONS = {
    "PL": ("Англия · Premier League", "eng"),
    "PD": ("Испания · La Liga", "esp"),
    "SA": ("Италия · Serie A", "ita"),
    "BL1": ("Германия · Bundesliga", "ger"),
    "FL1": ("Франция · Ligue 1", "fra"),
}

def main():
    token = os.getenv("FOOTBALL_DATA_TOKEN", "").strip()
    now = datetime.now(timezone.utc).isoformat()
    payload = {
        "updatedAt": now,
        "source": "Football-Data.org (fixtures/results); Openplay (xG source)",
        "modelVersion": "Openplay v1.3 · frozen research baseline",
        "modelConfig": {"window": 30, "halfLife": 12, "shrinkage": 3},
        "openplayDataThrough": "2026-05-24",
        "status": "waiting_for_openplay_2026_27",
        "notice": "В опубликованном снимке Openplay пока нет xG матчей сезона 2026/27. Поэтому свежие прогнозы не рассчитываются.",
        "competitions": [],
    }
    if not token:
        payload["status"] = "missing_api_token"
        payload["notice"] = "Добавьте FOOTBALL_DATA_TOKEN в GitHub Secrets, затем запустите workflow."
    else:
        headers = {"X-Auth-Token": token}
        for code, (label, league) in COMPETITIONS.items():
            item = {"code": code, "league": league, "name": label, "matches": [], "error": None}
            try:
                response = requests.get(
                    "https://api.football-data.org/v4/competitions/" + code + "/matches",
                    headers=headers, timeout=30,
                )
                response.raise_for_status()
                body = response.json()
                for match in body.get("matches", []):
                    item["matches"].append({
                        "id": match.get("id"),
                        "utcDate": match.get("utcDate"),
                        "status": match.get("status"),
                        "home": (match.get("homeTeam") or {}).get("name", "TBD"),
                        "away": (match.get("awayTeam") or {}).get("name", "TBD"),
                        "homeScore": ((match.get("score") or {}).get("fullTime") or {}).get("home"),
                        "awayScore": ((match.get("score") or {}).get("fullTime") or {}).get("away"),
                    })
            except Exception as exc:
                item["error"] = str(exc)[:240]
            payload["competitions"].append(item)
        payload["status"] = "fixtures_only"
        payload["notice"] = (
            "Расписание и результаты обновлены. Прогнозы не публикуются: "
            "сначала требуется подтвердить актуальный xG-снимок Openplay и сопоставление команд."
        )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
