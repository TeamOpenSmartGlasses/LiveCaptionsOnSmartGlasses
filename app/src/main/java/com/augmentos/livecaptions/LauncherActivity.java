package com.augmentos.livecaptions;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;


public class LauncherActivity extends AppCompatActivity {

    private static final String TAG = "LauncherActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeUIComponents();
    }

    private void initializeUIComponents() {
        Context mContext = this;

        // Spinners
//        Spinner transcribeLanguageSpinner = findViewById(R.id.transcribeLanguageSpinner);


        // Populate Spinners with options
//        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
//                mContext, R.array.language_options, android.R.layout.simple_spinner_item
//        );
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        transcribeLanguageSpinner.setAdapter(adapter);

        // Set initial values and listeners for Spinners
//        setupSpinner(transcribeLanguageSpinner, mContext, "transcribeLanguage");
    }

    private void setupSpinner(Spinner spinner, Context context, String preferenceKey) {
        String savedValue = "";

        if (preferenceKey.equals("transcribeLanguage")){
            savedValue = getChosenTranscribeLanguage(context);
        }

        ArrayAdapter<CharSequence> adapter = (ArrayAdapter<CharSequence>) spinner.getAdapter();
        int position = adapter.getPosition(savedValue);
        spinner.setSelection(position);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean isFirstSelection = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isFirstSelection) {
                    isFirstSelection = false;
                    return;
                }
                String selectedLanguage = parent.getItemAtPosition(position).toString();
                Log.d(TAG, preferenceKey + " updated to: " + selectedLanguage);
                if (preferenceKey.equals("transcribeLanguage")){
                    saveChosenTranscribeLanguage(context, selectedLanguage);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case where nothing is selected (optional)
            }
        });
    }

    public static void saveChosenTranscribeLanguage(Context context, String transcribeLanguageString) {
        Log.d(TAG, "set saveChosenTranscribeLanguage");
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(context.getResources().getString(R.string.SHARED_PREF_TRANSCRIBE_LANGUAGE), transcribeLanguageString)
                .apply();
    }

    public static String getChosenTranscribeLanguage(Context context) {
        String transcribeLanguageString = PreferenceManager.getDefaultSharedPreferences(context).getString(context.getResources().getString(R.string.SHARED_PREF_TRANSCRIBE_LANGUAGE), "");
        if (transcribeLanguageString.equals("")){
            saveChosenTranscribeLanguage(context, "Chinese");
            transcribeLanguageString = "Chinese";
        }
        return transcribeLanguageString;
    }

}
