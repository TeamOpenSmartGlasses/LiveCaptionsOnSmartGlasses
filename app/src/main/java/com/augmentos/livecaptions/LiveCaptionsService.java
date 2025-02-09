package com.augmentos.livecaptions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.augmentos.augmentoslib.TranscriptProcessor;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.augmentos.augmentoslib.AugmentOSLib;
import com.augmentos.augmentoslib.AugmentOSSettingsManager;
import com.augmentos.augmentoslib.SmartGlassesAndroidService;
import com.augmentos.augmentoslib.events.SpeechRecOutputEvent;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.List;

public class LiveCaptionsService extends SmartGlassesAndroidService {
    public static final String TAG = "LiveCaptionsService";

    public AugmentOSLib augmentOSLib;
    ArrayList<String> responsesBuffer;
    ArrayList<String> transcriptsBuffer;
    ArrayList<String> responsesToShare;
    Handler debugTranscriptsHandler = new Handler(Looper.getMainLooper());
    private boolean debugTranscriptsRunning = false;

    private boolean segmenterLoaded = false;
    private boolean segmenterLoading = false;
    private boolean hasUserBeenNotified = false;

    private Handler transcribeLanguageCheckHandler;
    private String lastTranscribeLanguage = null;
    private final int maxNormalTextCharsPerTranscript = 30;
    private final int maxCharsPerHanziTranscript = 12;
    private final int maxLines = 3;

    private final TranscriptProcessor normalTextTranscriptProcessor = new TranscriptProcessor(maxNormalTextCharsPerTranscript, maxLines);
    private final TranscriptProcessor hanziTextTranscriptProcessor = new TranscriptProcessor(maxCharsPerHanziTranscript, maxLines);
    private String currentLiveCaption = "";
    private String finalLiveCaption = "";
    private final Handler callTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    public LiveCaptionsService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void setup() {
        // Create AugmentOSLib instance with context: this
        augmentOSLib = new AugmentOSLib(this);

        // Subscribe to a data stream (ex: transcription), and specify a callback function
        // Initialize the language check handler
        transcribeLanguageCheckHandler = new Handler(Looper.getMainLooper());

        // Start periodic language checking
        startTranscribeLanguageCheckTask();

        //setup event bus subscribers
//        setupEventBusSubscribers();

        //make responses holder
        responsesBuffer = new ArrayList<>();
        responsesToShare = new ArrayList<>();
        responsesBuffer.add("Welcome to AugmentOS.");

        //make responses holder
        transcriptsBuffer = new ArrayList<>();

        Log.d(TAG, "Convoscope service started");

        completeInitialization();
    }

    public void processTranscriptionCallback(String transcript, String languageCode, long timestamp, boolean isFinal) {
        Log.d(TAG, "Got a transcript: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);

    }

    public void processTranslationCallback(String transcript, String languageCode, long timestamp, boolean isFinal, boolean foo) {
        Log.d(TAG, "Got a translation: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);
    }

    public void completeInitialization(){
        Log.d(TAG, "COMPLETE CONVOSCOPE INITIALIZATION");
    }

    @Override
    public void onDestroy(){
        Log.d(TAG, "onDestroy: Called");
        augmentOSLib.deinit();
        if (debugTranscriptsRunning) {
            debugTranscriptsHandler.removeCallbacksAndMessages(null);
        }
        Log.d(TAG, "ran onDestroy");
        super.onDestroy();
    }

    @Subscribe
    public void onTranscript(SpeechRecOutputEvent event) {
        String text = event.text;
        String languageCode = event.languageCode;
        long time = event.timestamp;
        boolean isFinal = event.isFinal;

        if (isFinal){
            Log.d(TAG, "Live Captions got final: " + text);
        }

        debounceAndShowTranscriptOnGlasses(text, isFinal);
    }

    private final Handler glassesTranscriptDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable glassesTranscriptDebounceRunnable;
    private long glassesTranscriptLastSentTime = 0;
    private final long GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY = 400; // in milliseconds

    private void debounceAndShowTranscriptOnGlasses(String transcript, boolean isFinal) {
        glassesTranscriptDebounceHandler.removeCallbacks(glassesTranscriptDebounceRunnable);
        long currentTime = System.currentTimeMillis();

        if (isFinal) {
            showTranscriptsToUser(transcript, true);
            return;
        }

        if (currentTime - glassesTranscriptLastSentTime >= GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY) {
            showTranscriptsToUser(transcript, false);
            glassesTranscriptLastSentTime = currentTime;
        } else {
            glassesTranscriptDebounceRunnable = () -> {
                showTranscriptsToUser(transcript, false);
                glassesTranscriptLastSentTime = System.currentTimeMillis();
            };
            glassesTranscriptDebounceHandler.postDelayed(glassesTranscriptDebounceRunnable, GLASSES_TRANSCRIPTS_DEBOUNCE_DELAY);
        }
    }

    private void showTranscriptsToUser(final String transcript, final boolean isFinal) {
        String processed_transcript = transcript;

        if (getChosenTranscribeLanguage(this).equals("Chinese (Pinyin)")) {
            if(segmenterLoaded) {
                processed_transcript = convertToPinyin(transcript);
            } else if (!segmenterLoading) {
                new Thread(this::loadSegmenter).start();
                hasUserBeenNotified = true;
                augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait...");
            } else if (!hasUserBeenNotified) {  //tell user we are loading the pinyin converter
                hasUserBeenNotified = true;
                augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait...");
            }
        }

        sendTextWallLiveCaptionLL(processed_transcript, isFinal);
    }

    private void loadSegmenter() {
        segmenterLoading = true;
        final JiebaSegmenter segmenter = new JiebaSegmenter();
        segmenterLoaded = true;
        segmenterLoading = false;
//        displayQueue.addTask(new DisplayQueue.Task(() -> sendTextWall("Pinyin Converter Loaded!"), true, false));
    }

    private String convertToPinyin(final String chineseText) {
        final JiebaSegmenter segmenter = new JiebaSegmenter();

        final List<SegToken> tokens = segmenter.process(chineseText, JiebaSegmenter.SegMode.SEARCH);

        final HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);

        StringBuilder pinyinText = new StringBuilder();

        for (SegToken token : tokens) {
            StringBuilder tokenPinyin = new StringBuilder();
            for (char character : token.word.toCharArray()) {
                try {
                    String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(character, format);
                    if (pinyinArray != null) {
                        // Use the first Pinyin representation if there are multiple
                        tokenPinyin.append(pinyinArray[0]);
                    } else {
                        // If character is not a Chinese character, append it as is
                        tokenPinyin.append(character);
                    }
                } catch (BadHanyuPinyinOutputFormatCombination e) {
                    e.printStackTrace();
                }
            }
            // Ensure the token is concatenated with a space only if it's not empty
            if (tokenPinyin.length() > 0) {
                pinyinText.append(tokenPinyin.toString()).append(" ");
            }
        }

        // Replace multiple spaces with a single space, but preserve newlines
        String cleanText = pinyinText.toString().trim().replaceAll("[ \\t]+", " ");  // Replace spaces and tabs only

        return cleanText;
    }

    public void sendTextWallLiveCaptionLL(final String newLiveCaption, final boolean isFinal) {
        callTimeoutHandler.removeCallbacks(timeoutRunnable);

        timeoutRunnable = () -> {
            // Call your desired function here
            augmentOSLib.sendHomeScreen();
        };
        callTimeoutHandler.postDelayed(timeoutRunnable, 16000);

        String textBubble = "\uD83D\uDDE8";

        if (!newLiveCaption.isEmpty()) {
            int maxLen;
            if (
                    getChosenTranscribeLanguage(this).equals("Japanese") ||
                    getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") ||
                    getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") && !segmenterLoaded) {
                maxLen = 40;
                currentLiveCaption = hanziTextTranscriptProcessor.processString(finalLiveCaption + " " + newLiveCaption, isFinal);
            } else {
                maxLen = 100;
                currentLiveCaption = normalTextTranscriptProcessor.processString(finalLiveCaption + " " + newLiveCaption, isFinal);
            }

            if (isFinal) {
                finalLiveCaption = newLiveCaption;
            }

            // Limit the length of the final live caption, in case it gets too long
            if (finalLiveCaption.length() > maxLen) {
                finalLiveCaption = finalLiveCaption.substring(finalLiveCaption.length() - maxLen);
            }
        }

        final String finalLiveCaptionString;
        if (!currentLiveCaption.isEmpty()) {
            finalLiveCaptionString = textBubble + currentLiveCaption;
        } else {
            finalLiveCaptionString = "";
        }

        augmentOSLib.sendDoubleTextWall(finalLiveCaptionString, "");
    }

    public static void saveChosenTranscribeLanguage(Context context, String transcribeLanguageString) {
        Log.d(TAG, "set saveChosenTranscribeLanguage");
        AugmentOSSettingsManager.setStringSetting(context, "transcribe_language", transcribeLanguageString);
    }

    public static String getChosenTranscribeLanguage(Context context) {
        String transcribeLanguageString = AugmentOSSettingsManager.getStringSetting(context, "transcribe_language");
        if (transcribeLanguageString.isEmpty()){
            saveChosenTranscribeLanguage(context, "Chinese");
            transcribeLanguageString = "Chinese";
        }
        return transcribeLanguageString;
    }

    private void startTranscribeLanguageCheckTask() {
        transcribeLanguageCheckHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Get the currently selected transcription language
                String currentTranscribeLanguage = getChosenTranscribeLanguage(getApplicationContext());

                // If the language has changed or this is the first call
                if (lastTranscribeLanguage == null || !lastTranscribeLanguage.equals(currentTranscribeLanguage)) {
                    if (lastTranscribeLanguage != null) {
                        augmentOSLib.stopTranscription(lastTranscribeLanguage);
                    }
                    augmentOSLib.requestTranscription(currentTranscribeLanguage);
                    finalLiveCaption = "";
                    lastTranscribeLanguage = currentTranscribeLanguage;
                }

                // Schedule the next check
                transcribeLanguageCheckHandler.postDelayed(this, 333); // Approximately 3 times a second
            }
        }, 200);
    }
}