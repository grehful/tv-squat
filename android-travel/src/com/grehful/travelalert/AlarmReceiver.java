package com.grehful.travelalert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 아침 알람이 울리면 검색 작업을 맡기고 다음 날 알람을 다시 건다. 재부팅/앱 업데이트 후에도 다시 건다. */
public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_ALARM = "com.grehful.travelalert.ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_ALARM.equals(intent.getAction())) Runner.enqueueJob(context);
        Runner.schedule(context);
    }
}
