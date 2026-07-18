CREATE TABLE cats (
  id INTEGER PRIMARY KEY,
  name VARCHAR NOT NULL,
  status VARCHAR NOT NULL,
  position VARCHAR NOT NULL,
  hair_length VARCHAR NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE FUNCTION touch_cat_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cats_touch_updated_at
  BEFORE UPDATE ON cats
  FOR EACH ROW EXECUTE FUNCTION touch_cat_updated_at();

CREATE FUNCTION notify_cat_update() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('cat_updates', NEW.id::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER cats_notify_update
  AFTER UPDATE ON cats
  FOR EACH ROW EXECUTE FUNCTION notify_cat_update();

INSERT INTO cats (id, name, status, position, hair_length) VALUES
(1, 'Momo', 'ASLEEP', 'on the windowsill', 'LONG'),
(2, 'Biscuit', 'AWAKE', 'under the bed', 'SHORT'),
(3, 'Waffle', 'HUNTING', 'on your keyboard', 'SHORT');
