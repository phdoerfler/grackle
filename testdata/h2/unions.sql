
CREATE TABLE collections (
    id TEXT PRIMARY KEY,
    item_type TEXT NOT NULL,
    itema TEXT,
    itemb TEXT
);

INSERT INTO collections (id, item_type, itema, itemb)
VALUES ('1', 'ItemA', 'A', NULL),
       ('2', 'ItemB', NULL, 'B');