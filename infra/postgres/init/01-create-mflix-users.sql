-- review-service gets mflix_reviews from POSTGRES_DB; user-service and assistant-service need
-- their own databases on the same server. The postgres image runs this once, on first start with
-- an empty data volume. On an existing volume, create a missing one by hand, e.g.
--   docker compose exec postgres createdb -U mflix mflix_assistant
CREATE DATABASE mflix_users;
CREATE DATABASE mflix_assistant;
