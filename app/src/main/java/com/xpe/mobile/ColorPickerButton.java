package com.xpe.mobile;

import com.xpe.mobile.i18n.UiText;
import com.xpe.mobile.i18n.UiDialogs;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.function.IntConsumer;

/** A touch-only color field. Dialog changes are drafts until Apply. */
@android.annotation.SuppressLint("ViewConstructor") // Created with a color value, never inflated from XML.
public final class ColorPickerButton extends Button {
    private int color;
    private final boolean alpha;
    private String label;
    private int labelResource;
    private IntConsumer listener;

    public ColorPickerButton(Context context, String label, int initial, boolean alpha) {
        super(context);
        this.label = label;
        this.alpha = alpha;
        setAllCaps(false);
        setGravity(Gravity.CENTER);
        setMinHeight(dp(40));
        setColor(initial);
        setOnClickListener(v -> showPicker());
    }
    public static ColorPickerButton addTo(LinearLayout parent, int label, int color, boolean alpha) {
        ColorPickerButton button = addTo(parent, "", color, alpha);
        button.labelResource = label;
        UiText.text((TextView) parent.getChildAt(parent.indexOfChild(button) - 1), label);
        UiText.onRelocalize(button, button::relocalizeUi);
        button.relocalizeUi();
        return button;
    }
    private void relocalizeUi() {
        if (labelResource != 0) label = UiText.string(getContext(), labelResource);
        setContentDescription(UiText.string(getContext(), R.string.color_choose_description, label, getText()));
    }
    public static ColorPickerButton addTo(LinearLayout parent, String label, int color, boolean alpha) {
        TextView title = new TextView(parent.getContext());
        title.setText(label);
        title.setTextSize(14);
        parent.addView(title);
        ColorPickerButton button = new ColorPickerButton(parent.getContext(), label, color, alpha);
        parent.addView(button, parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, button.dp(40), 1)
                : new LinearLayout.LayoutParams(-1, button.dp(40)));
        return button;
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : .45f);
    }
    public int getColor() { return color; }
    public void setOnColorChanged(IntConsumer listener) { this.listener = listener; }
    public void setColor(int value) {
        color = alpha ? value : value | 0xff000000;
        setText(alpha ? String.format(Locale.US, "#%06X · A %d", color & 0xffffff, Color.alpha(color))
                : String.format(Locale.US, "#%06X", color & 0xffffff));
        relocalizeUi();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color | 0xff000000);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), 0xff9da8b5);
        setBackground(bg);
        setTextColor(Color.red(color) * .299 + Color.green(color) * .587 + Color.blue(color) * .114 > 150
                ? Color.BLACK : Color.WHITE);
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void showPicker() {
        Picker picker = new Picker(getContext(), color, alpha);
        AlertDialog dialog = new UiDialogs.Builder(getContext()).setTitle(() -> label).setView(picker)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_apply, (d, w) -> {
                    setColor(picker.value);
                    if (listener != null) listener.accept(color);
                }).create();
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setGravity(Gravity.CENTER);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                window.setLayout(Math.min(dp(760), Math.round(metrics.widthPixels * .92f)),
                        Math.min(dp(480), Math.round(metrics.heightPixels * .9f)));
            }
        });
        dialog.show();
    }
    private final class Picker extends LinearLayout {
        int value;
        final float[] hsv = new float[3];
        final TextView sample;
        final SeekBar hue;
        final SeekBar[] channels;
        final TextView[] labels;
        final Palette palette;
        boolean updating;
        Picker(Context c, int initial, boolean alpha) {
            super(c);
            value = initial;
            Color.colorToHSV(value, hsv);
            setOrientation(HORIZONTAL);
            setPadding(dp(12), dp(4), dp(12), dp(8));
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(VERTICAL);
            addView(left, new LayoutParams(0, -1, 1));
            ScrollView settingsScroll = new ScrollView(c);
            LayoutParams settingsParams = new LayoutParams(0, -1, 1);
            settingsParams.setMarginStart(dp(12));
            addView(settingsScroll, settingsParams);
            LinearLayout settings = new LinearLayout(c);
            settings.setOrientation(VERTICAL);
            settingsScroll.addView(settings);
            sample = new TextView(c) {
                private final Paint checker = new Paint();
                @Override protected void onDraw(Canvas canvas) {
                    int tile = dp(8);
                    for (int y=0;y<getHeight();y+=tile) for (int x=0;x<getWidth();x+=tile) {
                        checker.setColor(((x/tile+y/tile)&1)==0 ? 0xffeeeeee : 0xffaaaaaa);
                        canvas.drawRect(x,y,x+tile,y+tile,checker);
                    }
                    checker.setColor(value); canvas.drawRect(0,0,getWidth(),getHeight(),checker);
                    super.onDraw(canvas);
                }
            };
            sample.setGravity(Gravity.CENTER);
            left.addView(sample, new LayoutParams(-1, dp(36)));
            palette = new Palette(c);
            UiText.description(palette, R.string.color_palette_description);
            left.addView(palette, new LayoutParams(-1, 0, 1));
            TextView hueLabel = new TextView(c); UiText.text(hueLabel, R.string.color_picker_hue); settings.addView(hueLabel);
            hue = new SeekBar(c); hue.setMax(360); UiText.description(hue, R.string.color_hue_description);
            settings.addView(hue, new LayoutParams(-1, dp(32)));
            hue.setOnSeekBarChangeListener(change(() -> { hsv[0] = hue.getProgress(); fromHsv(); }));
            LinearLayout presets = new LinearLayout(c); presets.setBaselineAligned(false);
            settings.addView(presets, new LayoutParams(-1, dp(36)));
            for (int preset : new int[]{0xffffff, 0x000000, 0xff4040, 0xffc040, 0x40d060, 0x40cfff, 0x6060ff, 0xe060ff}) {
                Button swatch = new Button(c);
                swatch.setMinWidth(0); swatch.setMinimumWidth(0);
                swatch.setBackgroundColor(preset | 0xff000000);
                swatch.setContentDescription(String.format(Locale.US, "Preset #%06X", preset));
                LayoutParams lp = new LayoutParams(0, -1, 1); lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                presets.addView(swatch, lp);
                swatch.setOnClickListener(v -> { value = (value & 0xff000000) | preset; Color.colorToHSV(value, hsv); refresh(); });
            }
            channels = new SeekBar[alpha ? 4 : 3]; labels = new TextView[channels.length];
            for (int i = 0; i < channels.length; i++) {
                final int channel = i;
                LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBaselineAligned(false); settings.addView(row);
                labels[i] = new TextView(c); row.addView(labels[i], new LayoutParams(dp(60), dp(32)));
                channels[i] = new SeekBar(c); channels[i].setMax(255);
                final int channelIndex = i;
                UiText.description(channels[i], () -> UiText.resources(getContext()).getStringArray(R.array.color_channel_names)[channelIndex]);
                row.addView(channels[i], new LayoutParams(0, dp(32), 1));
                channels[i].setOnSeekBarChangeListener(change(() -> {
                    int shift = channel == 3 ? 24 : (2 - channel) * 8;
                    value = (value & ~(255 << shift)) | (channels[channel].getProgress() << shift);
                    Color.colorToHSV(value, hsv); refresh();
                }));
            }
            refresh();
        }
        SeekBar.OnSeekBarChangeListener change(Runnable action) {
            return new SeekBar.OnSeekBarChangeListener() {
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
                public void onProgressChanged(SeekBar s, int p, boolean user) { if (!updating) action.run(); }
            };
        }
        void fromHsv() { value = Color.HSVToColor(Color.alpha(value), hsv); refresh(); }
        void refresh() {
            updating = true;
            UiText.text(sample, () -> UiText.string(getContext(), R.string.color_sample_alpha, value & 0xffffff, Color.alpha(value)));
            sample.invalidate();
            sample.setTextColor(Color.alpha(value) < 128 || hsv[2] > .7 && hsv[1] < .5 ? Color.BLACK : Color.WHITE);
            hue.setProgress(Math.round(hsv[0]));
            for (int i = 0; i < channels.length; i++) {
                int n = (value >>> (i == 3 ? 24 : (2 - i) * 8)) & 255;
                channels[i].setProgress(n); labels[i].setText(UiText.resources(getContext()).getString(R.string.color_picker_channel, new String[]{"R", "G", "B", "A"}[i], n));
            }
            updating = false; palette.updateGradients(); palette.invalidate();
        }
        final class Palette extends View {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private LinearGradient saturation, brightness;
            private float previousHue = -1;
            private int previousWidth, previousHeight;
            private final float[] hueColor = {0, 1, 1};
            Palette(Context c) { super(c); }
            void updateGradients() {
                int w = getWidth(), h = getHeight();
                if (w <= 0 || h <= 0) return;
                if (previousWidth != w || previousHue != hsv[0]) {
                    hueColor[0] = hsv[0];
                    saturation = new LinearGradient(0, 0, w, 0, Color.WHITE,
                            Color.HSVToColor(hueColor), Shader.TileMode.CLAMP);
                }
                if (previousHeight != h) brightness = new LinearGradient(0, 0, 0, h,
                        Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
                previousWidth = w; previousHeight = h; previousHue = hsv[0];
            }
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { updateGradients(); }
            @Override protected void onDraw(Canvas canvas) {
                float w = getWidth(), h = getHeight();
                paint.setShader(saturation);
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(brightness);
                canvas.drawRect(0, 0, w, h, paint); paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Color.WHITE);
                canvas.drawCircle(hsv[1] * w, (1 - hsv[2]) * h, dp(6), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    hsv[1] = Math.max(0, Math.min(1, e.getX() / Math.max(1, getWidth())));
                    hsv[2] = 1 - Math.max(0, Math.min(1, e.getY() / Math.max(1, getHeight())));
                    fromHsv();
                    if (action == MotionEvent.ACTION_UP) performClick();
                }
                return true;
            }
            @Override public boolean performClick() { super.performClick(); return true; }
        }
    }
}
