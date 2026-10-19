-- Phase 25 step 5: "hide from dashboard". A hidden session keeps running/parking/resuming as
-- usual; it just gets no widget and is listed in the sidebar's Hidden section instead. Cleared
-- automatically when the session needs the user (approval prompt, crash).
ALTER TABLE session ADD COLUMN hidden boolean NOT NULL DEFAULT false;
