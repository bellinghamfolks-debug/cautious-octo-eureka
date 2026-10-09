package org.accessiblecoffee.companion;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TARGET = "com.delonghigroup.daedalus";
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("مساعد ديلونجي الناطق");
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(22), dp(28), dp(22), dp(22));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title = new TextView(this);
        title.setText("مساعد ديلونجي لقارئ الشاشة");
        title.setTextSize(25);
        title.setTextColor(0xff161616);
        title.setAccessibilityHeading(true);
        root.addView(title);
        TextView intro = new TextView(this);
        intro.setText("\nنسخة مستقلة للتجربة، تعمل بجانب تطبيق My Coffee Lounge الرسمي أو المعدّل. " +
            "تساعدك على عرض الأزرار القابلة للقراءة، والوصول إلى حقل PIN، وفحص عناصر الشاشة، " +
            "والإعلان عن بعض تنبيهات ماكينة القهوة المكتوبة. لا تحتاج إلى روت، ولا ترسل بيانات إلى الإنترنت.\n" +
            "لا تعالج الصور التي لا تتوفر لها معلومات وصفية في التطبيق، ولا تغيّر شيفرة Flutter نفسها.");
        intro.setTextSize(17);
        root.addView(intro);
        status = new TextView(this);
        status.setTextSize(19);
        status.setTextColor(0xff1b5e20);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        addButton(root,"١. تفعيل خدمة مساعد ديلونجي في إعدادات إمكانية الوصول",v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addButton(root,"٢. فتح تطبيق My Coffee Lounge",v -> {
            Intent i=getPackageManager().getLaunchIntentForPackage(TARGET);
            if(i!=null)startActivity(i);
            else status.setText("لم أجد تطبيق My Coffee Lounge. ثبّته أولًا.");
        });
        TextView note = new TextView(this);
        note.setText("\nللاستخدام: فعّل الخدمة، ثم افتح ديلونجي. ستجد زرًا عائمًا باسم «مساعد». " +
            "اضغطه لعرض أدوات إصلاح التنقل، والصفحة الحالية، والعناصر القابلة للضغط. " +
            "هذه أداة مساندة، وليست شهادة بأن التطبيق الأصلي يطابق معايير الوصول.\n" +
            "نصيحة: لا تستخدم زرًا غير واضح الوظيفة لبدء تحضير القهوة.");
        note.setTextSize(16);
        root.addView(note);
        setContentView(root);
    }
    @Override protected void onResume() {
        super.onResume();
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String component = getPackageName()+"/"+CoffeeAccessibilityService.class.getName();
        status.setText(!TextUtils.isEmpty(enabled) && enabled.contains(component)
            ? "الحالة: الخدمة مفعّلة." : "الحالة: الخدمة غير مفعّلة بعد.");
    }
    private void addButton(LinearLayout root,String text,View.OnClickListener action) {
        Button button=new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setOnClickListener(action);
        root.addView(button,new LinearLayout.LayoutParams(-1,-2));
    }
    private int dp(float size) {return Math.round(size*getResources().getDisplayMetrics().density);}
}
