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
package com.android.systemui.statusbar.connectivity;

import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE;
import static com.android.settingslib.mobile.MobileMappings.toDisplayIconKey;
import static com.android.settingslib.mobile.MobileMappings.toIconKey;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.TelephonyNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Global;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthNr;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.ImsMmTelManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyIntents;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.graph.SignalDrawable;
import com.android.settingslib.mobile.MobileMappings.Config;
import com.android.settingslib.mobile.MobileStatusTracker;
import com.android.settingslib.mobile.MobileStatusTracker.MobileStatus;
import com.android.settingslib.mobile.MobileStatusTracker.SubscriptionDefaults;
import com.android.settingslib.mobile.TelephonyIcons;
import com.android.settingslib.net.SignalStrengthUtil;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy;
import com.android.systemui.statusbar.policy.FiveGServiceClient;
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState;
import com.android.systemui.statusbar.policy.FiveGServiceClient.IFiveGStateListener;
import com.android.systemui.util.CarrierConfigTracker;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.sun.systemui.statusbar.connectivity.CustomSignalController;

/**
 * Monitors the mobile signal changes and update the SysUI icons.
 */
public class MobileSignalController extends SignalController<MobileState, MobileIconGroup> {
    private static final SimpleDateFormat SSDF = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private static final int STATUS_HISTORY_SIZE = 64;
    private final TelephonyManager mPhone;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final CustomSignalController mCustomSignalController;
    private final CustomSignalController.Callback mCustomCallback;
    private final ImsMmTelManager mImsMmTelManager;
    private final SubscriptionDefaults mDefaults;
    private final MobileMappingsProxy mMobileMappingsProxy;
    private final String mNetworkNameDefault;
    private final String mNetworkNameSeparator;
    private final ContentObserver mObserver;
    // Save entire info for logging, we only use the id.
    final SubscriptionInfo mSubscriptionInfo;
    private Map<String, MobileIconGroup> mNetworkToIconLookup;

    private MobileIconGroup mDefaultIcons;
    private Config mConfig;
    @VisibleForTesting
    boolean mInflateSignalStrengths = false;
    @VisibleForTesting
    final MobileStatusTracker mMobileStatusTracker;

    // Save the previous STATUS_HISTORY_SIZE states for logging.
    private final String[] mMobileStatusHistory = new String[STATUS_HISTORY_SIZE];
    // Where to copy the next state into.
    private int mMobileStatusHistoryIndex;

    private int mCallState = TelephonyManager.CALL_STATE_IDLE;

    /****************************SideCar****************************/
    @VisibleForTesting
    FiveGStateListener mFiveGStateListener;
    @VisibleForTesting
    FiveGServiceState mFiveGState;
    private FiveGServiceClient mClient;
    /**********************************************************/

    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mIsConnectionFailed = false;

    private final MobileStatusTracker.Callback mMobileCallback =
            new MobileStatusTracker.Callback() {
                private String mLastStatus;

                @Override
                public void onMobileStatusChanged(boolean updateTelephony,
                        MobileStatus mobileStatus) {
                    if (DEBUG) {
                        Log.d(mTag, "onMobileStatusChanged="
                                + " updateTelephony=" + updateTelephony
                                + " mobileStatus=" + mobileStatus.toString());
                    }
                    String currentStatus = mobileStatus.toString();
                    if (!currentStatus.equals(mLastStatus)) {
                        mLastStatus = currentStatus;
                        String status = new StringBuilder()
                                .append(SSDF.format(System.currentTimeMillis())).append(",")
                                .append(currentStatus)
                                .toString();
                        recordLastMobileStatus(status);
                    }
                    updateMobileStatus(mobileStatus);
                    if (updateTelephony) {
                        updateTelephony();
                    } else {
                        notifyListenersIfNecessary();
                    }
                }
            };

    // TODO: Reduce number of vars passed in, if we have the NetworkController, probably don't
    // need listener lists anymore.
    public MobileSignalController(
            Context context,
            Config config,
            boolean hasMobileData,
            TelephonyManager phone,
            CallbackHandler callbackHandler,
            NetworkControllerImpl networkController,
            MobileMappingsProxy mobileMappingsProxy,
            SubscriptionInfo info,
            SubscriptionDefaults defaults,
            Looper receiverLooper,
            CarrierConfigTracker carrierConfigTracker,
            CustomSignalController customSignalController,
            MobileStatusTrackerFactory mobileStatusTrackerFactory
    ) {
        super("MobileSignalController(" + info.getSubscriptionId() + ")", context,
                NetworkCapabilities.TRANSPORT_CELLULAR, callbackHandler,
                networkController);
        mCarrierConfigTracker = carrierConfigTracker;
        mConfig = config;
        mCustomSignalController = customSignalController;
        mPhone = phone;
        mDefaults = defaults;
        mSubscriptionInfo = info;
        mMobileMappingsProxy = mobileMappingsProxy;
        mFiveGStateListener = new FiveGStateListener();
        mFiveGState = new FiveGServiceState();
        mNetworkNameSeparator = getTextIfExists(
                R.string.status_bar_network_name_separator).toString();
        mNetworkNameDefault = getTextIfExists(
                com.android.internal.R.string.lockscreen_carrier_default).toString();

        mNetworkToIconLookup = mMobileMappingsProxy.mapIconSets(mConfig);
        mDefaultIcons = mMobileMappingsProxy.getDefaultIcons(mConfig);

        String networkName = info.getCarrierName() != null ? info.getCarrierName().toString()
                : mNetworkNameDefault;
        mLastState.networkName = mCurrentState.networkName = networkName;
        mLastState.networkNameData = mCurrentState.networkNameData = networkName;
        mLastState.enabled = mCurrentState.enabled = hasMobileData;
        mLastState.iconGroup = mCurrentState.iconGroup = mDefaultIcons;

        mCustomCallback = new CustomSignalController.Callback() {
            @Override
            public void onSettingsChanged(boolean dataDisabledIcon, boolean show4gIcon) {
                mConfig = Config.readConfig(mContext);
                setConfiguration(mConfig);
                notifyListeners();
            }
        };
        mObserver = new ContentObserver(new Handler(receiverLooper)) {
            @Override
            public void onChange(boolean selfChange) {
                updateTelephony();
            }
        };
        mImsMmTelManager = ImsMmTelManager.createForSubscriptionId(info.getSubscriptionId());
        mMobileStatusTracker = mobileStatusTrackerFactory.createTracker(mMobileCallback);
        mNetworkCallback = new NetworkCallback(NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                mIsConnectionFailed =
                    !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        };
    }

    void setConfiguration(Config config) {
        mConfig = config;
        updateInflateSignalStrength();
        mNetworkToIconLookup = mMobileMappingsProxy.mapIconSets(mConfig);
        mDefaultIcons = mMobileMappingsProxy.getDefaultIcons(mConfig);
        updateTelephony();
    }

    void setAirplaneMode(boolean airplaneMode) {
        mCurrentState.airplaneMode = airplaneMode;
        notifyListenersIfNecessary();
    }

    void setUserSetupComplete(boolean userSetup) {
        mCurrentState.userSetup = userSetup;
        notifyListenersIfNecessary();
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        boolean isValidated = validatedTransports.get(mTransportType);
        mCurrentState.isDefault = connectedTransports.get(mTransportType);
        // Only show this as not having connectivity if we are default.
        mCurrentState.inetCondition = (isValidated || !mCurrentState.isDefault) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    void setCarrierNetworkChangeMode(boolean carrierNetworkChangeMode) {
        mCurrentState.carrierNetworkChangeMode = carrierNetworkChangeMode;
        updateTelephony();
    }

    /**
     * Start listening for phone state changes.
     */
    public void registerListener() {
        mMobileStatusTracker.setListening(true);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.MOBILE_DATA),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.MOBILE_DATA + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(Global.DATA_ROAMING),
                true, mObserver);
        mContext.getContentResolver().registerContentObserver(Global.getUriFor(
                Global.DATA_ROAMING + mSubscriptionInfo.getSubscriptionId()),
                true, mObserver);
        mContext.registerReceiver(mVolteSwitchObserver,
                new IntentFilter("org.codeaurora.intent.action.ACTION_ENHANCE_4G_SWITCH"), Context.RECEIVER_EXPORTED);
        if (mConfig.showVolteIcon || mConfig.showVowifiIcon) {
            try {
                mImsMmTelManager.registerImsStateCallback(mContext.getMainExecutor(),
                        mImsStateCallback);
            }catch (ImsException exception) {
                Log.e(mTag, "failed to call registerImsStateCallback ", exception);
            }
        }
        mConnectivityManager = (ConnectivityManager)
            mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        TelephonyNetworkSpecifier specifier = new TelephonyNetworkSpecifier.Builder()
            .setSubscriptionId(mSubscriptionInfo.getSubscriptionId()).build();
        builder.setNetworkSpecifier(specifier);
        final NetworkRequest request = builder.build();
        mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
        mCustomSignalController.addCallback(mCustomCallback);
    }

    /**
     * Stop listening for phone state changes.
     */
    public void unregisterListener() {
        mCustomSignalController.removeCallback(mCustomCallback);
        mMobileStatusTracker.setListening(false);
        mContext.getContentResolver().unregisterContentObserver(mObserver);
        mContext.unregisterReceiver(mVolteSwitchObserver);
        if (mConfig.showVolteIcon || mConfig.showVowifiIcon) {
            mImsMmTelManager.unregisterImsStateCallback(mImsStateCallback);
        }
        if (mNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        }
    }

    private void updateInflateSignalStrength() {
        mInflateSignalStrengths = SignalStrengthUtil.shouldInflateSignalStrength(mContext,
                mSubscriptionInfo.getSubscriptionId());
    }

    private int getNumLevels() {
        if (mInflateSignalStrengths) {
            return CellSignalStrength.getNumSignalStrengthLevels() + 1;
        }
        return CellSignalStrength.getNumSignalStrengthLevels();
    }

    @Override
    public int getCurrentIconId() {
        if (mCurrentState.iconGroup == TelephonyIcons.CARRIER_NETWORK_CHANGE) {
            return SignalDrawable.getCarrierChangeState(getNumLevels());
        } else if (mCurrentState.connected) {
            int level = mCurrentState.level;
            if (mInflateSignalStrengths) {
                level++;
            }

            boolean dataDisabled = mCurrentState.userSetup
                    && (mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                    || (mCurrentState.iconGroup == TelephonyIcons.NOT_DEFAULT_DATA
                            && mCurrentState.defaultDataOff));
            boolean noInternet = mCurrentState.inetCondition == 0;
            boolean isDataEnabled = mCurrentState.mobileDataEnabled;
            boolean isDataConnected = mCurrentState.dataState == TelephonyManager.DATA_CONNECTED;
            boolean isInService = isInService();
            boolean cutOut = !isDataEnabled
                || (isDataConnected && mIsConnectionFailed) || !isInService;
            if (mConfig.hideNoInternetState) {
                cutOut = false;
            }
            return SignalDrawable.getState(level, getNumLevels(), cutOut);
        } else if (mCurrentState.enabled) {
            return SignalDrawable.getEmptyState(getNumLevels());
        } else {
            return 0;
        }
    }

    @Override
    public int getQsCurrentIconId() {
        return getCurrentIconId();
    }

    private int getVolteResId() {
        int resId = 0;
        int voiceNetTye = mCurrentState.getVoiceNetworkType();
        if ( (mCurrentState.voiceCapable || mCurrentState.videoCapable)
                &&  mCurrentState.imsRegistered ) {
            resId = R.drawable.ic_volte;
        }else if ( (mCurrentState.telephonyDisplayInfo.getNetworkType() == TelephonyManager.NETWORK_TYPE_LTE
                    || mCurrentState.telephonyDisplayInfo.getNetworkType() ==
                        TelephonyManager.NETWORK_TYPE_LTE_CA)
                    && voiceNetTye  == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
            resId = R.drawable.ic_volte_no_voice;
        }
        return resId;
    }

    private void setListeners() {
        try {
            Log.d(mTag, "setListeners: register CapabilitiesCallback and RegistrationCallback");
            mImsMmTelManager.registerMmTelCapabilityCallback(mContext.getMainExecutor(),
                    mCapabilityCallback);
        } catch (ImsException e) {
            Log.e(mTag, "unable to register listeners.", e);
        }
        queryImsState();
    }

    private void queryImsState() {
        TelephonyManager tm = mPhone.createForSubscriptionId(mSubscriptionInfo.getSubscriptionId());
        mCurrentState.voiceCapable = tm.isVolteAvailable();
        mCurrentState.videoCapable = tm.isVideoTelephonyAvailable();
        mCurrentState.imsRegistered = mPhone.isImsRegistered(mSubscriptionInfo.getSubscriptionId());
        if (DEBUG) {
            Log.d(mTag, "queryImsState tm=" + tm + " phone=" + mPhone
                    + " voiceCapable=" + mCurrentState.voiceCapable
                    + " videoCapable=" + mCurrentState.videoCapable
                    + " imsResitered=" + mCurrentState.imsRegistered);
        }
        notifyListenersIfNecessary();
    }

    private void removeListeners() {
        Log.d(mTag, "removeListeners: unregister CapabilitiesCallback and RegistrationCallback");
        mImsMmTelManager.unregisterMmTelCapabilityCallback(mCapabilityCallback);
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        // If the device is on carrier merged WiFi, we should let WifiSignalController to control
        // the SysUI states.
        if (mNetworkController.isCarrierMergedWifi(mSubscriptionInfo.getSubscriptionId())) {
            return;
        }
        MobileIconGroup icons = getIcons();

        String contentDescription = getTextIfExists(getContentDescription()).toString();
        CharSequence dataContentDescriptionHtml = getTextIfExists(icons.dataContentDescription);

        //TODO: Hacky
        // The data content description can sometimes be shown in a text view and might come to us
        // as HTML. Strip any styling here so that listeners don't have to care
        CharSequence dataContentDescription = Html.fromHtml(
                dataContentDescriptionHtml.toString(), 0).toString();
        if (mCurrentState.inetCondition == 0) {
            dataContentDescription = mContext.getString(R.string.data_connection_no_internet);
        }

        int iconId = mCurrentState.getNetworkTypeIcon(mContext);
        final QsInfo qsInfo = getQsInfo(contentDescription, iconId);
        final SbInfo sbInfo = getSbInfo(contentDescription, iconId);

        int volteIcon = mConfig.showVolteIcon ? getVolteResId() : 0;
        MobileDataIndicators mobileDataIndicators = new MobileDataIndicators(
                sbInfo.icon,
                qsInfo.icon,
                sbInfo.ratTypeIcon,
                qsInfo.ratTypeIcon,
                mCurrentState.hasActivityIn(),
                mCurrentState.hasActivityOut(),
                volteIcon,
                dataContentDescription,
                dataContentDescriptionHtml,
                qsInfo.description,
                mSubscriptionInfo.getSubscriptionId(),
                mCurrentState.roaming,
                sbInfo.showTriangle,
                mCurrentState.isDefault);
        callback.setMobileDataIndicators(mobileDataIndicators);
    }

    private QsInfo getQsInfo(String contentDescription, int dataTypeIcon) {
        int qsTypeIcon = 0;
        IconState qsIcon = null;
        CharSequence qsDescription = null;

        if (mCurrentState.dataSim) {
            if (mCurrentState.showQuickSettingsRatIcon() || mConfig.alwaysShowDataRatIcon) {
                qsTypeIcon = dataTypeIcon;
            }

            boolean qsIconVisible = mCurrentState.enabled && !mCurrentState.isEmergency;
            qsIcon = new IconState(qsIconVisible, getQsCurrentIconId(), contentDescription);

            if (!mCurrentState.isEmergency) {
                qsDescription = mCurrentState.networkName;
            }
        }

        return new QsInfo(qsTypeIcon, qsIcon, qsDescription);
    }

    private SbInfo getSbInfo(String contentDescription, int dataTypeIcon) {
        final boolean dataDisabled = mCurrentState.isDataDisabledOrNotDefault();
        IconState statusIcon = new IconState(
                mCurrentState.enabled && !mCurrentState.airplaneMode,
                getCurrentIconId(), contentDescription);

        boolean showDataIconInStatusBar =
                (mCurrentState.dataConnected && mCurrentState.isDefault) || dataDisabled;
        int typeIcon =
                (showDataIconInStatusBar || mConfig.alwaysShowDataRatIcon) ? dataTypeIcon : 0;
        boolean showTriangle = mCurrentState.enabled && !mCurrentState.airplaneMode;

        if ( mConfig.enableRatIconEnhancement ) {
            typeIcon = getEnhancementDataRatIcon();
        }else if ( mConfig.enableDdsRatIconEnhancement ) {
            typeIcon = getEnhancementDdsRatIcon();
        }

        MobileIconGroup vowifiIconGroup = getVowifiIconGroup();
        if (mConfig.showVowifiIcon && vowifiIconGroup != null) {
            typeIcon = vowifiIconGroup.dataType;
            statusIcon = new IconState(true,
                    ((mCurrentState.enabled && !mCurrentState.airplaneMode) ? statusIcon.icon : -1),
                    statusIcon.contentDescription);
        }

        return new SbInfo(showTriangle, typeIcon, statusIcon);
    }

    @Override
    protected MobileState cleanState() {
        return new MobileState();
    }

    public boolean isInService() {
        return mCurrentState.isInService();
    }

    String getNetworkNameForCarrierWiFi() {
        return mPhone.getSimOperatorName();
    }

    private boolean isRoaming() {
        // During a carrier change, roaming indications need to be suppressed.
        if (isCarrierNetworkChangeActive()) {
            return false;
        }
        if (mCurrentState.isCdma()) {
            return mPhone.getCdmaEnhancedRoamingIndicatorDisplayNumber()
                    != TelephonyManager.ERI_OFF;
        } else {
            return mCurrentState.isRoaming();
        }
    }

    private boolean isCarrierNetworkChangeActive() {
        return mCurrentState.carrierNetworkChangeMode;
    }

    void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)) {
            updateNetworkName(intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_SPN),
                    intent.getStringExtra(TelephonyManager.EXTRA_DATA_SPN),
                    intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false),
                    intent.getStringExtra(TelephonyManager.EXTRA_PLMN));
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            updateDataSim();
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED)) {
            int carrierId = intent.getIntExtra(
                    TelephonyManager.EXTRA_CARRIER_ID, UNKNOWN_CARRIER_ID);
            mCurrentState.setCarrierId(carrierId);
        }
    }

    private void updateDataSim() {
        int activeDataSubId = mDefaults.getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)) {
            mCurrentState.dataSim = activeDataSubId == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mCurrentState.dataSim = true;
        }
    }

    /**
     * Updates the network's name based on incoming spn and plmn.
     */
    void updateNetworkName(boolean showSpn, String spn, String dataSpn,
            boolean showPlmn, String plmn) {
        if (CHATTY) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " dataSpn=" + dataSpn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        StringBuilder str = new StringBuilder();
        StringBuilder strData = new StringBuilder();
        if (showPlmn && plmn != null) {
            str.append(plmn);
            strData.append(plmn);
        }
        if (showSpn && spn != null && !str.toString().equalsIgnoreCase(spn)) {
            if (str.length() != 0) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
        }
        if (str.length() != 0) {
            mCurrentState.networkName = str.toString();
        } else {
            mCurrentState.networkName = mNetworkNameDefault;
        }
        if (showSpn && dataSpn != null && !strData.toString().equalsIgnoreCase(dataSpn)) {
            if (strData.length() != 0) {
                strData.append(mNetworkNameSeparator);
            }
            strData.append(dataSpn);
        }
        if (strData.length() != 0) {
            mCurrentState.networkNameData = strData.toString();
        } else {
            mCurrentState.networkNameData = mNetworkNameDefault;
        }
    }

    /**
     * Extracts the CellSignalStrengthCdma from SignalStrength then returns the level
     */
    private int getCdmaLevel(SignalStrength signalStrength) {
        List<CellSignalStrengthCdma> signalStrengthCdma =
                signalStrength.getCellSignalStrengths(CellSignalStrengthCdma.class);
        if (!signalStrengthCdma.isEmpty()) {
            return signalStrengthCdma.get(0).getLevel();
        }
        return CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
    }

    private void updateMobileStatus(MobileStatus mobileStatus) {
        mCurrentState.setFromMobileStatus(mobileStatus);
    }

    int getSignalLevel(SignalStrength signalStrength) {
        if (signalStrength == null) {
            return 0;
        }
        if (!signalStrength.isGsm() && mConfig.alwaysShowCdmaRssi) {
            return getCdmaLevel(signalStrength);
        } else {
            return signalStrength.getLevel();
        }
    }

    /**
     * Updates the current state based on ServiceState, SignalStrength, DataState,
     * TelephonyDisplayInfo, and sim state.  It should be called any time one of these is updated.
     * This will call listeners if necessary.
     */
    private void updateTelephony() {
        if (DEBUG) {
            Log.d(mTag, "updateTelephonySignalStrength: hasService="
                    + mCurrentState.isInService()
                    + " ss=" + mCurrentState.signalStrength
                    + " displayInfo=" + mCurrentState.telephonyDisplayInfo);
        }
        checkDefaultData();
        mCurrentState.connected = mCurrentState.isInService();
        if (mCurrentState.connected) {
            mCurrentState.level = getSignalLevel(mCurrentState.signalStrength);
            if (mConfig.showRsrpSignalLevelforLTE) {
                 if (DEBUG) {
                     Log.d(mTag, "updateTelephony CS:" + mCurrentState.getVoiceNetworkType()
                             + "/" + TelephonyManager.getNetworkTypeName(
                             mCurrentState.getVoiceNetworkType())
                             + ", PS:" + mCurrentState.getDataNetworkType()
                             + "/"+ TelephonyManager.getNetworkTypeName(
                             mCurrentState.getDataNetworkType()));
                 }
                 int dataType = mCurrentState.getDataNetworkType();
                 if (dataType == TelephonyManager.NETWORK_TYPE_LTE ||
                         dataType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                     mCurrentState.level = getAlternateLteLevel(mCurrentState.signalStrength);
                 } else if (dataType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                     int voiceType = mCurrentState.getVoiceNetworkType();
                     if (voiceType == TelephonyManager.NETWORK_TYPE_LTE ||
                             voiceType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                         mCurrentState.level = getAlternateLteLevel(mCurrentState.signalStrength);
                     }
                 }
            }
        }

        mCurrentState.setCarrierId(mPhone.getSimCarrierId());
        String iconKey = mMobileMappingsProxy.getIconKey(mCurrentState.telephonyDisplayInfo);
        if (mNetworkToIconLookup.get(iconKey) != null) {
            mCurrentState.iconGroup = mNetworkToIconLookup.get(iconKey);
        } else {
            mCurrentState.iconGroup = mDefaultIcons;
        }

        //Modem has centralized logic to display 5G icon based on carrier requirements
        //For 5G icon display, only query NrIconType reported by modem
        if ( mFiveGState.isNrIconTypeValid() ) {
            mCurrentState.iconGroup = mFiveGState.getIconGroup();
        }else {
            mCurrentState.iconGroup = getNetworkTypeIconGroup();
        }

        mCurrentState.dataConnected = mCurrentState.isDataConnected();

        mCurrentState.roaming = isRoaming();
        if (isCarrierNetworkChangeActive()) {
            mCurrentState.iconGroup = TelephonyIcons.CARRIER_NETWORK_CHANGE;
        } else if (isDataDisabled() && mCustomSignalController.showDataDisabledIcon()) {
            if (mSubscriptionInfo.getSubscriptionId() != mDefaults.getDefaultDataSubId()) {
                mCurrentState.iconGroup = TelephonyIcons.NOT_DEFAULT_DATA;
            } else {
                mCurrentState.iconGroup = TelephonyIcons.DATA_DISABLED;
            }
        }
        if (mCurrentState.isEmergencyOnly() != mCurrentState.isEmergency) {
            mCurrentState.isEmergency = mCurrentState.isEmergencyOnly();
            mNetworkController.recalculateEmergency();
        }
        // Fill in the network name if we think we have it.
        if (mCurrentState.networkName.equals(mNetworkNameDefault)
                && !TextUtils.isEmpty(mCurrentState.getOperatorAlphaShort())) {
            mCurrentState.networkName = mCurrentState.getOperatorAlphaShort();
        }
        // If this is the data subscription, update the currentState data name
        if (mCurrentState.networkNameData.equals(mNetworkNameDefault)
                && mCurrentState.dataSim
                && !TextUtils.isEmpty(mCurrentState.getOperatorAlphaShort())) {
            mCurrentState.networkNameData = mCurrentState.getOperatorAlphaShort();
        }


        if ( mConfig.alwaysShowNetworkTypeIcon ) {
            if(!mCurrentState.connected) {
                mCurrentState.iconGroup = TelephonyIcons.UNKNOWN;
            }else if (mFiveGState.isNrIconTypeValid()) {
                mCurrentState.iconGroup = mFiveGState.getIconGroup();
            }else {
                mCurrentState.iconGroup = getNetworkTypeIconGroup();
            }
        }
        mCurrentState.mobileDataEnabled = mPhone.isDataEnabled();
        mCurrentState.roamingDataEnabled = mPhone.isDataRoamingEnabled();

        notifyListenersIfNecessary();
    }

    /**
     * If we are controlling the NOT_DEFAULT_DATA icon, check the status of the other one
     */
    private void checkDefaultData() {
        if (mCurrentState.iconGroup != TelephonyIcons.NOT_DEFAULT_DATA) {
            mCurrentState.defaultDataOff = false;
            return;
        }

        mCurrentState.defaultDataOff = mNetworkController.isDataControllerDisabled();
    }

    void onMobileDataChanged() {
        checkDefaultData();
        notifyListenersIfNecessary();
    }

    boolean isDataDisabled() {
        return !mPhone.isDataConnectionAllowed();
    }

    private boolean isDataNetworkTypeAvailable() {
        boolean isAvailable = true;
        if (mCurrentState.telephonyDisplayInfo.getNetworkType() == TelephonyManager.NETWORK_TYPE_UNKNOWN ) {
            isAvailable = false;
        }else {
            int dataType = getDataNetworkType();
            int voiceType = getVoiceNetworkType();
            if ((dataType == TelephonyManager.NETWORK_TYPE_EVDO_A
                    || dataType == TelephonyManager.NETWORK_TYPE_EVDO_B
                    || dataType == TelephonyManager.NETWORK_TYPE_EHRPD
                    || dataType == TelephonyManager.NETWORK_TYPE_LTE
                    || dataType == TelephonyManager.NETWORK_TYPE_LTE_CA)
                    && (voiceType == TelephonyManager.NETWORK_TYPE_GSM
                    || voiceType == TelephonyManager.NETWORK_TYPE_1xRTT
                    || voiceType == TelephonyManager.NETWORK_TYPE_CDMA)
                    && ( !isCallIdle() )) {
                isAvailable = false;
            }
        }

        return isAvailable;
    }

    private boolean isCallIdle() {
        return mCallState == TelephonyManager.CALL_STATE_IDLE;
    }

    private int getVoiceNetworkType() {
        // TODO(b/214591923)
        //return mServiceState != null ?
        //        mServiceState.getVoiceNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    private int getDataNetworkType() {
        // TODO(b/214591923)
        //return mServiceState != null ?
        //        mServiceState.getDataNetworkType() : TelephonyManager.NETWORK_TYPE_UNKNOWN;
        return TelephonyManager.NETWORK_TYPE_UNKNOWN;
    }

    private int getAlternateLteLevel(SignalStrength signalStrength) {
        if (signalStrength == null) {
            Log.e(mTag, "getAlternateLteLevel signalStrength is null");
            return 0;
        }

        int lteRsrp = signalStrength.getLteDbm();
        if ( lteRsrp == SignalStrength.INVALID ) {
            int signalStrengthLevel = signalStrength.getLevel();
            if (DEBUG) {
                Log.d(mTag, "getAlternateLteLevel lteRsrp:INVALID "
                        + " signalStrengthLevel = " + signalStrengthLevel);
            }
            return signalStrengthLevel;
        }

        int rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (lteRsrp > -44) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (lteRsrp >= -97) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GREAT;
        else if (lteRsrp >= -105) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GOOD;
        else if (lteRsrp >= -113) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_MODERATE;
        else if (lteRsrp >= -120) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_POOR;
        else if (lteRsrp >= -140) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (DEBUG) {
            Log.d(mTag, "getAlternateLteLevel lteRsrp:" + lteRsrp + " rsrpLevel = " + rsrpLevel);
        }
        return rsrpLevel;
    }

    @VisibleForTesting
    void setActivity(int activity) {
        mCurrentState.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
        notifyListenersIfNecessary();
    }

    private void recordLastMobileStatus(String mobileStatus) {
        mMobileStatusHistory[mMobileStatusHistoryIndex] = mobileStatus;
        mMobileStatusHistoryIndex = (mMobileStatusHistoryIndex + 1) % STATUS_HISTORY_SIZE;
    }

    public void registerFiveGStateListener(FiveGServiceClient client) {
        int phoneId = mSubscriptionInfo.getSimSlotIndex();
        client.registerListener(phoneId, mFiveGStateListener);
        mClient = client;
    }

    public void unregisterFiveGStateListener(FiveGServiceClient client) {
        int phoneId = mSubscriptionInfo.getSimSlotIndex();
        client.unregisterListener(phoneId, mFiveGStateListener);
    }

    private MobileIconGroup getNetworkTypeIconGroup() {
        MobileIconGroup iconGroup = mDefaultIcons;
        int overrideNetworkType = mCurrentState.telephonyDisplayInfo.getOverrideNetworkType();
        String iconKey = null;
        if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
                || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                || overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ){
            int networkType = mCurrentState.telephonyDisplayInfo.getNetworkType();
            if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                networkType = mCurrentState.getVoiceNetworkType();
            }
            iconKey = toIconKey(networkType);
        } else{
            iconKey = toDisplayIconKey(overrideNetworkType);
        }

        return mNetworkToIconLookup.getOrDefault(iconKey, mDefaultIcons);
    }

    private boolean showDataRatIcon() {
        boolean result = false;
        if ( mCurrentState.mobileDataEnabled ) {
            if(mCurrentState.roamingDataEnabled || !mCurrentState.roaming) {
                result = true;
            }
        }
        return result;
    }

    private int getEnhancementDataRatIcon() {
        return showDataRatIcon() && mCurrentState.connected ? getRatIconGroup().dataType : 0;
    }

    private int getEnhancementDdsRatIcon() {
        return mCurrentState.dataSim && mCurrentState.connected ? getRatIconGroup().dataType : 0;
    }

    private MobileIconGroup getRatIconGroup() {
        MobileIconGroup iconGroup = mDefaultIcons;
        if ( mFiveGState.isNrIconTypeValid() ) {
            iconGroup = mFiveGState.getIconGroup();
        }else {
            iconGroup = getNetworkTypeIconGroup();
        }
        return iconGroup;
    }

    private boolean isVowifiAvailable() {
        return mCurrentState.voiceCapable
                && mCurrentState.imsRegistrationTech == REGISTRATION_TECH_IWLAN;
    }

    private MobileIconGroup getVowifiIconGroup() {
        if ( isVowifiAvailable() && !isCallIdle() ) {
            return TelephonyIcons.VOWIFI_CALLING;
        }else if (isVowifiAvailable()) {
            return TelephonyIcons.VOWIFI;
        }else {
            return null;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        pw.println("  mSubscription=" + mSubscriptionInfo + ",");
        pw.println("  mInflateSignalStrengths=" + mInflateSignalStrengths + ",");
        pw.println("  isDataDisabled=" + isDataDisabled() + ",");
        pw.println("  mConfig.enableRatIconEnhancement=" + mConfig.enableRatIconEnhancement + ",");
        pw.println("  mConfig.enableDdsRatIconEnhancement="
                + mConfig.enableDdsRatIconEnhancement + ",");
        pw.println("  mConfig.alwaysShowNetworkTypeIcon="
                + mConfig.alwaysShowNetworkTypeIcon + ",");
        pw.println("  mConfig.showVowifiIcon=" +  mConfig.showVowifiIcon + ",");
        pw.println("  mConfig.showVolteIcon=" +  mConfig.showVolteIcon + ",");
        pw.println("  mNetworkToIconLookup=" + mNetworkToIconLookup + ",");
        pw.println("  mMobileStatusTracker.isListening=" + mMobileStatusTracker.isListening());
        pw.println("  MobileStatusHistory");
        int size = 0;
        for (int i = 0; i < STATUS_HISTORY_SIZE; i++) {
            if (mMobileStatusHistory[i] != null) {
                size++;
            }
        }
        // Print out the previous states in ordered number.
        for (int i = mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - 1;
                i >= mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - size; i--) {
            pw.println("  Previous MobileStatus("
                    + (mMobileStatusHistoryIndex + STATUS_HISTORY_SIZE - i) + "): "
                    + mMobileStatusHistory[i & (STATUS_HISTORY_SIZE - 1)]);
        }
        pw.println("  mFiveGState=" + mFiveGState + ",");

        dumpTableData(pw);
    }

    class FiveGStateListener implements IFiveGStateListener{

        public void onStateChanged(FiveGServiceState state) {
            if (DEBUG) {
                Log.d(mTag, "onStateChanged: state=" + state);
            }
            mFiveGState = state;
            updateTelephony();
            notifyListeners();
        }
    }

    private ImsMmTelManager.CapabilityCallback mCapabilityCallback
        = new ImsMmTelManager.CapabilityCallback() {
        @Override
        public void onCapabilitiesStatusChanged(MmTelFeature.MmTelCapabilities config) {
            mCurrentState.voiceCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE);
            mCurrentState.videoCapable =
                    config.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VIDEO);
            Log.d(mTag, "onCapabilitiesStatusChanged isVoiceCapable=" + mCurrentState.voiceCapable
                    + " isVideoCapable=" + mCurrentState.videoCapable);
            notifyListenersIfNecessary();
        }
    };

    private final BroadcastReceiver mVolteSwitchObserver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(mTag, "action=" + intent.getAction());
            if ( mConfig.showVolteIcon ) {
                notifyListeners();
            }
        }
    };

    private final ImsStateCallback mImsStateCallback = new ImsStateCallback() {
        @Override
        public void onUnavailable(int reason) {
            Log.d(mTag, "ImsStateCallback.onUnavailable: reason=" + reason);
            removeListeners();
        }

        @Override
        public void onAvailable() {
            Log.d(mTag, "ImsStateCallback.onAvailable");
            setListeners();
        }

        @Override
        public void onError() {
            Log.e(mTag, "ImsStateCallback.onError");
            removeListeners();
        }
    };

    /** Box for QS icon info */
    private static final class QsInfo {
        final int ratTypeIcon;
        final IconState icon;
        final CharSequence description;

        QsInfo(int typeIcon, IconState iconState, CharSequence desc) {
            ratTypeIcon = typeIcon;
            icon = iconState;
            description = desc;
        }

        @Override
        public String toString() {
            return "QsInfo: ratTypeIcon=" + ratTypeIcon + " icon=" + icon;
        }
    }

    /** Box for status bar icon info */
    private static final class SbInfo {
        final boolean showTriangle;
        final int ratTypeIcon;
        final IconState icon;

        SbInfo(boolean show, int typeIcon, IconState iconState) {
            showTriangle = show;
            ratTypeIcon = typeIcon;
            icon = iconState;
        }

        @Override
        public String toString() {
            return "SbInfo: showTriangle=" + showTriangle + " ratTypeIcon=" + ratTypeIcon
                    + " icon=" + icon;
        }
    }
}
