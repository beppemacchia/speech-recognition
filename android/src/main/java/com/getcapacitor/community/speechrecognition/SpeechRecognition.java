package com.getcapacitor.community.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@CapacitorPlugin(permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = SpeechRecognition.SPEECH_RECOGNITION)})
public class SpeechRecognition extends Plugin implements Constants {

  private static final String TAG = "SpeechRecognition";
  private static final String LISTENING_EVENT = "listeningState";
  static final String SPEECH_RECOGNITION = "speechRecognition";
  private Receiver languageReceiver;
  private SpeechRecognizer speechRecognizer;
  private boolean isListening = false;
  // TODO set dynamically from js
  private static final int TIMEOUT_SPEECH = 5000;

  @Override
  public void load() {
    super.load();
    getActivity().runOnUiThread(() -> {
      if (isSpeechRecognitionAvailable()) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
        speechRecognizer.setRecognitionListener(new SpeechRecognitionListener());
        Logger.info(getLogTag(), "Instantiated SpeechRecognizer in load()");
      } else {
        Logger.error("Speech recognition not available on this device");
      }
    });
  }

  @PluginMethod
  public void available(PluginCall call) {
    boolean isAvailable = isSpeechRecognitionAvailable();
    Logger.info(getLogTag(), "Called for available(): " + isAvailable);
    JSObject ret = new JSObject();
    ret.put("available", isAvailable);
    call.resolve(ret);
  }

  @PluginMethod
  public void start(PluginCall call) {
    if (speechRecognizer == null) {
      call.reject("Speech recognition not available");
      return;
    }

    if (getPermissionState(SpeechRecognition.SPEECH_RECOGNITION) != com.getcapacitor.PermissionState.GRANTED) {
      call.reject("Microphone permission required");
      return;
    }

    if (isListening) {
      call.reject("Already listening");
      return;
    }

    String language = call.getString("language", Locale.getDefault().toString());
    int maxResults = call.getInt("maxResults", MAX_RESULTS);
    
    String prompt = call.getString("prompt", null);
    boolean partialResults = Boolean.TRUE.equals(call.getBoolean("partialResults", false));
    boolean showPopup = Boolean.TRUE.equals(call.getBoolean("popup", false));

    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

    intent.putExtra(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, RecognizerIntent.EXTRA_PREFER_OFFLINE);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getActivity().getPackageName());
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
    }
    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, TIMEOUT_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, TIMEOUT_SPEECH);


    if (prompt != null) {
      intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
    }

    getActivity().runOnUiThread(() -> {

      if (speechRecognizer != null) {
        speechRecognizer.cancel();
        speechRecognizer.destroy();
        speechRecognizer = null;
      }

      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
      SpeechRecognitionListener listener = new SpeechRecognitionListener();
      listener.setCall(call);
      listener.setPartialResults(partialResults);
      speechRecognizer.setRecognitionListener(listener);

      try {
        if (showPopup) {
          startActivityForResult(call, intent, "listeningResult");
        } else {
          isListening = true;
          speechRecognizer.startListening(intent);
          if (partialResults) {
            call.resolve();
          }
        }
      } catch (Exception ex) {
        call.reject(ex.getMessage());
      }


    });
  }

  @PluginMethod
  public void stop(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (speechRecognizer != null && isListening) {
        speechRecognizer.stopListening();
        isListening = false;
      }

      JSObject ret = new JSObject();
      ret.put("stopped", true);
      call.resolve(ret);
    });
  }


  @PluginMethod
  public void getSupportedLanguages(PluginCall call) {
    if (languageReceiver == null) {
      languageReceiver = new Receiver(call);
    }

    List<String> supportedLanguages = languageReceiver.getSupportedLanguages();
    if (supportedLanguages != null) {
      JSONArray languages = new JSONArray(supportedLanguages);
      call.resolve(new JSObject().put("languages", languages));
      return;
    }

    Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      detailsIntent.setPackage("com.google.android.googlequicksearchbox");
    }
    bridge.getActivity().sendOrderedBroadcast(detailsIntent, null, languageReceiver, null, Activity.RESULT_OK, null, null);
  }


  @PluginMethod
  public void isListening(PluginCall call) {
    call.resolve(new JSObject().put("listening", isListening));
  }

  @PluginMethod
  public void checkPermissions(PluginCall call) {
    super.checkPermissions(call);
  }

  @PluginMethod
  public void requestPermissions(PluginCall call) {
    requestPermissionForAlias(SpeechRecognition.SPEECH_RECOGNITION, call, "permissionsCallback");
  }

  @PermissionCallback
  private void permissionsCallback(PluginCall call) {
    this.checkPermissions(call);
  }

  @ActivityCallback
  private void listeningResult(PluginCall call, ActivityResult result) {
    if (call == null) {
      return;
    }

    boolean partialResults = Boolean.TRUE.equals(call.getBoolean("partialResults", false));
    if (partialResults) {
      call.resolve();
    }

    int resultCode = result.getResultCode();
    if (resultCode == Activity.RESULT_OK && result.getData() != null) {
      try {
        ArrayList<String> matchesList = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        JSObject resultObj = new JSObject();
        resultObj.put("matches", new JSArray(matchesList));
        if (partialResults) {
          notifyListeners("partialResults", resultObj);
        } else {
          call.resolve(resultObj);
        }

      } catch (Exception ex) {
        call.reject(ex.getMessage());
      }
    } else {
      call.reject(Integer.toString(resultCode));
    }

    isListening = false;
  }

  private boolean isSpeechRecognitionAvailable() {
    return SpeechRecognizer.isRecognitionAvailable(bridge.getContext());
  }

  @PluginMethod
  public void cancel(PluginCall call) {
    getActivity().runOnUiThread(() -> {
      if (speechRecognizer != null && isListening) {
        speechRecognizer.cancel();
        isListening = false;
      }

      JSObject ret = new JSObject();
      ret.put("cancelled", true);
      call.resolve(ret);
    });
  }


  private class SpeechRecognitionListener implements RecognitionListener {

    private PluginCall call;
    private boolean partialResults;
    private boolean isInPause;
    private final ArrayList<String> listPartialResults = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    public void setCall(PluginCall call) {
      this.call = call;
    }

    public void setPartialResults(boolean partialResults) {
      this.partialResults = partialResults;
    }

    private String getFinalPartialResult() {
      return String.join(" ", listPartialResults);
    }


    @Override
    public void onReadyForSpeech(Bundle params) {
      Logger.debug(TAG, "Ready for speech");
      JSObject ret = new JSObject();
      ret.put("status", "ready");
      notifyListeners(LISTENING_EVENT, ret);
    }

    @Override
    public void onBeginningOfSpeech() {
      Logger.debug(TAG, "Beginning of speech");
      getActivity().runOnUiThread(() -> {
        JSObject ret = new JSObject();
        ret.put("status", "started");
        notifyListeners(LISTENING_EVENT, ret);
      });
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
      Logger.debug(TAG, "End of speech");

      getActivity().runOnUiThread(() -> {
        long now = new Date().getTime();
        if (!partialResults) {
          isListening = false;
          JSObject ret = new JSObject();
          ret.put("status", "stopped");
          notifyListeners(LISTENING_EVENT, ret);
        } else {
          if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
          }

          timeoutRunnable = () -> {
            isListening = false;
            JSObject ret = new JSObject();
            ret.put("status", "stopped");
            notifyListeners(LISTENING_EVENT, ret);
          };

          handler.postDelayed(timeoutRunnable, TIMEOUT_SPEECH);

        }

      });

    }

    @Override
    public void onError(int error) {
      isListening = false;
      String errorMessage = getErrorMessage(error);
      Logger.error("Speech recognition error: " + errorMessage);

      JSObject errorData = new JSObject();
      errorData.put("error", errorMessage);
      errorData.put("code", error);
      errorData.put("status", "error");
      notifyListeners(LISTENING_EVENT, errorData);

      if (call != null) {
        call.reject(errorMessage);
      }
    }

    @Override
    public void onResults(Bundle results) {
      String finalResult = "";
      if (isListening) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String lastPartialResult = getFinalPartialResult();

        if (matches != null && !matches.isEmpty()) {
          finalResult = matches.get(0);
        } else if (!lastPartialResult.isEmpty()) {
          finalResult = lastPartialResult;
        }
      }

      JSArray jsArray = new JSArray();
      jsArray.put(finalResult);
      JSObject ret = new JSObject();
      ret.put("matches", jsArray);

      if (partialResults) {
        notifyListeners("partialResults", ret);
      } else if (call != null) {
        call.resolve(ret);
      }

      isListening = false;
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
      ArrayList<String> partialMatches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      if (partialMatches != null && !partialMatches.isEmpty()) {
        //noinspection SequencedCollectionMethodCanBeUsed
        String firstMatch = partialMatches.get(0);
        if (firstMatch.isEmpty()) {
          isInPause = true;
          return;
        }

        if (isInPause) {
          isInPause = false;
          listPartialResults.add(firstMatch);
        } else {
          int index = listPartialResults.isEmpty() ? 0 : listPartialResults.size() - 1;
          listPartialResults.set(index, firstMatch);
        }

        String partialResult = getFinalPartialResult();

        JSObject ret = new JSObject();
        JSArray matches = new JSArray();
        matches.put(partialResult);
        ret.put("matches", matches);
        notifyListeners("partialResults", ret);
      }
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
  }

  private String getErrorMessage(int error) {
    return switch (error) {
      case SpeechRecognizer.ERROR_AUDIO -> "Audio recording error";
      case SpeechRecognizer.ERROR_CLIENT -> "Client side error";
      case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions";
      case SpeechRecognizer.ERROR_NETWORK -> "Network error";
      case SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout";
      case SpeechRecognizer.ERROR_NO_MATCH -> "No match";
      case SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy";
      case SpeechRecognizer.ERROR_SERVER -> "Server error";
      case SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input";
      default -> "Didn't understand, please try again.";
    };
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    getActivity().runOnUiThread(() -> {
      if (speechRecognizer != null) {
        speechRecognizer.stopListening();
        speechRecognizer.destroy();
        speechRecognizer = null;
      }
    });
  }
}
