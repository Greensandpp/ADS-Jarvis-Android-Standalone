package com.adsjarvis.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION=44;
    private TextView status, reply;
    private EditText input;
    private OrbView orb;
    private SpeechRecognizer speech;
    private TextToSpeech tts;
    private boolean readyToSpeak=false;
    private String speechLocale="en-IN";
    private int cyan=Color.rgb(102,218,255);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(4,9,22));
        getWindow().setNavigationBarColor(Color.rgb(4,9,22));
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(32,32,32,24); body.setBackgroundColor(Color.rgb(4,9,22));
        ScrollView page=new ScrollView(this);page.addView(body);setContentView(page);
        TextView title=new TextView(this);title.setText("ADS · JARVIS");title.setTextSize(27);
        title.setTypeface(null,Typeface.BOLD);title.setTextColor(cyan);title.setGravity(Gravity.CENTER);
        body.addView(title);
        TextView sub=new TextView(this);sub.setText("STANDALONE ANDROID PROTOTYPE  •  NO GREENSOIL CONNECTION");
        sub.setTextSize(10);sub.setTextColor(Color.LTGRAY);sub.setGravity(Gravity.CENTER);body.addView(sub);
        orb=new OrbView(this);body.addView(orb,new LinearLayout.LayoutParams(-1,290));
        status=new TextView(this); status.setText("Ready · Tap microphone to talk");
        status.setTextColor(cyan);status.setGravity(Gravity.CENTER);status.setPadding(0,0,0,24);body.addView(status);
        Button mic=button("🎙  Speak",body);mic.setOnClickListener(v->startListening());
        Button language=button("Language: English (India) · Tap for Tamil",body);
        language.setOnClickListener(v->{speechLocale=speechLocale.equals("en-IN")?"ta-IN":"en-IN";language.setText(speechLocale.equals("ta-IN")?"Language: Tamil · Tap for English":"Language: English (India) · Tap for Tamil");status.setText("Recognition language: "+speechLocale);if(readyToSpeak)tts.setLanguage(Locale.forLanguageTag(speechLocale));});
        input=new EditText(this);input.setSingleLine(false);input.setMinLines(1);input.setHint("Type a command...");
        input.setTextColor(Color.WHITE);input.setHintTextColor(Color.GRAY);body.addView(input);
        Button send=button("Send command",body);send.setOnClickListener(v->{String s=input.getText().toString().trim();if(!s.isEmpty()){handle(s);input.setText("");}});
        reply=new TextView(this);reply.setTextColor(Color.WHITE);reply.setTextSize(16);
        reply.setPadding(8,30,8,10);reply.setText("Try: 'hello', 'remember my favorite color is blue', 'what do you remember', 'open settings'.");body.addView(reply);
        tts=new TextToSpeech(this,s->{readyToSpeak=s==TextToSpeech.SUCCESS;if(readyToSpeak)tts.setLanguage(new Locale("en","IN"));});
    }
    private Button button(String text,LinearLayout root){Button b=new Button(this);b.setText(text);root.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private void startListening(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION);return;}
        if(!SpeechRecognizer.isRecognitionAvailable(this)){status.setText("Speech recognition service unavailable");return;}
        if(speech!=null){speech.destroy();speech=null;}
        speech=SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){status.setText("Listening...");orb.setListening(true);}
            public void onBeginningOfSpeech(){} public void onRmsChanged(float v){} public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){orb.setListening(false);status.setText("Processing...");}
            public void onError(int e){orb.setListening(false);status.setText("Mic error "+e+" · Try again");}
            public void onResults(Bundle b){orb.setListening(false);ArrayList<String> list=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(list!=null&&!list.isEmpty())handle(list.get(0));}
            public void onPartialResults(Bundle b){} public void onEvent(int e,Bundle b){}
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,speechLocale);
        speech.startListening(i);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==MIC_PERMISSION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startListening();else status.setText("Microphone permission required for voice input");}
    private void handle(String cmd){
        CommandEngine.Result result=CommandEngine.run(cmd,getPreferences(MODE_PRIVATE).getString("memory",""));
        String answer=result.response;
        if(result.action==CommandEngine.Action.SAVE_MEMORY)getPreferences(MODE_PRIVATE).edit().putString("memory",result.memoryValue).apply();
        if(result.action==CommandEngine.Action.CLEAR_MEMORY)getPreferences(MODE_PRIVATE).edit().remove("memory").apply();
        if(result.action==CommandEngine.Action.OPEN_SETTINGS){try{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}catch(Exception e){answer="Unable to open Settings on this device.";}}
        status.setText("You: "+cmd);reply.setText(answer);
        if(readyToSpeak)tts.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"ads-jarvis-reply");
    }
    @Override protected void onDestroy(){if(speech!=null)speech.destroy();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
