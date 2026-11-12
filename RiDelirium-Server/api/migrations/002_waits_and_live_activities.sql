-- Run once on an existing database (new installs get these from schema.sql).
USE RideTogether;

-- "Wait for me": one row per request, so the friend's phone can ask what is pending, answer it, and the
-- sender can learn whether it was answered. No positions are stored, only the distance the sender gave.
-- Rows are purged after a day.
CREATE TABLE waits (
    id          CHAR(16)     NOT NULL PRIMARY KEY,   -- random hex
    from_rider  CHAR(36)     NOT NULL,
    to_rider    CHAR(36)     NOT NULL,
    distance_m  INT          NULL,
    created_at  DATETIME     NOT NULL,               -- UTC
    accepted_at DATETIME     NULL,                   -- UTC; set when the friend taps "OK, waiting"
    KEY (to_rider, created_at),
    KEY (from_rider, created_at),
    FOREIGN KEY (from_rider) REFERENCES riders (id) ON DELETE CASCADE,
    FOREIGN KEY (to_rider) REFERENCES riders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Where to push updates for a rider's Live Activity (iOS lock screen / Dynamic Island). The token belongs to
-- one running activity and changes with every ride; it is removed when the ride ends.
CREATE TABLE live_activities (
    rider_id   CHAR(36)     NOT NULL PRIMARY KEY,
    token      VARCHAR(255) NOT NULL,
    sandbox    TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at DATETIME     NOT NULL,
    FOREIGN KEY (rider_id) REFERENCES riders (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
