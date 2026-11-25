package com.clean.selfiestick;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Third boot trigger, for ROMs that never deliver BOOT_COMPLETED to sideloaded apps (the same workaround the reference
 * project uses): the system restores PERSISTED jobs after a reboot. Two jobs, both only while Connect on boot is on:
 * a periodic one, and a one-off "quick" one that re-arms itself, so one is always pending and its deadline has passed
 * when the device comes back up. Each run only calls startBootRun, which does nothing once this boot's run is done.
 */
public class BootJob extends JobService {
    static final int PERIODIC_ID = 42;
    static final int QUICK_ID = 44;
    static final long QUICK_MS = 3 * 60 * 1000L;

    private static JobScheduler scheduler(Context c) {
        return (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static boolean pending(JobScheduler js, int id) {
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == id) return true;
        return false;
    }

    /** Idempotent: schedules whatever is not already pending. */
    static void schedule(Context c) {
        try {
            JobScheduler js = scheduler(c);
            if (js == null) return;
            if (!pending(js, PERIODIC_ID)) {
                js.schedule(new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, BootJob.class))
                    .setPersisted(true).setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L).build());
            }
            if (!pending(js, QUICK_ID)) {
                js.schedule(new JobInfo.Builder(QUICK_ID, new ComponentName(c, BootJob.class))
                    .setMinimumLatency(QUICK_MS).setOverrideDeadline(QUICK_MS + 15000L).setPersisted(true).build());
            }
        } catch (RuntimeException e) {
            Log.w("SelfieStick", "boot job not scheduled: " + e);
        }
    }

    static void cancel(Context c) {
        try {
            JobScheduler js = scheduler(c);
            if (js == null) return;
            js.cancel(PERIODIC_ID);
            js.cancel(QUICK_ID);
        } catch (RuntimeException e) {
            Log.w("SelfieStick", "boot job not cancelled: " + e);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        if (!Prefs.bootEnabled(app)) {
            cancel(app);
            return false;
        }
        if (MiniTcp.trace) Log.i("SelfieStick", "boot job " + params.getJobId());
        BridgeService.startBootRun(app, "boot job");
        // Scheduling the id that is still running would cancel it, so re-arm a moment after this run has ended.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { public void run() { schedule(app); } }, 1000);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
