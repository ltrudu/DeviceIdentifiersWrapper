package com.zebra.deviceidentifierswrapper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;

import java.util.Base64;

/**
 * Device Identifiers Sample
 *
 * Original Device Identifier Sample Code:
 *          - Darryn Campbell
 *          - https://github.com/darryncampbell/EMDK-DeviceIdentifiers-Sample
 *
 *  Wrapper Code:
 *          - Trudu Laurent
 *          - https://github.com/ltrudu/DeviceIdentifiersWrapper-Sample
 *
 *  (c) Zebra 2020
 */

class RetrieveOEMInfoTask extends ExecutorTask<Object, Void, Boolean> {

    @Override
    protected Boolean doInBackground(Object... objects) {
        Context context = (Context) objects[0];
        Uri uri = (Uri) objects[1];
        IDIResultCallbacks idiResultCallbacks = (IDIResultCallbacks) objects[2];
        RetrieveOEMInfo(context, uri, idiResultCallbacks);
        return true;
    }

    private static void RetrieveOEMInfo(final Context context, final Uri uri, final IDIResultCallbacks callbackInterface) {
        //  For clarity, this code calls ContentResolver.query() on the UI thread but production code should perform queries asynchronously.
        //  See https://developer.android.com/guide/topics/providers/content-provider-basics.html for more information
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor == null || cursor.getCount() < 1)
        {
            if(callbackInterface != null)
            {
                callbackInterface.onDebugStatus("App not registered to call OEM Service:" + uri.toString()+"\nRegistering current application using profile manger, this may take a couple of seconds...");
            }
            // Let's register the application
            registerCurrentApplication(context, uri, new IDIResultCallbacks() {
                @Override
                public void onSuccess(String message) {
                    // The app has been registered
                    // Let's try again to get the identifier
                    Cursor cursor2 = context.getContentResolver().query(uri, null, null, null, null);
                    if (cursor2 == null || cursor2.getCount() < 1) {
                        if(callbackInterface != null)
                        {
                            callbackInterface.onError("Fail to register the app for OEM Service call:" + uri + "\nIt's time to debug this app ;)");
                            return;
                        }
                    }
                    getURIValue(cursor2, uri, callbackInterface);
                    return;
                }

                @Override
                public void onError(String message) {
                    if(callbackInterface != null)
                    {
                        callbackInterface.onError(message);
                        return;
                    }
                }

                @Override
                public void onDebugStatus(String message) {
                    if(callbackInterface != null)
                    {
                        callbackInterface.onDebugStatus(message);
                    }
                }
            });
        }
        else
        {
            // We have the right to call this service, and we obtained some data to parse...
            getURIValue(cursor, uri, callbackInterface);
        }
    }

    private static void registerCurrentApplication(Context context, Uri serviceIdentifier, IDIResultCallbacks callbackInterface)
    {
        String profileName = "AccessMgr-1";
        String profileData = "";
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            String path = context.getApplicationInfo().sourceDir;
            final String strName = packageInfo.applicationInfo.loadLabel(context.getPackageManager()).toString();
            final String strVendor = packageInfo.packageName;
            Signature sig = DIHelper.apkCertificate;

            // Let's check if we have a custom certificate
            if(sig == null)
            {
                // Nope, we will get the first apk signing certificate that we find
                // You can copy/paste this snippet if you want to provide your own
                // certificate
                // TODO: use the following code snippet to extract your custom certificate if necessary
                Signature[] arrSignatures = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    arrSignatures = packageInfo.signingInfo.getApkContentsSigners();
                }
                if(arrSignatures == null || arrSignatures.length == 0)
                {
                    if(callbackInterface != null)
                    {
                        callbackInterface.onError("Error : Package has no signing certificates... how's that possible ?");
                        return;
                    }
                }
                sig = arrSignatures[0];
            }

            /*
             * Get the X.509 certificate.
             */
            final byte[] rawCert = sig.toByteArray();

            // Get the certificate as a base64 string
            String encoded = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                encoded = Base64.getEncoder().encodeToString(rawCert);
            }

            profileData =
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<characteristic type=\"Profile\">" +
                            "<parm name=\"ProfileName\" value=\"" + profileName + "\"/>" +
                            "<characteristic type=\"AccessMgr\" version=\"9.2\">" +
                            "<parm name=\"OperationMode\" value=\"1\" />" +
                            "<parm name=\"ServiceAccessAction\" value=\"4\" />" +
                            "<parm name=\"ServiceIdentifier\" value=\"" + serviceIdentifier + "\" />" +
                            "<parm name=\"CallerPackageName\" value=\"" + context.getPackageName().toString() + "\" />" +
                            "<parm name=\"CallerSignature\" value=\"" + encoded + "\" />" +
                            "</characteristic>"+
                            "</characteristic>";
            DIProfileManagerCommand profileManagerCommand = new DIProfileManagerCommand(context);
            profileManagerCommand.execute(profileData, profileName, callbackInterface);
            //}
        } catch (Exception e) {
            e.printStackTrace();
            if(callbackInterface != null)
            {
                callbackInterface.onError("Error on profile: " + profileName + "\nError:" + e.getLocalizedMessage() + "\nProfileData:" + profileData);
            }
        }
    }


    private static void getURIValue(Cursor cursor, Uri uri, IDIResultCallbacks resultCallbacks)
    {
        while (cursor.moveToNext()) {
            if (cursor.getColumnCount() == 0)
            {
                //  No data in the cursor.  I have seen this happen on non-WAN devices
                String errorMsg = "Error: " + uri + " does not exist on this device";
                resultCallbacks.onDebugStatus(errorMsg);
            }
            else{
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    try {
                        @SuppressLint("Range") String data = cursor.getString(cursor.getColumnIndex(cursor.getColumnName(i)));
                        resultCallbacks.onSuccess(data);
                        cursor.close();
                        return;
                    }
                    catch (Exception e)
                    {
                        resultCallbacks.onDebugStatus(e.getLocalizedMessage());
                    }
                }
            }
        }
        cursor.close();
        resultCallbacks.onError("Data not found in Uri:" + uri);
    }
}