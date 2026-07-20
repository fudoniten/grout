ALTER TABLE directory_profiles
  ADD COLUMN context jsonb,
  ADD COLUMN locked  boolean NOT NULL DEFAULT false;
--;;

COMMENT ON COLUMN directory_profiles.context IS
  'Operator-supplied grounding notes for this group, e.g. {"text": "...",
   "links": ["..."]}. Threaded into /enrich/profile''s prompt verbatim (not
   fetched/summarized) so an operator can correct a misclassification like
   "these are retro VIDEO GAME ads, not vintage film content" without editing
   every child media row. NULL until set.';
--;;

COMMENT ON COLUMN directory_profiles.locked IS
  'True when dimensions/tags were set by a manual PATCH rather than the LLM.
   Growth-triggered re-enrichment (POST /grout/enrich-by-tag/:tag) skips
   calling Tunabrain for a locked profile (it still re-applies the existing
   dimensions/tags to any newly tagged media), so a human correction is not
   silently overwritten by the next automatic re-enrichment. Explicit
   force=true still re-enriches and re-locks with the fresh LLM result.';
--;;
