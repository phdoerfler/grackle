CREATE TABLE level0 (
  id VARCHAR PRIMARY KEY
);

CREATE TABLE level1 (
  id VARCHAR PRIMARY KEY,
  level0_id VARCHAR
);

CREATE TABLE level2 (
  id VARCHAR PRIMARY KEY,
  level1_id VARCHAR,
  attr BOOLEAN
);

INSERT INTO level0 (id)
VALUES ('00'),
       ('01');

INSERT INTO level1 (id, level0_id)
VALUES ('10', '00'),
       ('11', '00'),
       ('12', '01'),
       ('13', '01');

INSERT INTO level2 (id, level1_id, attr)
VALUES ('20', '10', false),
       ('21', '10', false),
       ('22', '11', false),
       ('23', '11', false),
       ('24', '12', true),
       ('25', '12', false),
       ('26', '13', false),
       ('27', '13', false);
