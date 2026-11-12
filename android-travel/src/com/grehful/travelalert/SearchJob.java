package com.grehful.travelalert;

import android.app.job.JobParameters;
import android.app.job.JobService;

import org.json.JSONObject;

/** 백그라운드에서 가격을 검색하고 알림을 띄운다. */
public class SearchJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                JSONObject result = Runner.search(getApplicationContext());
                String[] s = Engine.summary(result);
                Runner.notify(this, s[0], s[1]);
            } catch (Exception e) {
                Runner.notify(this, "✈️ 여행 가격 검색 실패", String.valueOf(e.getMessage()));
            }
            jobFinished(params, false);
        }, "travel-search").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
