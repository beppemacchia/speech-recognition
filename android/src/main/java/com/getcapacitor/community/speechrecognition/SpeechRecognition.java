package com.getcapacitor.community.speechrecognition;

import android.Manifest;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@CapacitorPlugin(
        permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = SpeechRecognition.SPEECH_RECOGNITION) }
)
public class SpeechRecognition extends Plugin implements Constants {

  public static final String TAG = "SpeechRecognition";
  private static final String LISTENING_EVENT = "listeningState";
  static final String SPEECH_RECOGNITION = "speechRecognition";
  private static final float SAMPLE_RATE = 16000.0f;

  private Map<String, Model> models = new HashMap<>();
  private Model currentModel;
  private SpeechService speechService;
  private final ReentrantLock lock = new ReentrantLock();
  private boolean listening = false;
  private boolean isModelLoaded = false;
  private String lastPartialResult = "";
  private String accumulatedText = "";
  private Handler mainHandler;
  private String currentLanguage = "en-GB";

  @Override
  public void load() {
    super.load();
    mainHandler = new Handler(Looper.getMainLooper());

    // Load models in background
    new Thread(() -> {
      try {
        loadModelsFromAssets();
      } catch (Exception e) {
        Logger.error(getLogTag() + " Error loading models: " + e.getMessage(), e);
      }
    }).start();
  }

  private void loadModelsFromAssets() {
    Context context = bridge.getContext();

    try {
      // Load english model from assets
      String enModelPath = getModelPath(context, "vosk-model-small-en-gb-0.15");
      if (enModelPath != null) {
        Model enModel = new Model(enModelPath);
        models.put("en-GB", enModel);
        Logger.info(getLogTag(), "English model loaded from assets");
      }

      // Load italian model from assets
      String itModelPath = getModelPath(context, "vosk-model-small-it-0.22");
      if (itModelPath != null) {
        Model itModel = new Model(itModelPath);
        models.put("it-IT", itModel);
        Logger.info(getLogTag(), "Italian model loaded from assets");
      }

      // Set english model as default
      currentModel = models.get("en-GB");
      if (currentModel != null) {
        isModelLoaded = true;
        Logger.info(getLogTag(), "Models loaded successfully");
      } else {
        Logger.error(getLogTag() + " No models available");
      }

    } catch (Exception e) {
      Logger.error(getLogTag() + " Failed to load models: " + e.getMessage(), e);
      isModelLoaded = false;
    }
  }

  private String getModelPath(Context context, String modelName) {
    try {
      // Models are in assets/models/
      File modelsDir = new File(context.getFilesDir(), "models");
      File modelDir = new File(modelsDir, modelName);

      // If the model has not yet been copied from assets, copy it
      if (!modelDir.exists()) {
        copyModelFromAssets(context, modelName, modelDir);
      }

      // Verify that the model is valid
      if (modelDir.exists() && isValidModelDirectory(modelDir)) {
        return modelDir.getAbsolutePath();
      }

      return null;
    } catch (Exception e) {
      Logger.error(getLogTag() + " Error getting model path: " + e.getMessage(), e);
      return null;
    }
  }

  private void copyModelFromAssets(Context context, String modelName, File destDir) throws IOException {
    String assetsPath = "models/" + modelName;
    destDir.mkdirs();

    try {
      String[] files = context.getAssets().list(assetsPath);
      if (files == null || files.length == 0) {
        Logger.error(getLogTag() + " Model not found in assets: " + assetsPath);
        return;
      }

      copyAssetFolder(context, assetsPath, destDir.getAbsolutePath());
      Logger.info(getLogTag(), "Model copied from assets: " + modelName);

    } catch (IOException e) {
      Logger.error(getLogTag() + " Failed to copy model from assets: " + e.getMessage(), e);
      throw e;
    }
  }

  private void copyAssetFolder(Context context, String srcPath, String destPath) throws IOException {
    String[] assets = context.getAssets().list(srcPath);
    if (assets == null) return;

    File destDir = new File(destPath);
    if (!destDir.exists()) {
      destDir.mkdirs();
    }

    for (String asset : assets) {
      String fullSrcPath = srcPath + "/" + asset;
      String fullDestPath = destPath + "/" + asset;

      // Controlla se è una cartella
      String[] subAssets = context.getAssets().list(fullSrcPath);
      if (subAssets != null && subAssets.length > 0) {
        // È una cartella, ricorsione
        copyAssetFolder(context, fullSrcPath, fullDestPath);
      } else {
        // È un file, copialo
        copyAssetFile(context, fullSrcPath, fullDestPath);
      }
    }
  }

  private void copyAssetFile(Context context, String srcPath, String destPath) throws IOException {
    java.io.InputStream in = null;
    java.io.OutputStream out = null;

    try {
      in = context.getAssets().open(srcPath);
      File outFile = new File(destPath);
      out = new java.io.FileOutputStream(outFile);

      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
    } finally {
      if (in != null) in.close();
      if (out != null) out.close();
    }
  }

  private boolean isValidModelDirectory(File dir) {
    // Verify that the directory contains the necessary files for a Vosk model
    File amFile = new File(dir, "am/final.mdl");
    File graphFile = new File(dir, "graph/HCLr.fst");

    return amFile.exists() && graphFile.exists();
  }

  private String getLanguageCode(String language) {
    if (language == null) return "en-GB";

    // Normalize the language code
    if (language.startsWith("en")) return "en-GB";
    if (language.startsWith("it")) return "it-IT";

    return "en-GB"; // Default
  }

  @PluginMethod
  public void available(PluginCall call) {
    Logger.info(getLogTag(), "Called for available(): " + isModelLoaded);
    JSObject result = new JSObject();
    result.put("available", isModelLoaded);
    result.put("languagesLoaded", new JSArray(models.keySet()));
    call.resolve(result);
  }

  @PluginMethod
  public void start(PluginCall call) {
    if (!isModelLoaded) {
      call.reject("Models not loaded yet. Please wait.");
      return;
    }

    if (getPermissionState(SPEECH_RECOGNITION) != PermissionState.GRANTED) {
      call.reject(MISSING_PERMISSION);
      return;
    }

    String language = call.getString("language", "it-IT");
    boolean partialResults = call.getBoolean("partialResults", true);

    // Change model if necessary
    String langCode = getLanguageCode(language);
    if (!langCode.equals(currentLanguage)) {
      Model newModel = models.get(langCode);
      if (newModel != null) {
        currentModel = newModel;
        currentLanguage = langCode;
        Logger.info(getLogTag(), "Switched to language: " + langCode);
      } else {
        Logger.warn(getLogTag(), "Model not available for: " + langCode + ", using current");
      }
    }

    beginListening(partialResults, call);
  }

  @PluginMethod
  public void stop(final PluginCall call) {
    try {
      stopListening();
      call.resolve();
    } catch (Exception ex) {
      call.reject(ex.getLocalizedMessage());
    }
  }

  @PluginMethod
  public void isListening(PluginCall call) {
    call.resolve(new JSObject().put("listening", this.listening));
  }

  @PluginMethod
  public void getSupportedLanguages(PluginCall call) {
    JSONArray languages = new JSONArray();
    for (String lang : models.keySet()) {
      languages.put(lang);
    }
    call.resolve(new JSObject().put("languages", languages));
  }

  private void beginListening(boolean partialResults, PluginCall call) {
    Logger.info(getLogTag(), "Beginning continuous listening with Vosk");

    try {
      lock.lock();

      if (speechService != null) {
        speechService.stop();
        speechService.shutdown();
        speechService = null;
      }

      // Reset accumulated text when starting new session
      accumulatedText = "";
      lastPartialResult = "";

      if (currentModel == null) {
        throw new Exception("No model available");
      }

      Recognizer recognizer = new Recognizer(currentModel, SAMPLE_RATE);

      // Enable partial results if required
      if (partialResults) {
        recognizer.setMaxAlternatives(1);
        recognizer.setWords(true);
      }

      speechService = new SpeechService(recognizer, SAMPLE_RATE);

      speechService.startListening(new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
          if (hypothesis == null || hypothesis.isEmpty()) return;

          try {
            JSONObject json = new JSONObject(hypothesis);
            String partial = json.optString("partial", "");

            if (!partial.isEmpty() && !partial.equals(lastPartialResult)) {
              lastPartialResult = partial;

              mainHandler.post(() -> {
                JSObject ret = new JSObject();
                JSArray matches = new JSArray();

                // Combines the accumulated text with the current partial result
                String fullText = accumulatedText.isEmpty()
                        ? partial
                        : accumulatedText + " " + partial;

                matches.put(fullText);
                ret.put("matches", matches);
                notifyListeners("partialResults", ret);
              });
            }
          } catch (Exception e) {
            Logger.error(getLogTag() + " Error parsing partial result: " + e.getMessage());
          }
        }

        @Override
        public void onResult(String hypothesis) {
          if (hypothesis == null || hypothesis.isEmpty()) return;

          try {
            JSONObject json = new JSONObject(hypothesis);
            // Navigate the structure: alternatives[0].text
            String pText = "";
            if (json.has("alternatives")) {
              JSONArray alternatives = json.getJSONArray("alternatives");
              if (alternatives.length() > 0) {
                JSONObject firstAlternative = alternatives.getJSONObject(0);
                pText = firstAlternative.optString("text", "");
              }
            }

            String text = pText;

            if (!text.isEmpty()) {
              mainHandler.post(() -> {
                // Add the completed text to the accumulator
                if (!accumulatedText.isEmpty()) {
                  accumulatedText += " " + text;
                } else {
                  accumulatedText = text;
                }

                // Reset partial result because it has been completed
                lastPartialResult = "";

                JSObject ret = new JSObject();
                JSArray matches = new JSArray();
                matches.put(accumulatedText);
                ret.put("matches", matches);
                ret.put("status", "result");
                notifyListeners("partialResults", ret);
              });
            }
          } catch (Exception e) {
            Logger.error(getLogTag() + " Error parsing result: " + e.getMessage());
          }
        }

        @Override
        public void onFinalResult(String hypothesis) {
          if (hypothesis == null || hypothesis.isEmpty()) return;

          try {
            JSONObject json = new JSONObject(hypothesis);
            String text = json.optString("text", "");

            mainHandler.post(() -> {
              // Also add the final result to the accumulator
              if (!text.isEmpty()) {
                if (!accumulatedText.isEmpty()) {
                  accumulatedText += " " + text;
                } else {
                  accumulatedText = text;
                }
              }

              JSObject ret = new JSObject();
              JSArray matches = new JSArray();
              matches.put(accumulatedText);
              ret.put("matches", matches);
              ret.put("status", "success");

              if (call != null && !partialResults) {
                call.resolve(ret);
              } else {
                notifyListeners("partialResults", ret);
              }
            });
          } catch (Exception e) {
            Logger.error(getLogTag() + " Error parsing final result: " + e.getMessage());
          }
        }

        @Override
        public void onError(Exception exception) {
          Logger.error(getLogTag() + " Recognition error: " + exception.getMessage());

          mainHandler.post(() -> {
            if (call != null) {
              call.reject("Recognition error: " + exception.getMessage());
            }

            JSObject ret = new JSObject();
            ret.put("status", "error");
            ret.put("message", exception.getMessage());
            notifyListeners(LISTENING_EVENT, ret);
          });

          stopListening();
        }

        @Override
        public void onTimeout() {
          Logger.info(getLogTag(), "Recognition timeout - but continuing...");
          // With Vosk we can continue listening even after timeout
        }
      });

      listening = true;

      mainHandler.post(() -> {
        JSObject ret = new JSObject();
        ret.put("status", "started");
        notifyListeners(LISTENING_EVENT, ret);

        if (partialResults && call != null) {
          call.resolve();
        }
      });

    } catch (Exception ex) {
      Logger.error(getLogTag() + " Error starting recognition: " + ex.getMessage(), ex);
      if (call != null) {
        call.reject(ex.getMessage());
      }
    } finally {
      lock.unlock();
    }
  }

  private void stopListening() {
    try {
      lock.lock();

      if (listening && speechService != null) {
        speechService.stop();
        listening = false;
        lastPartialResult = "";
        // Don't reset accumulatedText here, so the last text remains available

        mainHandler.post(() -> {
          JSObject ret = new JSObject();
          ret.put("status", "stopped");
          notifyListeners(LISTENING_EVENT, ret);
        });
      }
    } catch (Exception ex) {
      Logger.error(getLogTag() + " Error stopping recognition: " + ex.getMessage());
    } finally {
      lock.unlock();
    }
  }

  @Override
  protected void handleOnDestroy() {
    if (speechService != null) {
      speechService.stop();
      speechService.shutdown();
      speechService = null;
    }

    // Release the models
    for (Model model : models.values()) {
      try {
        model.close();
      } catch (Exception e) {
        Logger.error(getLogTag() + " Error closing model: " + e.getMessage());
      }
    }
    models.clear();

    super.handleOnDestroy();
  }
}
