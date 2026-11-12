-- Run once on an existing database (new installs get this from schema.sql).
USE RideTogether;

-- People a rider has been in a crew with, kept 30 days after they last rode together so "recent riders" can be
-- invited again. One row per pair; rider_a < rider_b. Holds no positions or ride data.
CREATE TABLE ridden_with (
    rider_a CHAR(36) NOT NULL,
    rider_b CHAR(36) NOT NULL,
    last_at DATETIME NOT NULL,                       -- UTC; when they were last in a crew together
    PRIMARY KEY (rider_a, rider_b),
    KEY (rider_b),
    FOREIGN KEY (rider_a) REFERENCES riders (id) ON DELETE CASCADE,
    FOREIGN KEY (rider_b) REFERENCES riders (id) ON DELETE CASCADE,
    CHECK (rider_a < rider_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
