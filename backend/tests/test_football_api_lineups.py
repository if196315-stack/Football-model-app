"""Tests for API-Football lineup lookup, matching, caching and cooldown."""
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import football_api_lineups as source


def api_fixture(fixture_id=987):
    return {
        "fixture": {"id": fixture_id, "date": "2026-11-11T12:00:00+00:00"},
        "teams": {"home": {"name": "Home FC"}, "away": {"name": "Away FC"}},
    }


def api_lineups():
    sides = []
    for team_id, name in ((1, "Home FC"), (2, "Away FC")):
        sides.append({
            "team": {"id": team_id, "name": name},
            "formation": "4-3-3",
            "startXI": [{"player": {"id": i, "name": f"{name} Player {i}", "number": i,
                                     "pos": "M", "grid": "2:1"}} for i in range(1, 12)],
            "substitutes": [{"player": {"id": 20, "name": f"{name} Substitute", "number": 20}}],
            "coach": {"name": f"{name} Coach"},
        })
    return sides


def test_normalize_api_football_lineups():
    result = source.normalize_lineups(api_lineups())
    assert result["source"] == "API-Football"
    assert result["home"]["confirmed"] is True
    assert len(result["home"]["starters"]) == 11
    assert result["home"]["starters"][0]["name"] == "Home FC Player 1"
    assert result["away"]["formation"] == "4-3-3"
    reversed_result = source.normalize_lineups(list(reversed(api_lineups())), "Home FC", "Away FC")
    assert reversed_result["home"]["starters"][0]["name"] == "Home FC Player 1"
    assert reversed_result["away"]["starters"][0]["name"] == "Away FC Player 1"
    assert source.normalize_lineups(api_lineups()[:1]) is None


def test_fixture_matching_requires_both_teams_and_close_kickoff():
    match = {"home": "Home FC", "away": "Away FC", "timeUtc": "2026-11-11T12:00:00Z"}
    assert source._fixture_match(match, [api_fixture()])["fixture"]["id"] == 987
    wrong = api_fixture()
    wrong["teams"]["away"]["name"] = "Other FC"
    assert source._fixture_match(match, [wrong]) is None


def test_refresh_maps_fixture_and_fetches_official_lineup(monkeypatch):
    now = datetime(2026, 11, 11, 11, 30, tzinfo=timezone.utc)
    cache = {"matches": {"pitch-1": {
        "status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
        "home": "Home FC", "away": "Away FC",
    }}}
    calls = []

    def fake_get(session, path, api_key, params=None):
        calls.append((path, params))
        return [api_fixture()] if path == "/fixtures" else api_lineups()

    monkeypatch.setattr(source, "_api_get", fake_get)
    result = source.refresh_lineups(cache, "secret", session=object(), now=now, pause_seconds=0)
    row = cache["matches"]["pitch-1"]
    assert row["footballApiFixtureId"] == 987
    assert row["lineups"]["source"] == "API-Football"
    assert result["fixtureDateLookups"] == 1
    assert result["lineupsFetched"] == 1
    assert calls == [("/fixtures", {"date": "2026-11-11", "timezone": "UTC"}),
                     ("/fixtures/lineups", {"fixture": 987})]


def test_fixture_date_cache_and_lineup_retry_cooldown(monkeypatch):
    now = datetime(2026, 11, 11, 11, 30, tzinfo=timezone.utc)
    cache = {
        "footballApiFixturesByDate": {
            "2026-11-11": {"fetchedAt": now.isoformat(), "fixtures": [api_fixture()]}
        },
        "matches": {"pitch-1": {
            "status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
            "home": "Home FC", "away": "Away FC", "footballApiFixtureId": 987,
            "lineupsAttemptedAt": "2026-11-11T11:20:00Z",
        }},
    }
    monkeypatch.setattr(source, "_api_get", lambda *args, **kwargs: (_ for _ in ()).throw(
        AssertionError("cooldown should prevent request")
    ))
    result = source.refresh_lineups(cache, "secret", session=object(), now=now, pause_seconds=0)
    assert result["fixtureDateLookups"] == 0
    assert result["lineupAttempts"] == 0


def test_does_not_query_lineups_too_early(monkeypatch):
    now = datetime(2026, 11, 11, 10, 0, tzinfo=timezone.utc)
    cache = {"footballApiFixturesByDate": {
        "2026-11-11": {"fetchedAt": now.isoformat(), "fixtures": [api_fixture()]}
    }, "matches": {"pitch-1": {
        "status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
        "home": "Home FC", "away": "Away FC",
    }}}
    calls = []
    monkeypatch.setattr(source, "_api_get", lambda *args, **kwargs: calls.append(args[1]) or [])
    result = source.refresh_lineups(cache, "secret", session=object(), now=now, pause_seconds=0)
    assert calls == []
    assert result["lineupAttempts"] == 0



def test_daily_api_football_budget_counts_all_endpoint_requests(monkeypatch):
    now = datetime(2026, 11, 11, 11, 30, tzinfo=timezone.utc)
    cache = {
        "footballApiDailyUsage": {"date": "2026-11-11", "requests": 100},
        "matches": {"pitch-1": {
            "status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
            "home": "Home FC", "away": "Away FC", "footballApiFixtureId": 987,
        }},
    }
    monkeypatch.setattr(source, "_api_get", lambda *args, **kwargs: (_ for _ in ()).throw(
        AssertionError("daily API request budget must prevent another request")
    ))
    result = source.refresh_lineups(
        cache, "secret", session=object(), now=now, pause_seconds=0,
        eligible_match_ids={"pitch-1"}, max_lineup_requests=None,
        max_api_requests=100, daily_request_limit=100,
    )
    assert result["apiFootballRequestsThisRun"] == 0
    assert result["apiFootballRequestsToday"] == 100
    assert result["lineupsFetched"] == 0
