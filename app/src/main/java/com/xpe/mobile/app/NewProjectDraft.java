package com.xpe.mobile.app;

import android.app.AlertDialog;
import android.net.Uri;
import android.widget.EditText;
import android.widget.TextView;

final class NewProjectDraft {
    AlertDialog dialog;
    EditText name;
    EditText composer;
    EditText charter;
    EditText level;
    EditText bpm;
    TextView audioStatus;
    TextView illustrationStatus;
    Uri audioUri;
    Uri illustrationUri;
    String audioExtension;
    String illustrationExtension;
    String audioDisplayName;
    String illustrationDisplayName;
}
