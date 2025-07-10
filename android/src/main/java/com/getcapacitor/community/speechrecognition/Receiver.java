package com.getcapacitor.community.speechrecognition;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.ArrayList;
import java.util.List;

public class Receiver extends BroadcastReceiver implements Constants {

    public static final String TAG = "Receiver";

    private List<String> supportedLanguagesList;
    private String languagePref;
    private final PluginCall call;

    public Receiver(PluginCall call) {
        super();
        this.call = call;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle extras = getResultExtras(true);
        if (extras != null) {

            if (extras.containsKey(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)) {
                languagePref = extras.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE);
            }

            if (extras.containsKey("com.google.recognition.extra.OFFLINE_AVAILABLE_LANGUAGES")) {
                byte[] data = extras.getByteArray(
                        "com.google.recognition.extra.OFFLINE_AVAILABLE_LANGUAGES");

                if (data != null) {
                    supportedLanguagesList = extractPrintableStrings(data);

                    JSArray languagesArray = new JSArray(supportedLanguagesList);
                    JSObject result = new JSObject();
                    result.put("languages", languagesArray);
                    call.resolve(result);
                    return;
                }
            }

        }

        call.reject(ERROR);
    }

    public List<String> getSupportedLanguages() {
        return supportedLanguagesList;
    }

    public String getLanguagePreference() {
        return languagePref;
    }

    private static ArrayList<String> extractPrintableStrings(byte[] data) {
        ArrayList<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (byte b : data) {
            char c = (char) (b & 0xFF); // unsigned

            if (isPrintableAscii(c)) {
                current.append(c);
            } else {
                if (current.length() >= 4) {
                    result.add(current.toString());
                }
                current.setLength(0);
            }
        }

        // Append last string if valid
        if (current.length() >= 4) {
            result.add(current.toString());
        }

        return result;
    }

    private static boolean isPrintableAscii(char c) {
        return c >= 32 && c <= 126; // visible chars
    }
}
