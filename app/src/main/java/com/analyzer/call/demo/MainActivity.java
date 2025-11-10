package com.analyzer.call.demo;

import android.app.Activity;
import android.os.Bundle;

import com.google.gson.Gson;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Gson gson = new Gson();
    }
}
