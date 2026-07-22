package com.weathersky.live;

import android.graphics.*;
import android.os.Handler;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;
import java.util.*;

public class WeatherWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new SkyEngine(); }

    private final class SkyEngine extends Engine {
        private final Handler handler = new Handler();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private final ArrayList<Drop> drops = new ArrayList<>();
        private boolean visible;
        private int width, height;
        private float cloudOffset;
        private final Runnable frame = this::drawFrame;

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = w; height = h; drops.clear();
            for (int i = 0; i < 90; i++) drops.add(new Drop(random.nextFloat()*w, random.nextFloat()*h, 12+random.nextFloat()*18));
            drawFrame();
        }
        @Override public void onVisibilityChanged(boolean value) { visible = value; if (value) drawFrame(); else handler.removeCallbacks(frame); }
        @Override public void onSurfaceDestroyed(SurfaceHolder holder) { visible = false; handler.removeCallbacks(frame); }

        private void drawFrame() {
            Canvas c = null;
            try {
                c = getSurfaceHolder().lockCanvas();
                if (c == null) return;
                long hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                boolean night = hour < 6 || hour >= 20;
                int top = night ? Color.rgb(8,15,40) : Color.rgb(70,145,240);
                int bottom = night ? Color.rgb(35,53,90) : Color.rgb(190,225,255);
                paint.setShader(new LinearGradient(0,0,0,height,top,bottom,Shader.TileMode.CLAMP));
                c.drawRect(0,0,width,height,paint); paint.setShader(null);

                paint.setColor(night ? Color.rgb(235,241,255) : Color.rgb(255,230,130));
                c.drawCircle(width*0.78f,height*0.2f,Math.max(46,width*0.07f),paint);
                if (night) {
                    paint.setColor(Color.argb(210,255,255,255));
                    for (int i=0;i<35;i++) {
                        float x=(i*97%1000)/1000f*width, y=(i*47%400)/400f*height*.45f;
                        c.drawCircle(x,y,1+(i%3),paint);
                    }
                }

                cloudOffset = (cloudOffset + 0.7f) % (width + 500);
                drawCloud(c, cloudOffset-300, height*.25f, 1.0f);
                drawCloud(c, (cloudOffset+width*.55f)%(width+500)-250, height*.38f, .72f);

                paint.setStrokeWidth(4); paint.setStrokeCap(Paint.Cap.ROUND); paint.setColor(Color.argb(140,215,235,255));
                for (Drop d : drops) {
                    c.drawLine(d.x,d.y,d.x-5,d.y+22,paint);
                    d.y += d.speed; d.x -= 1.8f;
                    if (d.y>height) { d.y=-30; d.x=random.nextFloat()*width; }
                }
            } finally { if (c != null) getSurfaceHolder().unlockCanvasAndPost(c); }
            handler.removeCallbacks(frame);
            if (visible) handler.postDelayed(frame, 33);
        }

        private void drawCloud(Canvas c,float x,float y,float s) {
            paint.setColor(Color.argb(205,245,248,255));
            c.drawOval(x,y,x+300*s,y+90*s,paint);
            c.drawCircle(x+85*s,y,65*s,paint);
            c.drawCircle(x+165*s,y-22*s,88*s,paint);
            c.drawCircle(x+240*s,y+5*s,58*s,paint);
        }
    }
    private static final class Drop { float x,y,speed; Drop(float x,float y,float speed){this.x=x;this.y=y;this.speed=speed;} }
}
