package com.test.vuro.activity;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

class GPSInfoService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
