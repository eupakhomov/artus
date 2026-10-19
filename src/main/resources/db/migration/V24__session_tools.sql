-- Phase 26: tool catalog. `tools` is the list of catalog names picked at creation (shown on the
-- widget, carried by duplicate/quick session); `plugin_dirs` the plugin directories handed to a
-- Claude sidecar's SDK `plugins` option. Both are snapshots: later catalog edits never touch a session.
ALTER TABLE session ADD COLUMN tools jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE session ADD COLUMN plugin_dirs jsonb NOT NULL DEFAULT '[]'::jsonb;
