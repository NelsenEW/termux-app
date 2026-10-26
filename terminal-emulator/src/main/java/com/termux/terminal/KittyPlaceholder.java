package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

/** A virtual Kitty placement painted over normal Unicode placeholder cells. */
public final class KittyPlaceholder {
    public static final int CODE_POINT = 0x10eeee;
    public final long imageId;
    public final long placementId;
    private final Bitmap bitmap;
    private final Rect source;
    private final int columns;
    private final int rows;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private KittyPlaceholder(KittyImage command, Bitmap bitmap, Rect source, int columns, int rows) {
        imageId = command.getImageId();
        placementId = command.getPlacementId();
        this.bitmap = bitmap;
        this.source = source;
        this.columns = columns;
        this.rows = rows;
    }

    static KittyPlaceholder create(KittyImage command, Bitmap bitmap, int cellWidth, int cellHeight) {
        int x = command.getSourceX(), y = command.getSourceY();
        int width = command.getSourceWidth() > 0 ? command.getSourceWidth() : bitmap.getWidth() - x;
        int height = command.getSourceHeight() > 0 ? command.getSourceHeight() : bitmap.getHeight() - y;
        if (x < 0 || y < 0 || width < 1 || height < 1 ||
            (long) x + width > bitmap.getWidth() || (long) y + height > bitmap.getHeight()) return null;
        cellWidth = Math.max(1, cellWidth);
        cellHeight = Math.max(1, cellHeight);
        int columns = command.getColumns(), rows = command.getRows();
        if (columns < 1 && rows > 0) columns = Math.max(1, (int) Math.ceil((double) width * rows * cellHeight / height / cellWidth));
        if (rows < 1 && columns > 0) rows = Math.max(1, (int) Math.ceil((double) height * columns * cellWidth / width / cellHeight));
        if (columns < 1) columns = (width + cellWidth - 1) / cellWidth;
        if (rows < 1) rows = (height + cellHeight - 1) / cellHeight;
        if (columns > KittyImage.PLACEMENT_CELLS__MAX || rows > KittyImage.PLACEMENT_CELLS__MAX) return null;
        return new KittyPlaceholder(command, bitmap, new Rect(x, y, x + width, y + height), columns, rows);
    }

    public void drawCell(Canvas canvas, float left, float top, float cellWidth, float cellHeight,
                         int row, int column) {
        if (row < 0 || column < 0 || row >= rows || column >= columns) return;
        float scale = Math.min(columns * cellWidth / source.width(), rows * cellHeight / source.height());
        float imageLeft = left - column * cellWidth;
        float imageTop = top - row * cellHeight;
        int save = canvas.save();
        canvas.clipRect(left, top, left + cellWidth, top + cellHeight);
        canvas.drawBitmap(bitmap, source, new RectF(imageLeft, imageTop,
            imageLeft + source.width() * scale, imageTop + source.height() * scale), paint);
        canvas.restoreToCount(save);
    }
}
