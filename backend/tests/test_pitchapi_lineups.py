"""PitchAPI lineup cache tests."""
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pitchapi_data_source as source


def sample_lineups(confirmed=False):
    return {
        "match_id": "m_123456",
        "home": {
            "formation": "4-3-3", "confirmed": confirmed,
            "lineup_type": None if confirmed else "lastStarting11",
            "coach": {"name": "Coach A"},
            "starters": [{"player_id": "p_123456", "name": "Player A",
                          "shirt_number": "9", "position_id": 4, "is_captain": True}],
            "subs": [{"player_id": "p_234567", "name": "Player B"}],
        },
        "away": {
            "formation": "4-2-3-1", "confirmed": confirmed,
            "starters": [], "subs": [],
        },
    }


def test_normalize_lineups_preserves_confirmation_and_players():
    result = source.normalize_lineups(sample_lineups(False))
    assert result["source"] == "PitchAPI"
    assert result["home"]["confirmed"] is False
    assert result["home"]["lineupType"] == "lastStarting11"
    assert result["home"]["starters"][0]["name"] == "Player A"
    assert result["home"]["starters"][0]["captain"] is True
    assert source.normalize_lineups({"home": {}}) is None


def test_refresh_lineups_only_fetches_near_term_unconfirmed_fixtures(monkeypatch):
    now = datetime(2026, 11, 10, 12, tzinfo=timezone.utc)
    cache = {"matches": {
        "near": {"status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z"},
        "far": {"status": "scheduled", "timeUtc": "2026-11-14T12:00:00Z"},
        "done": {"status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
                 "lineups": source.normalize_lineups(sample_lineups(True))},
    }}
    calls = []

    def fake_fetch(session, path, api_key, params=None):
        calls.append(path)
        return sample_lineups(False)

    monkeypatch.setattr(source, "fetch", fake_fetch)
    summary = source.refresh_upcoming_lineups(
        cache, "test-key", session=object(), now=now, pause_seconds=0
    )
    assert calls == ["/matches/near/lineups"]
    assert summary == {"lineupAttempts": 1, "lineupsFetched": 1, "lineupErrors": 0}
    assert cache["matches"]["near"]["lineups"]["home"]["confirmed"] is False
    assert "lineups" not in cache["matches"]["far"]


def test_refresh_lineups_respects_retry_cooldown(monkeypatch):
    now = datetime(2026, 11, 10, 12, tzinfo=timezone.utc)
    cache = {"matches": {
        "near": {"status": "scheduled", "timeUtc": "2026-11-11T12:00:00Z",
                 "lineupsAttemptedAt": "2026-11-10T09:00:00Z"}
    }}
    monkeypatch.setattr(source, "fetch", lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("must not fetch")))
    summary = source.refresh_upcoming_lineups(
        cache, "test-key", session=object(), now=now, pause_seconds=0
    )
    assert summary["lineupAttempts"] == 0
