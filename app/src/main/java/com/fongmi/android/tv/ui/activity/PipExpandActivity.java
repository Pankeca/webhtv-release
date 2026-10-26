package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;

/** Transparent trampoline: starting any new activity while in PiP expands the window, then this finishes immediately. */
public class PipExpandActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        overridePendingTransition(0, 0);
    }
}
