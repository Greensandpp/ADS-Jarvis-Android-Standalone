package com.adsjarvis.standalone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

public final class OrbView extends View {
    private final Paint paint = new Paint(3);
    private boolean listening = false;
    private long start = System.currentTimeMillis();

    public OrbView(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    public void setListening(boolean value) { listening = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx=getWidth()/2f, cy=getHeight()/2f;
        float r=Math.min(getWidth(),getHeight())*.285f;
        float t=(System.currentTimeMillis()-start)/1000f;
        int glow=listening?0xff63f7ff:0xff3bcaff;

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x142c91ff);
        canvas.drawCircle(cx,cy,r*1.47f,paint);
        for(int i=0;i<9;i++){
            float rad=r*(1.05f+i*.075f)+(float)Math.sin(t*1.4+i*.47)*3;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(i==0?2.8f:1.25f);
            paint.setColor(Color.argb(Math.max(14,102-i*10),35,181,250));
            canvas.drawCircle(cx,cy,rad,paint);
        }
        for(int i=0;i<3;i++){
            float rad=r*(1.25f+i*.11f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4.5f);
            paint.setColor(Color.argb(180-i*42,54,205,255));
            float rotation=(t*(i%2==0?23:-19))+i*120;
            canvas.drawArc(cx-rad,cy-rad,cx+rad,cy+rad,rotation,46+i*19,false,paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx-r*.21f,cy-r*.30f,r*1.45f,
            new int[]{0xffd7fbff,0xff24cfff,0xff0965d7,0xff071831},
            new float[]{0f,.18f,.55f,1f},Shader.TileMode.CLAMP));
        canvas.drawCircle(cx,cy,r*(.78f+(float)Math.sin(t*2.2f)*.024f),paint);
        paint.setShader(null);
        paint.setColor(0x70eaffff);
        canvas.drawCircle(cx-r*.22f,cy-r*.27f,r*.13f,paint);
        postInvalidateDelayed(45);
    }
}
