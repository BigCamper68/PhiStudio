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
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = base.getSystemService(LocaleManager.class);
            // Import a pre-Android-13 preference once; the platform owns subsequent choices.
            if (!store.getBoolean("platform_migrated", false)) {
                String legacy = store.getString("locale", "");
                if (manager.getApplicationLocales().isEmpty() && supported(legacy))
                    manager.setApplicationLocales(LocaleList.forLanguageTags(legacy));
                store.edit().putBoolean("platform_migrated", true).apply();
            }
            selection = manager.getApplicationLocales().toLanguageTags();
        } else {
            selection = store.getString("locale", "");
            if (!supported(selection)) selection = "";
        }
        refreshResources();
    }

    public Resources resources() { return resources; }
    public Locale locale() { return resources.getConfiguration().getLocales().get(0); }
    public String selection() { return selection; }
    public void setLanguageChanged(Runnable listener) { languageChanged = listener; }
    public String[] codes() { return resources.getStringArray(R.array.i18n_locale_codes); }
    public String[] names() { return resources.getStringArray(R.array.i18n_locale_names); }

    public void select(String tag) {
        if (!tag.isEmpty() && !supported(tag)) throw new IllegalArgumentException("Unsupported locale: " + tag);
        if (selection.equals(tag)) return;
        selection = tag;
        if (Build.VERSION.SDK_INT >= 33) base.getSystemService(LocaleManager.class)
                .setApplicationLocales(LocaleList.forLanguageTags(tag));
        else store.edit().putString("locale", tag).apply();
        refreshResources();
        if (languageChanged != null) languageChanged.run();
    }

    public void onConfigurationChanged() {
        if (Build.VERSION.SDK_INT >= 33) selection = base.getSystemService(LocaleManager.class)
                .getApplicationLocales().toLanguageTags();
        refreshResources();
        if (languageChanged != null) languageChanged.run();
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
        String tags = selection.isEmpty() ? system.toLanguageTags()
                : selection + (system.isEmpty() ? "" : "," + system.toLanguageTags());
        config.setLocales(LocaleList.forLanguageTags(tags));
        resources = base.createConfigurationContext(config).getResources();
    }
}
