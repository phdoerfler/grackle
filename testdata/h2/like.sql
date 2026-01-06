CREATE TABLE likes (
    id INTEGER PRIMARY KEY,
    notnullable TEXT NOT NULL,
    nullable TEXT
);

INSERT INTO likes (id, notnullable, nullable) VALUES
    (1, 'foo', NULL),
    (2, 'bar', 'baz');

