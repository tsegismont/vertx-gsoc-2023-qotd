DROP TABLE IF EXISTS quotes;

CREATE TABLE quotes
(
  quote_id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
  text     VARCHAR NOT NULL,
  author   VARCHAR NOT NULL
);


INSERT INTO quotes (text, author)
VALUES ('Hello, IT. Have you tried turning it off and on again?', 'Roy Trenneman');

INSERT INTO quotes (text, author)
VALUES ('Hello, IT... Have you tried forcing an unexpected reboot?', 'Maurice Moss');
