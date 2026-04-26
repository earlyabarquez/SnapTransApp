package com.example.cameratranslator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay drawn on top of the camera preview.
 * - Shows a subtle dashed border around each detected text block.
 * - When the user taps a block, it lights up with a highlight fill.
 */
public class TextOverlayView extends View {

    // Idle border: thin white-ish stroke
    private final Paint idlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Highlighted fill + stroke
    private final Paint highlightFill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<MainActivity.DetectedBlock> blocks = new ArrayList<>();
    private RectF highlightedRect = null;

    public TextOverlayView(Context ctx) {
        super(ctx);
        init();
    }

    public TextOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    public TextOverlayView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        init();
    }

    private void init() {
        // Idle outline
        idlePaint.setStyle(Paint.Style.STROKE);
        idlePaint.setColor(Color.argb(120, 255, 255, 255));
        idlePaint.setStrokeWidth(2f);

        // Highlight fill (semi-transparent purple)
        highlightFill.setStyle(Paint.Style.FILL);
        highlightFill.setColor(Color.argb(70, 102, 71, 207));   // #6647CF @ 27%

        // Highlight border
        highlightStroke.setStyle(Paint.Style.STROKE);
        highlightStroke.setColor(Color.argb(230, 102, 71, 207));
        highlightStroke.setStrokeWidth(3f);
    }

    /** Called from OCR thread via mainHandler.post() */
    public void setBlocks(List<MainActivity.DetectedBlock> newBlocks) {
        this.blocks = newBlocks;
        // Clear stale highlight if block list refreshed
        if (highlightedRect != null) {
            boolean found = false;
            for (MainActivity.DetectedBlock b : newBlocks) {
                if (b.bounds.equals(highlightedRect)) { found = true; break; }
            }
            if (!found) highlightedRect = null;
        }
        invalidate();
    }

    public void setHighlighted(RectF rect) {
        this.highlightedRect = rect;
        invalidate();

        // Auto-clear highlight after 4 s so the UI doesn't stay frozen-looking
        postDelayed(() -> {
            highlightedRect = null;
            invalidate();
        }, 4000);
    }

    public void clearHighlight() {
        highlightedRect = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = 6f;

        for (MainActivity.DetectedBlock block : blocks) {
            RectF r = block.bounds;

            // Highlighted block
            if (highlightedRect != null && r.equals(highlightedRect)) {
                canvas.drawRoundRect(r, radius, radius, highlightFill);
                canvas.drawRoundRect(r, radius, radius, highlightStroke);
            } else {
                // Idle — just a subtle outline so user knows what's tappable
                canvas.drawRoundRect(r, radius, radius, idlePaint);
            }
        }
    }
}