package com.dshmobile.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small native UI factory. No AndroidX/Compose dependency. */
public final class Ui {
    public static final int PRIMARY = Color.parseColor("#4D6BFE");
    public static final int PRIMARY_SOFT = Color.parseColor("#EEF2FF");

    private Ui() {}

    public static int bg(Context c) { return c.getColor(R.color.ds_bg); }
    public static int bgSoft(Context c) { return c.getColor(R.color.ds_bg_soft); }
    public static int text(Context c) { return c.getColor(R.color.ds_text); }
    public static int textSecondary(Context c) { return c.getColor(R.color.ds_text_secondary); }
    public static int border(Context c) { return c.getColor(R.color.ds_border); }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static TextView title(Context ctx, String value) {
        TextView tv = new TextView(ctx);
        tv.setText(value); tv.setTextColor(text(ctx)); tv.setTextSize(22); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setIncludeFontPadding(false); return tv;
    }

    public static TextView body(Context ctx, String value) {
        TextView tv = new TextView(ctx);
        tv.setText(value); tv.setTextColor(text(ctx)); tv.setTextSize(15); tv.setIncludeFontPadding(false); return tv;
    }

    public static TextView hint(Context ctx, String value) {
        TextView tv = new TextView(ctx);
        tv.setText(value); tv.setTextColor(textSecondary(ctx)); tv.setTextSize(13); tv.setIncludeFontPadding(false); return tv;
    }

    public static TextView sectionHeader(Context ctx, String value) {
        TextView tv = new TextView(ctx);
        tv.setText(value); tv.setTextColor(PRIMARY); tv.setTextSize(13); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.05f); tv.setIncludeFontPadding(false); return tv;
    }

    public static Button primaryButton(Context ctx, String value) {
        Button b = new Button(ctx);
        b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setMinHeight(dp(ctx, 48));
        b.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0);
        b.setBackground(primaryBackground(ctx));
        b.setStateListAnimator(null);
        installPressMotion(b);
        return b;
    }

    public static Button outlineButton(Context ctx, String value) {
        Button b = new Button(ctx);
        b.setText(value); b.setTextColor(PRIMARY); b.setTextSize(15); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD); b.setMinHeight(dp(ctx, 48));
        b.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        b.setBackground(outlineBackground(ctx));
        b.setStateListAnimator(null);
        installPressMotion(b);
        return b;
    }

    private static android.graphics.drawable.Drawable primaryBackground(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(PRIMARY); base.setCornerRadius(dp(c, 15));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255,255,255)), base, null);
    }

    private static android.graphics.drawable.Drawable outlineBackground(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(bg(c)); base.setStroke(dp(c, 1), border(c)); base.setCornerRadius(dp(c, 14));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(30, 77,107,254)), base, null);
    }

    private static void installPressMotion(final View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                    break;
            }
            return false;
        });
    }

    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL); card.setBackgroundResource(R.drawable.card_bg);
        int p = dp(ctx, 16); card.setPadding(p,p,p,p); card.setElevation(dp(ctx, 1)); return card;
    }

    public static GradientDrawable softBg(Context ctx) {
        GradientDrawable g = new GradientDrawable(); g.setColor(bgSoft(ctx)); g.setCornerRadius(dp(ctx, 12)); return g;
    }

    public static View divider(Context ctx) {
        View v = new View(ctx); v.setBackgroundColor(border(ctx));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))); return v;
    }
}
