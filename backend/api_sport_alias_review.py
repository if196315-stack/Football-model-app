"""Audit-only, reviewed API-Sport alias candidates.

This module is intentionally not imported by production ingestion. It provides
exact, competition-scoped identity checks for diagnostics and regression tests;
it does not perform fuzzy matching or fixture selection.
"""

ALIASES = {
    ("Roma", 23): ("AS Roma", "2702"),
    ("Paderborn", 35): ("SC Paderborn 07", "2561"),
    ("Elversberg", 35): ("SV 07 Elversberg", "2598"),
    ("Augsburg", 35): ("FC Augsburg", "2600"),
    ("Union Berlin", 35): ("1. FC Union Berlin", "2547"),
    ("Mainz 05", 35): ("1. FSV Mainz 05", "2556"),
    ("Borussia Mönchengladbach", 35): ("Borussia M'gladbach", "2527"),
    ("Freiburg", 35): ("SC Freiburg", "2538"),
    ("Hoffenheim", 35): ("TSG Hoffenheim", "2569"),
    ("Bayern München", 7): ("FC Bayern München", "2672"),
    ("Schalke 04", 35): ("FC Schalke 04", "2530"),
    ("Werder Bremen", 35): ("SV Werder Bremen", "2534"),
    ("Lens", 34): ("RC Lens", "1648"),
    ("Monaco", 34): ("AS Monaco", "1653"),
    ("Salzburg", 679): ("Red Bull Salzburg", "2046"),
    ("Ararat Armenia", 679): ("FC Ararat-Armenia", "262229"),
}


def resolve_reviewed_team_id(pitchapi_name, tournament_id, observed_name, observed_team_id):
    """Return the reviewed ID only on exact alias, tournament and ID agreement."""
    candidate = ALIASES.get((pitchapi_name, int(tournament_id)))
    if candidate is None:
        return None
    expected_name, expected_id = candidate
    if observed_name != expected_name or str(observed_team_id) != expected_id:
        return None
    return expected_id
