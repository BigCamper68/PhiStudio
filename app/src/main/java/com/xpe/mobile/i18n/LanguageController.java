package com.xpe.mobile.i18n;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import com.xpe.mobile.R;
import java.util.Locale;

/** Activity-owned resource source. Language changes never enter the editor command pipeline. */
public final class LanguageController {
    private final Context base;
    private final SharedPreferences store;
    private volatile Resources resources;
    private String selection;
    private Runnable languageChanged;

    public LanguageController(Context base) {
        this.base = base;
        store = base.getSharedPreferences("phistudio_language", Context.MODE_PRIVATE);
        selection = readSelection();
        if (supported(selection)) store.edit().putString("locale", selection).apply();
        refreshResources();
    }

    public Resources resources() { return resources; }
    public Locale locale() { return resources.getConfiguration().getLocales().get(0); }
    public String selection() { return selection; }
    public void setLanguageChanged(Runnable listener) { languageChanged = listener; }
    public String[] codes() { return resources.getStringArray(R.array.i18n_locale_codes); }
    public String[] names() { return resources.getStringArray(R.array.i18n_locale_names); }

    public void select(String tag) {
        if (!supported(tag)) throw new IllegalArgumentException("Unsupported locale: " + tag);
        if (selection.equals(tag)) return;
        selection = tag;
        store.edit().putString("locale", tag).apply();
        if (Build.VERSION.SDK_INT >= 33) base.getSystemService(LocaleManager.class)
                .setApplicationLocales(LocaleList.forLanguageTags(tag));
        refreshResources();
        if (languageChanged != null) languageChanged.run();
    }

    public void onConfigurationChanged() {
        selection = readSelection();
        refreshResources();
        if (languageChanged != null) languageChanged.run();
    }

    private String readSelection() {
        if (Build.VERSION.SDK_INT >= 33) {
            String platform = base.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags();
            if (!platform.isEmpty()) return platform;
        }
        String saved = store.getString("locale", "en");
        return supported(saved) ? saved : "en";
    }

    private boolean supported(String tag) {
        if (tag == null || tag.isEmpty()) return false;
        for (String code : base.getResources().getStringArray(R.array.i18n_locale_codes))
            if (code.equals(tag)) return true;
        return false;
    }

    private void refreshResources() {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        LocaleList system = Build.VERSION.SDK_INT >= 33
                ? base.getSystemService(LocaleManager.class).getSystemLocales()
                : Resources.getSystem().getConfiguration().getLocales();
        // Preserve Android's locale-list fallback after an explicit app choice.
        String tags = selection + (system.isEmpty() ? "" : "," + system.toLanguageTags());
        config.setLocales(LocaleList.forLanguageTags(tags));
        resources = base.createConfigurationContext(config).getResources();
    }
}
