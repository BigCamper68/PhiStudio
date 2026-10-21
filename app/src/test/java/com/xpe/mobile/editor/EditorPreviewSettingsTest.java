package com.xpe.mobile.editor;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import com.xpe.mobile.R;
import com.xpe.mobile.config.EditorSettings;
import com.xpe.mobile.model.ChartDocument;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EditorPreviewSettingsTest {
    @Test public void controlsStartEnabledRejectInvalidScaleAndApplyIndependentChoices() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();activity.setTheme(R.style.AppTheme);
        EditorView editor=new EditorView(activity);editor.setChart(ChartDocument.fromJson("{}"));
        WorkbenchView workbench=new WorkbenchView(activity,editor,()->new TextView(activity));activity.setContentView(workbench);
        EditorSettings[] saved={new EditorSettings()};List<String> messages=new ArrayList<>();
        EditorDialogController dialog=new EditorDialogController(activity,editor,new EditorDialogController.Host(){
            public WorkbenchView workbench(){return workbench;}
            public EditorSettings editorSettings(){return saved[0];}
            public void applyEditorSettings(EditorSettings value){saved[0]=value;editor.applySettings(value);}
            public long packageOffsetMs(){return 0;}public long audioDurationMs(){return 0;}
            public void onLineAppearanceChanged(){}public List<String> hitSoundChoices(){return Collections.emptyList();}
            public boolean isHitSoundAvailable(String reference){return false;}public void showMessage(String text){messages.add(text);}
        });
        dialog.showSettings();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        CheckBox shaders=(CheckBox)text(workbench,activity.getString(R.string.settings_grid_preview_shaders));
        CheckBox fields=(CheckBox)text(workbench,activity.getString(R.string.settings_grid_preview_noise_fields));
        assertTrue(shaders.isChecked());assertTrue(fields.isChecked());
        View label=text(workbench,activity.getString(R.string.settings_preview_note_scale));
        ViewGroup parent=(ViewGroup)label.getParent();EditText scale=(EditText)parent.getChildAt(parent.indexOfChild(label)+1);
        assertEquals("1.000",scale.getText().toString());
        shaders.setChecked(false);fields.setChecked(true);scale.setText("1.201");
        text(workbench,activity.getString(R.string.action_apply)).performClick();
        assertEquals(1,saved[0].previewNoteScale,0);assertTrue(saved[0].gridPreviewShaders);
        scale.setText("1.200");text(workbench,activity.getString(R.string.action_apply)).performClick();
        assertEquals(1.2,saved[0].previewNoteScale,0);assertFalse(saved[0].gridPreviewShaders);assertTrue(saved[0].gridPreviewNoiseFields);
        assertEquals(58,saved[0].noteWidthPixels,0);
        dialog.showSettings();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(((CheckBox)text(workbench,activity.getString(R.string.settings_grid_preview_shaders))).isChecked());
    }
    private static TextView text(View root,String label) {
        if(root instanceof TextView && ((TextView)root).getText().toString().equals(label))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++) {
            TextView found=text(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;
        }
        return null;
    }
}
