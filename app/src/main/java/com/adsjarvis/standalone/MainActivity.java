package com.adsjarvis.standalone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 44;
    private static final int BG=0xff030a18, PANEL=0xff0c1a2c, CYAN=0xff68e3ff, MUTED=0xff829bb3;
    private TextView status, reply;
    private EditText input;
    private OrbView orb;
    private SpeechRecognizer speech;
    private TextToSpeech tts;
    private boolean readyToSpeak=false;
    private String speechLocale="en-IN";

    private int dp(float value) { return (int)(value*getResources().getDisplayMetrics().density+.5f); }
    private GradientDrawable background(int fill,int stroke,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));
        if(stroke!=0)d.setStroke(dp(1),stroke);return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout vertical(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private void add(LinearLayout parent,View child,int height){parent.addView(child,new LinearLayout.LayoutParams(-1,height<0?height:dp(height)));}
    private Button button(String name,int fill,int fg){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(fg);
        b.setBackgroundTintList(null);b.setBackground(background(fill,fill==PANEL?0xff27516b:0,16));
        b.setMinHeight(dp(52));return b;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout screen=vertical();screen.setPadding(dp(22),dp(18),dp(22),dp(24));
        scroll.addView(screen);setContentView(scroll);

        TextView eyebrow=text("ALPHA DIGITRONIX   /   PRIVATE PROTOTYPE",10,MUTED,true);
        eyebrow.setLetterSpacing(.15f);eyebrow.setGravity(Gravity.CENTER);add(screen,eyebrow,-2);

        TextView header=text("J A R V I S",30,Color.WHITE,true);
        header.setGravity(Gravity.CENTER);header.setPadding(0,dp(12),0,dp(4));add(screen,header,-2);

        TextView subtitle=text("STANDALONE  •  ANDROID  •  v0.4",11,CYAN,true);
        subtitle.setGravity(Gravity.CENTER);add(screen,subtitle,-2);

        orb=new OrbView(this);
        add(screen,orb,310);

        status=text("SYSTEM ONLINE   /   READY TO LISTEN",12,CYAN,true);
        status.setGravity(Gravity.CENTER);status.setLetterSpacing(.08f);
        add(screen,status,-2);

        TextView hint=text("Tap the microphone and speak naturally",13,MUTED,false);
        hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(10),0,dp(20));
        add(screen,hint,-2);

        Button mic=button("●  Start talking",0xff037bb0,Color.WHITE);
        add(screen,mic,56);mic.setOnClickListener(v->startListening());

        Button language=button("English (India)    ·    Change language",PANEL,CYAN);
        LinearLayout.LayoutParams languageParams=new LinearLayout.LayoutParams(-1,dp(51));
        languageParams.topMargin=dp(12);screen.addView(language,languageParams);
        language.setOnClickListener(v->{
            speechLocale=speechLocale.equals("en-IN")?"ta-IN":"en-IN";
            language.setText(speechLocale.equals("ta-IN")?"தமிழ் / Tamil    ·    Change language":"English (India)    ·    Change language");
            status.setText("VOICE LANGUAGE   /   "+speechLocale);
            if(readyToSpeak)tts.setLanguage(Locale.forLanguageTag(speechLocale));
        });

        LinearLayout card=vertical();card.setPadding(dp(17),dp(16),dp(17),dp(16));
        card.setBackground(background(PANEL,0xff1d435e,20));
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);
        cardParams.topMargin=dp(24);screen.addView(card,cardParams);
        TextView cardTitle=text("CONVERSATION",11,CYAN,true);
        cardTitle.setLetterSpacing(.1f);add(card,cardTitle,-2);
        reply=text("Say hello, ask me to remember something, or open Settings.",15,Color.WHITE,false);
        reply.setPadding(0,dp(12),0,dp(4));add(card,reply,-2);

        TextView inputTitle=text("TYPE A COMMAND",11,MUTED,true);
        inputTitle.setPadding(0,dp(22),0,dp(9));add(screen,inputTitle,-2);
        input=new EditText(this);input.setTextSize(15);input.setSingleLine(false);
        input.setHint("Message Jarvis...");input.setTextColor(Color.WHITE);
        input.setHintTextColor(MUTED);input.setPadding(dp(15),dp(8),dp(15),dp(8));
        input.setBackground(background(PANEL,0xff21445d,14));add(screen,input,56);

        Button send=button("Send message",PANEL,CYAN);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(-1,dp(49));
        sendParams.topMargin=dp(10);screen.addView(send,sendParams);
        send.setOnClickListener(v->{String q=input.getText().toString().trim();
            if(!q.isEmpty()){handle(q);input.setText("");}});
        TextView foot=text("ON-DEVICE DEMO  •  CLOUD AI NOT CONNECTED\nNOT LINKED TO GREENSOIL SERVICES",10,MUTED,false);
        foot.setGravity(Gravity.CENTER);foot.setPadding(0,dp(22),0,0);add(screen,foot,-2);

        tts=new TextToSpeech(this,s->{readyToSpeak=s==TextToSpeech.SUCCESS;
            if(readyToSpeak)tts.setLanguage(new Locale("en","IN"));});
    }

    private void startListening(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION);return;
        }
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            status.setText("SPEECH SERVICE UNAVAILABLE");return;
        }
        if(speech!=null){speech.destroy();speech=null;}
        speech=SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle b){status.setText("LISTENING...");orb.setListening(true);}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float value){}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){orb.setListening(false);status.setText("PROCESSING VOICE...");}
            public void onError(int e){orb.setListening(false);status.setText("VOICE ERROR "+e+"  /  RETRY");}
            public void onResults(Bundle b){orb.setListening(false);
                ArrayList<String> v=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(v!=null&&!v.isEmpty())handle(v.get(0));
            }
            public void onPartialResults(Bundle b){}
            public void onEvent(int type,Bundle b){}
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,speechLocale);
        speech.startListening(i);
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==MIC_PERMISSION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startListening();
        else if(code==MIC_PERMISSION)status.setText("MICROPHONE PERMISSION REQUIRED");
    }
    private void handle(String command){
        CommandEngine.Result result=CommandEngine.run(command,getPreferences(MODE_PRIVATE).getString("memory",""));
        String answer=result.response;
        if(result.action==CommandEngine.Action.SAVE_MEMORY)
            getPreferences(MODE_PRIVATE).edit().putString("memory",result.memoryValue).apply();
        if(result.action==CommandEngine.Action.CLEAR_MEMORY)
            getPreferences(MODE_PRIVATE).edit().remove("memory").apply();
        if(result.action==CommandEngine.Action.OPEN_SETTINGS){
            try{startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));}
            catch(Exception e){answer="Unable to open Settings on this device.";}
        }
        status.setText("INPUT RECEIVED  /  "+speechLocale);
        reply.setText("YOU\n"+command+"\n\nJARVIS\n"+answer);
        if(readyToSpeak)tts.speak(answer,TextToSpeech.QUEUE_FLUSH,null,"jarvis-reply");
    }
    @Override protected void onDestroy(){
        if(speech!=null)speech.destroy();
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}
