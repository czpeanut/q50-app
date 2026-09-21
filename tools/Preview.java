import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/** Desktop port of DashView's geometry so the 800x480 layout can be judged without a car. */
public class Preview {
    static final int RPM=13,COOLANT=14,SPEED=17,GEAR=22,ACCEL=23,BRAKE=24,STEER=25,ODO=43,
        TP_FR=36,TP_FL=37,TP_RR=38,TP_RL=39;
    static final float REDLINE=7000f, COOLANT_MIN=40f, COOLANT_MAX=120f, COOLANT_WARN=105f;
    static final float TPMS_LOW=30f, TPMS_HIGH=44f, STEER_FULL=390f, PEDAL_FULL=90f;
    static final Color BG_TOP=c(0xFF070B14), BG_BOT=c(0xFF0D1522);
    static final Color CYAN=c(0xFF3FD2FF), CYAN_DIM=c(0xFF1B4E66), CYAN_GLOW=c(0x553FD2FF);
    static final Color WHITE=c(0xFFEAF6FF), GREY=c(0xFF5A6B7C);
    static final Color AMBER=c(0xFFFFB020), RED=c(0xFFFF4545), GREEN=c(0xFF46E08A);
    static final Color PANEL=c(0xCC0A1420);
    static Color c(int argb){ return new Color((argb>>16)&255,(argb>>8)&255,argb&255,(argb>>>24)&255); }

    static float[] v=new float[64]; static boolean[] have=new boolean[64];
    static void set(int t,float x){ v[t]=x; have[t]=true; }
    static float g(int t){ return have[t]?v[t]:0f; }
    static boolean h(int t){ return have[t]; }

    static float W=800,H=480;
    static float rpmX,rpmY0,rpmY1,barW,cooX,carCx,carCy,carW,carH,dialCx,dialCy,dialR;
    static Rectangle2D.Float[] tyreBox=new Rectangle2D.Float[4];
    static float[] tdx=new float[4],tdy=new float[4];
    static int[] tyreType={TP_FL,TP_FR,TP_RL,TP_RR};
    static float pedalL,pedalR,pedalY0,pedalY1;
    static Graphics2D G;
    static boolean cjk=true;
    static String FONT="WenQuanYi Zen Hei";

    static void layout(){
        barW=W*0.0625f; rpmX=W*0.0225f; cooX=W-rpmX-barW; rpmY0=H*0.20f; rpmY1=H*0.929f;
        carW=W*0.2375f; carH=H*0.479f; carCx=W*0.5f; carCy=H*0.604f;
        dialR=H*0.0833f; dialCx=W*0.75f; dialCy=H*0.1083f;
        float bw=W*0.1875f,bh=H*0.125f,leftX=W*0.11f,rightX=W*0.7025f,topY=H*0.3646f,botY=H*0.625f;
        tyreBox[0]=new Rectangle2D.Float(leftX,topY,bw,bh);
        tyreBox[1]=new Rectangle2D.Float(rightX,topY,bw,bh);
        tyreBox[2]=new Rectangle2D.Float(leftX,botY,bw,bh);
        tyreBox[3]=new Rectangle2D.Float(rightX,botY,bw,bh);
        tdx[0]=carCx-carW*0.46f; tdy[0]=carCy-carH*0.20f;
        tdx[1]=carCx+carW*0.46f; tdy[1]=carCy-carH*0.20f;
        tdx[2]=carCx-carW*0.46f; tdy[2]=carCy+carH*0.26f;
        tdx[3]=carCx+carW*0.46f; tdy[3]=carCy+carH*0.26f;
        pedalL=W*0.3125f; pedalR=W*0.6875f; pedalY0=H*0.8333f; pedalY1=H*0.90f;
    }
    static void font(float px,boolean mono){
        G.setFont(new Font(mono?"monospaced":FONT,Font.PLAIN,Math.round(px)));
    }
    static void text(String s,float x,float y,int align){ // 0 L,1 C,2 R
        FontMetrics fm=G.getFontMetrics(); int w=fm.stringWidth(s);
        float px=align==0?x:align==1?x-w/2f:x-w;
        G.drawString(s,px,y);
    }
    static void rect(float l,float t,float r,float b,Color col,boolean fill,float sw){
        G.setColor(col);
        Shape s=new Rectangle2D.Float(l,t,r-l,b-t);
        if(fill) G.fill(s); else { G.setStroke(new BasicStroke(sw)); G.draw(s); }
    }
    static void frame(float l,float t,float r,float b){
        rect(l,t,r,b,PANEL,true,0); rect(l,t,r,b,CYAN_GLOW,false,3.2f); rect(l,t,r,b,CYAN_DIM,false,1.4f);
    }
    static void panel(Rectangle2D.Float b){ frame((float)b.getMinX(),(float)b.getMinY(),(float)b.getMaxX(),(float)b.getMaxY()); }
    static void label(String s,float cx,float top){
        G.setColor(GREY); font(H*0.045f,false); text(s,cx,top,1);
    }
    static String fmt(float val,int dec){
        if(dec==0) return String.valueOf(Math.round(val));
        return String.format("%."+dec+"f",val);
    }
    static void glow(Shape s,Color col,float w){
        G.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),0x40));
        G.setStroke(new BasicStroke(w,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); G.draw(s);
        G.setColor(col);
        G.setStroke(new BasicStroke(w*0.28f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); G.draw(s);
    }
    static String tyreLabel(int i){
        if(cjk){ switch(i){ case 0: return "左前胎壓"; case 1: return "右前胎壓";
                            case 2: return "左後胎壓"; default: return "右後胎壓"; } }
        switch(i){ case 0: return "FRONT LEFT"; case 1: return "FRONT RIGHT";
                   case 2: return "REAR LEFT"; default: return "REAR RIGHT"; }
    }
    static String gearText(){
        if(!h(GEAR)) return "--";
        int x=(int)(g(GEAR)+0.5f);
        if(x==1)return "P"; if(x==2)return "R"; if(x==3)return "N"; if(x==4)return "D";
        if(x>=16&&x<=22) return "M"+(x-15);
        return "--";
    }

    static void drawCar(){
        float hw=carW*0.5f,hh=carH*0.5f,x=carCx,y=carCy;
        GeneralPath p=new GeneralPath();
        p.moveTo(x-hw,y+hh*0.52f); p.lineTo(x-hw,y-hh*0.18f);
        p.quadTo(x-hw*0.94f,y-hh*0.52f,x-hw*0.62f,y-hh*0.62f);
        p.lineTo(x-hw*0.44f,y-hh*0.95f);
        p.quadTo(x,y-hh*1.06f,x+hw*0.44f,y-hh*0.95f);
        p.lineTo(x+hw*0.62f,y-hh*0.62f);
        p.quadTo(x+hw*0.94f,y-hh*0.52f,x+hw,y-hh*0.18f);
        p.lineTo(x+hw,y+hh*0.52f);
        p.quadTo(x+hw*0.86f,y+hh*0.76f,x+hw*0.54f,y+hh*0.78f);
        p.lineTo(x-hw*0.54f,y+hh*0.78f);
        p.quadTo(x-hw*0.86f,y+hh*0.76f,x-hw,y+hh*0.52f);
        p.closePath();
        p.moveTo(x-hw*0.52f,y-hh*0.60f);
        p.quadTo(x,y-hh*0.72f,x+hw*0.52f,y-hh*0.60f);
        p.lineTo(x+hw*0.62f,y-hh*0.22f); p.lineTo(x-hw*0.62f,y-hh*0.22f); p.closePath();
        p.append(new Rectangle2D.Float(x-hw*0.92f,y+hh*0.02f,hw*0.46f,hh*0.20f),false);
        p.append(new Rectangle2D.Float(x+hw*0.46f,y+hh*0.02f,hw*0.46f,hh*0.20f),false);
        p.append(new Rectangle2D.Float(x-hw*0.30f,y+hh*0.52f,hw*0.60f,hh*0.14f),false);
        glow(p,CYAN,carW*0.028f);
    }

    static void drawStatic(){
        G.setPaint(new GradientPaint(0,0,BG_TOP,0,H,BG_BOT)); G.fillRect(0,0,(int)W,(int)H);
        Random rnd=new Random(20260921L);
        for(int i=0;i<160;i++){
            float x=rnd.nextFloat()*W,y=rnd.nextFloat()*H,rad=0.4f+rnd.nextFloat()*1.1f;
            G.setColor(c(0xFF000000|(0x203040+rnd.nextInt(0x304050))));
            G.fill(new Ellipse2D.Float(x-rad,y-rad,rad*2,rad*2));
        }
        drawCar();
        frame(rpmX,rpmY0,rpmX+barW,rpmY1); frame(cooX,rpmY0,cooX+barW,rpmY1);
        G.setColor(WHITE); font(H*0.071f,false);
        text(cjk?"轉速":"RPM",rpmX,H*0.0833f,0);
        text(cjk?"水溫":"COOLANT",cooX+barW,H*0.0833f,2);
        frame(W*0.1875f,H*0.0583f,W*0.2875f,H*0.1833f);
        label(cjk?"檔位":"GEAR",W*0.2375f,H*0.0458f);
        label(cjk?"轉向角":"STEERING",W*0.475f,H*0.0458f);
        G.setColor(CYAN_DIM); G.setStroke(new BasicStroke(1.6f));
        G.draw(new Ellipse2D.Float(dialCx-dialR,dialCy-dialR,dialR*2,dialR*2));
        for(int i=0;i<12;i++){
            double a=Math.PI*2*i/12.0;
            G.draw(new Line2D.Float(dialCx+(float)Math.cos(a)*dialR*0.84f,dialCy+(float)Math.sin(a)*dialR*0.84f,
                                    dialCx+(float)Math.cos(a)*dialR*0.96f,dialCy+(float)Math.sin(a)*dialR*0.96f));
        }
        for(int i=0;i<4;i++){
            Rectangle2D.Float b=tyreBox[i]; boolean left=(i==0||i==2);
            float ax=left?(float)b.getMaxX():(float)b.getMinX(), ay=(float)b.getCenterY();
            G.setColor(CYAN_DIM); G.setStroke(new BasicStroke(1.4f));
            G.draw(new Line2D.Float(ax,ay,tdx[i],tdy[i]));
            G.setColor(CYAN); G.fill(new Ellipse2D.Float(tdx[i]-3.2f,tdy[i]-3.2f,6.4f,6.4f));
            panel(b);
            G.setColor(GREY); font(H*0.038f,false);
            text(tyreLabel(i),(float)b.getMinX()+W*0.012f,(float)b.getMinY()+H*0.038f,0);
        }
        panel(new Rectangle2D.Float(pedalL,pedalY0,pedalR-pedalL,pedalY1-pedalY0));
        G.setColor(CYAN_DIM); G.setStroke(new BasicStroke(1.4f));
        float mid=(pedalL+pedalR)*0.5f; G.draw(new Line2D.Float(mid,pedalY0,mid,pedalY1));
        G.setColor(GREY); font(H*0.046f,false);
        text(cjk?"剎車":"BRAKE",pedalL,pedalY0-H*0.018f,0);
        text(cjk?"加速":"THROTTLE",pedalR,pedalY0-H*0.018f,2);
        label(cjk?"車速 km/h":"SPEED km/h",W*0.80f,H*0.7917f);
    }

    static void drawLive(){
        // tach
        final int N=24; float inner=barW*0.18f,x0=rpmX+inner,x1=rpmX+barW-inner;
        float span=(rpmY1-rpmY0)-inner*2f, seg=span/N;
        float lit=h(RPM)?g(RPM)/REDLINE:0f; lit=Math.max(0,Math.min(1,lit));
        int on=(int)(lit*N+0.5f);
        for(int i=0;i<N;i++){
            float t=rpmY1-inner-(i+1)*seg; boolean isOn=i<on, isRed=i>=N-4;
            if(isOn){ rect(x0-2,t+seg*0.10f-2,x1+2,t+seg*0.82f+2,isRed?RED:CYAN_GLOW,true,0);
                      rect(x0,t+seg*0.10f,x1,t+seg*0.82f,isRed?RED:CYAN,true,0); }
            else rect(x0,t+seg*0.10f,x1,t+seg*0.82f,isRed?c(0xFF3A1414):c(0xFF13212C),true,0);
        }
        G.setColor(h(RPM)?WHITE:GREY); font(H*0.058f,true);
        text(h(RPM)?fmt(g(RPM),0):"--",rpmX,H*0.1667f,0);
        // coolant
        float ix=barW*0.18f,cx0=cooX+ix,cx1=cooX+barW-ix,cy0=rpmY0+ix,cy1=rpmY1-ix;
        float frac=h(COOLANT)?(g(COOLANT)-COOLANT_MIN)/(COOLANT_MAX-COOLANT_MIN):0f;
        frac=Math.max(0,Math.min(1,frac));
        float top=cy1-(cy1-cy0)*frac;
        Color col=(h(COOLANT)&&g(COOLANT)>=COOLANT_WARN)?RED:CYAN;
        rect(cx0,cy0,cx1,cy1,c(0xFF10202B),true,0);
        if(h(COOLANT)&&frac>0.01f){
            G.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),0x66));
            G.fill(new Rectangle2D.Float(cx0,top,cx1-cx0,cy1-top));
            G.setColor(col); G.setStroke(new BasicStroke(2.4f));
            GeneralPath w=new GeneralPath(); w.moveTo(cx0,top); w.quadTo((cx0+cx1)*0.5f,top-4,cx1,top);
            G.draw(w);
        }
        G.setColor(h(COOLANT)?(col==RED?RED:WHITE):GREY); font(H*0.058f,true);
        text(h(COOLANT)?fmt(g(COOLANT),0):"--",cooX+barW,H*0.1667f,2);
        // gear
        G.setColor(h(GEAR)?CYAN:GREY); font(H*0.105f,true);
        text(gearText(),W*0.2375f,H*0.1583f,1);
        // steering
        float deg=g(STEER);
        G.setColor(h(STEER)?WHITE:GREY); font(H*0.0958f,true);
        text(h(STEER)?fmt(deg,0):"--",W*0.475f,H*0.1625f,1);
        AffineTransform old=G.getTransform();
        G.rotate(Math.toRadians(h(STEER)?deg:0),dialCx,dialCy);
        G.setColor(h(STEER)?CYAN:GREY); G.setStroke(new BasicStroke(dialR*0.16f));
        G.draw(new Ellipse2D.Float(dialCx-dialR*0.62f,dialCy-dialR*0.62f,dialR*1.24f,dialR*1.24f));
        G.setStroke(new BasicStroke(dialR*0.13f));
        G.draw(new Line2D.Float(dialCx-dialR*0.62f,dialCy,dialCx+dialR*0.62f,dialCy));
        G.draw(new Line2D.Float(dialCx,dialCy,dialCx,dialCy+dialR*0.62f));
        G.setTransform(old);
        if(h(STEER)){
            float f=Math.max(-1,Math.min(1,deg/STEER_FULL));
            G.setColor(CYAN); G.setStroke(new BasicStroke(3f));
            G.draw(new Arc2D.Float(dialCx-dialR,dialCy-dialR,dialR*2,dialR*2,90,-f*180,Arc2D.OPEN));
        }
        // tyres
        for(int i=0;i<4;i++){
            Rectangle2D.Float b=tyreBox[i]; int t=tyreType[i];
            boolean ok=h(t); float psi=g(t);
            boolean low=ok&&psi<TPMS_LOW, high=ok&&psi>TPMS_HIGH;
            Color cc=(!ok)?GREY:(low||high)?AMBER:WHITE;
            G.setColor(cc); font(H*0.070f,true);
            String num=ok?fmt(psi,1):"--";
            float nx=(float)b.getMinX()+W*0.012f, ny=(float)b.getMaxY()-H*0.018f;
            float nw=G.getFontMetrics().stringWidth(num);
            text(num,nx,ny,0);
            G.setColor(GREY); font(H*0.040f,false);
            text("PSI",nx+nw+W*0.012f,ny,0);
            G.setColor(!ok?GREY:(low||high)?AMBER:GREEN);
            float r2=H*0.014f;
            G.fill(new Ellipse2D.Float((float)b.getMaxX()-W*0.018f-r2,(float)b.getMinY()+H*0.032f-r2,r2*2,r2*2));
        }
        // pedals
        float m=(pedalL+pedalR)*0.5f, half=(pedalR-pedalL)*0.5f-3f;
        float brk=h(BRAKE)?Math.max(0,Math.min(1,g(BRAKE)/PEDAL_FULL)):0f;
        if(brk>0.005f){
            G.setColor(new Color(255,69,69,0x66)); G.fill(new Rectangle2D.Float(m-half*brk,pedalY0+3,half*brk-2,pedalY1-pedalY0-6));
            G.setColor(RED); G.fill(new Rectangle2D.Float(m-half*brk,pedalY0+3,4,pedalY1-pedalY0-6));
        }
        float thr=h(ACCEL)?Math.max(0,Math.min(1,g(ACCEL)/1000f)):0f;
        if(thr>0.005f){
            G.setColor(new Color(63,210,255,0x66)); G.fill(new Rectangle2D.Float(m+2,pedalY0+3,half*thr-2,pedalY1-pedalY0-6));
            G.setColor(CYAN); G.fill(new Rectangle2D.Float(m+half*thr-4,pedalY0+3,4,pedalY1-pedalY0-6));
        }
        float net=thr-brk;
        if(Math.abs(net)>0.02f){
            boolean up=net>0; float mag=Math.abs(net);
            float ay=carCy-carH*0.60f, aw=carW*0.15f*(0.55f+mag*0.45f), ah=carH*0.13f*(0.55f+mag*0.45f);
            GeneralPath a=new GeneralPath();
            if(up){ a.moveTo(carCx,ay-ah); a.lineTo(carCx+aw,ay); a.lineTo(carCx+aw*0.42f,ay);
                    a.lineTo(carCx+aw*0.42f,ay+ah*0.55f); a.lineTo(carCx-aw*0.42f,ay+ah*0.55f);
                    a.lineTo(carCx-aw*0.42f,ay); a.lineTo(carCx-aw,ay); }
            else  { a.moveTo(carCx,ay+ah*0.55f); a.lineTo(carCx+aw,ay-ah*0.30f); a.lineTo(carCx+aw*0.42f,ay-ah*0.30f);
                    a.lineTo(carCx+aw*0.42f,ay-ah); a.lineTo(carCx-aw*0.42f,ay-ah);
                    a.lineTo(carCx-aw*0.42f,ay-ah*0.30f); a.lineTo(carCx-aw,ay-ah*0.30f); }
            a.closePath();
            G.setColor(up?new Color(63,210,255,0x55):new Color(255,69,69,0x55)); G.fill(a);
            G.setColor(up?CYAN:RED); G.setStroke(new BasicStroke(2f)); G.draw(a);
        }
        // speed
        G.setColor(h(SPEED)?WHITE:GREY); font(H*0.129f,true);
        text(h(SPEED)?fmt(g(SPEED),0):"--",W*0.80f,H*0.929f,1);
        // odo
        font(H*0.038f,false); G.setColor(GREY);
        text(cjk?"總里程 km":"ODO km",W*0.11f,H*0.8167f,0);
        G.setColor(CYAN); font(H*0.038f,true);
        text(h(ODO)?fmt(g(ODO),0):"--",W*0.11f,H*0.8833f,0);
    }

    public static void main(String[] a) throws Exception {
        boolean warn = a.length>0 && a[0].equals("warn");
        cjk = !(a.length>1 && a[1].equals("en"));
        layout();
        set(RPM, warn?6900f:3120f); set(COOLANT, warn?109f:88f); set(SPEED, warn?118f:64f);
        set(GEAR,4f); set(ACCEL, warn?0f:340f); set(BRAKE, warn?62f:0f);
        set(STEER, warn?-148f:12f); set(ODO,48213f);
        set(TP_FL,39.2f); set(TP_FR,39.2f); set(TP_RL,38.5f); set(TP_RR, warn?27.5f:38.2f);
        BufferedImage img=new BufferedImage(800,480,BufferedImage.TYPE_INT_RGB);
        G=img.createGraphics();
        G.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        G.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        G.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
        drawStatic(); drawLive();
        String name = warn?"dash-warn.png":(cjk?"dash-normal.png":"dash-en.png");
        ImageIO.write(img,"png",new File(name));
        System.out.println("wrote "+name);
    }
}
