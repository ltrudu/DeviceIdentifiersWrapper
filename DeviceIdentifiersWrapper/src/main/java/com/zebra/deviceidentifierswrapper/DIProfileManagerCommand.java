package com.zebra.deviceidentifierswrapper;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import com.symbol.emdk.EMDKBase;
import com.symbol.emdk.EMDKException;
import com.symbol.emdk.EMDKManager;
import com.symbol.emdk.EMDKResults;
import com.symbol.emdk.ProfileManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;

class DIProfileManagerCommand extends DICommandBase {
    public class ErrorHolder
    {
        // Provides the error type for characteristic-error
        public String sErrorType = "";

        // Provides the parm name for parm-error
        public String sParmName = "";

        // Provides error description
        public String sErrorDescription = "";
    }

    // Membres
    private String TAG = "DIWrapperMX";

    // Callback interface to get the hand back when the profile has been executed
    private IDIResultCallbacks idiProfileManagerCommandResult = null;

    // Profile content in XML
    private String msProfileData = "";

    // Profile name to execute
    private String msProfileName = "";

    //Declare a variable to store ProfileManager object
    private ProfileManager mProfileManager = null;

    //Declare a variable to store EMDKManager object
    private EMDKManager mEMDKManager = null;

    // An ArrayList that will contains errors if we find some
    private ArrayList<ErrorHolder> mErrors = new ArrayList<>();

    // Provides full error description string
    public String msErrorString = "";

    // To prevent multiple initializations at the same time
    private boolean bInitializing = false;

    // Status Listener implementation (ensure that we retrieve the profile manager asynchronously
    EMDKManager.StatusListener mStatusListener = new EMDKManager.StatusListener() {
        @Override
        public void onStatus(EMDKManager.StatusData statusData, EMDKBase emdkBase) {
            if(statusData.getResult() == EMDKResults.STATUS_CODE.SUCCESS)
            {
                logMessage("Profile manager retrieved.", EMessageType.DEBUG);
                onProfileManagerInitialized((ProfileManager)emdkBase);
            }
            else
            {
                String errorMessage = "Error when trying to retrieve ProfileManager: " + getResultCode(statusData.getResult());
                logMessage(errorMessage, EMessageType.ERROR);

                // Timed out, try again
                if (statusData.getResult() == EMDKResults.STATUS_CODE.FEATURE_NOT_READY_TO_USE) {
                    onEMDKManagerRetrieved(mEMDKManager);
                }
            }
        }
    };

    // EMDKListener implementation
    EMDKManager.EMDKListener mEMDKListener = new EMDKManager.EMDKListener() {
        @Override
        public void onOpened(EMDKManager emdkManager) {
            logMessage("EMDKManager.EMDKListener.onOpened", EMessageType.DEBUG);
            onEMDKManagerRetrieved(emdkManager);
        }

        @Override
        public void onClosed() {
            logMessage("EMDKManager.EMDKListener.onClosed", EMessageType.DEBUG);
            onEMDKManagerClosed();
        }
    };


    public DIProfileManagerCommand(Context aContext) {
        super(aContext);
        mSettings = new DICommandBaseSettings(){{
           mTimeOutMS = 120000;
           mEnableTimeOutMechanism = true;
           mCommandId = "DWProfileManagerCommand";
        }};
    }

    @Override
    protected void onTimeOut(DICommandBaseSettings settings) {
        super.onTimeOut(settings);
        onEMDKManagerClosed();
    }

    public void execute(String mxProfile, String mxProfileName, IDIResultCallbacks resutCallback)
    {
        // Let's start the timeout mechanism
        super.execute(mSettings);
        idiProfileManagerCommandResult = resutCallback;
        msProfileData = mxProfile;
        msProfileName = mxProfileName;
        initializeEMDK();
    }

    private void initializeEMDK()
    {
        if(bInitializing)
            return;
        bInitializing = true;
        if(mEMDKManager == null)
        {
            EMDKResults results = null;
            try
            {
                //The EMDKManager object will be created and returned in the callback.
                results = EMDKManager.getEMDKManager(mContext.getApplicationContext(), mEMDKListener);
            }
            catch(Exception e)
            {
                logMessage("Error while requesting EMDKManager.\n" + e.getLocalizedMessage(), EMessageType.ERROR);
                e.printStackTrace();
                waitForEMDK();
                return;
            }

            //Check the return status of EMDKManager object creation.
            if(results.statusCode == EMDKResults.STATUS_CODE.SUCCESS) {
                logMessage("EMDKManager request command issued with success", EMessageType.DEBUG);
            }else {
                logMessage("EMDKManager request command error", EMessageType.ERROR);
                waitForEMDK();
            }
        }
        else
        {
            onEMDKManagerRetrieved(mEMDKManager);
        }
    }

    private void waitForEMDK()
    {
        logMessage("EMDKManager error, this could be a BOOT_COMPLETED issue.", EMessageType.DEBUG);
        logMessage("Starting watcher thread to wait for EMDK initialization.", EMessageType.DEBUG);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long startDate = new Date().getTime();
                long delta = 0;
                while(mEMDKManager == null || delta < DIHelper.MAX_EMDK_TIMEOUT_IN_MS ) {
                    // Try to initialize EMDK
                    logMessage("Calling EMDK Initialization method", EMessageType.DEBUG);
                    initializeEMDK();
                    try {
                        logMessage("Waiting " + DIHelper.WAIT_PERIOD_BEFORE_RETRY_EMDK_RETRIEVAL_IN_MS + " milliseconds before retrying.", EMessageType.DEBUG);
                        Thread.sleep(DIHelper.WAIT_PERIOD_BEFORE_RETRY_EMDK_RETRIEVAL_IN_MS);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    delta = new Date().getTime() - startDate;
                    logMessage("Delta in ms since first EMDK retrieval try: " + delta + "ms stops at " + DIHelper.MAX_EMDK_TIMEOUT_IN_MS + "ms", EMessageType.DEBUG);
                }
                bInitializing = false;
                logMessage("Could not retrieve EMDK Manager after waiting " + DIHelper.WAIT_PERIOD_BEFORE_RETRY_EMDK_RETRIEVAL_IN_MS/DIHelper.SEC_IN_MS + " seconds. Please contact your administrator or check logcat for any EMDK related error.", EMessageType.ERROR);
            }
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void onEMDKManagerRetrieved(EMDKManager emdkManager)
    {
        mEMDKManager = emdkManager;
        logMessage("EMDK Manager retrieved.", EMessageType.DEBUG);
        if(mProfileManager == null)
        {
            try {
                logMessage("Requesting profile manager.", EMessageType.DEBUG);
                logMessage("Current API version: " + android.os.Build.VERSION.SDK_INT, EMessageType.VERBOSE);
                if(android.os.Build.VERSION.SDK_INT < 33) {
                    logMessage("Requesting profile manager Asynchonously", EMessageType.DEBUG);
                    emdkManager.getInstanceAsync(EMDKManager.FEATURE_TYPE.PROFILE, mStatusListener);
                }
                else
                {
                    logMessage("Requesting profile manager synchronized", EMessageType.DEBUG);
                    ProfileManager profileManager = (ProfileManager) emdkManager.getInstance(EMDKManager.FEATURE_TYPE.PROFILE);
                    if(profileManager != null)
                    {
                        onProfileManagerInitialized(profileManager);
                    }
                }
            } catch (EMDKException e) {
                logMessage("Error when trying to retrieve profile manager: " + e.getMessage(), EMessageType.ERROR);
            }
        }
        else
        {
            logMessage("EMDK Manager already initialized.", EMessageType.DEBUG);
            onProfileManagerInitialized(mProfileManager);
        }
    }

    private void onEMDKManagerClosed()
    {
        releaseManagers();
    }

    private void releaseManagers()
    {
        if(mProfileManager != null)
        {
            mProfileManager = null;
            logMessage("Profile Manager reseted.", EMessageType.DEBUG);
        }

        //This callback will be issued when the EMDK closes unexpectedly.
        if (mEMDKManager != null) {
            mEMDKManager.release();
            logMessage("EMDKManager released.", EMessageType.DEBUG);
            mEMDKManager = null;
            logMessage("EMDKManager reseted.", EMessageType.DEBUG);
        }
    }

    private void onProfileManagerInitialized(ProfileManager profileManager)
    {
        mProfileManager = profileManager;
        bInitializing = false;
        logMessage("Processing MX Content", EMessageType.DEBUG);
        processMXContent();
    }

    private void onProfileExecutedWithSuccess()
    {
        releaseManagers();
        if(idiProfileManagerCommandResult != null)
        {
            idiProfileManagerCommandResult.onSuccess("Success applying profile:" + msProfileName + "\nProfileData:" + msProfileData);
        }

    }

    private void onProfileExecutedError(String message)
    {
        releaseManagers();
        if(idiProfileManagerCommandResult != null)
        {
            idiProfileManagerCommandResult.onError("Error on profile: " + msProfileName + "\nError:" + message + "\nProfileData:" + msProfileData);
        }

   }

    private void onProfileExecutedStatusChanged(String message)
    {
        if(idiProfileManagerCommandResult != null)
        {
            idiProfileManagerCommandResult.onDebugStatus(message);
        }
    }

    private void processMXContent()
    {
        String[] params = new String[1];
        params[0] = msProfileData;

        logMessage("Processing profile :" + msProfileData, EMessageType.VERBOSE);
        EMDKResults results = mProfileManager.processProfile(msProfileName, ProfileManager.PROFILE_FLAG.SET, params);

        //Check the return status of processProfile
            if(results.statusCode == EMDKResults.STATUS_CODE.CHECK_XML) {

            // Get XML response as a String
            String statusXMLResponse = results.getStatusString();

            try {
                // Empty Error Holder Array List if it already exists
                mErrors.clear();

                // Create instance of XML Pull Parser to parse the response
                XmlPullParser parser = Xml.newPullParser();
                // Provide the string response to the String Reader that reads
                // for the parser
                parser.setInput(new StringReader(statusXMLResponse));
                // Call method to parse the response
                parseXML(parser);

                if ( mErrors.size() == 0 ) {

                    logMessage("Profile executed with success: " + msProfileName, EMessageType.SUCCESS);
                    onProfileExecutedWithSuccess();
                }
                else {
                    String errorMessage = "";
                    for(ErrorHolder error : mErrors)
                    {
                        errorMessage += "Profile processing error.\t" + "Type:" + error.sErrorType + "\tParamName:" + error.sParmName + "\tDescription:" + error.sErrorDescription;
                    }
                    logMessage(errorMessage, EMessageType.ERROR);
                    onProfileExecutedError(errorMessage);
                    return;
                }

            } catch (XmlPullParserException e) {
                String errorMessage = "Error while trying to parse ProfileManager XML Response: " + e.getLocalizedMessage();
                logMessage(errorMessage, EMessageType.ERROR);
                onProfileExecutedError(errorMessage);
                return;
            }
        }
        else if(results.statusCode == EMDKResults.STATUS_CODE.SUCCESS)
        {
            logMessage("Profile executed with success: " + msProfileName, EMessageType.DEBUG);
            onProfileExecutedWithSuccess();
            return;
        }
        else
        {
            String errorMessage = "Profile update failed." + getResultCode(results.statusCode) + "\nProfil:\n" + msProfileName;
            logMessage(errorMessage, EMessageType.ERROR);
            onProfileExecutedError(errorMessage);
            return;
        }
    }

    // Method to parse the XML response using XML Pull Parser
    private void parseXML(XmlPullParser myParser) {
        int event;
        try {
            // Retrieve error details if parm-error/characteristic-error in the response XML
            event = myParser.getEventType();
            // An object that will store a temporary error holder if an error characteristic is found
            ErrorHolder tempErrorHolder = null;
            //logMessage("XML document", EMessageType.VERBOSE);
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = myParser.getName();
                switch (event) {
                    case XmlPullParser.START_TAG:
                        //logMessage("XML Element:<" + myParser.getText()+">", EMessageType.VERBOSE);
                        if (name.equals("characteristic-error"))
                        {
                            if(tempErrorHolder == null)
                                tempErrorHolder = new ErrorHolder();
                            tempErrorHolder.sErrorType = myParser.getAttributeValue(null, "type");
                            if(tempErrorHolder.sParmName != null && TextUtils.isEmpty(tempErrorHolder.sParmName) == false)
                            {
                                msErrorString += "Nom: " + tempErrorHolder.sParmName + "\nType: " + tempErrorHolder.sErrorType + "\nDescription: " + tempErrorHolder.sErrorDescription + ")";
                                mErrors.add(tempErrorHolder);
                                tempErrorHolder = null;
                            }
                        }
                        else if (name.equals("parm-error"))
                        {
                            if(tempErrorHolder == null)
                                tempErrorHolder = new ErrorHolder();
                            tempErrorHolder.sParmName = myParser.getAttributeValue(null, "name");
                            tempErrorHolder.sErrorDescription = myParser.getAttributeValue(null, "desc");
                            if(tempErrorHolder.sErrorType != null && TextUtils.isEmpty(tempErrorHolder.sErrorType) == false)
                            {
                                msErrorString += "Nom: " + tempErrorHolder.sParmName + "\nType: " + tempErrorHolder.sErrorType + "\nDescription: " + tempErrorHolder.sErrorDescription + ")";
                                mErrors.add(tempErrorHolder);
                                tempErrorHolder = null;
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        //logMessage("XML Element:<//" + myParser.getText()+">", EMessageType.VERBOSE);
                        break;
                }
                event = myParser.next();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void logMessage(String message, EMessageType messageType)
    {
        switch(messageType)
        {
            case ERROR:
                Log.e(TAG, message);
                onProfileExecutedStatusChanged("ERROR:" + message);
                break;
            case SUCCESS:
                Log.v(TAG, message);
                onProfileExecutedStatusChanged("SUCCESS:" + message);
                break;
            case VERBOSE:
                Log.v(TAG, message);
                onProfileExecutedStatusChanged("VERBOSE:" + message);
                break;
            case WARNING:
                Log.w(TAG, message);
                onProfileExecutedStatusChanged("WARNING:" + message);
                break;
            case DEBUG:
                Log.d(TAG,message);
                onProfileExecutedStatusChanged("DEBUG:" + message);
        }
    }

    private String getResultCode(EMDKResults.STATUS_CODE aStatusCode)
    {
        switch (aStatusCode)
        {
            case FAILURE:
                return "FAILURE";
            case NULL_POINTER:
                return "NULL_POINTER";
            case EMPTY_PROFILENAME:
                return "EMPTY_PROFILENAME";
            case EMDK_NOT_OPENED:
                return "EMDK_NOT_OPENED";
            case CHECK_XML:
                return "CHECK_XML";
            case PREVIOUS_REQUEST_IN_PROGRESS:
                return "PREVIOUS_REQUEST_IN_PROGRESS";
            case PROCESSING:
                return "PROCESSING";
            case NO_DATA_LISTENER:
                return "NO_DATA_LISTENER";
            case FEATURE_NOT_READY_TO_USE:
                return "FEATURE_NOT_READY_TO_USE";
            case FEATURE_NOT_SUPPORTED:
                return "FEATURE_NOT_SUPPORTED";
            case UNKNOWN:
            default:
                return "UNKNOWN";
        }
    }

}
