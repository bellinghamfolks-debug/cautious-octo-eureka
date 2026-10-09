package org.accessiblecoffee.companion;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class CoffeeAccessibilityService extends AccessibilityService {
    private static final String TARGET="com.delonghigroup.daedalus";
    private WindowManager windows;
    private LinearLayout wrapper, panel, results;
    private TextView status;
    private TextToSpeech tts;
    private boolean expanded=false, spokenAlerts=true;
    private String lastAlert="";
    private long lastAlertTime=0;

    private static final class ScreenEntry {
        final String path,label;
        final boolean editable, clickable, unnamed;
        ScreenEntry(String p,String s,boolean e,boolean c,boolean u) {
            path=p;label=s;editable=e;clickable=c;unnamed=u;
        }
    }
    private static final class Frame {
        final AccessibilityNodeInfo node;
        final String path;
        Frame(AccessibilityNodeInfo n,String p){node=n;path=p;}
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        windows=(WindowManager)getSystemService(Context.WINDOW_SERVICE);
        tts=new TextToSpeech(this, status -> {
            if(status==TextToSpeech.SUCCESS)tts.setLanguage(new Locale("ar","SA"));
        });
        createOverlay();
    }
    private void createOverlay() {
        wrapper=new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        wrapper.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.rgb(249,249,249));
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1),Color.rgb(65,65,65));
        wrapper.setBackground(bg);

        Button toggle=button("مساعد ديلونجي: فتح أدوات الناطق",v -> toggleOverlay());
        wrapper.addView(toggle);
        panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);
        wrapper.addView(panel);
        status=new TextView(this);
        status.setTextSize(17);
        status.setTextColor(Color.BLACK);
        status.setText("افتح My Coffee Lounge ثم اختر «فحص الصفحة».");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);

        rowButton("فحص الصفحة والعناصر التي لا تحمل اسمًا",v -> audit());
        rowButton("عرض الأزرار المتاحة بنطق عربي",v -> showClickableElements());
        rowButton("الانتقال إلى حقل PIN أولًا",v -> focusPin());
        rowButton("زر المفضلة (القلب)",v -> activateFavorite());
        rowButton("الانتقال إلى زر دليل الاستخدام",v -> focusManual());
        rowButton("زيادة المستوى إذا كان شريط التحكم يدعمها",v -> adjustGrind(+1));
        rowButton("تقليل المستوى إذا كان شريط التحكم يدعمها",v -> adjustGrind(-1));
        rowButton("تبديل تنبيهات الأخطاء المنطوقة",v -> {
            spokenAlerts=!spokenAlerts;
            setStatus(spokenAlerts?"تنبيهات الأخطاء المنطوقة مفعّلة.":"تنبيهات الأخطاء المنطوقة متوقفة.");
        });

        ScrollView scroller=new ScrollView(this);
        results=new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(results);
        panel.addView(scroller,new LinearLayout.LayoutParams(-1,dp(260)));

        WindowManager.LayoutParams params=new WindowManager.LayoutParams(
            dp(310),-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            -3
        );
        params.gravity=Gravity.TOP|Gravity.END;
        params.x=dp(9);params.y=dp(96);
        windows.addView(wrapper,params);
    }
    private void toggleOverlay() {
        expanded=!expanded;
        panel.setVisibility(expanded?View.VISIBLE:View.GONE);
        if(expanded) setStatus("اختر أداة لإصلاح التنقل أو فحص الصفحة.");
        wrapper.requestLayout();
    }
    private Button button(String name,View.OnClickListener listener) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(name);
        b.setTextSize(14);
        b.setContentDescription(name);
        b.setOnClickListener(listener);
        return b;
    }
    private void rowButton(String text,View.OnClickListener l) {
        panel.addView(button(text,l),new LinearLayout.LayoutParams(-1,-2));
    }
    private void setStatus(String s) {
        if(status!=null) status.setText(s);
    }
    private int dp(int i) {return Math.round(i*getResources().getDisplayMetrics().density);}
    private AccessibilityNodeInfo targetRoot() {
        AccessibilityNodeInfo r=getRootInActiveWindow();
        if(r==null||r.getPackageName()==null||!TARGET.contentEquals(r.getPackageName())) {
            setStatus("افتح تطبيق My Coffee Lounge أولًا. لا يمكن فحص شاشة تطبيق آخر.");
            return null;
        }
        return r;
    }
    private static String text(CharSequence x) {
        return x==null?"":x.toString().trim();
    }
    private static boolean editor(AccessibilityNodeInfo n) {
        String c=text(n.getClassName()).toLowerCase(Locale.ROOT);
        return n.isEditable()||c.contains("edittext");
    }
    private static String visibleName(AccessibilityNodeInfo n) {
        if(editor(n)) {
            String hint=text(n.getHintText());
            return hint.isEmpty()?"حقل إدخال":hint;
        }
        if(n.isPassword())return "حقل كلمة المرور";
        String x=text(n.getContentDescription());
        if(!x.isEmpty())return x;
        x=text(n.getText());
        if(!x.isEmpty())return x;
        return "";
    }
    private static String descendantLabel(AccessibilityNodeInfo n) {
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(n);
        int count=0;
        while(!q.isEmpty()&&count++<18) {
            AccessibilityNodeInfo t=q.poll();
            if(t!=n&&!t.isPassword()) {
                String s=visibleName(t);
                if(!s.isEmpty())return s;
            }
            for(int i=0;i<t.getChildCount();i++){
                AccessibilityNodeInfo ch=t.getChild(i);
                if(ch!=null)q.add(ch);
            }
        }
        return "";
    }
    private static String arabicLabel(String name, boolean editable) {
        String s=name.trim();
        String low=s.toLowerCase(Locale.ROOT);
        if(editable) {
            if(low.contains("pin"))return "حقل إدخال رمز PIN";
            if(low.contains("country")||low.contains("paese"))return "حقل الدولة";
            return "حقل إدخال: "+s;
        }
        if(low.equals("cuore")||low.equals("heart")||low.equals("favorite")||
            low.equals("favourite")||low.equals("favorites")||low.equals("favourites"))
            return "زر المفضلة (إضافة أو إزالة)";
        if(low.equals("image")||low.equals("immagine")||low.equals("صورة"))
            return "صورة؛ لا يوجد وصف واضح من التطبيق الأصلي";
        if(low.contains("non trovo il pin"))return "رابط: لا أجد رمز PIN";
        if(low.contains("manual")||low.contains("manuale")||low.contains("دليل"))
            return "دليل استخدام الماكينة";
        if(low.equals("register")||low.equals("registrati"))return "إنشاء حساب";
        if(low.equals("log in")||low.equals("login")||low.equals("accedi"))return "تسجيل الدخول";
        if(low.equals("google"))return "تسجيل الدخول باستخدام Google";
        if(low.equals("email")||low.equals("e-mail"))return "تسجيل الدخول بالبريد الإلكتروني";
        return s;
    }
    private List<ScreenEntry> scan(AccessibilityNodeInfo root) {
        List<ScreenEntry> list=new ArrayList<>();
        ArrayDeque<Frame> pending=new ArrayDeque<>();
        pending.add(new Frame(root,""));
        int inspected=0,anonymous=0;
        while(!pending.isEmpty() && inspected++<450) {
            Frame f=pending.removeFirst();
            AccessibilityNodeInfo n=f.node;
            if(n.isVisibleToUser()) {
                boolean editable=editor(n);
                boolean click=n.isClickable()||editable||n.isCheckable();
                if(click) {
                    String label=visibleName(n);
                    if(label.isEmpty())label=descendantLabel(n);
                    boolean unnamed=label.isEmpty();
                    if(unnamed)label="عنصر تفاعلي دون اسم "+(++anonymous);
                    if(!n.isPassword())list.add(new ScreenEntry(f.path,arabicLabel(label,editable),editable,click,unnamed));
                }
            }
            for(int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo child=n.getChild(i);
                if(child!=null)pending.addLast(new Frame(child,f.path+"/"+i));
            }
        }
        return list;
    }
    private void audit() {
        results.removeAllViews();
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        List<ScreenEntry> nodes=scan(root);
        int unnamed=0,inputs=0;
        for(ScreenEntry x:nodes){if(x.unnamed)unnamed++;if(x.editable)inputs++;}
        setStatus("عدد عناصر التحكم المرئية: "+nodes.size()+". دون أسماء واضحة: "+unnamed+
            ". حقول الإدخال: "+inputs+". لا تعني هذه النتيجة أن قارئ الشاشة لا يقرأ العناصر الداخلية جميعها.");
        int n=0;
        for(ScreenEntry x:nodes) {
            if(x.unnamed) addResult(++n+": "+x.label+"، وصف غير متاح.");
            if(n>=12)break;
        }
        if(n==0)addResult("لم أكتشف أزرارًا بلا أسماء في شجرة إمكانية الوصول الحالية.");
    }
    private void showClickableElements() {
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        results.removeAllViews();
        List<ScreenEntry> nodes=scan(root);
        // Logical workaround for the documented PIN ordering issue.
        nodes.sort(Comparator.comparingInt(n -> n.editable &&
            n.label.toLowerCase(Locale.ROOT).contains("pin")?0:1));
        Set<String> seen=new HashSet<>();
        int added=0,unknown=0;
        for(ScreenEntry item:nodes) {
            if(item.unnamed) {unknown++;continue;}
            String key=item.path+"|"+item.label;
            if(!seen.add(key))continue;
            if(added++>=30)break;
            final String path=item.path;
            final String label=item.label;
            results.addView(button("اضغط: "+label,v -> clickPathWithConfirmation(path,label)),new LinearLayout.LayoutParams(-1,-2));
        }
        if(added==0) addResult("لم أجد أزرارًا مسماة يمكن عرضها. ربما يخفي Flutter بعض العناصر عن النظام.");
        setStatus("عرض "+Math.min(30,added)+" عنصرًا مع خيارات ناطقة. عدد العناصر بلا اسم: "+unknown+
            ". لا تُعرض الأزرار غير المعروفة للتشغيل حفاظًا على سلامتك.");
    }
    private void clickPathWithConfirmation(String path,String label) {
        String lower=label.toLowerCase(Locale.ROOT);
        if(lower.contains("brew")||lower.contains("تحضير")||lower.contains("start")||lower.contains("rinse")
          ||lower.contains("تنظيف")||lower.contains("تكلس")||lower.contains("إزالة الترسبات")) {
            results.removeAllViews();
            addResult("تحذير: قد يؤدي هذا الزر إلى تشغيل الماكينة أو ضخ ماء ساخن. تأكد من وجود الكوب.");
            results.addView(button("أؤكد الضغط على: "+label,v -> executePath(path)),new LinearLayout.LayoutParams(-1,-2));
            results.addView(button("إلغاء",v -> showClickableElements()),new LinearLayout.LayoutParams(-1,-2));
        }else executePath(path);
    }
    private AccessibilityNodeInfo nodeAtPath(AccessibilityNodeInfo root,String path) {
        AccessibilityNodeInfo n=root;
        if(path.isEmpty())return n;
        for(String s:path.substring(1).split("/")){
            if(s.isEmpty())continue;
            int ix;
            try{ix=Integer.parseInt(s);}catch(NumberFormatException e){return null;}
            if(ix>=n.getChildCount())return null;
            n=n.getChild(ix);
            if(n==null)return null;
        }
        return n;
    }
    private void executePath(String path) {
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        AccessibilityNodeInfo n=nodeAtPath(root,path);
        if(n==null){setStatus("تغيرت الشاشة. افتح قائمة العناصر مرة أخرى.");return;}
        AccessibilityNodeInfo p=n;
        for(int i=0;i<5&&p!=null;i++,p=p.getParent()){
            if(p.isClickable()&&p.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                setStatus("أرسلت إجراء الضغط. تحقق من النتيجة في شاشة ديلونجي.");return;
            }
        }
        setStatus("تعذر تنفيذ الضغط. الزر غير قابل للتفعيل من خدمة إمكانية الوصول.");
    }
    private void focusPin() {focusNamed("pin",true);}
    private void focusManual() {focusNamed("manual",false);}
    private void focusNamed(String term,boolean wantEditor) {
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);
        while(!q.isEmpty()){
            AccessibilityNodeInfo n=q.removeFirst();
            String label=visibleName(n).toLowerCase(Locale.ROOT);
            if((!wantEditor||editor(n)) &&
                (label.contains(term)||(!wantEditor&&(label.contains("manuale")||label.contains("دليل"))))) {
                if(n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)||
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)){
                    setStatus("تم نقل التركيز إلى "+(wantEditor?"حقل PIN":"دليل الاستخدام")+".");
                    return;
                }
            }
            for(int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)q.add(c);
            }
        }
        setStatus("العنصر غير ظاهر في شجرة إمكانية الوصول. لا يمكن الوصول إليه بأمان من دون بيانات إضافية.");
    }
    private void activateFavorite() {
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        List<ScreenEntry> all=scan(root);
        for(ScreenEntry n:all) {
            String s=n.label.toLowerCase(Locale.ROOT);
            if(s.contains("المفضلة")||s.contains("cuore")||s.contains("heart")||s.contains("favourit")) {
                clickPathWithConfirmation(n.path,"المفضلة");
                return;
            }
        }
        setStatus("تعذر تحديد زر المفضلة في هذه الشاشة. لا أضغط على أيقونة مجهولة لتجنب الخطأ.");
    }
    private void adjustGrind(int direction) {
        AccessibilityNodeInfo root=targetRoot();
        if(root==null)return;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(root);
        while(!q.isEmpty()){
            AccessibilityNodeInfo n=q.removeFirst();
            AccessibilityNodeInfo.RangeInfo range=n.getRangeInfo();
            String lab=visibleName(n).toLowerCase(Locale.ROOT);
            if(range!=null && (lab.contains("grind")||lab.contains("bean")||
                lab.contains("طحن")||lab.contains("macin"))) {
                float step=Math.max(1f,(range.getMax()-range.getMin())/10f);
                float changed=Math.max(range.getMin(),Math.min(range.getMax(),range.getCurrent()+step*direction));
                Bundle args=new Bundle();
                args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,changed);
                if(n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args)){
                    setStatus("تم إرسال تعديل المستوى إلى "+changed+". تحقق من نتيجة الضبط في التطبيق.");
                    return;
                }
            }
            for(int i=0;i<n.getChildCount();i++){
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)q.add(c);
            }
        }
        setStatus("لم أجد شريط طحن قابلًا للتعديل عبر خدمات إمكانية الوصول. لم أرسل أي حركة عشوائية للماكينة.");
    }
    private void addResult(String s) {
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setPadding(dp(8),dp(8),dp(8),dp(8));
        results.addView(t);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event==null||event.getPackageName()==null||
            !TARGET.contentEquals(event.getPackageName())||!spokenAlerts)return;
        // Only preapproved alarm phrases; never speak an email, PIN, password or general form content.
        if(event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
           event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null||root.getPackageName()==null||!TARGET.contentEquals(root.getPackageName()))return;
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();
        q.add(root);
        int inspected=0;
        while(!q.isEmpty() && inspected++<220) {
            AccessibilityNodeInfo n=q.removeFirst();
            if(!n.isPassword()&&!editor(n)){
                String s=(text(n.getText())+" "+text(n.getContentDescription())).toLowerCase(Locale.ROOT);
                String alert=classifyAlert(s);
                if(alert!=null){announceOnce(alert);return;}
            }
            for(int i=0;i<n.getChildCount();i++) {
                AccessibilityNodeInfo c=n.getChild(i);
                if(c!=null)q.add(c);
            }
        }
    }
    private static String classifyAlert(String s) {
        if((s.contains("water")&&s.contains("tank")&&(s.contains("empty")||
            s.contains("missing")||s.contains("removed")))||s.contains("خزان الماء فارغ")
            ||s.contains("خزان الماء غير موجود"))
            return "تنبيه من ماكينة القهوة: تحقق من خزان الماء.";
        if((s.contains("coffee")&&s.contains("beans")&&s.contains("empty"))||
            s.contains("out of coffee beans")||s.contains("حبوب القهوة فارغة"))
            return "تنبيه: أضف حبوب القهوة.";
        if(s.contains("descaling required")||s.contains("إزالة التكلس مطلوبة"))
            return "تنبيه: الماكينة تطلب إزالة التكلس.";
        if(s.contains("ground container")&&s.contains("full"))
            return "تنبيه: حاوية تفل القهوة ممتلئة.";
        if(s.contains("general alarm")||s.contains("إنذار عام"))
            return "تنبيه: ظهر إنذار عام بالماكينة. راجع تعليمات الجهاز.";
        return null;
    }
    private void announceOnce(String alert) {
        long now=SystemClock.elapsedRealtime();
        if(alert.equals(lastAlert)&&now-lastAlertTime<90_000)return;
        lastAlert=alert;lastAlertTime=now;
        if(tts!=null)tts.speak(alert,TextToSpeech.QUEUE_FLUSH,null,"delonghi_warning");
        setStatus(alert);
    }
    @Override public void onInterrupt() {
        if(tts!=null)tts.stop();
    }
    @Override public void onDestroy() {
        if(wrapper!=null&&windows!=null)try{windows.removeView(wrapper);}catch(Exception ignored){}
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}
