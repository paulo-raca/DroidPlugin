/*
**        DroidPlugin Project
**
** Copyright(c) 2015 Andy Zhang <zhangyong232@gmail.com>
**
** This file is part of DroidPlugin.
**
** DroidPlugin is free software: you can redistribute it and/or
** modify it under the terms of the GNU Lesser General Public
** License as published by the Free Software Foundation, either
** version 3 of the License, or (at your option) any later version.
**
** DroidPlugin is distributed in the hope that it will be useful,
** but WITHOUT ANY WARRANTY; without even the implied warranty of
** MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
** Lesser General Public License for more details.
**
** You should have received a copy of the GNU Lesser General Public
** License along with DroidPlugin.  If not, see <http://www.gnu.org/licenses/lgpl.txt>
**
**/

package com.morgoo.droidplugin.am;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.text.TextUtils;

import com.morgoo.droidplugin.pm.IApplicationCallback;
import com.morgoo.droidplugin.pm.IPluginManagerImpl;
import com.morgoo.droidplugin.reflect.FieldUtils;
import com.morgoo.droidplugin.stub.AbstractServiceStub;
import com.morgoo.helper.AttributeCache;
import com.morgoo.helper.Log;
import com.morgoo.helper.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * This is a more complex process management services.
 * Main function is:
 * 1, N predefined system processes. There are 4 launchmod of activity, 1 Ge service, a ContentProvider under each process.
 * 2, each plug-in can run in multiple processes, which is determined by the plug-in their own processName property.
 * 3, plug-in system can run simultaneously processes N, M plug (M <= N or M> = N).
 * 4, a plurality of plug-in running in the same process, the same as if their signatures. (We can be determined by a switch.)
 * 5, when running the first M + 1 plug, if N predefined processes is occupied, the lowest-priority process is kill off. Vacate the predefined processes to run this plug-ins.
 * Created by Andy Zhang (zhangyong232@gmail.com) on 2015/3/10.
 */
public class MyActivityManagerService extends BaseActivityManagerService {

    private static final String TAG = MyActivityManagerService.class.getSimpleName();
    private StaticProcessList mStaticProcessList = new StaticProcessList();
    private RunningProcesList mRunningProcessList = new RunningProcesList();

    public MyActivityManagerService(Context hostContext) {
        super(hostContext);
        mRunningProcessList.setContext(mHostContext);
    }

    @Override
    public void onCreate(IPluginManagerImpl pluginManagerImpl) throws Exception {
        super.onCreate(pluginManagerImpl);
        AttributeCache.init(mHostContext);
        mStaticProcessList.onCreate(mHostContext);
        mRunningProcessList.setContext(mHostContext);
    }

    @Override
    public void onDestory() {
        mRunningProcessList.clear();
        mStaticProcessList.clear();
        runProcessGC();
        super.onDestory();
    }

    @Override
    protected void onProcessDied(int pid, int uid) {
        mRunningProcessList.onProcessDied(pid, uid);
        runProcessGC();
        super.onProcessDied(pid, uid);
    }

    @Override
    public boolean registerApplicationCallback(int callingPid, int callingUid, IApplicationCallback callback) {
        boolean b = super.registerApplicationCallback(callingPid, callingUid, callback);
        mRunningProcessList.addItem(callingPid, callingUid);
        if (callingPid == android.os.Process.myPid()) {
            String stubProcessName = Utils.getProcessName(mHostContext, callingPid);
            String targetProcessName = Utils.getProcessName(mHostContext, callingPid);
            String targetPkg = mHostContext.getPackageName();
            mRunningProcessList.setProcessName(callingPid, stubProcessName, targetProcessName, targetPkg);
        }
        if (TextUtils.equals(mHostContext.getPackageName(), Utils.getProcessName(mHostContext, callingPid))) {
            String stubProcessName = mHostContext.getPackageName();
            String targetProcessName = mHostContext.getPackageName();
            String targetPkg = mHostContext.getPackageName();
            mRunningProcessList.setProcessName(callingPid, stubProcessName, targetProcessName, targetPkg);
        }
        return b;
    }

    @Override
    public ProviderInfo selectStubProviderInfo(int callingPid, int callingUid, ProviderInfo targetInfo) throws RemoteException {
        runProcessGC();

        // Start the process of running look to see whether there is compliance with the conditions of the process, if there is a direct use of
        String stubProcessName1 = mRunningProcessList.getStubProcessByTarget(targetInfo);
        if (stubProcessName1 != null) {
            List<ProviderInfo> stubInfos = mStaticProcessList.getProviderInfoForProcessName(stubProcessName1);
            for (ProviderInfo stubInfo : stubInfos) {
                if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                    mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                    return stubInfo;
                }
            }
        }

        List<String> stubProcessNames = mStaticProcessList.getProcessNames();
        for (String stubProcessName : stubProcessNames) {
            List<ProviderInfo> stubInfos = mStaticProcessList.getProviderInfoForProcessName(stubProcessName);
            if (mRunningProcessList.isProcessRunning(stubProcessName)) {
                if (mRunningProcessList.isPkgEmpty(stubProcessName)) {// Empty process, not running any add-on package.
                    for (ProviderInfo stubInfo : stubInfos) {
                        if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else if (mRunningProcessList.isPkgCanRunInProcess(targetInfo.packageName, stubProcessName, targetInfo.processName)) {
                    for (ProviderInfo stubInfo : stubInfos) {
                        if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else {
                    // We need to process signed the same situation.
                }
            } else {
                for (ProviderInfo stubInfo : stubInfos) {
                    if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                        mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                        return stubInfo;
                    }
                }
                throw throwException("I did not find the right StubInfo");
            }
        }
        throw throwException("There is no process available with");
    }


    @Override
    public ServiceInfo getTargetServiceInfo(int callingPid, int callingUid, ServiceInfo stubInfo) throws RemoteException {
        //TODO getTargetServiceInfo
        return null;
    }

    @Override
    public String getProcessNameByPid(int pid) {
        return mRunningProcessList.getTargetProcessNameByPid(pid);
    }

    @Override
    public ServiceInfo selectStubServiceInfo(int callingPid, int callingUid, ServiceInfo targetInfo) throws RemoteException {
        runProcessGC();

        // Start the process of running look to see whether there is compliance with the conditions of the process, if there is a direct use of
        String stubProcessName1 = mRunningProcessList.getStubProcessByTarget(targetInfo);
        if (stubProcessName1 != null) {
            List<ServiceInfo> stubInfos = mStaticProcessList.getServiceInfoForProcessName(stubProcessName1);
            for (ServiceInfo stubInfo : stubInfos) {
                if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                    mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                    return stubInfo;
                }
            }
        }

        List<String> stubProcessNames = mStaticProcessList.getProcessNames();
        for (String stubProcessName : stubProcessNames) {
            List<ServiceInfo> stubInfos = mStaticProcessList.getServiceInfoForProcessName(stubProcessName);
            if (mRunningProcessList.isProcessRunning(stubProcessName)) {// The predefined processes are running.
                if (mRunningProcessList.isPkgEmpty(stubProcessName)) {// Empty process, not running any add-on package.
                    for (ServiceInfo stubInfo : stubInfos) {
                        if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else if (mRunningProcessList.isPkgCanRunInProcess(targetInfo.packageName, stubProcessName, targetInfo.processName)) {
                    for (ServiceInfo stubInfo : stubInfos) {
                        if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else {
                    // To consider here signature the same situation, a plurality of plug-public process.
                }
            } else { // This process is not predefined.
                for (ServiceInfo stubInfo : stubInfos) {
                    if (!mRunningProcessList.isStubInfoUsed(stubInfo)) {
                        mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                        return stubInfo;
                    }
                }
                throw throwException("I did not find the right StubInfo");
            }
        }
        throw throwException("There is no process available with");
    }

    private RemoteException throwException(String msg) {
        RemoteException remoteException = new RemoteException();
        remoteException.initCause(new RuntimeException(msg));
        return remoteException;
    }


    @Override
    public void onActivityCreated(int callingPid, int callingUid, ActivityInfo stubInfo, ActivityInfo targetInfo) {
        mRunningProcessList.addActivityInfo(callingPid, callingUid, stubInfo, targetInfo);
    }

    @Override
    public void onActivityDestory(int callingPid, int callingUid, ActivityInfo stubInfo, ActivityInfo targetInfo) {
        mRunningProcessList.removeActivityInfo(callingPid, callingUid, stubInfo, targetInfo);
        runProcessGC();
    }

    @Override
    public void onActivtyOnNewIntent(int callingPid, int callingUid, ActivityInfo stubInfo, ActivityInfo targetInfo, Intent intent) {
        mRunningProcessList.addActivityInfo(callingPid, callingUid, stubInfo, targetInfo);
    }

    @Override
    public void onServiceCreated(int callingPid, int callingUid, ServiceInfo stubInfo, ServiceInfo targetInfo) {
        mRunningProcessList.addServiceInfo(callingPid, callingUid, stubInfo, targetInfo);
    }

    @Override
    public void onServiceDestory(int callingPid, int callingUid, ServiceInfo stubInfo, ServiceInfo targetInfo) {
        mRunningProcessList.removeServiceInfo(callingPid, callingUid, stubInfo, targetInfo);
        runProcessGC();
    }

    @Override
    public void onProviderCreated(int callingPid, int callingUid, ProviderInfo stubInfo, ProviderInfo targetInfo) {
        mRunningProcessList.addProviderInfo(callingPid, callingUid, stubInfo, targetInfo);
    }

    @Override
    public void onReportMyProcessName(int callingPid, int callingUid, String stubProcessName, String targetProcessName, String targetPkg) {
        mRunningProcessList.setProcessName(callingPid, stubProcessName, targetProcessName, targetPkg);
    }

    @Override
    public List<String> getPackageNamesByPid(int pid) {
        return new ArrayList<String>(mRunningProcessList.getPackageNameByPid(pid));
    }

    @Override
    public ActivityInfo selectStubActivityInfo(int callingPid, int callingUid, ActivityInfo targetInfo) throws RemoteException {
        runProcessGC();
//        if (targetInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
//            targetInfo.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
//        }
//
//        if (targetInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
//            targetInfo.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
//        }
//
//        if (targetInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
//            targetInfo.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
//        }

        boolean Window_windowIsTranslucent = false;
        boolean Window_windowIsFloating = false;
        boolean Window_windowShowWallpaper = false;
        try {
            Class<?> R_Styleable_Class = Class.forName("com.android.internal.R$styleable");
            int[] R_Styleable_Window = (int[]) FieldUtils.readStaticField(R_Styleable_Class, "Window");
            int R_Styleable_Window_windowIsTranslucent = (int) FieldUtils.readStaticField(R_Styleable_Class, "Window_windowIsTranslucent");
            int R_Styleable_Window_windowIsFloating = (int) FieldUtils.readStaticField(R_Styleable_Class, "Window_windowIsFloating");
            int R_Styleable_Window_windowShowWallpaper = (int) FieldUtils.readStaticField(R_Styleable_Class, "Window_windowShowWallpaper");

            AttributeCache.Entry ent = AttributeCache.instance().get(targetInfo.packageName, targetInfo.theme,
                    R_Styleable_Window);
            if (ent != null && ent.array != null) {
                Window_windowIsTranslucent = ent.array.getBoolean(R_Styleable_Window_windowIsTranslucent, false);
                Window_windowIsFloating = ent.array.getBoolean(R_Styleable_Window_windowIsFloating, false);
                Window_windowShowWallpaper = ent.array.getBoolean(R_Styleable_Window_windowShowWallpaper, false);
            }
        } catch (Throwable e) {
            Log.e(TAG, "error on read com.android.internal.R$styleable", e);
        }

        boolean useDialogStyle = Window_windowIsTranslucent || Window_windowIsFloating || Window_windowShowWallpaper;

        // Start the process of running look to see whether there is compliance with the conditions of the process, if there is a direct use of
        String stubProcessName1 = mRunningProcessList.getStubProcessByTarget(targetInfo);
        if (stubProcessName1 != null) {
            List<ActivityInfo> stubInfos = mStaticProcessList.getActivityInfoForProcessName(stubProcessName1, useDialogStyle);
            for (ActivityInfo stubInfo : stubInfos) {
                if (stubInfo.launchMode == targetInfo.launchMode) {
                    if (stubInfo.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                        mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                        return stubInfo;
                    } else if (!mRunningProcessList.isStubInfoUsed(stubInfo, targetInfo, stubProcessName1)) {
                        mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                        return stubInfo;
                    }
                }
            }
        }

        List<String> stubProcessNames = mStaticProcessList.getProcessNames();
        for (String stubProcessName : stubProcessNames) {
            List<ActivityInfo> stubInfos = mStaticProcessList.getActivityInfoForProcessName(stubProcessName, useDialogStyle);
            if (mRunningProcessList.isProcessRunning(stubProcessName)) {// The predefined processes are running.
                if (mRunningProcessList.isPkgEmpty(stubProcessName)) {// Empty process, not running any add-on package.
                    for (ActivityInfo stubInfo : stubInfos) {
                        if (stubInfo.launchMode == targetInfo.launchMode) {
                            if (stubInfo.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                                mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                                return stubInfo;
                            } else if (!mRunningProcessList.isStubInfoUsed(stubInfo, targetInfo, stubProcessName1)) {
                                mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                                return stubInfo;
                            }
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else if (mRunningProcessList.isPkgCanRunInProcess(targetInfo.packageName, stubProcessName, targetInfo.processName)) {
                    for (ActivityInfo stubInfo : stubInfos) {
                        if (stubInfo.launchMode == targetInfo.launchMode) {
                            if (stubInfo.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                                mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                                return stubInfo;
                            } else if (!mRunningProcessList.isStubInfoUsed(stubInfo, targetInfo, stubProcessName1)) {
                                mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                                return stubInfo;
                            }
                        }
                    }
                    throw throwException("I did not find the right StubInfo");
                } else {
                    // To consider here signature the same situation, a plurality of plug-public process.
                }
            } else { // This process is not predefined.
                for (ActivityInfo stubInfo : stubInfos) {
                    if (stubInfo.launchMode == targetInfo.launchMode) {
                        if (stubInfo.launchMode == ActivityInfo.LAUNCH_MULTIPLE) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        } else if (!mRunningProcessList.isStubInfoUsed(stubInfo, targetInfo, stubProcessName1)) {
                            mRunningProcessList.setTargetProcessName(stubInfo, targetInfo);
                            return stubInfo;
                        }
                    }
                }
                throw throwException("I did not find the right StubInfo");
            }
        }
        throw throwException("There is no process available with");
    }

    private static final Comparator<RunningAppProcessInfo> sProcessComparator = new Comparator<RunningAppProcessInfo>() {
        @Override
        public int compare(RunningAppProcessInfo lhs, RunningAppProcessInfo rhs) {
            if (lhs.importance == rhs.importance) {
                return 0;
            } else if (lhs.importance > rhs.importance) {
                return 1;
            } else {
                return -1;
            }
        }
    };

    // Run the GC process
    private void runProcessGC() {
        if (mHostContext == null) {
            return;
        }
        ActivityManager am = (ActivityManager) mHostContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }

        List<RunningAppProcessInfo> infos = am.getRunningAppProcesses();
        List<RunningAppProcessInfo> myInfos = new ArrayList<RunningAppProcessInfo>();
        if (infos == null || infos.size() < 0) {
            return;
        }

        List<String> pns = mStaticProcessList.getOtherProcessNames();
        pns.add(mHostContext.getPackageName());
        for (RunningAppProcessInfo info : infos) {
            if (info.uid == android.os.Process.myUid()
                    && info.pid != android.os.Process.myPid()
                    && !pns.contains(info.processName)
                    && mRunningProcessList.isPlugin(info.pid)
                    && !mRunningProcessList.isPersistentApplication(info.pid)
                    /*&& !mRunningProcessList.isPersistentApplication(info.pid)*/) {
                myInfos.add(info);
            }
        }
        Collections.sort(myInfos, sProcessComparator);
        for (RunningAppProcessInfo myInfo : myInfos) {
            if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_GONE) {
                doGc(myInfo);
            } else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_EMPTY) {
                doGc(myInfo);
            } else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                doGc(myInfo);
            } else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                doGc(myInfo);
            } /* Else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE) {
                // Kill the process, the state can not be saved. But my business?
            } */ else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
                // Kill the process
            } else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                //visible
            } else if (myInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                // Foreground process.
            }
        }

    }

    private void doGc(RunningAppProcessInfo myInfo) {
        int activityCount = mRunningProcessList.getActivityCountByPid(myInfo.pid);
        int serviceCount = mRunningProcessList.getServiceCountByPid(myInfo.pid);
        int providerCount = mRunningProcessList.getProviderCountByPid(myInfo.pid);
        if (activityCount <= 0 && serviceCount <= 0 && providerCount <= 0) {
            // Kill empty processes.
            Log.i(TAG, "doGc kill process(pid=%s,uid=%s processName=%s)", myInfo.pid, myInfo.uid, myInfo.processName);
            try {
                android.os.Process.killProcess(myInfo.pid);
            } catch (Throwable e) {
                Log.e(TAG, "error on killProcess", e);
            }
        } else if (activityCount <= 0 && serviceCount > 0 /*&& !mRunningProcessList.isPersistentApplication(myInfo.pid)*/) {
            List<String> names = mRunningProcessList.getStubServiceByPid(myInfo.pid);
            if (names != null && names.size() > 0) {
                for (String name : names) {
                    Intent service = new Intent();
                    service.setClassName(mHostContext.getPackageName(), name);
                    AbstractServiceStub.startKillService(mHostContext, service);
                    Log.i(TAG, "doGc kill process(pid=%s,uid=%s processName=%s) service=%s", myInfo.pid, myInfo.uid, myInfo.processName, service);
                }
            }
        }
    }
}
