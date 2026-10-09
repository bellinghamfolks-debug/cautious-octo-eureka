#!/usr/bin/env python3
"""Add a real Android ar-SA resources.arsc string configuration to DeLonghi beta.
This does NOT change Flutter semantics, and output MUST be zipaligned and signed."""
import sys,re,struct,zipfile
from pathlib import Path
TR={
'abc_action_bar_home_description':'الانتقال إلى الصفحة الرئيسية',
'abc_action_bar_up_description':'الرجوع إلى الأعلى',
'abc_action_menu_overflow_description':'خيارات إضافية',
'abc_action_mode_done':'تم',
'abc_activity_chooser_view_see_all':'عرض الكل',
'abc_activitychooserview_choose_application':'اختيار تطبيق',
'abc_capital_off':'إيقاف','abc_capital_on':'تشغيل','abc_search_hint':'بحث',
'abc_searchview_description_clear':'مسح النص',
'abc_searchview_description_query':'البحث',
'abc_searchview_description_search':'بحث',
'abc_searchview_description_submit':'إرسال البحث',
'abc_searchview_description_voice':'البحث الصوتي',
'copy':'نسخ','copy_toast_msg':'تم نسخ الرابط إلى الحافظة',
'fallback_menu_item_copy_link':'نسخ الرابط',
'fallback_menu_item_open_in_browser':'فتح في المتصفح',
'fallback_menu_item_share_link':'مشاركة الرابط',
'fcm_fallback_notification_channel_label':'تنبيهات متنوعة',
'mcsdk_content_description_close_button':'إغلاق',
'mcsdk_content_description_image':'صورة',
'mcsdk_default_notification_channel_name':'التسويق',
'mcsdk_foreground_notification_channel_name':'رسائل القرب',
'mcsdk_iam_activity_label':'رسالة داخل التطبيق',
'mcsdk_notification_activity_label':'رسالة إشعار',
'menu_search':'بحث','messenger_send_button_text':'إرسال',
'not_set':'غير محدد','preference_copied':'تم النسخ إلى الحافظة: %1$s',
'search_menu_title':'بحث','v7_preference_off':'إيقاف',
'v7_preference_on':'تشغيل','exo_download_completed':'اكتمل التنزيل',
'exo_download_description':'تنزيل','exo_download_downloading':'جارٍ التنزيل',
'exo_download_failed':'فشل التنزيل',
'exo_download_notification_channel_name':'التنزيلات',
'exo_download_paused':'التنزيلات متوقفة مؤقتًا',
'exo_download_paused_for_network':'بانتظار اتصال الشبكة للتنزيل',
'exo_download_paused_for_wifi':'بانتظار Wi-Fi للتنزيل',
'exo_download_removing':'جارٍ حذف التنزيلات',
'common_open_on_phone':'فتح على الهاتف',
'common_signin_button_text':'تسجيل الدخول',
'common_signin_button_text_long':'تسجيل الدخول بحساب Google'}
def u32(b,o):return struct.unpack_from('<I',b,o)[0]
def hdr(b,o):return struct.unpack_from('<HHI',b,o)
def string_pool(b,p):
    typ,h,sz=hdr(b,p)
    assert typ==1 and u32(b,p+12)==0
    n,flags,start=u32(b,p+8),u32(b,p+16),u32(b,p+20)
    out=[]
    for i in range(n):
        o=p+start+u32(b,p+h+4*i)
        if flags&0x100:
            x=b[o];o+=1
            if x&128:o+=1
            ln=b[o];o+=1
            if ln&128:ln=((ln&127)<<8)|b[o];o+=1
            out.append(b[o:o+ln].decode('utf8'))
        else:
            ln=struct.unpack_from('<H',b,o)[0];o+=2
            if ln&0x8000:ln=((ln&0x7fff)<<16)|struct.unpack_from('<H',b,o)[0];o+=2
            out.append(b[o:o+ln*2].decode('utf-16le'))
    return out
def enc_len(n):
    if n<128:return bytes([n])
    assert n<32768
    return bytes([(n>>8)|128,n&255])
def encode_pool(a):
    buf=bytearray();offs=[]
    for s in a:
        raw=s.encode('utf8');offs.append(len(buf))
        buf.extend(enc_len(len(s.encode('utf-16le'))//2)+enc_len(len(raw))+raw+b'\0')
    buf.extend(b'\0'*(-len(buf)%4))
    return (struct.pack('<HHI',1,28,28+4*len(a)+len(buf))
         + struct.pack('<IIIII',len(a),0,0x100,28+4*len(a),0)
         + struct.pack('<%dI'%len(a),*offs)+buf)
def add_ar(d):
    assert hdr(d,0)[0]==2 and u32(d,8)==1
    gs=string_pool(d,12)
    pkg=12+hdr(d,12)[2];_,ph,psz=hdr(d,pkg)
    assert _==0x200
    tnames=string_pool(d,pkg+u32(d,pkg+268))
    knames=string_pool(d,pkg+u32(d,pkg+276))
    typid=tnames.index('string')+1
    pos=pkg+ph;parts=[];template=None;num=None;insert=None
    while pos<pkg+psz:
        ty,h,sz=hdr(d,pos);item=bytes(d[pos:pos+sz]);parts.append(item)
        if ty==0x202 and item[8]==typid:num=u32(item,12)
        if ty==0x201 and item[8]==typid:
            if item[28:32]==b'\0\0\0\0':template=item
            insert=len(parts)
        pos+=sz
    assert pos==pkg+psz and num and template
    h=hdr(template,0)[1];cnt=u32(template,12);start=u32(template,16)
    lang=bytearray(template[20:h]);lang[8:12]=b'arSA'
    entries={};translated=[]
    for idx in range(cnt):
        off=u32(template,h+4*idx)
        if off==0xffffffff:continue
        o=start+off
        if struct.unpack_from('<HH',template,o)!=(8,0):continue
        b=bytearray(template[o:o+16])
        if b[11]!=3:continue
        name=knames[u32(b,4)]
        if name not in TR:continue
        struct.pack_into('<I',b,12,len(gs))
        gs.append(TR[name]);entries[idx]=b;translated.append(name)
    assert len(translated)>=35, len(translated)
    newh=20+len(lang);offs=[0xffffffff]*num;body=bytearray()
    for ix,b in sorted(entries.items()):
        offs[ix]=len(body);body+=b
    st=newh+num*4
    part=struct.pack('<HHI',0x201,newh,st+len(body))
    part+=struct.pack('<BBHII',typid,0,0,num,st)+lang
    part+=struct.pack('<%dI'%num,*offs)+body
    parts.insert(insert,part)
    pkgout=bytearray(d[pkg:pkg+ph]+b''.join(parts))
    struct.pack_into('<I',pkgout,4,len(pkgout))
    out=bytearray(d[:12]+encode_pool(gs)+pkgout)
    struct.pack_into('<I',out,4,len(out))
    assert hdr(out,0)[2]==len(out)
    return bytes(out),translated
def main():
    source,dest=map(Path,sys.argv[1:3]);names=[]
    with zipfile.ZipFile(source) as zin,zipfile.ZipFile(dest,'w',allowZip64=True) as zout:
        for item in zin.infolist():
            n=item.filename
            if re.match(r'^META-INF/[^/]+\.(?:SF|RSA|DSA|EC|MF)$',n,re.I):continue
            data=zin.read(n)
            if n=='resources.arsc':data,names=add_ar(data)
            zi=zipfile.ZipInfo(n);zi.date_time=item.date_time
            zi.compress_type=zipfile.ZIP_STORED if n=='resources.arsc' else item.compress_type
            zi.external_attr=item.external_attr
            zout.writestr(zi,data)
    with zipfile.ZipFile(dest) as z:assert z.testzip() is None
    print('Arabic native labels:',len(names),','.join(names))
if __name__=='__main__':main()
