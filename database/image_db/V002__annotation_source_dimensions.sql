-- image-service only. Apply after V001 while connected to image_db.
-- Existing annotations use the original picture dimensions as their source basis.
-- If an old picture lacks real dimensions, NOT NULL deliberately stops the migration;
-- backfill those real dimensions before retrying instead of inventing placeholder values.

BEGIN;

ALTER TABLE annotation_set
    ADD COLUMN source_width integer,
    ADD COLUMN source_height integer;

UPDATE annotation_set a
SET source_width = p.width_px,
    source_height = p.height_px
FROM picture_asset p
WHERE p.id = a.picture_id;

ALTER TABLE annotation_set
    ALTER COLUMN source_width SET NOT NULL,
    ALTER COLUMN source_height SET NOT NULL,
    ADD CONSTRAINT ck_annotation_source_dimensions
        CHECK (source_width > 0 AND source_height > 0);

COMMENT ON COLUMN annotation_set.source_width IS
    'Width of the source picture basis used for normalized annotations, not the rendered canvas width.';
COMMENT ON COLUMN annotation_set.source_height IS
    'Height of the source picture basis used for normalized annotations, not the rendered canvas height.';

COMMIT;
