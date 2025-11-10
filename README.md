# 代码调用分析
## 配置
* settings.gradle
```groovy
pluginManagement {
    plugins {
        id 'com.analyzer.call' version '1.0.0'
    }
}
```
* build.gradle
```groovy
plugins {
    id 'call_analyzer'
}


callAnalyzer {

    excludePackage = [
            "com.aaa.bbb.ccc"
    ]

    excludePrefix = [
            "androidx",
            "kotlinx",
            "java",
            "kotlin",
            "android",
    ]
}
```

## 运行

```shell
./gradlew :app:analyzerDebugCalls -s
```

## 输出
```shell
app/build/reports

calls.csv         calls.json        calls.text        dependencies.json
```

* calls.csv
```text
"com.google.gson.Gson.<init>()V",".onCreate(Landroid/os/Bundle;)V

"
```

* calls.json
```json
[
  {
    "moduleName": "com.google.code.gson:gson:2.10.1",
    "className": "com.google.gson.Gson.class",
    "callerMethod": "com.analyzer.call.demo.MainActivity.onCreate(Landroid/os/Bundle;)V",
    "targetMethod": "com.google.gson.Gson.\u003cinit\u003e()V"
  }
]
```

* calls.text
```text
=== Analyzer Call Report ===
Dependencies Scanned: 1
Calls Scanned: 1
-------------------------------------------------------
com.google.code.gson:gson:2.10.1
-------------------------------------------------------
com.google.code.gson:gson:2.10.1
    -> com.google.gson.Gson.<init>()V
        --> com.analyzer.call.demo.MainActivity.onCreate(Landroid/os/Bundle;)V
```

* dependencies.json
```json
{
  "com.google.code.gson:gson:2.10.1": "/Users/juneleo/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
}
```




