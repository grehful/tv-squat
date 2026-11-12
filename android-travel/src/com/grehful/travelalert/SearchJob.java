package com.grehful.travelalert;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

import org.json.JSONObject;

/** 백그라운드에서 가격을 검색하고 알림을 띄운다. */
public class SearchJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject result = Runner.search(ctx);
                    String[] s = Engine.summary(result);
                    Runner.notify(ctx, s[0], s[1]);
                } catch (Throwable e) {
                    Runner.notify(ctx, "✈️ 여행 가격 검색 실패", e.getMessage() == null ? e.toString() : e.getMessage());
                }
                jobFinished(params, false);
            }
        }, "travel-search").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
