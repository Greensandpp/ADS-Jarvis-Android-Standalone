package com.adsjarvis.standalone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

public final class OrbView extends View {
    private final Paint paint = new Paint(3);
    private boolean listening = false;
    private long start = System.currentTimeMillis();
    public OrbView(Context context) { super(context); }
    public void setListening(boolean isListening) { listening = isListening; invalidate(); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.27f;
        double t=(System.currentTimeMillis()-start)/1000.0;
        for(int i=5;i>=0;i--) {
            float radius=(float)(r*(.65+i*.15)+Math.sin(t*2+i*.6)*5);
            int alpha=Math.max(10,82-i*12);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.7f+i*.55f);
            paint.setColor(android.graphics.Color.argb(alpha,listening?70:40, listening?240:165,255));
            c.drawCircle(x,y,radius,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(android.graphics.Color.rgb(listening?35:18,listening?165:104,220));
        c.drawCircle(x,y,(float)(r*.7+Math.sin(t*3)*4),paint);
        paint.setColor(android.graphics.Color.rgb(170,235,255));
        c.drawCircle(x-r*.18f,y-r*.2f,r*.16f,paint);
        postInvalidateDelayed(45);
    }
}
