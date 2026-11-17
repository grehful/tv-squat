package com.grehful.travelalert;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * 백그라운드에서 가격을 검색하고 알림을 띄운다.
 * - 아침 작업(JOB_ID): 그날 검색. 휴대폰이 중간에 멈추면 나중에 이어서(오늘 남은 횟수만큼) 검색
 * - 다시 검색(RETRY_JOB_ID): 인터넷 문제로 놓친 날짜만 다시 검색
 */
public class SearchJob extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        SharedPreferences p = Runner.prefs(ctx);
        int today = Day.todayKst(System.currentTimeMillis());
        boolean resume = params.getJobId() == Runner.RETRY_JOB_ID;
        if (params.getJobId() == Runner.JOB_ID) {
            resume = p.getInt("morningDay", -1) == today; // 오늘 이미 시작했다가 멈춘 아침 작업
            p.edit().putInt("morningDay", today).apply();
        }
        final boolean isResume = resume;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean stopped = false;
                try {
                    JSONObject result = Runner.search(ctx, isResume);
                    stopped = Thread.currentThread().isInterrupted();
                    String[] s = Engine.summary(result);
                    // 다시 검색한 결과는 소리 없이 기존 알림을 고쳐 씀
                    Runner.notify(ctx, s[0], s[1], isResume);
                } catch (Throwable e) {
                    Runner.notify(ctx, "✈️ 여행 가격 검색 실패", e.getMessage() == null ? e.toString() : e.getMessage(), isResume);
                }
                if (!stopped) jobFinished(params, false);
            }
        }, "travel-search");
        worker.start();
        return true;
    }

    /** 휴대폰이 작업을 멈출 때 (잠자기 등): 검색을 멈추고, 나중에 다시 실행되게 한다. */
    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return true;
    }
}
