-- Ruinborn v1: base schema (concept section 14). 29 tables.
-- IDs with AUTOINCREMENT: deleted IDs are never reused (important for events, marches and clients).
-- Timestamps: INTEGER ms UTC. Booleans: INTEGER 0/1. JSON: TEXT.
-- Deviation: resource amounts in player are REAL (see docs/DECISIONS.md, E-07).

CREATE TABLE schema_version (
    version INTEGER NOT NULL
);

CREATE TABLE server_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    pw_hash       TEXT    NOT NULL,
    role          TEXT    NOT NULL DEFAULT 'PLAYER',
    created_at    INTEGER NOT NULL,
    last_login_at INTEGER NOT NULL DEFAULT 0,
    banned_until  INTEGER NOT NULL DEFAULT 0,
    ban_reason    TEXT,
    muted_until   INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE session (
    token_hash TEXT PRIMARY KEY,
    account_id INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE player (
    id                   INTEGER PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    food                 REAL    NOT NULL,
    wood                 REAL    NOT NULL,
    steel                REAL    NOT NULL,
    res_at               INTEGER NOT NULL,
    protection_until     INTEGER NOT NULL DEFAULT 0,
    shield_until         INTEGER NOT NULL DEFAULT 0,
    catchup_until        INTEGER NOT NULL DEFAULT 0,
    last_active_at       INTEGER NOT NULL,
    max_zombie_level     INTEGER NOT NULL DEFAULT 0,
    zombies_defeated     INTEGER NOT NULL DEFAULT 0,
    troops_trained       INTEGER NOT NULL DEFAULT 0,
    skin                 TEXT    NOT NULL DEFAULT 'SKIN_DEFAULT',
    frame                TEXT    NOT NULL DEFAULT 'FRAME_DEFAULT',
    alliance_block_until INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE building (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    plot      TEXT    NOT NULL,
    type      TEXT    NOT NULL,
    level     INTEGER NOT NULL,
    PRIMARY KEY (player_id, plot)
);

CREATE TABLE timer (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id  INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    kind       TEXT    NOT NULL,
    target     TEXT    NOT NULL,
    payload    TEXT    NOT NULL,
    cost       TEXT    NOT NULL,
    started_at INTEGER NOT NULL,
    ends_at    INTEGER NOT NULL,
    total_ms   INTEGER NOT NULL,
    help_max   INTEGER NOT NULL DEFAULT 0,
    help_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE timer_help (
    timer_id  INTEGER NOT NULL REFERENCES timer (id) ON DELETE CASCADE,
    helper_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    PRIMARY KEY (timer_id, helper_id)
);

CREATE TABLE research (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    tech      TEXT    NOT NULL,
    level     INTEGER NOT NULL,
    PRIMARY KEY (player_id, tech)
);

CREATE TABLE troop (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    type      TEXT    NOT NULL,
    tier      INTEGER NOT NULL,
    home      INTEGER NOT NULL DEFAULT 0,
    wounded   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, type, tier)
);

CREATE TABLE hero (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    hero      TEXT    NOT NULL,
    level     INTEGER NOT NULL DEFAULT 1,
    xp        INTEGER NOT NULL DEFAULT 0,
    march_id  INTEGER,
    PRIMARY KEY (player_id, hero)
);

CREATE TABLE item (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    item      TEXT    NOT NULL,
    count     INTEGER NOT NULL,
    PRIMARY KEY (player_id, item)
);

CREATE TABLE map_object (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind        TEXT    NOT NULL,
    x           INTEGER NOT NULL,
    y           INTEGER NOT NULL,
    zone        INTEGER NOT NULL,
    level       INTEGER NOT NULL DEFAULT 0,
    res_type    TEXT,
    amount      INTEGER NOT NULL DEFAULT 0,
    player_id   INTEGER REFERENCES player (id) ON DELETE CASCADE,
    occupied_by INTEGER,
    UNIQUE (x, y)
);

CREATE TABLE march (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id    INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL,
    hero         TEXT,
    troops       TEXT    NOT NULL,
    from_x       INTEGER NOT NULL,
    from_y       INTEGER NOT NULL,
    to_x         INTEGER NOT NULL,
    to_y         INTEGER NOT NULL,
    target_id    INTEGER,
    state        TEXT    NOT NULL,
    depart_at    INTEGER NOT NULL,
    arrive_at    INTEGER NOT NULL,
    gather_start INTEGER NOT NULL DEFAULT 0,
    gather_rate  REAL    NOT NULL DEFAULT 0,
    cargo        TEXT    NOT NULL DEFAULT '{"food":0,"wood":0,"steel":0}',
    rally_id     INTEGER,
    host_id      INTEGER
);

CREATE TABLE rally (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    alliance_id INTEGER NOT NULL,
    leader_id   INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    target_id   INTEGER NOT NULL,
    launch_at   INTEGER NOT NULL,
    state       TEXT    NOT NULL
);

CREATE TABLE alliance (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    tag         TEXT    NOT NULL UNIQUE,
    leader_id   INTEGER NOT NULL,
    join_mode   TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL
);

CREATE TABLE alliance_member (
    player_id   INTEGER PRIMARY KEY REFERENCES player (id) ON DELETE CASCADE,
    alliance_id INTEGER NOT NULL REFERENCES alliance (id) ON DELETE CASCADE,
    rank        TEXT    NOT NULL,
    joined_at   INTEGER NOT NULL
);

CREATE TABLE alliance_request (
    alliance_id INTEGER NOT NULL REFERENCES alliance (id) ON DELETE CASCADE,
    player_id   INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    PRIMARY KEY (alliance_id, player_id)
);

CREATE TABLE alliance_gift (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id  INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    level      INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    claimed    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE chat_message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    channel    TEXT    NOT NULL,
    sender_id  INTEGER,
    text       TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    deleted    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE chat_report (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id  INTEGER NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    reporter_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    created_at  INTEGER NOT NULL,
    resolved    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE report (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    player_id  INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    kind       TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    read       INTEGER NOT NULL DEFAULT 0,
    payload    TEXT    NOT NULL
);

CREATE TABLE daily_progress (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    day       TEXT    NOT NULL,
    task      TEXT    NOT NULL,
    progress  INTEGER NOT NULL DEFAULT 0,
    claimed   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, day, task)
);

CREATE TABLE achievement (
    player_id    INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    achievement  TEXT    NOT NULL,
    completed_at INTEGER NOT NULL,
    claimed_at   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, achievement)
);

CREATE TABLE cosmetic (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    cosmetic  TEXT    NOT NULL,
    PRIMARY KEY (player_id, cosmetic)
);

CREATE TABLE defense_loss (
    player_id INTEGER NOT NULL REFERENCES player (id) ON DELETE CASCADE,
    at        INTEGER NOT NULL
);

CREATE TABLE scheduled_event (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    due_at  INTEGER NOT NULL,
    type    TEXT    NOT NULL,
    payload TEXT    NOT NULL DEFAULT '{}'
);

CREATE TABLE processed_request (
    player_id  INTEGER NOT NULL,
    request_id TEXT    NOT NULL,
    created_at INTEGER NOT NULL,
    status     INTEGER NOT NULL,
    response   TEXT    NOT NULL,
    PRIMARY KEY (player_id, request_id)
);

CREATE TABLE audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id   INTEGER,
    action     TEXT    NOT NULL,
    target     TEXT,
    details    TEXT,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_timer_player ON timer (player_id);
CREATE INDEX idx_march_player ON march (player_id);
CREATE INDEX idx_march_target ON march (target_id);
CREATE INDEX idx_map_object_kind ON map_object (kind);
CREATE INDEX idx_chat_channel ON chat_message (channel, id);
CREATE INDEX idx_report_player ON report (player_id, id);
CREATE INDEX idx_event_due ON scheduled_event (due_at);
