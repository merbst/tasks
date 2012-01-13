/**
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.actfm.sync;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;

import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.dao.TaskDao.TaskCriteria;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.service.StartupService;
import com.todoroo.astrid.service.SyncV2Service.SyncResultCallback;
import com.todoroo.astrid.service.SyncV2Service.SyncV2Provider;
import com.todoroo.astrid.service.TaskService;

/**
 * Exposes sync action
 *
 */
public class ActFmSyncV2Provider implements SyncV2Provider {

    @Autowired ActFmPreferenceService actFmPreferenceService;

    @Autowired ActFmSyncService actFmSyncService;

    @Autowired ExceptionService exceptionService;

    @Autowired TaskService taskService;

    static {
        AstridDependencyInjector.initialize();
    }

    public ActFmSyncV2Provider() {
        DependencyInjectionService.getInstance().inject(this);
    }

    @Override
    public boolean isActive() {
        return actFmPreferenceService.isLoggedIn();
    }

    private static final String LAST_TAG_FETCH_TIME = "actfm_lastTag"; //$NON-NLS-1$

    // --- synchronize active

    @Override
    public void synchronizeActiveTasks(boolean manual,
            final SyncResultCallback callback) {

        if(!manual)
            return;

        callback.started();
        callback.incrementMax(50);

        final AtomicInteger finisher = new AtomicInteger(2);

        new Thread(tagFetcher(callback, finisher)).start();

        startTaskFetcher(manual, callback, finisher);

        pushQueued(callback, finisher);
    }

    private Runnable tagFetcher(final SyncResultCallback callback,
            final AtomicInteger finisher) {
        return new Runnable() {
            @Override
            public void run() {
                int time = Preferences.getInt(LAST_TAG_FETCH_TIME, 0);
                try {
                    time = actFmSyncService.fetchTags(time);
                    Preferences.setInt(LAST_TAG_FETCH_TIME, time);
                } catch (JSONException e) {
                    exceptionService.reportError("actfm-sync", e); //$NON-NLS-1$
                } catch (IOException e) {
                    exceptionService.reportError("actfm-sync", e); //$NON-NLS-1$
                } finally {
                    callback.incrementProgress(20);
                    if(finisher.decrementAndGet() == 0)
                        callback.finished();
                }
            }
        };
    }

    private void startTaskFetcher(final boolean manual, final SyncResultCallback callback,
            final AtomicInteger finisher) {
        actFmSyncService.fetchActiveTasks(manual, new Runnable() {
            @Override
            public void run() {
                callback.incrementProgress(30);
                if(finisher.decrementAndGet() == 0)
                    callback.finished();
            }
        });
    }

    private void pushQueued(final SyncResultCallback callback,
            final AtomicInteger finisher) {
        TodorooCursor<Task> cursor = taskService.query(Query.select(Task.PROPERTIES).
                where(Criterion.or(
                        Criterion.and(TaskCriteria.isActive(),
                                Task.ID.gt(StartupService.INTRO_TASK_SIZE),
                                Task.REMOTE_ID.eq(0)),
                        Criterion.and(Task.REMOTE_ID.gt(0),
                                Task.MODIFICATION_DATE.gt(Task.LAST_SYNC)))));

        try {
            callback.incrementMax(cursor.getCount() * 20);
            finisher.addAndGet(cursor.getCount());

            for(int i = 0; i < cursor.getCount(); i++) {
                cursor.moveToNext();
                final Task task = new Task(cursor);

                new Thread(new Runnable() {
                    public void run() {
                        try {
                            actFmSyncService.pushTaskOnSave(task, task.getMergedValues());
                        } finally {
                            callback.incrementProgress(20);
                            if(finisher.decrementAndGet() == 0)
                                callback.finished();
                        }
                    }
                }).start();
            }
        } finally {
            cursor.close();
        }
    }

}
