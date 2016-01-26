/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IInterface;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Controls a test {@link Context} as would be provided by the Android framework to an
 * {@code Activity}, {@code Service} or other system-instantiated component.
 *
 * Contains Fake<Component> classes like FakeContext for components that require complex and
 * reusable stubbing. Others can be mocked using Mockito functions in tests or constructor/public
 * methods of this class.
 */
public class ContextFixture implements TestFixture<Context> {
    private static final String TAG = "ContextFixture";

    public class FakeContentProvider extends MockContentProvider {
        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            if (uri.compareTo(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "raw")) == 0) {
                return Uri.withAppendedPath(uri, "1");
            }
            return null;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                            String sortOrder) {
            return null;
        }

        @Override
        public Bundle call(String method, String request, Bundle args) {
            return null;
        }
    }

    public class FakeContext extends MockContext {
        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public boolean bindService(
                Intent serviceIntent,
                ServiceConnection connection,
                int flags) {
            if (mServiceByServiceConnection.containsKey(connection)) {
                throw new RuntimeException("ServiceConnection already bound: " + connection);
            }
            IInterface service = mServiceByComponentName.get(serviceIntent.getComponent());
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: "
                        + serviceIntent.getComponent());
            }
            mServiceByServiceConnection.put(connection, service);
            connection.onServiceConnected(serviceIntent.getComponent(), service.asBinder());
            return true;
        }

        @Override
        public void unbindService(
                ServiceConnection connection) {
            IInterface service = mServiceByServiceConnection.remove(connection);
            if (service == null) {
                throw new RuntimeException("ServiceConnection not found: " + connection);
            }
            connection.onServiceDisconnected(mComponentNameByService.get(service));
        }

        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.TELEPHONY_SERVICE:
                    return mTelephonyManager;
                case Context.APP_OPS_SERVICE:
                    return mAppOpsManager;
                case Context.NOTIFICATION_SERVICE:
                    return mNotificationManager;
                case Context.USER_SERVICE:
                    return mUserManager;
                case Context.CARRIER_CONFIG_SERVICE:
                    return mCarrierConfigManager;
                case Context.POWER_SERVICE:
                    // PowerManager is a final class so cannot be mocked, return real service
                    return TestApplication.getAppContext().getSystemService(name);
                case Context.TELEPHONY_SUBSCRIPTION_SERVICE:
                    return mSubscriptionManager;
                case Context.WIFI_SERVICE:
                    return mWifiManager;
                case Context.ALARM_SERVICE:
                    return mAlarmManager;
                default:
                    return null;
            }
        }

        @Override
        public int getUserId() {
            return 0;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public String getOpPackageName() {
            return "com.android.internal.telephony";
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            for (int i = 0 ; i < filter.countActions(); i++) {
                mBroadcastReceiversByAction.put(filter.getAction(i), receiver);
            }
            return null;
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler) {
            return null;
        }

        @Override
        public void sendBroadcast(Intent intent) {
            logd("sendBroadcast called for " + intent.getAction());
            for (BroadcastReceiver broadcastReceiver :
                    mBroadcastReceiversByAction.get(intent.getAction())) {
                broadcastReceiver.onReceive(mContext, intent);
            }
        }

        @Override
        public void sendBroadcast(Intent intent, String receiverPermission) {
            // TODO -- need to ensure this is captured
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, BroadcastReceiver resultReceiver, Handler scheduler,
                int initialCode, String initialData, Bundle initialExtras) {
            logd("sendOrderedBroadcastAsUser called for " + intent.getAction());
            sendBroadcast(intent);
            if (resultReceiver != null) {
                resultReceiver.onReceive(this, intent);
            }
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, int appOp, BroadcastReceiver resultReceiver,
                Handler scheduler, int initialCode, String initialData, Bundle initialExtras) {
            logd("sendOrderedBroadcastAsUser called for " + intent.getAction());
            sendBroadcast(intent);
            if (resultReceiver != null) {
                resultReceiver.onReceive(this, intent);
            }
        }

        @Override
        public void sendOrderedBroadcastAsUser(Intent intent, UserHandle user,
                String receiverPermission, int appOp, Bundle options,
                BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
                String initialData, Bundle initialExtras) {
            logd("sendOrderedBroadcastAsUser called for " + intent.getAction());
            sendBroadcast(intent);
            if (resultReceiver != null) {
                resultReceiver.onReceive(this, intent);
            }
        }

        @Override
        public void sendStickyBroadcastAsUser(Intent intent, UserHandle user) {
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            return this;
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            // Don't bother enforcing anything in mock.
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return mSharedPreferences;
        }

        @Override
        public String getPackageName() {
            return "com.android.internal.telephony";
        }

        public int testMethod() {
            return 0;
        }

        public boolean testMethod1() {
            return true;
        }
    }

    private final Multimap<String, ComponentName> mComponentNamesByAction =
            ArrayListMultimap.create();
    private final Map<ComponentName, IInterface> mServiceByComponentName =
            new HashMap<ComponentName, IInterface>();
    private final Map<ComponentName, ServiceInfo> mServiceInfoByComponentName =
            new HashMap<ComponentName, ServiceInfo>();
    private final Map<IInterface, ComponentName> mComponentNameByService =
            new HashMap<IInterface, ComponentName>();
    private final Map<ServiceConnection, IInterface> mServiceByServiceConnection =
            new HashMap<ServiceConnection, IInterface>();
    private final Multimap<String, BroadcastReceiver> mBroadcastReceiversByAction =
            ArrayListMultimap.create();

    // The application context is the most important object this class provides to the system
    // under test.
    private final Context mContext = spy(new FakeContext());

    // We then create a spy on the application context allowing standard Mockito-style
    // when(...) logic to be used to add specific little responses where needed.

    private final Resources mResources = mock(Resources.class);
    private final PackageManager mPackageManager = mock(PackageManager.class);
    private final TelephonyManager mTelephonyManager = mock(TelephonyManager.class);
    private final AppOpsManager mAppOpsManager = mock(AppOpsManager.class);
    private final NotificationManager mNotificationManager = mock(NotificationManager.class);
    private final UserManager mUserManager = mock(UserManager.class);
    private final CarrierConfigManager mCarrierConfigManager = mock(CarrierConfigManager.class);
    private final SubscriptionManager mSubscriptionManager = mock(SubscriptionManager.class);
    private final AlarmManager mAlarmManager = mock(AlarmManager.class);
    private final WifiManager mWifiManager = mock(WifiManager.class);
    private final ContentProvider mContentProvider = spy(new FakeContentProvider());
    private final SharedPreferences mSharedPreferences = mock(SharedPreferences.class);
    private final MockContentResolver mContentResolver = new MockContentResolver();
    private final PersistableBundle mBundle = new PersistableBundle();

    public ContextFixture() {
        MockitoAnnotations.initMocks(this);

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServices((Intent) any(), anyInt());

        doAnswer(new Answer<List<ResolveInfo>>() {
            @Override
            public List<ResolveInfo> answer(InvocationOnMock invocation) throws Throwable {
                return doQueryIntentServices(
                        (Intent) invocation.getArguments()[0],
                        (Integer) invocation.getArguments()[1]);
            }
        }).when(mPackageManager).queryIntentServicesAsUser((Intent) any(), anyInt(), anyInt());

        // return default value unless overridden by test
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                return args[1];
            }
        }).when(mSharedPreferences).getBoolean(anyString(), anyBoolean());

        doReturn(mBundle).when(mCarrierConfigManager).getConfigForSubId(anyInt());

        mContentResolver.addProvider(Telephony.Sms.CONTENT_URI.getAuthority(), mContentProvider);
        mContentResolver.addProvider(Settings.System.CONTENT_URI.getAuthority(), mContentProvider);
    }

    @Override
    public Context getTestDouble() {
        return mContext;
    }

    public void putResource(int id, final String value) {
        when(mResources.getText(eq(id))).thenReturn(value);
        when(mResources.getString(eq(id))).thenReturn(value);
        when(mResources.getString(eq(id), any())).thenAnswer(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                return String.format(value, Arrays.copyOfRange(args, 1, args.length));
            }
        });
    }

    public void putBooleanResource(int id, boolean value) {
        when(mResources.getBoolean(eq(id))).thenReturn(value);
    }

    public void putStringArrayResource(int id, String[] values) {
        doReturn(values).when(mResources).getStringArray(eq(id));
    }

    public PersistableBundle getCarrierConfigBundle() {
        return mBundle;
    }

    private void addService(String action, ComponentName name, IInterface service) {
        mComponentNamesByAction.put(action, name);
        mServiceByComponentName.put(name, service);
        mComponentNameByService.put(service, name);
    }

    private List<ResolveInfo> doQueryIntentServices(Intent intent, int flags) {
        List<ResolveInfo> result = new ArrayList<ResolveInfo>();
        for (ComponentName componentName : mComponentNamesByAction.get(intent.getAction())) {
            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.serviceInfo = mServiceInfoByComponentName.get(componentName);
            result.add(resolveInfo);
        }
        return result;
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }
}