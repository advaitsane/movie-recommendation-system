-- review-service gets mflix_reviews from POSTGRES_DB; user-service needs its own database on the
-- same server. The postgres image runs this once, on first start with an empty data volume.
CREATE DATABASE mflix_users;
