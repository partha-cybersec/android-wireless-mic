package com.partha.smartkey;

import android.app.admin.DeviceAdminReceiver;

/** Device admin with only the "force-lock" policy, so Smart Key can turn the screen off. */
public class LockAdmin extends DeviceAdminReceiver { }
