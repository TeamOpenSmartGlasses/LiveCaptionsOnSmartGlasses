package com.mentra.livecaptions;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.teamopensmartglasses.augmentoslib.AugmentOSLib;
import com.teamopensmartglasses.augmentoslib.AugmentOSSettingsManager;
import com.teamopensmartglasses.augmentoslib.DataStreamType;
import com.teamopensmartglasses.augmentoslib.SmartGlassesAndroidService;
import com.teamopensmartglasses.augmentoslib.SpeechRecUtils;
import com.teamopensmartglasses.augmentoslib.events.SpeechRecOutputEvent;
import com.teamopensmartglasses.augmentoslib.events.StartAsrStreamRequestEvent;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.EventBusException;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    private DisplayQueue displayQueue;

    private Handler transcribeLanguageCheckHandler;
    private String lastTranscribeLanguage = null;
    private final int maxNormalTextCharsPerTranscript = 30;
    private final int maxCharsPerHanziTranscript = 12;

    private final TranscriptProcessor normalTextTranscriptProcessor = new TranscriptProcessor(maxNormalTextCharsPerTranscript);
    private final TranscriptProcessor hanziTextTranscriptProcessor = new TranscriptProcessor(maxCharsPerHanziTranscript);
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

        // Create AugmentOSLib instance with context: this
        augmentOSLib = new AugmentOSLib(this);

        // Subscribe to a data stream (ex: transcription), and specify a callback function
        // Initialize the language check handler
        transcribeLanguageCheckHandler = new Handler(Looper.getMainLooper());

        // Start periodic language checking
        startTranscribeLanguageCheckTask();

        //setup event bus subscribers
//        setupEventBusSubscribers();

        displayQueue = new DisplayQueue();

        //make responses holder
        responsesBuffer = new ArrayList<>();
        responsesToShare = new ArrayList<>();
        responsesBuffer.add("Welcome to AugmentOS.");

        //make responses holder
        transcriptsBuffer = new ArrayList<>();

        Log.d(TAG, "Convoscope service started");



        completeInitialization();

    }

//    protected void setupEventBusSubscribers() {
//        EventBus eventBus = EventBus.getDefault();
//        if (!eventBus.isRegistered(this)) {
//            try {
//                eventBus.register(this);
//            } catch (EventBusException e) {
//                Log.w("EventBus", "Subscriber already registered: " + e.getMessage());
//            }
//        }
//    }

    public void processTranscriptionCallback(String transcript, String languageCode, long timestamp, boolean isFinal) {
        Log.d(TAG, "Got a transcript: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);

    }

    public void processTranslationCallback(String transcript, String languageCode, long timestamp, boolean isFinal, boolean foo) {
        Log.d(TAG, "Got a translation: " + transcript + ", which is FINAL? " + isFinal + " and has language code: " + languageCode);
    }

    public void completeInitialization(){
        Log.d(TAG, "COMPLETE CONVOSCOPE INITIALIZATION");

        displayQueue.startQueue();
    }

    @Override
    public void onDestroy(){
        Log.d(TAG, "onDestroy: Called");
        Log.d(TAG, "Deinit augmentOSLib");
        augmentOSLib.deinit();
        Log.d(TAG, "csePoll handler remove");
        Log.d(TAG, "displayPoll handler remove");
        Log.d(TAG, "debugTranscriptsHnalderPoll handler remove");
        if (debugTranscriptsRunning) {
            debugTranscriptsHandler.removeCallbacksAndMessages(null);
        }
        Log.d(TAG, "locationSystem remove");
        EventBus.getDefault().unregister(this);

        if (displayQueue != null) displayQueue.stopQueue();

        Log.d(TAG, "ran onDestroy");
        super.onDestroy();
    }

    @Subscribe
    public void onTranscript(SpeechRecOutputEvent event) {
        String text = event.text;
        String languageCode = event.languageCode;
        long time = event.timestamp;
        boolean isFinal = event.isFinal;

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
                displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
            } else if (!hasUserBeenNotified) {  //tell user we are loading the pinyin converter
                hasUserBeenNotified = true;
                displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendTextWall("Loading Pinyin Converter, Please Wait..."), true, false, false));
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
            if (getChosenTranscribeLanguage(this).equals("Chinese (Hanzi)") ||
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

        displayQueue.addTask(new DisplayQueue.Task(() -> augmentOSLib.sendDoubleTextWall(finalLiveCaptionString, ""), true, false, true));
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
                        augmentOSLib.stopTranscription(SpeechRecUtils.languageToLocale(lastTranscribeLanguage));
                    }
                    augmentOSLib.requestTranscription(SpeechRecUtils.languageToLocale(currentTranscribeLanguage));
                    finalLiveCaption = "";
                    lastTranscribeLanguage = currentTranscribeLanguage;
                }

                // Schedule the next check
                transcribeLanguageCheckHandler.postDelayed(this, 333); // Approximately 3 times a second
            }
        }, 200);
    }

    public static class TranscriptProcessor {

        private final int maxCharsPerLine;
        private final int maxLines = 3;

        private Deque<String> lines;

        private String partialText;

        public TranscriptProcessor(int maxCharsPerLine) {
            this.maxCharsPerLine = maxCharsPerLine;
            this.lines = new ArrayDeque<>();
            this.partialText = "";
        }

        public String processString(String newText, boolean isFinal) {
            newText = (newText == null) ? "" : newText.trim();

            if (!isFinal) {
                // Store this as the current partial text (overwriting old partial)
                partialText = newText;
                return buildPreview(partialText);
            } else {
                // We have a final text -> clear out the partial text to avoid duplication
                partialText = "";

                // Wrap this final text
                List<String> wrapped = wrapText(newText, maxCharsPerLine);
                for (String chunk : wrapped) {
                    appendToLines(chunk);
                }

                // Return only the finalized lines
                return getTranscript();
            }
        }

        private String buildPreview(String partial) {
            // Wrap the partial text
            List<String> partialChunks = wrapText(partial, maxCharsPerLine);

            // Combine with finalized lines
            List<String> combined = new ArrayList<>(lines);
            combined.addAll(partialChunks);

            // Truncate if necessary
            if (combined.size() > maxLines) {
                combined = combined.subList(combined.size() - maxLines, combined.size());
            }

            // Add padding to ensure exactly maxLines are displayed
            int linesToPad = maxLines - combined.size();
            for (int i = 0; i < linesToPad; i++) {
                combined.add(""); // Add empty lines at the end
            }

            return String.join("\n", combined);
        }

        private void appendToLines(String chunk) {
            if (lines.isEmpty()) {
                lines.addLast(chunk);
            } else {
                String lastLine = lines.removeLast();
                String candidate = lastLine.isEmpty() ? chunk : lastLine + " " + chunk;

                if (candidate.length() <= maxCharsPerLine) {
                    lines.addLast(candidate);
                } else {
                    // Put back the last line if it doesn't fit
                    lines.addLast(lastLine);
                    lines.addLast(chunk);
                }
            }

            // Ensure we don't exceed maxLines
            while (lines.size() > maxLines) {
                lines.removeFirst();
            }
        }

        private List<String> wrapText(String text, int maxLineLength) {
            List<String> result = new ArrayList<>();
            while (!text.isEmpty()) {
                if (text.length() <= maxLineLength) {
                    result.add(text);
                    break;
                } else {
                    int splitIndex = maxLineLength;
                    // move splitIndex left until we find a space
                    while (splitIndex > 0 && text.charAt(splitIndex) != ' ') {
                        splitIndex--;
                    }
                    // If we didn't find a space, force split
                    if (splitIndex == 0) {
                        splitIndex = maxLineLength;
                    }

                    String chunk = text.substring(0, splitIndex).trim();
                    result.add(chunk);
                    text = text.substring(splitIndex).trim();
                }
            }
            return result;
        }

        public String getTranscript() {
            // Create a copy of the lines for manipulation
            List<String> allLines = new ArrayList<>(lines);

            // Add padding to ensure exactly maxLines are displayed
            int linesToPad = maxLines - allLines.size();
            for (int i = 0; i < linesToPad; i++) {
                allLines.add(""); // Add empty lines at the end
            }

            String finalString = String.join("\n", allLines);

            lines.clear();

            return finalString;
        }

        public void clear() {
            lines.clear();
            partialText = "";
        }

        public int getMaxCharsPerLine() {
            return maxCharsPerLine;
        }

        public int getMaxLines() {
            return maxLines;
        }
    }
}